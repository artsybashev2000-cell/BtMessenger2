package com.example.btmessenger

import android.app.Application
import android.content.Context

/** Общие объекты приложения: живут дольше экрана, поэтому связь не зависит от Activity. */
class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: ChatRepository
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var link: BtLink
        private set

    /** Экран приложения сейчас на виду. */
    @Volatile
    var uiVisible = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = ChatRepository(this)
        notifier = Notifier(this).also { it.createChannels() }
        link = BtLink(this, prefs, repo)
    }
}

val Context.core: App get() = applicationContext as App
