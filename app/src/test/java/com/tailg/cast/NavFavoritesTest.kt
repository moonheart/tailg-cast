package com.tailg.cast

import com.tailg.probe.NavFavorites
import org.junit.Assert.assertEquals
import org.junit.Test

class NavFavoritesTest {

    private fun fav(name: String, lat: Double, lon: Double, dev: Int = 0): NavFavorites.Favorite {
        val f = NavFavorites.Favorite()
        f.name = name
        f.lat = lat
        f.lon = lon
        f.dev = dev
        return f
    }

    @Test
    fun buildsMotorcycleRouteUri() {
        assertEquals(
            "amapuri://route/plan/?sourceApplication=tailg-cast" +
                "&dlat=30.123456&dlon=104.654321" +
                "&dname=%E5%A4%A9%E5%BA%9C%E5%B9%BF%E5%9C%BA&dev=0&t=11",
            fav("天府广场", 30.123456, 104.654321).motorUri(),
        )
    }

    @Test
    fun encodesAmpersandInName() {
        val uri = fav("A&B", 30.0, 104.0).motorUri()
        assertEquals(true, uri.contains("dname=A%26B"))
        assertEquals(true, uri.endsWith("&dev=0&t=11"))
    }

    @Test
    fun wgs84SetsDevOne() {
        val uri = fav("家", 30.0, 104.0, dev = 1).motorUri()
        assertEquals(true, uri.contains("&dev=1&t=11"))
    }

    @Test
    fun negativeCoordinatesKeepSixDecimals() {
        val uri = fav("南半球", -33.868820, 151.209290).motorUri()
        assertEquals(true, uri.contains("dlat=-33.868820"))
        assertEquals(true, uri.contains("dlon=151.209290"))
    }

    @Test
    fun blankNameFallsBack() {
        val uri = fav("", 30.0, 104.0).motorUri()
        assertEquals(true, uri.contains("dname=%E7%9B%AE%E7%9A%84%E5%9C%B0"))
    }
}
