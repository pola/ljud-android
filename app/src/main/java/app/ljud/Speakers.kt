package app.ljud

import android.content.SharedPreferences
import org.json.JSONObject

/** Custom speaker names, shared by the UI and the notification. */
object Speakers {
    /** Aliases keyed "id:<device id>" and "name:<reported name>". */
    fun aliases(prefs: SharedPreferences): Map<String, String> {
        val o = runCatching { JSONObject(prefs.getString("aliases", "{}")!!) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun name(aliases: Map<String, String>, d: Device, phoneDeviceId: String?): String =
        d.id?.let { aliases["id:$it"] } ?: aliases["name:${d.name}"]
            ?: if (d.id != null && d.id == phoneDeviceId) "This phone" else d.name
}
