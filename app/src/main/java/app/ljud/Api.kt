package app.ljud

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val BASE = "https://api.spotify.com/v1"

class ApiException(val status: Int, message: String, val reason: String?) : IOException(message) {
    val isNoDevice get() = status == 404 || reason == "NO_ACTIVE_DEVICE"
}

data class User(val id: String, val name: String)
data class Track(val uri: String, val name: String, val artists: String, val album: String, val durationMs: Long, val pos: Int)
data class Playlist(val id: String, val uri: String, val name: String, val ownerId: String, val ownerName: String, val total: Int)
data class Album(val id: String, val uri: String, val name: String, val artists: String, val total: Int)
data class Device(val id: String?, val name: String, val type: String, val isActive: Boolean, val volume: Int?)
data class Playback(val device: Device?, val isPlaying: Boolean, val progressMs: Long, val track: Track?)
data class SearchResults(val tracks: List<Track>, val albums: List<Album>, val playlists: List<Playlist>)

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)
private fun JSONArray?.objects(): List<JSONObject?> =
    if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) null else optJSONObject(it) }

private fun artists(a: JSONArray?) = a.objects().mapNotNull { it?.str("name") }.joinToString(", ")

private fun parseTrack(o: JSONObject?, pos: Int, album: String? = null): Track? {
    if (o == null) return null
    val uri = o.str("uri").ifEmpty { return null }
    return Track(
        uri = uri,
        name = o.str("name"),
        artists = o.optJSONArray("artists")?.let { artists(it) } ?: o.obj("show")?.str("name").orEmpty(),
        album = album ?: o.obj("album")?.str("name").orEmpty(),
        durationMs = o.optLong("duration_ms"),
        pos = pos,
    )
}

private fun parsePlaylist(o: JSONObject?): Playlist? {
    if (o == null) return null
    val owner = o.obj("owner")
    return Playlist(
        id = o.str("id").ifEmpty { return null },
        uri = o.str("uri"),
        name = o.str("name"),
        ownerId = owner?.str("id").orEmpty(),
        ownerName = owner?.str("display_name")?.ifEmpty { null } ?: owner?.str("id").orEmpty(),
        total = (o.obj("tracks") ?: o.obj("items"))?.optInt("total") ?: 0,
    )
}

private fun parseAlbum(o: JSONObject?): Album? {
    if (o == null) return null
    return Album(
        id = o.str("id").ifEmpty { return null },
        uri = o.str("uri"),
        name = o.str("name"),
        artists = artists(o.optJSONArray("artists")),
        total = o.optInt("total_tracks"),
    )
}

private fun parseDevice(o: JSONObject) = Device(
    id = o.str("id").ifEmpty { null },
    name = o.str("name"),
    type = o.str("type"),
    isActive = o.optBoolean("is_active"),
    volume = if (o.isNull("volume_percent")) null else o.optInt("volume_percent"),
)

/** Playlist items hold the track under "track" (classic) or "item" (newer API). */
private fun itemTrack(it: JSONObject?): JSONObject? = it?.obj("track") ?: it?.obj("item")

