# 云端账号登录链路（官方 App 逆向结论）

目的：让真应用自己登录台铃账号，拿 `Authorization` token，替代 MVP 里手工粘贴 token。

结论先说：**登录不是用户名密码，是「手机号 + 短信验证码」，发短信前还要过一道 H5 滑块验证码。**
滑块页面由官方 H5 承载，App 通过 `addJavascriptInterface` 收 `ticket/randstr`，再换 `captchaPassToken`。

## 1. 端点

Base：`https://www.tailgdd.com/v1/api/`

| 步骤 | 方法 | 路径 | 参数 |
|---|---|---|---|
| 滑块校验 | POST | `app/captcha/slider/verify` | `ticket`, `randstr` |
| 发短信 | POST | `app/sms/loginCode?phone=...&captchaPassToken=...` | query |
| 登录 | POST | `app/login` | `macCode`, `phone`, `smsCode`, `autoCompleteUserDetail=true` |

`app/captcha/slider/verify` 返回 `captchaPassToken`；`app/sms/loginCode` 用它给手机发码；
`app/login` 成功后，**token 不在 body 里，在响应头 `Authorization`**。

## 2. 官方流程（反编译对照）

`CodeLoginViewModel`：

```
check() -> 打开 WebDialog，加载 https://www.tailgdd.com/document/appCode.html
H5 滑块完成 -> tailgAppJsInterface.setSmsInfo({"ticket":...,"randstr":...})
  -> sliderVerify(ticket, randstr)
  -> 得到 captchaPassToken
  -> loginCode(phone, captchaPassToken)   // 发短信
用户填 6 位码 -> login(mac, phone, smsCode)
  -> 响应头 Authorization 被 TailgRetrofit 的 tokenInterceptor 存下来
```

`TailgRetrofit` 之后每个请求都带：

```
Authorization: <token>
Forward-Service-Ip: localhost
language: zh_CN
```

`macCode` 是手机 Wi-Fi MAC（`MacCodeUtils.getMac()`），官方用它做设备标识；真应用可以用
`ANDROID_ID` 或安装时生成的 UUID 替代，是否需要与官方一致待验证。

## 3. H5 桥

页面：`https://www.tailgdd.com/document/appCode.html`

JS 接口名：`tailgAppJsInterface`

| 方法 | 用途 |
|---|---|
| `getDeviceId()` | 返回手机 MAC |
| `getAppLoginToken()` | 返回当前 token |
| `setSmsInfo(String json)` | `{ticket, randstr}`，滑块完成 |
| `setToken(String)` | 预留 |
| `setError(String)` | 错误 |

真应用做法：Compose 里放一个 `AndroidView(WebView)`，注册同名 JS 接口，收到 `setSmsInfo` 后
调自己的 `sliderVerify` / `loginCode`；验证码用原生输入框，登录成功后从响应头取 token。

## 4. 风险与注意

- **验证码 H5 可能改版**：接口名/字段一旦变，登录就断；需要保留「手动粘贴 token」兜底。
- **token 有效期**：官方 token 会过期（当前 HAR 里的到 2026-11-30）。过期后要么重新登录，要么看是否有刷新端点。
- **不要把 token 写进明文配置**：真应用用 Android Keystore 加密后存 DataStore。
- **账号风控**：短时间反复发码可能触发限制；调试时用测试号码，别刷生产。
- **合规**：这是复刻官方私有接口，只适合自用/小范围；对外分发要避开官方商标，并说明风险。

## 5. 真应用实现（tailg-cast）

已落地：

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/tailg/cast/data/CloudLoginApi.kt` | OkHttp 调滑块校验、发短信、登录；token 从响应头取 |
| `app/src/main/java/com/tailg/cast/LoginActivity.kt` | 手机号 → WebView 滑块 → 短信码 → 登录，Compose UI |
| `app/src/main/java/com/tailg/cast/ui/SettingsPage.kt` | 云端卡片：手机号登录 / 重新登录 / 退出登录，保留手动 token 兜底 |

细节：

- H5 页面实际是 `https://www.tailgdd.com/document/appCode.html`，内部用
  `https://captcha.253.com/TJCaptcha.js` 的 `TencentCaptcha`（appId `199180182`，embed 模式）。
- WebView 注入 `tailgAppJsInterface`，只依赖 `setSmsInfo(String)` / `setError(String)`；
  官方页面还会调 `getDeviceId()` / `getAppLoginToken()`，已一并实现。
- `macCode` 复刻官方 `MacCodeUtils.getUniqueDeviceId()`：
  `"2" + UUID.nameUUIDFromBytes(ANDROID_ID.toByteArray()).toString().replace("-", "")`。
- 请求头：`Authorization: ""`、`Forward-Service-Ip: localhost`、`language: zh_CN`。
- token 先存 `Config.cloudToken`（应用私有 `config.properties`），**下一步换 Keystore**。

还没验证：

- 真实手机号收短信、`app/login` 返回 `Authorization` 的完整链路（需要用户自己的号码，避免乱发短信）。
- token 过期后的刷新/重登策略。
