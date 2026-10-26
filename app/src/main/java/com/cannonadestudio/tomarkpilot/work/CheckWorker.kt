package com.cannonadestudio.tomarkpilot.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cannonadestudio.tomarkpilot.MainActivity
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Repo
import java.util.concurrent.TimeUnit

/** Every 12 h: download the lists, then notify (see [Notifier]). */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        // Failures are fine – we still check maintenance with what we have.
        Repo.refreshAll()
        Repo.backupNow()
        Repo.syncLogbook()

        Notifier.checkFeeds(applicationContext)
        Notifier.checkMaintenance(applicationContext)
        return Result.success()
    }

    companion object {
        fun createChannel(ctx: Context) = Notifier.createChannels(ctx)

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily_check", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