/** Blocking Spotify Web API client. Call from a background thread. */
class Api(private val auth: Auth) {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun call(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JSONObject? = null,
    ): JSONObject? {
        val q = query.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        val url = if (path.startsWith("http")) path else BASE + path + if (q.isEmpty()) "" else "?$q"
        var forced = false
        repeat(4) {
            val token = auth.accessToken(forced)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                } else if (method != "GET") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()
                }
                val status = conn.responseCode
                val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()
                val text = (if (status >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                when {
                    status == 401 && !forced -> forced = true
                    status == 429 -> Thread.sleep((retryAfter ?: 1).coerceIn(1, 10) * 1000)
                    status in 200..299 ->
                        return if (text.isBlank()) null else runCatching { JSONObject(text) }.getOrNull()
                    else -> {
                        val err = runCatching { JSONObject(text).opt("error") }.getOrNull()
                        val (msg, reason) = when (err) {
                            is JSONObject -> err.optString("message", text) to err.optString("reason").ifEmpty { null }
                            is String -> err to null
                            else -> text.take(200) to null
                        }
                        throw ApiException(status, "Spotify: $msg", reason)
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Spotify kept rejecting the request")
    }

    private fun get(path: String, query: List<Pair<String, String>> = emptyList()) =
        call("GET", path, query) ?: throw IOException("Empty response from Spotify")

    private fun paged(path: String, query: List<Pair<String, String>>, max: Int): List<JSONObject?> {
        val out = mutableListOf<JSONObject?>()
        var page = get(path, query)
        while (true) {
            out += page.optJSONArray("items").objects()
            if (out.size >= max) return out.take(max)
            val next = page.str("next").ifEmpty { return out }
            page = get(next)
        }
    }

    fun me(): User {
        val o = get("/me")
        return User(o.str("id"), o.str("display_name").ifEmpty { o.str("id") })
    }

    fun playlists(): List<Playlist> =
        paged("/me/playlists", listOf("limit" to "50"), 2000).mapNotNull(::parsePlaylist)

    fun playlistTracks(id: String): List<Track> {
        val q = listOf("limit" to "100", "additional_types" to "track")
        val items = try {
            paged("/playlists/$id/items", q, 10_000)
        } catch (e: ApiException) {
            if (e.status !in listOf(403, 404, 405, 410)) throw e
            paged("/playlists/$id/tracks", q, 10_000)
        }
        return items.mapIndexedNotNull { i, it -> parseTrack(itemTrack(it), i) }
    }

    fun liked(max: Int = 2000): List<Track> =
        paged("/me/tracks", listOf("limit" to "50"), max).mapIndexedNotNull { i, it -> parseTrack(itemTrack(it), i) }

    fun albumTracks(a: Album): List<Track> =
        paged("/albums/${a.id}/tracks", listOf("limit" to "50"), 2000)
            .mapIndexedNotNull { i, it -> parseTrack(it, i, a.name) }

    fun search(query: String): SearchResults {
        // Development-mode apps get at most 10 results per type.
        val o = get("/search", listOf("q" to query, "type" to "track,album,playlist", "limit" to "10"))
        return SearchResults(
            tracks = o.obj("tracks")?.optJSONArray("items").objects().mapIndexedNotNull { i, t -> parseTrack(t, i) },
            albums = o.obj("albums")?.optJSONArray("items").objects().mapNotNull(::parseAlbum),
            playlists = o.obj("playlists")?.optJSONArray("items").objects().mapNotNull(::parsePlaylist),
        )
    }

    fun playback(): Playback? {
        val o = call("GET", "/me/player", listOf("additional_types" to "track,episode")) ?: return null
        return Playback(
            device = o.obj("device")?.let(::parseDevice),
            isPlaying = o.optBoolean("is_playing"),
            progressMs = o.optLong("progress_ms"),
            track = parseTrack(o.obj("item"), 0),
        )
    }

    fun devices(): List<Device> =
        get("/me/player/devices").optJSONArray("devices").objects().mapNotNull { it?.let(::parseDevice) }

    private fun dq(device: String?) = if (device == null) emptyList() else listOf("device_id" to device)

    fun play(device: String?, body: JSONObject?) { call("PUT", "/me/player/play", dq(device), body) }
    fun pause() { call("PUT", "/me/player/pause") }
    fun next() { call("POST", "/me/player/next") }
    fun previous() { call("POST", "/me/player/previous") }
    fun volume(percent: Int) { call("PUT", "/me/player/volume", listOf("volume_percent" to percent.coerceIn(0, 100).toString())) }
    fun transfer(deviceId: String, play: Boolean) {
        call("PUT", "/me/player", body = JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", play))
    }
}
