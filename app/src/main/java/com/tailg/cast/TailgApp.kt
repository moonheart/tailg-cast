package com.tailg.cast

import android.app.Application

class TailgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivKitBridge.init(this)
    }
}
