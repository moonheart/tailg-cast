package com.tailg.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodeSearchTest {

    @Test
    fun convertsBaiduMercatorToGcj() {
        // 天府广场，百度网页搜索返回的 BD-09 墨卡托厘米坐标。
        val (lat, lon) = GeocodeHelper.bd09mcToGcj(1158541545.0, 356713388.0)
        assertEquals(30.6574, lat, 0.001)
        assertEquals(104.0659, lon, 0.001)
    }

    @Test
    fun parsesBaiduSearchResults() {
        val json = """
            {"content":[
              {"name":"天府广场","addr":"成都市青羊区人民南路一段86号",
               "x":1158541545,"y":356713388,"uid":"a"},
              {"name":"天府广场-地铁站","addr":"成都地铁1号线;地铁2号线",
               "x":1158542659,"y":356713159,"uid":"b"},
              {"name":"没有坐标的联想词","addr":"","x":0,"y":0,"uid":"c"},
              {"name":"天府广场","addr":"成都市青羊区人民南路一段86号",
               "x":1158541545,"y":356713388,"uid":"d"}
            ]}
        """.trimIndent()

        val hits = GeocodeHelper.parseBaiduSearch(json)

        assertEquals(2, hits.size)
        assertEquals("天府广场", hits[0].name)
        assertEquals("成都市青羊区人民南路一段86号", hits[0].address)
        assertEquals("百度", hits[0].source)
        assertEquals(30.6574, hits[0].lat, 0.001)
        assertEquals(104.0659, hits[0].lon, 0.001)
        assertTrue(hits[1].lat > 30.0 && hits[1].lon > 100.0)
    }

    @Test
    fun parsesNominatimSearchAndConvertsToGcj() {
        val json = """
            [{"place_id":1,"lat":"30.6599157","lon":"104.0638546",
              "name":"天府广场",
              "display_name":"天府广场, 西御河街道, 青羊区, 成都市, 四川省, 中国"},
             {"place_id":2,"lat":"30.6570000","lon":"104.0800000",
              "display_name":"春熙路, 锦江区, 成都市, 四川省, 中国"}]
        """.trimIndent()

        val hits = GeocodeHelper.parseNominatimSearch(json)

        assertEquals(2, hits.size)
        assertEquals("天府广场", hits[0].name)
        assertEquals("OSM", hits[0].source)
        assertTrue(hits[0].address.contains("成都市"))
        // 成都一带 GCJ-02 纬度略小于 WGS-84、经度略大，确认做了国测偏移。
        assertEquals(30.6575, hits[0].lat, 0.001)
        assertEquals(104.0664, hits[0].lon, 0.001)
        // 没有 name 时用 display_name 的第一段。
        assertEquals("春熙路", hits[1].name)
    }

    @Test
    fun buildsKeylessSearchUrls() {
        val baidu = GeocodeHelper.buildBaiduSearchUrl("天府广场")
        assertTrue(baidu.startsWith("https://map.baidu.com/"))
        assertTrue(baidu.contains("wd=%E5%A4%A9%E5%BA%9C%E5%B9%BF%E5%9C%BA"))
        assertTrue(baidu.contains("c=1"))

        val osm = GeocodeHelper.buildNominatimSearchUrl("天府广场")
        assertTrue(osm.startsWith("https://nominatim.openstreetmap.org/search?"))
        assertTrue(osm.contains("format=jsonv2"))
        assertTrue(osm.contains("q=%E5%A4%A9%E5%BA%9C%E5%B9%BF%E5%9C%BA"))
    }
}
