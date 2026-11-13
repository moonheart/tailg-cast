package com.tailg.cast.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 官方账号登录：手机号 + 短信验证码 + H5 滑块。
 *
 * 端点和字段来自 com.tailg.run.intelligence 的反编译：
 *   app/captcha/slider/verify  {ticket, randstr} -> captchaPassToken
 *   app/sms/loginCode?phone=&captchaPassToken=    -> 发短信
 *   app/login {macCode, phone, smsCode, autoCompleteUserDetail}
 *   token 在 app/login 的响应头 Authorization
 */
class CloudLoginApi(base: String) {

    /** 登录结果：token 在响应头，uid 在响应体 data.uid。 */
    data class LoginResult(val token: String, val uid: String)

    private val baseUrl = if (base.endsWith("/")) base else "$base/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun sliderVerify(ticket: String, randstr: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("ticket", ticket)
            .put("randstr", randstr)
        val json = post("app/captcha/slider/verify", body)
        val token = json.optJSONObject("data")?.optString("captchaPassToken").orEmpty()
        if (token.isBlank()) throw IOException("滑块校验成功但没拿到 captchaPassToken")
        token
    }

    suspend fun sendSms(phone: String, captchaPassToken: String) = withContext(Dispatchers.IO) {
        val url = (baseUrl + "app/sms/loginCode").toHttpUrl().newBuilder()
            .addQueryParameter("phone", phone)
            .addQueryParameter("captchaPassToken", captchaPassToken)
            .build()
        execute(
            Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null))
                .withCommonHeaders()
                .build(),
        )
        Unit
    }

    suspend fun login(phone: String, smsCode: String, macCode: String): LoginResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("macCode", macCode)
                .put("phone", phone)
                .put("smsCode", smsCode)
                .put("autoCompleteUserDetail", "true")
            val (json, headers) = execute(
                Request.Builder()
                    .url(baseUrl + "app/login")
                    .post(body.toString().toRequestBody(jsonType))
                    .withCommonHeaders()
                    .build(),
            )
            val token = headers["Authorization"].orEmpty()
            if (token.isBlank()) {
                throw IOException("登录成功但响应头没有 Authorization（code=${json.optString("code")}）")
            }
            val uid = json.optJSONObject("data")?.optString("uid").orEmpty()
            LoginResult(token, uid)
        }

    private fun post(path: String, body: JSONObject): JSONObject {
        val (json, _) = execute(
            Request.Builder()
                .url(baseUrl + path)
                .post(body.toString().toRequestBody(jsonType))
                .withCommonHeaders()
                .build(),
        )
        return json
    }

    private fun execute(request: Request): Pair<JSONObject, Headers> {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}：${text.take(200)}")
            }
            val json = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw IOException("返回不是 JSON：${text.take(200)}")
            }
            if (json.optString("code", "") != "0") {
                throw IOException("${json.optString("msg", "请求失败")}（code=${json.optString("code")}）")
            }
            return json to response.headers
        }
    }

    private fun Request.Builder.withCommonHeaders(): Request.Builder = apply {
        // 官方 Retrofit 无 token 时也会带一个空的 Authorization
        header("Authorization", "")
        header("Forward-Service-Ip", "localhost")
        header("language", "zh_CN")
    }
}
