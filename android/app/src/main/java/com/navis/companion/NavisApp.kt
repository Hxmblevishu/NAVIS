package com.navis.companion

import android.app.Application
import android.util.Log

class NavisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("NavisApp", "NAVIS Assistive Companion Application Initialized")
    }
}
