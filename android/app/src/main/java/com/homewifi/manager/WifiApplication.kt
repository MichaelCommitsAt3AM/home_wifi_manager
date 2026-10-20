package com.homewifi.manager

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class WifiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("network", "Network activity", NotificationManager.IMPORTANCE_DEFAULT))
        scheduleNotifications(this)
    }
}
fun scheduleNotifications(context: Context) {
    val request = PeriodicWorkRequestBuilder<ActivityWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("network-activity", ExistingPeriodicWorkPolicy.KEEP, request)
}
@EntryPoint @InstallIn(SingletonComponent::class)
interface WorkerDependencies {
    fun config(): SecureConfig
    fun factory(): ApiFactory
}
class ActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, WorkerDependencies::class.java)
        val config = deps.config()
        if (!config.notifications || config.token.isBlank()) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        return try {
            val api = deps.factory().create()
            if (config.cursor < 0) {
                config.cursor = api.events(limit = 1).firstOrNull()?.id ?: 0
                return Result.success()
            }
            val categories = api.settings().notification_categories
            val events = api.events(after = config.cursor, limit = 500)
            val relevant = events.filter { it.kind in categories }
            if (relevant.isNotEmpty()) {
                val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val message = relevant.takeLast(4).joinToString("\n") { it.kind.replace('_', ' ') }
                val notification = NotificationCompat.Builder(applicationContext, "network")
                    .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Home network · ${relevant.size} updates")
                    .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                applicationContext.getSystemService(NotificationManager::class.java).notify(100, notification)
            }
            events.maxOfOrNull { it.id }?.let { config.cursor = it }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: retrofit2.HttpException) { if (e.code() == 401) Result.failure() else Result.retry()
        } catch (_: Exception) { Result.retry() }
    }
}
