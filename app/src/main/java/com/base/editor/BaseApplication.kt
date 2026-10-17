package com.base.editor

import android.app.Application

class BaseApplication : Application() {
    companion object {
        var instance: BaseApplication? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
