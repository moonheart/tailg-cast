package com.tailg.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavTextTest {

    @Test
    fun parsesAmapUriStyleParams() {
        val c = NavText.parseCoordinates("https://x.test/?lat=30.7623&lon=103.9708")!!
        assertEquals(30.7623, c.lat, 0.0000001)
        assertEquals(103.9708, c.lon, 0.0000001)
    }

    @Test
    fun parsesChineseLabels() {
        val c = NavText.parseCoordinates("纬度：30.7623 经度：103.9708")!!
        assertEquals(30.7623, c.lat, 0.0000001)
        assertEquals(103.9708, c.lon, 0.0000001)
    }

    @Test
    fun parsesLonBeforeLat() {
        val c = NavText.parseCoordinates("lon=103.9708, lat=30.7623")!!
        assertEquals(30.7623, c.lat, 0.0000001)
        assertEquals(103.9708, c.lon, 0.0000001)
    }

    @Test
    fun parsesPlainPair() {
        val c = NavText.parseCoordinates("30.762300, 103.970800")!!
        assertEquals(30.7623, c.lat, 0.0000001)
        assertEquals(103.9708, c.lon, 0.0000001)
    }

    @Test
    fun parsesSwappedPair() {
        val c = NavText.parseCoordinates("103.970800,30.762300")!!
        assertEquals(30.7623, c.lat, 0.0000001)
        assertEquals(103.9708, c.lon, 0.0000001)
    }

    @Test
    fun parsesSpaceSeparatedPair() {
        val c = NavText.parseCoordinates("30.762300 103.970800")!!
        assertEquals(30.7623, c.lat, 0.0000001)
        assertEquals(103.9708, c.lon, 0.0000001)
    }

    @Test
    fun rejectsGarbageAndZero() {
        assertNull(NavText.parseCoordinates("没有坐标"))
        assertNull(NavText.parseCoordinates("0.000000,0.000000"))
    }
}
