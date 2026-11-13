package com.tailg.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarQrTest {

    @Test
    fun tailgUrlWithSsidAndPassword() {
        val info = CarQr.parse(
            "http://www.tailgdd.com:3001/appdownload/?TL_c37282ba633&91778425&ap=1",
        )
        assertEquals("TL_c37282ba633", info?.ssid)
        assertEquals("91778425", info?.password)
        assertEquals("url", info?.kind)
    }

    @Test
    fun tailgUrlWithoutQueryIsDownloadOnly() {
        assertNull(CarQr.parse("http://www.tailgdd.com:3001/appdownload/"))
    }

    @Test
    fun keyValueUrl() {
        val info = CarQr.parse(
            "http://example.com/appdownload/?ssid=My_Car&password=abc12345",
        )
        assertEquals("My_Car", info?.ssid)
        assertEquals("abc12345", info?.password)
    }

    @Test
    fun urlDecodesPercentAndKeepsPlus() {
        val info = CarQr.parse(
            "http://example.com/appdownload/?My%20Car&a%2Bb%2Bc%2Bd",
        )
        assertEquals("My Car", info?.ssid)
        assertEquals("a+b+c+d", info?.password)
    }

    @Test
    fun standardWifiQr() {
        val info = CarQr.parse("WIFI:T:WPA;S:TL_c37282ba633;P:91778425;;")
        assertEquals("TL_c37282ba633", info?.ssid)
        assertEquals("91778425", info?.password)
        assertEquals("wifi", info?.kind)
    }

    @Test
    fun standardWifiQrWithEscapes() {
        val info = CarQr.parse("WIFI:T:WPA;S:My\\;Car;P:pass\\;1234;;")
        assertEquals("My;Car", info?.ssid)
        assertEquals("pass;1234", info?.password)
    }

    @Test
    fun twoLineFallback() {
        val info = CarQr.parse("TL_c37282ba633\n91778425")
        assertEquals("TL_c37282ba633", info?.ssid)
        assertEquals("91778425", info?.password)
        assertEquals("lines", info?.kind)
    }

    @Test
    fun invalidQrReturnsNull() {
        assertNull(CarQr.parse(null))
        assertNull(CarQr.parse(""))
        assertNull(CarQr.parse("garbage"))
        assertNull(CarQr.parse("WIFI:T:WPA;S:only-ssid;;"))
        assertNull(CarQr.parse("http://example.com/appdownload/?ap=1"))
        assertNull(CarQr.parse("SSID\nshort"))
    }
}
