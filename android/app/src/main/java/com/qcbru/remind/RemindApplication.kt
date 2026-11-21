package com.qcbru.remind

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.qcbru.remind.data.LocalStore
import com.qcbru.remind.alerts.AlertNotifier

class RemindApplication : Application() {
    lateinit var store: LocalStore
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        store = LocalStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("reminders", "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("priority_alarms", "Priority alarms", NotificationManager.IMPORTANCE_HIGH))
        AlertNotifier.migrate(this)
    }
}
