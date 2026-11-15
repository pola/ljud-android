package app.ljud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
 * Keeps the built-in player running while "Use this phone as a speaker" is on,
 * and shows what the account is playing (on any speaker) as a media
 * notification with controls.
 *
 * Holds a wake lock + Wi-Fi lock and audio focus only while the phone itself plays.
 */
class PlayerService : Service() {
    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val ACTION_STOP = "app.ljud.action.STOP_PLAYER"
        private const val ACTION_PREV = "app.ljud.action.PREV"
        private const val ACTION_TOGGLE = "app.ljud.action.TOGGLE"
        private const val ACTION_NEXT = "app.ljud.action.NEXT"
        private const val CUSTOM_TURN_OFF = "turn_off"
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

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: Handler
    private lateinit var workerThread: HandlerThread
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var audio: AudioManager
    private lateinit var session: MediaSession
    private lateinit var api: Api
    private var focus: AudioFocusRequest? = null
    private var started = false

    // Last known state (main thread).
    private var playback: Playback? = null
    private var playbackAt = 0L
    private var cover: Bitmap? = null
    private var coverUrl: String? = null
    private var lastKey = ""

    private val phoneDeviceId by lazy { NativePlayer.deviceId(deviceName(this)) }

    /** Every 2 s: locks/focus for local playback, and refresh the notification text. */
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
            render()
            main.postDelayed(this, 2000)
        }
    }

    /** Poll Spotify for what's playing: every 5 s while playing, else every 20 s. */
    private val poll = object : Runnable {
        override fun run() {
            val p = try {
                api.playback()
            } catch (_: Exception) {
                null
            }
            main.post {
                playback = p
                playbackAt = SystemClock.elapsedRealtime()
                render()
            }
            val url = p?.track?.albumRef?.images?.pick(300)
            if (url != null && url != coverUrl) {
                val bmp = Covers.load(url)
                main.post {
                    coverUrl = url
                    cover = bmp
                    lastKey = ""
                    render()
                }
            }
            worker.postDelayed(this, if (p?.isPlaying == true) 5000 else 20000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        api = Api(Auth.get(this))
        workerThread = HandlerThread("ljud-poll").apply { start() }
        worker = Handler(workerThread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "What's playing on your Spotify account, while the phone speaker is on"
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
        session = MediaSession(this, "ljud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(ACTION_TOGGLE, forcePlay = true)
                override fun onPause() = command(ACTION_TOGGLE, forcePause = true)
                override fun onSkipToNext() = command(ACTION_NEXT)
                override fun onSkipToPrevious() = command(ACTION_PREV)
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == CUSTOM_TURN_OFF) turnOff()
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                turnOff()
                return START_NOT_STICKY
            }
            ACTION_PREV, ACTION_TOGGLE, ACTION_NEXT -> {
                command(intent.action!!)
                if (started) return START_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!started) {
            started = true
            val token = intent?.getStringExtra(EXTRA_TOKEN)
            val dir = cacheDir(this)
            val name = deviceName(this)
            Thread { NativePlayer.start(dir, name, token) }.start()
            main.post(monitor)
            worker.post(poll)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(monitor)
        worker.removeCallbacks(poll)
        workerThread.quitSafely()
        releaseLocks()
        session.release()
        Thread { NativePlayer.stop() }.start()
        super.onDestroy()
    }

    private fun turnOff() {
        getSharedPreferences("ljud", MODE_PRIVATE).edit().putBoolean("phone_speaker", false).apply()
        stopSelf()
    }

    /** Run a playback command against Spotify (on the worker thread), then refresh. */
    private fun command(action: String, forcePlay: Boolean = false, forcePause: Boolean = false) {
        val playing = playback?.isPlaying == true
        // Optimistic update so the button flips immediately.
        if (action == ACTION_TOGGLE) {
            playback = playback?.copy(isPlaying = if (forcePlay) true else if (forcePause) false else !playing)
            render()
        }
        worker.post {
            try {
                when (action) {
                    ACTION_PREV -> api.previous()
                    ACTION_NEXT -> api.next()
                    ACTION_TOGGLE -> if ((playing && !forcePlay) || forcePause) api.pause() else resume()
                }
            } catch (_: Exception) {
            }
            worker.removeCallbacks(poll)
            worker.postDelayed(poll, 700)
        }
    }

    private fun resume() {
        try {
            api.play(null, null)
        } catch (e: ApiException) {
            if (!e.isNoDevice) throw e
            // Nothing active: resume on the speaker used last.
            val last = getSharedPreferences("ljud", MODE_PRIVATE).getString("last_device", null) ?: throw e
            api.play(last, null)
        }
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

    // ---------------------------------------------------------------- notification

    private fun phoneStatusText(): String? {
        val status = NativePlayer.status()
        return when {
            status == "ready" -> null
            status.startsWith("offline") -> "Phone speaker offline, reconnecting…"
            status.startsWith("error") -> "Phone speaker: " + status.removePrefix("error: ")
            else -> "Phone speaker connecting…"
        }
    }

    /** Update the notification and media session if anything visible changed. */
    private fun render() {
        val p = playback
        val track = p?.track
        val key = listOf(track?.uri, p?.isPlaying, p?.device?.id, p?.device?.volume, coverUrl, phoneStatusText()).toString()
        if (key == lastKey) return
        lastKey = key

        session.setMetadata(
            MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, track?.name ?: "ljud")
                putString(MediaMetadata.METADATA_KEY_ARTIST, track?.artists ?: idleText())
                putString(MediaMetadata.METADATA_KEY_ALBUM, track?.album ?: "")
                putLong(MediaMetadata.METADATA_KEY_DURATION, track?.durationMs ?: 0)
                if (track != null && cover != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
            }.build(),
        )
        val state = when {
            track == null -> PlaybackState.STATE_NONE
            p.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, p?.progressMs ?: 0, if (p?.isPlaying == true) 1f else 0f, playbackAt)
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_TURN_OFF, "Turn off phone speaker", R.drawable.ic_power).build(),
                )
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun idleText(): String =
        phoneStatusText() ?: "Ready as “${deviceName(this)}”"

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlayerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(): Notification {
        val p = playback
        val track = p?.track
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (track == null) {
            return builder
                .setContentTitle("ljud · phone speaker")
                .setContentText(idleText())
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_power), "Turn off", serviceIntent(ACTION_STOP, 1)).build())
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
                .build()
        }
        val speaker = p.device?.let { Speakers.name(Speakers.aliases(getSharedPreferences("ljud", MODE_PRIVATE)), it, phoneDeviceId) }
        val sub = listOfNotNull(speaker?.let { "on $it" }, phoneStatusText()).joinToString(" · ")
        val action = { icon: Int, title: String, a: String, code: Int ->
            Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(a, code)).build()
        }
        return builder
            .setContentTitle(track.name)
            .setContentText(track.artists)
            .setSubText(sub)
            .setLargeIcon(cover)
            .addAction(action(R.drawable.ic_skip_previous, "Previous", ACTION_PREV, 2))
            .addAction(
                if (p.isPlaying) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 3)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE, 3),
            )
            .addAction(action(R.drawable.ic_skip_next, "Next", ACTION_NEXT, 4))
            .addAction(action(R.drawable.ic_power, "Turn off", ACTION_STOP, 1))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }
}
