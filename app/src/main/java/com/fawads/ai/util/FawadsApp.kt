package com.fawads.ai.util

import android.app.Application

class FawadsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: FawadsApp
            private set
    }
}
