package com.carter.glasslauncher

import android.app.Application

class GlassLauncherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GlassLauncherApp
            private set
    }
}
