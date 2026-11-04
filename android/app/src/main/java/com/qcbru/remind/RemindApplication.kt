package com.qcbru.remind

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.qcbru.remind.data.LocalStore

class RemindApplication : Application() {
    lateinit var store: LocalStore
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
            else PlayIntegrityAppCheckProviderFactory.getInstance()
        )
        store = LocalStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
