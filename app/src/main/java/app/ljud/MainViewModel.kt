package app.ljud

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Tab { Library, Search }

sealed interface Detail {
    data object Liked : Detail
    data class OfPlaylist(val playlist: Playlist) : Detail
    data class OfAlbum(val album: Album) : Detail
    data class OfArtist(val artist: Artist) : Detail
}

/** One entry in a long-press menu. */
class MenuEntry(val label: String, val run: () -> Unit)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val auth = Auth(app)
    private val api = Api(auth)
    private val prefs = app.getSharedPreferences("ljud", Context.MODE_PRIVATE)

    var loggedIn by mutableStateOf(auth.token != null); private set
    var loggingIn by mutableStateOf(false); private set
    var user by mutableStateOf<User?>(null); private set
    var playlists by mutableStateOf(emptyList<Playlist>()); private set
    var playlistsLoading by mutableStateOf(false); private set

    var tab by mutableStateOf(Tab.Library)
    var detail by mutableStateOf<Detail?>(null); private set
    var tracks by mutableStateOf(emptyList<Track>()); private set
    var tracksLoading by mutableStateOf(false); private set
    /** Albums & singles on an artist page. */
    var artistAlbums by mutableStateOf(emptyList<Album>()); private set
    /** Screens to return to with Back. */
    private val detailStack = ArrayDeque<Detail>()

    var query by mutableStateOf("")
    var results by mutableStateOf<SearchResults?>(null); private set
    var searching by mutableStateOf(false); private set

    var playback by mutableStateOf<Playback?>(null); private set
    /** SystemClock.elapsedRealtime() when [playback] was fetched. */
    var playbackAt by mutableLongStateOf(0L); private set

    var showDevices by mutableStateOf(false); private set
    var devices by mutableStateOf(emptyList<Device>()); private set
    var devicesLoading by mutableStateOf(false); private set
    var aliases by mutableStateOf(loadAliases()); private set

    var message by mutableStateOf<String?>(null)

    @Volatile var foreground = false
    private var pendingPlay: JSONObject? = null
    private var hasPendingPlay = false
    private var detailJob: Job? = null
    private var pollJob: Job? = null

    init {
        if (loggedIn) loadLibrary()
        viewModelScope.launch {
            while (true) {
                if (foreground && loggedIn && pollJob?.isActive != true) refreshPlayback()
                if (foreground && phoneSpeaker) pollPhone()
                delay(3000)
            }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun handle(e: Exception) {
        when (e) {
            is CancellationException -> throw e
            is NotLoggedInException -> {
                loggedIn = false
                message = e.message
            }
            else -> message = e.message ?: e.toString()
        }
    }

    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: Exception) {
            handle(e)
        }
    }

    // ---------------------------------------------------------------- login

    fun login(activity: Activity) {
        if (loggingIn) auth.cancel()
        loggingIn = true
        viewModelScope.launch {
            try {
                val pending = io { auth.begin() }
                CustomTabsIntent.Builder().build().launchUrl(activity, pending.url)
                io { auth.finish(pending) }
                loggedIn = true
                loadLibrary()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (loggingIn) message = "Login failed: ${e.message}"
            } finally {
                loggingIn = false
            }
        }
    }

    fun cancelLogin() {
        loggingIn = false
        auth.cancel()
    }

    fun logout() {
        auth.logout()
        loggedIn = false
        user = null
        playlists = emptyList()
        playback = null
        detail = null
        results = null
    }

    // ---------------------------------------------------------------- library

    fun loadLibrary() = launchSafe {
        playlistsLoading = true
        try {
            user = io { api.me() }
            playlists = io { api.playlists() }
        } finally {
            playlistsLoading = false
        }
    }

    /** Open a list, remembering the current one for Back. */
    fun open(d: Detail) {
        detail?.let { if (it != d) detailStack.addLast(it) }
        load(d)
    }

    private fun load(d: Detail) {
        detail = d
        tracks = emptyList()
        artistAlbums = emptyList()
        tracksLoading = true
        detailJob?.cancel()
        detailJob = launchSafe {
            try {
                when (d) {
                    Detail.Liked -> tracks = io { api.liked() }
                    is Detail.OfPlaylist -> tracks = io { api.playlistTracks(d.playlist.id) }
                    is Detail.OfAlbum -> tracks = io { api.albumTracks(d.album) }
                    is Detail.OfArtist -> {
                        val (t, a) = io { api.artistPage(d.artist) }
                        tracks = t
                        artistAlbums = a
                    }
                }
            } finally {
                tracksLoading = false
            }
        }
    }

    fun back() {
        detailJob?.cancel()
        val previous = detailStack.removeLastOrNull()
        if (previous != null) load(previous) else detail = null
    }

    /** Leave all lists (e.g. when switching tabs). */
    fun closeDetails() {
        detailJob?.cancel()
        detailStack.clear()
        detail = null
    }

    // ---------------------------------------------------------------- menus
    // Keep the order in sync with the TUI (`menu_for` in tui/src/app.rs):
    // "Play …" first for albums and artists, then the album, then the artist(s).

    private fun goToAlbum(a: Album) = MenuEntry("Go to album “${a.name}”") { open(Detail.OfAlbum(a)) }
    private fun goToArtist(a: Artist) = MenuEntry("Go to artist “${a.name}”") { open(Detail.OfArtist(a)) }

    fun menuFor(t: Track): List<MenuEntry> =
        listOfNotNull(t.albumRef?.let(::goToAlbum)) + t.artistRefs.map(::goToArtist)

    fun menuFor(a: Album): List<MenuEntry> =
        listOf(MenuEntry("Play album") { playContext(a.uri) }, goToAlbum(a)) + a.artistRefs.map(::goToArtist)

    fun menuFor(a: Artist): List<MenuEntry> =
        listOf(MenuEntry("Play artist") { playContext(a.uri) }, goToArtist(a))

    private fun playContext(uri: String) = play(JSONObject().put("context_uri", uri))

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) return
        launchSafe {
            searching = true
            try {
                results = io { api.search(q) }
            } finally {
                searching = false
            }
        }
    }

    // ---------------------------------------------------------------- playback

    fun refreshSoon(delayMs: Long = 600) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            delay(delayMs)
            refreshPlayback()
        }
    }

    private suspend fun refreshPlayback() {
        try {
            val p = io { api.playback() }
            playback = p
            playbackAt = SystemClock.elapsedRealtime()
            p?.device?.id?.let { prefs.edit().putString("last_device", it).apply() }
        } catch (e: Exception) {
            if (e is NotLoggedInException || e is CancellationException) handle(e)
            // Other polling errors are transient; stay quiet.
        }
    }

    fun progressMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = playback ?: return 0
        val dur = p.track?.durationMs ?: Long.MAX_VALUE
        return if (p.isPlaying) (p.progressMs + (now - playbackAt)).coerceAtMost(dur) else p.progressMs
    }

    /** Play [list] starting at [index], in the context of the open playlist/album if any. */
    fun playFrom(list: List<Track>, index: Int, context: Detail?) {
        val t = list.getOrNull(index) ?: return
        val body = when (context) {
            is Detail.OfPlaylist -> JSONObject()
                .put("context_uri", context.playlist.uri)
                .put("offset", JSONObject().put("position", t.pos))
            is Detail.OfAlbum -> JSONObject()
                .put("context_uri", context.album.uri)
                .put("offset", JSONObject().put("position", t.pos))
            else -> JSONObject().put("uris", JSONArray(list.drop(index).take(100).map { it.uri }))
        }
        play(body)
    }

    private fun play(body: JSONObject?, deviceId: String? = null) = viewModelScope.launch {
        try {
            io { api.play(deviceId, body) }
            refreshSoon()
        } catch (e: ApiException) {
            if (!e.isNoDevice || deviceId != null) return@launch handle(e)
            // No active speaker: try the last one used, otherwise ask.
            val last = prefs.getString("last_device", null)
            if (last != null && runCatching { io { api.play(last, body) } }.isSuccess) {
                refreshSoon()
                return@launch
            }
            pendingPlay = body
            hasPendingPlay = true
            openDevices()
            message = "Choose a speaker to play on"
        } catch (e: Exception) {
            handle(e)
        }
    }

    fun togglePlay() {
        val p = playback
        if (p == null) {
            play(null)
            return
        }
        playback = p.copy(isPlaying = !p.isPlaying, progressMs = progressMs())
        playbackAt = SystemClock.elapsedRealtime()
        if (p.isPlaying) launchSafe { io { api.pause() }; refreshSoon() } else play(null)
    }

    fun next() = launchSafe { io { api.next() }; refreshSoon() }
    fun previous() = launchSafe { io { api.previous() }; refreshSoon() }

    fun setVolume(percent: Int) {
        val p = playback
        val dev = p?.device ?: return
        playback = p.copy(device = dev.copy(volume = percent))
        devices = devices.map { if (it.id == dev.id) it.copy(volume = percent) else it }
        launchSafe { io { api.volume(percent) }; refreshSoon(1000) }
    }

    // ---------------------------------------------------------------- speakers

    fun openDevices() {
        showDevices = true
        devicesLoading = true
        launchSafe {
            try {
                devices = io { api.devices() }
            } finally {
                devicesLoading = false
            }
        }
    }

    fun closeDevices() {
        showDevices = false
        hasPendingPlay = false
        pendingPlay = null
    }

    fun chooseDevice(d: Device) {
        val id = d.id ?: run {
            message = "Spotify doesn't allow controlling this speaker"
            return
        }
        prefs.edit().putString("last_device", id).apply()
        showDevices = false
        if (hasPendingPlay) {
            hasPendingPlay = false
            play(pendingPlay, id).also { pendingPlay = null }
        } else {
            launchSafe {
                io { api.transfer(id, true) }
                message = "Playing on ${deviceName(d)}"
                refreshSoon()
            }
        }
    }

    fun deviceName(d: Device): String =
        d.id?.let { aliases["id:$it"] } ?: aliases["name:${d.name}"]
            ?: if (d.id == phoneDeviceId) "This phone" else d.name

    fun hasAlias(d: Device) = deviceName(d) != d.name

    fun renameDevice(d: Device, name: String) {
        val m = aliases.toMutableMap()
        val n = name.trim()
        if (n.isEmpty()) {
            d.id?.let { m.remove("id:$it") }
            m.remove("name:${d.name}")
        } else {
            d.id?.let { m["id:$it"] = n }
            m["name:${d.name}"] = n
        }
        aliases = m
        prefs.edit().putString("aliases", JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ---------------------------------------------------------------- phone speaker

    private val appCtx = app
    private val playerAuth = Auth(app, "player_auth", PLAYER_CLIENT_ID, 5588, PLAYER_SCOPES)
    var phoneSpeaker by mutableStateOf(prefs.getBoolean("phone_speaker", false)); private set
    var phoneStatus by mutableStateOf(""); private set
    var phoneLoggingIn by mutableStateOf(false); private set
    private val phoneName = PlayerService.deviceName(app)
    val phoneDeviceId: String by lazy { NativePlayer.deviceId(phoneName) }
    private var pendingPhoneToken: String? = null

    /** Called from Activity.onStart. */
    fun onForeground(activity: Activity) {
        foreground = true
        refreshSoon(0)
        // The notification's "Turn off" button may have switched it off meanwhile.
        phoneSpeaker = prefs.getBoolean("phone_speaker", false)
        val token = pendingPhoneToken
        if (token != null) {
            pendingPhoneToken = null
            enablePhone(activity, token)
        } else if (phoneSpeaker) {
            startPhoneService(activity, null)
        }
    }

    fun setPhoneSpeaker(activity: Activity, on: Boolean) {
        if (!on) {
            prefs.edit().putBoolean("phone_speaker", false).apply()
            phoneSpeaker = false
            phoneStatus = ""
            PlayerService.stop(activity)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (NativePlayer.hasCredentials(PlayerService.cacheDir(activity))) {
            enablePhone(activity, null)
            return
        }
        // First time: the built-in player needs its own Spotify login.
        phoneLoggingIn = true
        viewModelScope.launch {
            try {
                val pending = io { playerAuth.begin() }
                CustomTabsIntent.Builder().build().launchUrl(activity, pending.url)
                val token = io { playerAuth.finish(pending) }
                playerAuth.logout() // librespot caches its own reusable credentials
                if (foreground) enablePhone(appCtx, token.access) else pendingPhoneToken = token.access
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message = "Phone speaker login failed: ${e.message}"
            } finally {
                phoneLoggingIn = false
            }
        }
    }

    private fun enablePhone(ctx: Context, token: String?) {
        prefs.edit().putBoolean("phone_speaker", true).apply()
        phoneSpeaker = true
        phoneStatus = "starting"
        startPhoneService(ctx, token)
    }

    private fun startPhoneService(ctx: Context, token: String?) {
        try {
            PlayerService.start(ctx, token)
        } catch (e: Exception) {
            // Android refuses to start it from the background; retry when visible.
            if (token != null) pendingPhoneToken = token
        }
    }

    private suspend fun pollPhone() {
        val status = io { NativePlayer.status() }
        val becameReady = status == "ready" && phoneStatus != "ready"
        phoneStatus = status
        if (becameReady && showDevices) {
            devices = io { runCatching { api.devices() }.getOrDefault(devices) }
        }
    }

    private fun loadAliases(): Map<String, String> {
        val o = runCatching { JSONObject(prefs.getString("aliases", "{}")!!) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }
}
