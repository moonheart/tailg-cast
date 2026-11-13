package com.tailg.cast

/** 从高德分享文本 / 剪贴板里提取经纬度。 */
object NavText {

    data class Coord(val lat: Double, val lon: Double)

    private val LAT_LON = Regex(
        """(?i)(?:lat|latitude|纬度)\s*[=:：]\s*(-?\d+(?:\.\d+)?)[\s\S]{0,80}?(?:lon|lng|longitude|经度)\s*[=:：]\s*(-?\d+(?:\.\d+)?)"""
    )
    private val LON_LAT = Regex(
        """(?i)(?:lon|lng|longitude|经度)\s*[=:：]\s*(-?\d+(?:\.\d+)?)[\s\S]{0,80}?(?:lat|latitude|纬度)\s*[=:：]\s*(-?\d+(?:\.\d+)?)"""
    )
    private val PAIR = Regex("""(-?\d{1,3}\.\d{3,})\s*[,，]\s*(-?\d{1,3}\.\d{3,})""")
    private val SPACE_PAIR = Regex("""(-?\d{1,3}\.\d{4,})\s+(-?\d{1,3}\.\d{4,})""")

    fun parseCoordinates(text: String): Coord? {
        if (text.isBlank()) return null
        LAT_LON.find(text)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (valid(lat, lon)) return Coord(lat!!, lon!!)
        }
        LON_LAT.find(text)?.let { m ->
            val lon = m.groupValues[1].toDoubleOrNull()
            val lat = m.groupValues[2].toDoubleOrNull()
            if (valid(lat, lon)) return Coord(lat!!, lon!!)
        }
        PAIR.find(text)?.let { m ->
            val a = m.groupValues[1].toDoubleOrNull()
            val b = m.groupValues[2].toDoubleOrNull()
            if (valid(a, b)) return Coord(a!!, b!!)
            if (valid(b, a)) return Coord(b!!, a!!)
        }
        SPACE_PAIR.find(text)?.let { m ->
            val a = m.groupValues[1].toDoubleOrNull()
            val b = m.groupValues[2].toDoubleOrNull()
            if (valid(a, b)) return Coord(a!!, b!!)
            if (valid(b, a)) return Coord(b!!, a!!)
        }
        return null
    }

    private fun valid(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (lat.isNaN() || lon.isNaN()) return false
        if (lat == 0.0 && lon == 0.0) return false
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }
}
