package com.example.btmessenger

import android.content.Context
import java.util.UUID

/** Настройки: профиль пользователя, id установки, список доверенных собеседников. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Постоянный случайный id этой установки — по нему телефоны узнают друг друга. */
    val installId: String by lazy {
        sp.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_INSTALL_ID, it).apply()
        }
    }

    var userName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_NAME, value.cleanLine(64)).apply()

    var userRole: String
        get() = sp.getString(KEY_ROLE, "") ?: ""
        set(value) = sp.edit().putString(KEY_ROLE, value.cleanLine(64)).apply()

    var profileAsked: Boolean
        get() = sp.getBoolean(KEY_PROFILE_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_PROFILE_ASKED, value).apply()

    var notificationsAsked: Boolean
        get() = sp.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    var showAllDevices: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ALL, value).apply()

    /**
     * Доверенные — те, к кому вы подключались сами или чьё подключение приняли.
     * От них входящее подключение принимается без вопроса (нужно для автопереподключения).
     */
    @Synchronized
    fun isTrusted(address: String): Boolean = trusted().contains(address)

    @Synchronized
    fun trust(address: String) {
        val set = HashSet(trusted())
        if (set.add(address)) sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    @Synchronized
    fun untrust(address: String) {
        val set = HashSet(trusted())
        if (set.remove(address)) sp.edit().putStringSet(KEY_TRUSTED, set).apply()
    }

    private fun trusted(): Set<String> = sp.getStringSet(KEY_TRUSTED, null) ?: emptySet()

    private companion object {
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_NAME = "user_name"
        const val KEY_ROLE = "user_role"
        const val KEY_PROFILE_ASKED = "profile_asked"
        const val KEY_SHOW_ALL = "show_all_devices"
        const val KEY_NOTIF_ASKED = "notifications_asked"
        const val KEY_TRUSTED = "trusted"
    }
}
