package com.centrifugamais.app

import android.content.Context

/** Everything needed to talk to the washer without going back to the cloud. */
data class SavedDevice(
    val id: Long,
    val name: String,
    val type: Int,
    val ip: String,
    val port: Int,
    val protocol: Int,
    val token: String,
    val key: String,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("centrifuga", Context.MODE_PRIVATE)

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit().putString("email", v).apply()

    var spinSpeed: Int
        get() = sp.getInt("spinSpeed", -1)
        set(v) = sp.edit().putInt("spinSpeed", v).apply()

    var spinTime: Int
        get() = sp.getInt("spinTime", -1)
        set(v) = sp.edit().putInt("spinTime", v).apply()

    /**
     * What each spin-time level turned out to be on this machine: level -> minutes,
     * or -1 when the machine refused that level.
     */
    var spinLevels: Map<Int, Int>
        get() = (sp.getString("spinLevels", "") ?: "").split(',').mapNotNull { e ->
            val parts = e.split(':')
            if (parts.size == 2) parts[0].toIntOrNull()?.let { k -> parts[1].toIntOrNull()?.let { k to it } } else null
        }.toMap()
        set(v) = sp.edit().putString("spinLevels", v.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    /**
     * Version 0.2.0 could wrongly learn "refused" when it resumed a paused cycle;
     * drop those entries once.
     */
    fun migrateSpinLevels() {
        if (sp.getBoolean("levelsMigrated3", false)) return
        spinLevels = spinLevels.filterValues { it >= 0 }
        sp.edit().putBoolean("levelsMigrated3", true).apply()
    }

    var device: SavedDevice?
        get() {
            if (!sp.contains("dev_id")) return null
            return SavedDevice(
                id = sp.getLong("dev_id", 0),
                name = sp.getString("dev_name", "") ?: "",
                type = sp.getInt("dev_type", 0),
                ip = sp.getString("dev_ip", "") ?: "",
                port = sp.getInt("dev_port", 6444),
                protocol = sp.getInt("dev_protocol", 3),
                token = sp.getString("dev_token", "") ?: "",
                key = sp.getString("dev_key", "") ?: "",
            )
        }
        set(d) {
            val e = sp.edit()
            if (d == null) {
                listOf("dev_id", "dev_name", "dev_type", "dev_ip", "dev_port", "dev_protocol", "dev_token", "dev_key")
                    .forEach { e.remove(it) }
            } else {
                e.putLong("dev_id", d.id).putString("dev_name", d.name).putInt("dev_type", d.type)
                    .putString("dev_ip", d.ip).putInt("dev_port", d.port).putInt("dev_protocol", d.protocol)
                    .putString("dev_token", d.token).putString("dev_key", d.key)
            }
            e.apply()
        }
}
