package com.tailg.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RideStatsTest {

    private val summaryJson = """
        {
          "code": 0,
          "msg": "操作成功",
          "data": {
            "dayMileage": 29244,
            "weekMileage": 0,
            "monthsMileage": 0,
            "yearMileage": 0,
            "totalMileage": 4362800,
            "ridingCount": 2,
            "ridingTime": 62,
            "maxSpeed": "71",
            "avgSpeed": 28,
            "carbonSaving": 5.0,
            "carbonAbsorption": 1.0,
            "ridingDateDtoList": []
          }
        }
    """.trimIndent()

    private val travelJson = """
        {
          "code": "0",
          "data": [
            {
              "travelDate": "2026-11-13",
              "totalMileage": 29244.0,
              "totalTime": 3690,
              "days": 0, "hours": 1, "min": 1, "sec": 30,
              "deviceTravelDtoList": [
                {
                  "deviceTravelId": "trip-sample-0001",
                  "startTime": "2026-11-13 18:40:20",
                  "endTime": "2026-11-13 19:14:29",
                  "mileage": 14688,
                  "averageSpeed": 25.80614934114202,
                  "maxSpeed": 71,
                  "days": 0, "hours": 0, "min": 34, "sec": 9
                },
                {
                  "deviceTravelId": "trip-sample-0002",
                  "startTime": "2026-11-13 09:08:42",
                  "endTime": "2026-11-13 09:36:03",
                  "mileage": 14556,
                  "averageSpeed": 31.9327239488117,
                  "maxSpeed": 70,
                  "days": 0, "hours": 0, "min": 27, "sec": 21
                }
              ]
            },
            {
              "travelDate": "2026-11-12",
              "totalMileage": 12000.0,
              "totalTime": 1800,
              "deviceTravelDtoList": [
                {
                  "deviceTravelId": "only-one",
                  "startTime": "2026-11-12 08:00:00",
                  "endTime": "2026-11-12 08:30:00",
                  "mileage": 12000,
                  "averageSpeed": 24,
                  "maxSpeed": 60
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses day summary`() {
        val s = RideStats.parseSummary(summaryJson, "days")!!
        assertEquals(29244.0, s.mileageM, 0.001)
        assertEquals(4362800.0, s.totalMileageM, 0.001)
        assertEquals(2, s.rideCount)
        assertEquals(62, s.ridingMinutes)
        assertEquals(71.0, s.maxSpeed, 0.001)
        assertEquals(28.0, s.avgSpeed, 0.001)
        assertEquals(5.0, s.carbonSaving, 0.001)
        assertEquals(1.0, s.carbonTrees, 0.001)
    }

    @Test
    fun `summary picks the mileage field for the model`() {
        val week = summaryJson.replace("\"weekMileage\": 0", "\"weekMileage\": 145495")
        assertEquals(145495.0, RideStats.parseSummary(week, "weeks")!!.mileageM, 0.001)
        val month = summaryJson.replace("\"monthsMileage\": 0", "\"monthsMileage\": 231767")
        assertEquals(231767.0, RideStats.parseSummary(month, "months")!!.mileageM, 0.001)
    }

    @Test
    fun `parses string numbers from the cloud`() {
        val json = summaryJson
            .replace("\"dayMileage\": 29244", "\"dayMileage\": \"29244\"")
            .replace("\"ridingCount\": 2", "\"ridingCount\": \"2\"")
            .replace("\"ridingTime\": 62", "\"ridingTime\": \"62\"")
        val s = RideStats.parseSummary(json, "days")!!
        assertEquals(29244.0, s.mileageM, 0.001)
        assertEquals(2, s.rideCount)
        assertEquals(62, s.ridingMinutes)
    }

    @Test
    fun `week key matches official yyyyMMww shape`() {
        val key = weekKey()
        assertEquals(8, key.length)
        assertTrue(key.take(6).toInt() in 200001..299912)
    }

    @Test
    fun `empty data is a zero summary not an error`() {
        val s = RideStats.parseSummary("""{"code":"0","data":null}""", "days")!!
        assertEquals(0.0, s.mileageM, 0.001)
        assertEquals(0, s.rideCount)
    }

    @Test
    fun `non zero code returns null`() {
        assertNull(RideStats.parseSummary("""{"code":"401","msg":"token"}""", "days"))
        assertNull(RideStats.parseDays("""{"code":"401","msg":"token"}"""))
    }

    @Test
    fun `parses travel days and trips`() {
        val days = RideStats.parseDays(travelJson)!!
        assertEquals(2, days.size)
        assertEquals("2026-11-13", days[0].date)
        assertEquals(29244.0, days[0].mileageM, 0.001)
        assertEquals(3690, days[0].durationSec)
        assertEquals(2, days[0].trips.size)
        assertEquals(14688.0, days[0].trips[0].mileageM, 0.001)
        assertEquals(34 * 60 + 9, days[0].trips[0].durationSec)
        assertEquals(71.0, days[0].trips[0].maxSpeed, 0.001)
        // 没有 hours/min/sec 时用起止时间补时长。
        assertEquals(1800, days[1].trips[0].durationSec)
    }

    @Test
    fun `parses track points and drops zero coordinates`() {
        val json = """
            {"code":"0","data":[
              {"reportTime":"2026-11-13 18:40:20","lng":104.065000,"lat":30.659000,"speed":0},
              {"reportTime":"2026-11-13 18:41:09","lng":104.066000,"lat":30.660000,"speed":20},
              {"reportTime":"bad","lng":0,"lat":0,"speed":0}
            ]}
        """.trimIndent()
        val points = RideStats.parsePoints(json)!!
        assertEquals(2, points.size)
        assertEquals(20.0, points[1].speed, 0.001)
    }

    @Test
    fun `decimate keeps first and last`() {
        val points = (0 until 1000).map {
            TrackPoint(30.0 + it / 100000.0, 104.0, 10.0, "t$it")
        }
        val out = RideStats.decimate(points, 100)
        assertEquals(100, out.size)
        assertEquals(points.first().lat, out.first().lat, 0.0000001)
        assertEquals(points.last().lat, out.last().lat, 0.0000001)
    }

    @Test
    fun `wgs84 converts to gcj02`() {
        val (lat, lon) = WgsGcj.toGcj(39.9087, 116.3975)
        assertTrue(abs(lat - 39.9101) < 0.002)
        assertTrue(abs(lon - 116.4038) < 0.002)
    }

    @Test
    fun `formats distance duration and speed`() {
        assertEquals("29.2 km", fmtKm(29244.0))
        assertEquals("4,362.8 km", fmtKm(4362800.0))
        assertEquals("1 小时 1 分", fmtDuration(3690))
        assertEquals("34 分", fmtDuration(2049))
        assertEquals("28 秒", fmtDuration(28))
        assertEquals("71", fmtSpeed(71.0))
        assertEquals("25.8", fmtSpeed(25.806))
        assertEquals("—", fmtDuration(0))
    }
}
