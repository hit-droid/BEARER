package com.offlineagent

import android.app.Application
import com.offlineagent.di.AppContainer

class OfflineAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
