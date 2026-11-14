package dev.antisnorr

import android.app.Application
import dev.antisnorr.alert.AlertNotifier
import dev.antisnorr.core.NightLogger
import dev.antisnorr.core.Settings

class AntiSnorrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        AlertNotifier.createChannels(this)
        NightLogger.init(this)
    }
}
