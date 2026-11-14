package app.ljud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import java.io.File

/** JNI bridge to the Rust player (libljudplayer.so). */
object NativePlayer {
    init {
        System.loadLibrary("ljudplayer")
    }

    @JvmStatic external fun start(cacheDir: String, name: String, token: String?): Boolean
    @JvmStatic external fun stop()
    @JvmStatic external fun status(): String
    @JvmStatic external fun hasCredentials(cacheDir: String): Boolean
    @JvmStatic external fun deviceId(name: String): String
    @JvmStatic external fun isPlaying(): Boolean
    @JvmStatic external fun pause()
}

/**
 * Keeps the built-in player running while "Use this phone as a speaker" is on.
 * Holds a wake lock + Wi-Fi lock and audio focus only while music is playing.
 */
class PlayerService : Service() {
    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val ACTION_STOP = "app.ljud.action.STOP_PLAYER"
        private const val CHANNEL = "player"
        private const val NOTIFICATION_ID = 1

        fun cacheDir(ctx: Context): String = File(ctx.filesDir, "player").apply { mkdirs() }.absolutePath

        /** The Spotify Connect name of this phone, e.g. "Galaxy S24 (ljud)". */
        fun deviceName(ctx: Context): String {
            val name = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
            return "$name (ljud)"
        }

        fun start(ctx: Context, token: String?) {
            ctx.startForegroundService(Intent(ctx, PlayerService::class.java).putExtra(EXTRA_TOKEN, token))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PlayerService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null
    private var lastText = ""

    private val monitor = object : Runnable {
        override fun run() {
            val playing = NativePlayer.isPlaying()
            if (playing && !wakeLock.isHeld) {
                wakeLock.acquire(6 * 60 * 60 * 1000L)
                wifiLock.acquire()
                requestFocus()
            } else if (!playing && wakeLock.isHeld) {
                releaseLocks()
            }
            val status = NativePlayer.status()
            val text = when {
                playing -> "Playing"
                status == "ready" -> "Ready as “${deviceName(this@PlayerService)}”"
                status.startsWith("offline") -> "Offline, reconnecting…"
                status.startsWith("error") -> status.removePrefix("error: ").replaceFirstChar { it.uppercase() }
                else -> "Connecting…"
            }
            if (text != lastText) {
                lastText = text
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone speaker", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone is available as a Spotify speaker"
                setShowBadge(false)
            },
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ljud:player")
            .apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ljud:player")
            .apply { setReferenceCounted(false) }
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("ljud", MODE_PRIVATE).edit().putBoolean("phone_speaker", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        val dir = cacheDir(this)
        val name = deviceName(this)
        Thread { NativePlayer.start(dir, name, token) }.start()
        handler.removeCallbacks(monitor)
        handler.post(monitor)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        releaseLocks()
        Thread { NativePlayer.stop() }.start()
        super.onDestroy()
    }

    private fun releaseLocks() {
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                // A call or another app took over: pause (Spotify sees a normal pause).
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    NativePlayer.pause()
                }
            }
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("ljud · phone speaker")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }
}
