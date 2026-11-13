package com.tailg.cast

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tailg.cast.data.CloudLoginApi
import com.tailg.cast.ui.SettingField
import com.tailg.cast.ui.theme.TailgCastTheme
import com.tailg.probe.Config
import com.tailg.probe.ProbeService
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * 官方账号登录：手机号 + 短信验证码 + H5 滑块。
 *
 * 滑块页面是官方 https://www.tailgdd.com/document/appCode.html：
 * 它加载 253 的 TencentCaptcha，成功后调用 tailgAppJsInterface.setSmsInfo({ticket,randstr})。
 * 拿到 ticket 后走 app/captcha/slider/verify -> app/sms/loginCode -> app/login。
 */
class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TailgCastTheme {
                LoginFlow(
                    onDone = {
                        setResult(RESULT_OK)
                        finish()
                    },
                )
            }
        }
    }
}

private enum class Step { Phone, Sms }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { CloudLoginApi(Config.load(ctx).cloudBase) }
    // 和官方 MacCodeUtils.getUniqueDeviceId 一致：2 + nameUUID(Android ID)
    val deviceId = remember {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (androidId.isNullOrBlank()) {
            "2" + UUID.randomUUID().toString().replace("-", "")
        } else {
            "2" + UUID.nameUUIDFromBytes(androidId.toByteArray()).toString().replace("-", "")
        }
    }

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(Step.Phone) }
    var captcha by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    if (captcha) {
        CaptchaScreen(
            deviceId = deviceId,
            existingToken = Config.load(ctx).cloudToken,
            onCancel = { captcha = false },
            onResult = { ticket, randstr ->
                captcha = false
                busy = true
                error = ""
                scope.launch {
                    try {
                        val pass = api.sliderVerify(ticket, randstr)
                        api.sendSms(phone, pass)
                        step = Step.Sms
                        busy = false
                        toast(ctx, "验证码已发送到 ${mask(phone)}")
                    } catch (t: Throwable) {
                        busy = false
                        error = t.message ?: "发送验证码失败"
                    }
                }
            },
            onError = { message ->
                captcha = false
                error = message
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("登录台铃账号", fontWeight = FontWeight.SemiBold)
                        Text(
                            "手机号 + 短信验证码",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step == Step.Phone) {
                Text(
                    "登录后 token 会保存在本机，用来拉车辆状态和远控。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingField(
                    label = "手机号",
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                    placeholder = "11 位手机号",
                    number = true,
                )
                Button(
                    onClick = {
                        error = ""
                        captcha = true
                    },
                    enabled = phone.length == 11 && phone.startsWith("1") && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("获取验证码", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    "验证码已发送到 ${mask(phone)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                SettingField(
                    label = "短信验证码",
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    placeholder = "6 位数字",
                    number = true,
                )
                Button(
                    onClick = {
                        busy = true
                        error = ""
                        scope.launch {
                            try {
                                val result = api.login(phone, code, deviceId)
                                Config.saveValue(ctx, "cloudToken", result.token)
                                if (result.uid.isNotBlank()) {
                                    Config.saveValue(ctx, "cloudUid", result.uid)
                                }
                                val now = System.currentTimeMillis()
                                Config.saveValue(ctx, "cloudTokenAt", now.toString())
                                Config.saveValue(ctx, "cloudTokenCheckedAt", now.toString())
                                ProbeService.config?.cloudToken = result.token
                                if (result.uid.isNotBlank()) {
                                    ProbeService.config?.cloudUid = result.uid
                                }
                                ProbeService.config?.cloudTokenAt = now
                                ProbeService.config?.cloudTokenCheckedAt = now
                                busy = false
                                toast(ctx, "登录成功")
                                onDone()
                            } catch (t: Throwable) {
                                busy = false
                                error = t.message ?: "登录失败"
                            }
                        }
                    },
                    enabled = code.length == 6 && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("登录", fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            error = ""
                            captcha = true
                        },
                        enabled = !busy,
                    ) { Text("重新获取") }
                    TextButton(
                        onClick = {
                            step = Step.Phone
                            code = ""
                            error = ""
                        },
                        enabled = !busy,
                    ) { Text("换个手机号") }
                }
            }

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }

            if (error.isNotBlank()) {
                Text(
                    error,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                "滑块由官方 H5 提供；验证码走官方短信接口，频繁请求可能被限流。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaScreen(
    deviceId: String,
    existingToken: String,
    onCancel: () -> Unit,
    onResult: (String, String) -> Unit,
    onError: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val webView = remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(
                CaptchaBridge(deviceId, existingToken, onResult, onError),
                "tailgAppJsInterface",
            )
            loadUrl("https://www.tailgdd.com/document/appCode.html")
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "滑块验证",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("取消") }
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

private class CaptchaBridge(
    private val deviceId: String,
    private val token: String,
    private val onResult: (String, String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun setSmsInfo(data: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val o = JSONObject(data)
                val ticket = o.optString("ticket")
                val randstr = o.optString("randstr")
                if (ticket.isBlank()) {
                    onError("滑块结果里没有 ticket")
                } else {
                    onResult(ticket, randstr)
                }
            } catch (t: Throwable) {
                onError("解析滑块结果失败：${t.message}")
            }
        }
    }

    @JavascriptInterface
    fun setError(data: String) {
        Handler(Looper.getMainLooper()).post {
            onError("滑块验证失败：$data")
        }
    }

    @JavascriptInterface
    fun getDeviceId(): String = deviceId

    @JavascriptInterface
    fun getAppLoginToken(): String = token

    @JavascriptInterface
    fun setToken(token: String) {
        // 官方页面预留，当前 H5 不使用。
    }
}

private fun mask(phone: String): String {
    if (phone.length < 7) return phone
    return phone.take(3) + "****" + phone.takeLast(4)
}

private fun toast(ctx: Context, text: String) {
    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
