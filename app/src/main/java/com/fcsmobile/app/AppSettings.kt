package com.fcsmobile.app

import android.content.Context
import androidx.work.*
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("FCSMobile", Context.MODE_PRIVATE)
    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) { prefs.edit().putString("host", value).apply() }
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) { prefs.edit().putString("token", value).apply() }
    var excluded: Set<String>
        get() = prefs.getStringSet("excluded", emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("excluded", value).apply() }
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    var status: String
        get() = prefs.getString("status", "En attente de configuration") ?: ""
        set(value) { prefs.edit().putString("status", value).apply() }
    val lastSuccess: Long
        get() = prefs.getLong("last_success", 0)
    val lastAutomaticSuccess: Long
        get() = prefs.getLong("last_automatic_success", 0)
    fun recordSuccess(automatic: Boolean) {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_success", now)
            .putLong(if (automatic) "last_automatic_success" else "last_manual_success", now).apply()
    }
    val configured get() = host.isNotBlank() && token.isNotBlank()

    companion object {
        const val PERIODIC = "fcs-mobile-periodic"
        const val REQUESTED = "fcs-mobile-requested"
        private const val SCHEDULE_VERSION = 2
        fun constraints() = Constraints.Builder().setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val settings = AppSettings(context)
            if (!settings.automatic || !settings.configured) {
                manager.cancelUniqueWork(PERIODIC)
                return
            }
            val migrate = settings.prefs.getInt("schedule_version", 0) < SCHEDULE_VERSION
            val operation = manager.enqueueUniquePeriodicWork(PERIODIC,
                if (migrate) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .setInputData(workDataOf(BackupWorker.AUTOMATIC to true))
                    .setConstraints(constraints()).setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build())
            if (migrate) operation.result.addListener({
                try {
                    operation.result.get()
                    settings.prefs.edit().putInt("schedule_version", SCHEDULE_VERSION).apply()
                } catch (_: Exception) {
                    settings.status = "Planification automatique interrompue — rouvrir l’application pour réessayer"
                }
            }, ContextCompat.getMainExecutor(context))
        }

        fun requestBackup(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(REQUESTED, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints())
                    .setInputData(workDataOf(BackupWorker.AUTOMATIC to false))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
