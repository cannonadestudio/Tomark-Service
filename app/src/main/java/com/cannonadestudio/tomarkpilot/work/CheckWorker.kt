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

/** Daily: download bulletin list, notify about new bulletins and maintenance coming due. */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        // Failures are fine – we still check maintenance with what we have.
        Repo.refreshBulletins()
        Repo.refreshContacts()
        Repo.refreshTypes()
        Repo.syncLogbook()

        val s = Repo.state.value
        val feed = Repo.feed.value

        // New bulletins applicable to any of the owner's aircraft
        val applicable = s.aircraft.flatMap { Repo.bulletinsFor(it, feed) }.map { it.id }.toSet()
        val fresh = applicable - s.notifiedBulletinIds
        if (fresh.isNotEmpty()) {
            notify(
                ID_BULLETINS,
                applicationContext.getString(R.string.notif_new_bulletins, fresh.size),
                feed.bulletins.filter { it.id in fresh }.joinToString("\n") { "${it.id}: ${it.title}" },
                MainActivity.TAB_BULLETINS,
            )
            Repo.update { it.copy(notifiedBulletinIds = it.notifiedBulletinIds + fresh) }
        }

        // Maintenance per aircraft
        s.aircraft.forEachIndexed { i, a ->
            val due = Maintenance.compute(a, s.completions).filter { Maintenance.needsAttention(it) }
            if (due.isNotEmpty()) {
                val lang = java.util.Locale.getDefault().language
                notify(
                    ID_MAINT_BASE + i,
                    applicationContext.getString(R.string.notif_maintenance, a.registration, due.size),
                    due.joinToString("\n") { it.task.title.get(lang) },
                    MainActivity.TAB_SERVICE,
                )
            }
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String, tab: Int) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL = "maintenance"
        private const val ID_BULLETINS = 1000
        private const val ID_MAINT_BASE = 2000

        fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily_check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
