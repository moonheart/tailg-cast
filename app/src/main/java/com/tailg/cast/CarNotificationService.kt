package com.tailg.cast

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tailg.probe.CarCmd
import com.tailg.probe.Config
import com.tailg.probe.LogSink
import com.tailg.probe.ProbeService
import java.io.ByteArrayOutputStream

/**
 * 监听系统通知与媒体会话，将曲目信息、微信/QQ等应用通知与短信同步到台铃仪表盘，
 * 并响应仪表盘下发的音乐控制（播放/暂停/切歌）指令。
 */
class CarNotificationService : NotificationListenerService() {

    private data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
    )

    private val activeControllers = mutableListOf<MediaController>()
    private var lastNotifyKey: String = ""
    private var lastNotifyAt: Long = 0L
    private var lastTrackKey: String = ""
    private var lastTrackAt: Long = 0L
    private var lastPlayingState: Boolean? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        synchronized(this) {
            rebindControllers(list.orEmpty())
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (metadata == null) return
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            val author = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.trim().orEmpty()
            val artist = author.ifEmpty {
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
            }
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
            if (title.isEmpty()) return

            val info = TrackInfo(title, artist, album)
            cachedTrack = info
            updateSongSummary(info, isAnyPlaying())

            if (!isMusicSyncEnabled() || !isCarReachable()) return
            val key = "$title|$artist|$album"
            val now = System.currentTimeMillis()
            if (key == lastTrackKey && now - lastTrackAt < 5000L) return
            if (isAnyPlaying()) {
                lastTrackKey = key
                lastTrackAt = now
                CarCmd.musicMsg(title, artist, album, "")
                CarCmd.musicStatus(true)
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state == null) return
            val playing = state.state == PlaybackState.STATE_PLAYING
            updateSongSummary(cachedTrack, playing)
            if (!isMusicSyncEnabled() || !isCarReachable()) return
            if (lastPlayingState != playing) {
                lastPlayingState = playing
                CarCmd.musicStatus(playing)
                if (playing) {
                    cachedTrack?.let { t ->
                        lastTrackKey = "${t.title}|${t.artist}|${t.album}"
                        lastTrackAt = System.currentTimeMillis()
                        CarCmd.musicMsg(t.title, t.artist, t.album, "")
                    }
                }
            }
        }

        override fun onSessionDestroyed() {
            lastPlayingState = false
            updateSongSummary(cachedTrack, false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceConnected = true
        registerMusicHandler()
        registerMediaSessions()
        LogSink.line("NOTIFY", "CarNotificationService 已启动")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        registerMusicHandler()
        registerMediaSessions()
        LogSink.line("NOTIFY", "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        isServiceConnected = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        isServiceConnected = false
        if (instance === this) instance = null
        synchronized(this) {
            for (c in activeControllers) {
                runCatching { c.unregisterCallback(mediaCallback) }
            }
            activeControllers.clear()
        }
        runCatching {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            msm?.removeOnActiveSessionsChangedListener(sessionListener)
        }
        super.onDestroy()
    }

    private fun registerMusicHandler() {
        val ctx = applicationContext
        ProbeService.musicHandler = object : ProbeService.CarMusicHandler {
            override fun onCarMusicControl(status: Int) {
                handleCarMusicControl(ctx, status)
            }

            override fun onCarMusicQueryStatus() {
                if (!isMusicSyncEnabled()) return
                val playing = isAnyPlaying()
                CarCmd.musicStatus(playing)
                if (playing) {
                    cachedTrack?.let { t ->
                        CarCmd.musicMsg(t.title, t.artist, t.album, "")
                    }
                }
            }
        }
    }

    private fun registerMediaSessions() {
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
            val comp = ComponentName(this, CarNotificationService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionListener, comp)
            val current = msm.getActiveSessions(comp)
            synchronized(this) {
                rebindControllers(current)
            }
        } catch (t: Throwable) {
            LogSink.line("MUSIC", "注册 MediaSession 失败: ${t.message}")
        }
    }

    private fun rebindControllers(list: List<MediaController>) {
        for (c in activeControllers) {
            runCatching { c.unregisterCallback(mediaCallback) }
        }
        activeControllers.clear()
        activeControllers.addAll(list)
        for (c in activeControllers) {
            runCatching { c.registerCallback(mediaCallback) }
        }
        // 初始化一次当前正在播放的曲目
        val primary = activeControllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: activeControllers.firstOrNull()
        primary?.metadata?.let { mediaCallback.onMetadataChanged(it) }
    }

    private fun isAnyPlaying(): Boolean {
        synchronized(this) {
            for (c in activeControllers) {
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) return true
            }
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return am?.isMusicActive == true
    }

    private fun handleCarMusicControl(ctx: Context, status: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        when (status) {
            0 -> { // 暂停
                if (isAnyPlaying()) {
                    dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
            1 -> { // 播放
                if (!isAnyPlaying()) {
                    dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            2 -> { // 上一首
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                CarCmd.musicStatus(true)
            }
            3 -> { // 下一首
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
                CarCmd.musicStatus(true)
            }
        }
        LogSink.line("MUSIC", "收到车机音乐控制: status=$status")
    }

    private fun dispatchMediaKey(am: AudioManager, keyCode: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        // 过滤常驻前台服务 / 分组摘要 / 进度条
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = n.extras ?: return
        val rawTitle = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (rawTitle.isEmpty() || rawText.isEmpty()) return

        val title = stripBidi(rawTitle)
        val content = stripBidi(rawText)
        if (isNoiseNotification(pkg, title, content, n)) return

        val now = System.currentTimeMillis()
        val key = "$pkg|$title|$content"
        if (key == lastNotifyKey && now - lastNotifyAt < 3000L) return

        if (!isCarReachable()) return
        val cfg = ProbeService.config ?: Config.load(this)

        if (SMS_PACKAGES.contains(pkg)) {
            if (!cfg.syncSms) return
            lastNotifyKey = key
            lastNotifyAt = now
            CarCmd.smsMsg(title, content)
            lastPushSummary = "短信: $title - $content"
            LogSink.line("SMS>>", "$title: $content")
            return
        }

        if (!cfg.syncNotify) return
        if (!isPackageAllowed(pkg, cfg.notifyApps)) return

        lastNotifyKey = key
        lastNotifyAt = now

        val appName = getAppName(pkg)
        // 仅在 Wi-Fi TCP 在线时附带缩放后的图标，避免 BLE MTU 过载
        val iconBase64 = if (ProbeService.carOnline()) loadAppIconBase64(pkg) else null
        CarCmd.notifyMsg(appName, title, content, pkg, iconBase64)
        lastPushSummary = "$appName: $title - $content"
        LogSink.line("NOTIFY>>", "[$appName] $title: $content")
    }

    private fun isPackageAllowed(pkg: String, notifyApps: String): Boolean {
        val rule = notifyApps.trim()
        if (rule == "*") return true
        if (rule.isEmpty()) return false
        return rule.split(",").any { it.trim() == pkg }
    }

    private fun isNoiseNotification(pkg: String, title: String, content: String, n: Notification): Boolean {
        if (content.contains("正在运行") && title.contains("正在运行")) return true
        if (Build.VERSION.SDK_INT >= 26 && SMS_PACKAGES.contains(pkg) && n.channelId == "Mms_Default") {
            if (title.contains("正在运行")) return true
        }
        return false
    }

    private fun stripBidi(s: String): String {
        var out = s
        for (ch in BIDI_CHARS) {
            out = out.replace(ch, "")
        }
        return out
    }

    private fun getAppName(pkg: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun loadAppIconBase64(pkg: String): String? {
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val bmp = drawableToBitmap(drawable, 96)
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, maxSize: Int): Bitmap {
        val src = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(b)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            b
        }
        return if (src.width > maxSize || src.height > maxSize) {
            Bitmap.createScaledBitmap(src, maxSize, maxSize, true)
        } else {
            src
        }
    }

    private fun isMusicSyncEnabled(): Boolean {
        val cfg = ProbeService.config ?: Config.load(this)
        return cfg.syncMusic
    }

    private fun isCarReachable(): Boolean {
        return ProbeService.carOnline() || com.tailg.probe.BleLink.connected
    }

    private fun updateSongSummary(info: TrackInfo?, playing: Boolean) {
        currentSongSummary = if (info == null) {
            if (playing) "播放中（未获取到曲目名）" else "暂无播放"
        } else {
            val stateLabel = if (playing) "播放中" else "已暂停"
            val artistPart = if (info.artist.isNotBlank()) " - ${info.artist}" else ""
            "${info.title}$artistPart ($stateLabel)"
        }
    }

    companion object {
        @Volatile
        var isServiceConnected: Boolean = false
            private set

        @Volatile
        var currentSongSummary: String = "暂无播放"
            private set

        @Volatile
        var lastPushSummary: String = "尚未推送"
            private set

        @Volatile
        private var cachedTrack: TrackInfo? = null

        @Volatile
        private var instance: CarNotificationService? = null

        private val BIDI_CHARS = arrayOf(
            "\u2066", "\u2067", "\u2068", "\u2069",
            "\u202a", "\u202b", "\u202c", "\u202d", "\u202e",
        )

        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.miui.mms",
            "com.huawei.message",
            "com.hihonor.mms",
            "com.coloros.mms",
            "com.oplus.mms",
            "com.vivo.message",
            "com.samsung.android.messaging",
        )

        fun isEnabled(ctx: Context): Boolean {
            return NotificationManagerCompat.getEnabledListenerPackages(ctx)
                .contains(ctx.packageName)
        }

        /** 手动触发一次当前音乐状态同步（UI 测试或重连后使用）。 */
        fun syncMusicNow(): Boolean {
            val inst = instance ?: return false
            val playing = inst.isAnyPlaying()
            val ok = CarCmd.musicStatus(playing)
            cachedTrack?.let { t ->
                CarCmd.musicMsg(t.title, t.artist, t.album, "")
            }
            return ok
        }
    }
}
