package com.telegramproxy

import android.app.Application

class TelegramProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Eager init managers
        SubscriptionManager.get(this)
        XrayCore.get(this)
    }
}
