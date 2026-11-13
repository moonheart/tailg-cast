package com.tailg.cast.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CloudLoginApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CloudLoginApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = CloudLoginApi(server.url("/v1/api/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `slider verify posts ticket and randstr and returns pass token`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":"0","data":{"captchaPassToken":"pass-123"}}"""),
        )

        val pass = api.sliderVerify("ticket-1", "rand-1")

        assertEquals("pass-123", pass)
        val request = server.takeRequest()
        assertEquals("/v1/api/app/captcha/slider/verify", request.path)
        assertEquals("""{"ticket":"ticket-1","randstr":"rand-1"}""", request.body.readUtf8())
        assertEquals("", request.getHeader("Authorization"))
        assertEquals("localhost", request.getHeader("Forward-Service-Ip"))
        assertEquals("zh_CN", request.getHeader("language"))
    }

    @Test
    fun `send sms puts phone and token in query`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"0"}"""))

        api.sendSms("13800138000", "pass-123")

        val request = server.takeRequest()
        assertEquals(
            "/v1/api/app/sms/loginCode?phone=13800138000&captchaPassToken=pass-123",
            request.path,
        )
        assertEquals("POST", request.method)
    }

    @Test
    fun `login posts fields and reads authorization header`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Authorization", "Bearer test-token")
                .setBody("""{"code":"0","data":{"uid":"u1"}}"""),
        )

        val result = api.login("13800138000", "123456", "2abcdef")

        assertEquals("Bearer test-token", result.token)
        assertEquals("u1", result.uid)
        val request = server.takeRequest()
        assertEquals("/v1/api/app/login", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("13800138000", body.getString("phone"))
        assertEquals("123456", body.getString("smsCode"))
        assertEquals("2abcdef", body.getString("macCode"))
        assertEquals("true", body.getString("autoCompleteUserDetail"))
    }

    @Test
    fun `non zero code throws with server message`() {
        server.enqueue(MockResponse().setBody("""{"code":"100","msg":"验证码错误"}"""))

        val error = assertThrows(IOException::class.java) {
            runBlocking { api.sliderVerify("t", "r") }
            Unit
        }
        assertEquals(true, error.message!!.contains("验证码错误"))
    }

    @Test
    fun `missing authorization header is an error`() {
        server.enqueue(MockResponse().setBody("""{"code":"0","data":{}}"""))

        assertThrows(IOException::class.java) {
            runBlocking { api.login("13800138000", "123456", "2x") }
            Unit
        }
    }
}
