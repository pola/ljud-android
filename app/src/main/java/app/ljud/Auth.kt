package app.ljud

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

const val CLIENT_ID = "33f4456f57d24de0ae5cadc7cca095b6"
private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
val APP_SCOPES = listOf(
    "user-read-private",
    "user-read-playback-state",
    "user-modify-playback-state",
    "user-read-currently-playing",
    "playlist-read-private",
    "playlist-read-collaborative",
    "user-library-read",
)

/** librespot's client id and the scopes it requests; used for the built-in player's login. */
const val PLAYER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
val PLAYER_SCOPES = listOf(
    "streaming", "user-read-playback-state", "user-modify-playback-state",
    "user-read-currently-playing", "user-read-private", "app-remote-control",
)

data class Token(val access: String, val refresh: String, val expiresAt: Long)

class NotLoggedInException(msg: String) : Exception(msg)

/**
 * Spotify login with Authorization Code + PKCE (no client secret).
 *
 * The redirect URI is the loopback address already registered for the desktop
 * client. During login the app listens on 127.0.0.1:8989 itself; the browser
 * (a Custom Tab) is redirected there, and the app answers by sending the
 * browser to ljud://auth-done, which brings the app back to the front.
 */
class Auth(
    context: Context,
    prefsName: String = "auth",
    private val clientId: String = CLIENT_ID,
    port: Int = 8989,
    scopes: List<String> = APP_SCOPES,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val redirectUri = "http://127.0.0.1:$port/login"
    private val redirectPort = port
    private val scope = scopes.joinToString(" ")

    @Volatile
    var token: Token? = load()
        private set

    @Volatile
    private var server: ServerSocket? = null

    private fun load(): Token? {
        val a = prefs.getString("access", null) ?: return null
        val r = prefs.getString("refresh", null) ?: return null
        return Token(a, r, prefs.getLong("expires_at", 0))
    }

    private fun save(t: Token) {
        token = t
        prefs.edit()
            .putString("access", t.access)
            .putString("refresh", t.refresh)
            .putLong("expires_at", t.expiresAt)
            .apply()
    }

    fun logout() {
        token = null
        prefs.edit().clear().apply()
    }

    class Pending(val url: Uri, val verifier: String, val state: String, val server: ServerSocket)

    /** Start listening for the redirect and build the authorize URL. */
    fun begin(): Pending {
        cancel()
        val verifier = randomString(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val state = randomString(16)
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), redirectPort))
        server = s
        val url = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", scope)
            .build()
        return Pending(url, verifier, state, s)
    }

    /** Stop a login in progress (unblocks [finish]). */
    fun cancel() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    /** Blocking: wait for the browser redirect, then exchange the code for a token. */
    fun finish(p: Pending): Token {
        try {
            p.server.soTimeout = 10 * 60 * 1000
            while (true) {
                val sock = p.server.accept()
                val code = try {
                    handleRedirect(sock, p.state)
                } finally {
                    try {
                        sock.close()
                    } catch (_: IOException) {
                    }
                }
                if (code != null) {
                    val t = exchange(code, p.verifier)
                    save(t)
                    return t
                }
            }
        } finally {
            try {
                p.server.close()
            } catch (_: IOException) {
            }
            if (server === p.server) server = null
        }
    }

    /** Returns the auth code, null to keep waiting, or throws on a login error. */
    private fun handleRedirect(sock: Socket, state: String): String? {
        sock.soTimeout = 3000
        val line = try {
            BufferedReader(InputStreamReader(sock.getInputStream())).readLine()
        } catch (_: IOException) {
            null
        } ?: return null
        val path = line.split(" ").getOrNull(1) ?: ""
        val out = sock.getOutputStream()
        if (!path.startsWith("/login")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return null
        }
        val uri = Uri.parse("http://127.0.0.1$path")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val ok = code != null && uri.getQueryParameter("state") == state
        val msg = when {
            ok -> "Logged in. <a href=\"ljud://auth-done\">Return to ljud</a>"
            error != null -> "Login failed: ${error.take(100).filter { it.isLetterOrDigit() || it == '_' }}"
            else -> "Login failed. Please try again."
        }
        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width\">" +
            "<body style=\"font-family:sans-serif;margin:2em;font-size:1.2em\">$msg</body>"
        val body = html.toByteArray()
        val head = if (ok) "HTTP/1.1 302 Found\r\nLocation: ljud://auth-done\r\n" else "HTTP/1.1 200 OK\r\n"
        out.write((head + "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
        if (!ok) throw IOException(if (error != null) "Spotify login failed: $error" else "Login failed (state mismatch)")
        return code
    }

    private fun exchange(code: String, verifier: String): Token =
        tokenRequest(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "client_id" to clientId,
                "code_verifier" to verifier,
            ),
            null,
        )

    /** A valid access token, refreshing it if it is about to expire. */
    @Synchronized
    fun accessToken(forceRefresh: Boolean = false): String {
        val t = token ?: throw NotLoggedInException("Not logged in")
        if (forceRefresh || t.expiresAt < System.currentTimeMillis() + 60_000) {
            val new = try {
                tokenRequest(
                    mapOf("grant_type" to "refresh_token", "refresh_token" to t.refresh, "client_id" to clientId),
                    t.refresh,
                )
            } catch (e: TokenException) {
                if (e.status in 400..401) {
                    logout()
                    throw NotLoggedInException("Your Spotify session expired. Please log in again.")
                }
                throw e
            }
            save(new)
            return new.access
        }
        return t.access
    }

    private class TokenException(val status: Int, msg: String) : IOException(msg)

    private fun tokenRequest(form: Map<String, String>, oldRefresh: String?): Token {
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray()
        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw TokenException(status, "Spotify login error $status: $text")
            val j = JSONObject(text)
            return Token(
                access = j.getString("access_token"),
                refresh = j.optString("refresh_token").ifEmpty { oldRefresh ?: throw IOException("No refresh token from Spotify") },
                expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun randomString(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
