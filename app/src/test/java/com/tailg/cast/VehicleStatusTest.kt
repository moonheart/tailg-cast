package com.tailg.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleStatusTest {

    private val sample = """
        {
          "code": 0,
          "msg": "操作成功",
          "data": {
            "mac": "1B4941C66177",
            "passwordInfo": {"main": "1037215680", "children": ["2042987680"]},
            "isGps": 1,
            "imeiGps": "000000000000001",
            "online": true,
            "defenceStatus": 1,
            "acc": 0,
            "muteStatus": 0,
            "longitude": 104.065000,
            "latitude": 30.659000,
            "isNfc": "0",
            "isCushionLock": 1,
            "iccId": "89860000000000000001",
            "mqHost": "mq.example.com",
            "mqPort": "6668",
            "mqUsername": "mockMqUser",
            "mqPassword": "secret",
            "gpsReportTime": "2026-10-30T19:43:07.000+08:00",
            "simStatus": true,
            "batteryChargeStatus": 4,
            "bmsTlvType": 176,
            "carId": 100001,
            "userId": 200001,
            "carType": "TL3000DT-14W",
            "carPhoto": "https://cdn.example.com/car.png",
            "coding": "TESTCODE",
            "modelType": 283,
            "imei": "000000000000001",
            "frame": "TESTVIN0000000001",
            "carName": "台铃电动车",
            "carNickName": "小铃铛",
            "voltage": 83.6,
            "electricQuantity": 100,
            "mileage": 95.0,
            "batteryBindDate": "2026-04-21T08:00:00.000+08:00",
            "batteryTypeLabel": "锂电池",
            "batterySpecLabel": "锂电池72V35AH",
            "trajectory": "1",
            "perfectStatus": "1",
            "isFirstLoad": false,
            "shareCarFlag": false,
            "isTelligence": "1",
            "maxMileage": 95,
            "ftqc": "TEST-FTQC-00001",
            "fmac": "AA11BB22CC33",
            "linkCode": "0",
            "isScreen": "0",
            "isCushionInduction": "1",
            "isHillParking": "0",
            "isThinkRide": "1",
            "isTempleInduction": "1",
            "isCamera": "0",
            "isSlipSlope": "1",
            "screenSize": "0",
            "screenType": "0",
            "isEnhancedKickstand": "0",
            "mfrCode": "0",
            "isThinkRideBLE": "0",
            "isSkinChange": "1",
            "isBindStore": 0,
            "isWarranty": 0,
            "isCheck": 0
          },
          "success": true
        }
    """.trimIndent()

    @Test
    fun parsesCoreVehicleFields() {
        val s = VehicleStatus.parse(sample)!!
        assertEquals("小铃铛", s.nickname)
        assertEquals("TL3000DT-14W", s.model)
        assertEquals(true, s.online)
        assertEquals(100, s.battery)
        assertEquals(83.6, s.voltage!!, 0.0001)
        assertEquals(95.0, s.rangeKm!!, 0.0001)
        assertEquals(95.0, s.maxRangeKm!!, 0.0001)
        assertEquals(true, s.locked)
        assertEquals(false, s.accOn)
        assertEquals(ChargeState.CHARGING, s.charge)
        assertEquals("000000000000001", s.imei)
        assertEquals("89860000000000000001", s.iccid)
        assertEquals("TESTVIN0000000001", s.frame)
        assertEquals("TESTCODE", s.modelCode)
        assertEquals("锂电池", s.batteryType)
        assertEquals("锂电池72V35AH", s.batterySpec)
        assertEquals("", s.batteryCycles)
        assertEquals("2026-04-21", s.batteryBindDate)
        assertEquals("mq.example.com", s.mqHost)
        assertEquals("6668", s.mqPort)
    }

    @Test
    fun parsesCoordinatesAndGpsTime() {
        val s = VehicleStatus.parse(sample)!!
        assertEquals(30.659000, s.latitude!!, 0.0000001)
        assertEquals(104.065000, s.longitude!!, 0.0000001)
        assertEquals("2026-10-30 19:43:07", s.gpsTime)
    }

    @Test
    fun parsesStringAndNumberFlags() {
        val s = VehicleStatus.parse(sample)!!
        val map = s.features.associate { it.name to it.supported }
        assertFalse(map.getValue("NFC"))
        assertTrue(map.getValue("座桶锁"))
        assertTrue(map.getValue("坐垫感应"))
        assertFalse(map.getValue("边撑感应"))
        assertFalse(map.getValue("坡道驻车"))
        assertTrue(map.getValue("防滑"))
        assertFalse(map.getValue("屏幕"))
        assertFalse(map.getValue("摄像头"))
        assertTrue(map.getValue("GPS"))
    }

    @Test
    fun extrasKeepUnknownFieldsButHideSecrets() {
        val s = VehicleStatus.parse(sample)!!
        val labels = s.extras.map { it.label }
        assertTrue(labels.contains("轨迹"))
        assertTrue(labels.contains("车辆完善状态"))
        assertEquals("176", s.extras.associate { it.label to it.value }["BMS 协议号"])
        assertFalse(labels.any { it.contains("密码", ignoreCase = true) })
        assertFalse(labels.contains("mqUsername"))
        assertFalse(labels.contains("mqPassword"))
        assertFalse(labels.contains("passwordInfo"))
        assertFalse(labels.contains("userId"))
        assertFalse(labels.contains("carPhoto"))
        assertTrue(s.raw.contains("\"carNickName\""))
        assertFalse(s.raw.contains("secret"))
        assertFalse(s.raw.contains("mqPassword"))
        assertFalse(s.raw.contains("mockMqUser"))
        assertFalse(s.raw.contains("passwordInfo"))
    }

    @Test
    fun rejectsErrorAndGarbage() {
        assertNull(VehicleStatus.parse("""{"code":401,"msg":"登录失效"}"""))
        assertNull(VehicleStatus.parse("not json"))
        assertNull(VehicleStatus.parse("""{"code":0,"msg":"ok"}"""))
    }

    @Test
    fun missingOptionalFieldsStayEmpty() {
        val s = VehicleStatus.parse(
            """{"code":0,"data":{"carName":"OnlyName","online":"false"}}"""
        )!!
        assertEquals("OnlyName", s.nickname)
        assertEquals("", s.model)
        assertEquals(false, s.online)
        assertNull(s.battery)
        assertNull(s.voltage)
        assertNull(s.locked)
        assertEquals(ChargeState.UNKNOWN, s.charge)
        assertEquals("", s.address)
        assertEquals("", s.gpsTime)
        assertNull(s.latitude)
        assertNull(s.longitude)
        assertTrue(s.extras.isEmpty())
    }

    @Test
    fun chargeStatusMapping() {
        fun charge(code: Int): ChargeState = VehicleStatus.parse(
            """{"code":0,"data":{"batteryChargeStatus":$code}}"""
        )!!.charge

        assertEquals(ChargeState.NOT_CHARGING, charge(0))
        assertEquals(ChargeState.COMPLETE, charge(1))
        assertEquals(ChargeState.ILLEGAL, charge(2))
        assertEquals(ChargeState.PROTECTED, charge(3))
        assertEquals(ChargeState.CHARGING, charge(4))
        assertEquals(ChargeState.UNKNOWN, charge(9))
    }

    private val bms = """
        {"code":0,"data":{"details":[{
          "batteryChargeStatus":0,
          "batteryCurrent":5.0,
          "batteryCyclesNum":143,
          "batteryTemperature":23,
          "soc":63,
          "soh":100,
          "batteryCapacity":35.0,
          "batteryVoltage":72.0,
          "protocolNumber":176
        }]}}
    """.trimIndent()

    private fun baseNotCharging(): VehicleStatus = VehicleStatus.parse(
        sample.replace("\"batteryChargeStatus\": 4", "\"batteryChargeStatus\": 0")
    )!!

    @Test
    fun bmsCurrentOverridesSpuriousNotCharging() {
        val base = baseNotCharging()
        assertEquals(ChargeState.NOT_CHARGING, base.charge)

        val merged = VehicleStatus.mergeBms(base, bms)
        assertEquals(ChargeState.CHARGING, merged.charge)
        assertEquals(63, merged.battery)
        assertEquals("143", merged.batteryCycles)
        assertEquals(5.0, merged.batteryCurrent!!, 0.001)
        assertEquals(23.0, merged.batteryTemperature!!, 0.001)
        assertEquals(63.0, merged.batterySoc!!, 0.001)
        assertEquals(100.0, merged.batterySoh!!, 0.001)
        assertEquals(35.0, merged.batteryCapacityAh!!, 0.001)
        assertEquals(72.0, merged.batteryNominalVoltage!!, 0.001)
    }

    @Test
    fun bmsZeroCurrentKeepsNotCharging() {
        val zeroCurrent = bms.replace("\"batteryCurrent\":5.0", "\"batteryCurrent\":0.0")
        val merged = VehicleStatus.mergeBms(baseNotCharging(), zeroCurrent)
        assertEquals(ChargeState.NOT_CHARGING, merged.charge)
        assertEquals("143", merged.batteryCycles)
    }

    @Test
    fun bmsErrorKeepsBase() {
        val base = baseNotCharging()
        assertSame(base, VehicleStatus.mergeBms(base, """{"code":500,"msg":"x"}"""))
        assertSame(base, VehicleStatus.mergeBms(base, """{"code":0,"data":{}}"""))
    }
}
