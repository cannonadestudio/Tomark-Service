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
import com.cannonadestudio.tomarkpilot.MainActivity
import com.cannonadestudio.tomarkpilot.R
import com.cannonadestudio.tomarkpilot.data.DueStatus
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.ui.fmtH

/**
 * Local push notifications (no server push needed):
 *  - maintenance: once when an item is within [AppState.remindHours] of flight or [AppState.remindDays], once when it
 *    becomes due / overdue – checked daily in the background and right after flight hours change in the app;
 *  - bulletins: new ones for the owner's aircraft; AD / mandatory ones on the "important" channel;
 *  - announcements (Komunikaty from the panel).
 */
object Notifier {
    const val CH_MAINT = "maintenance"
    const val CH_IMPORTANT = "important"
    private const val ID_BULLETINS = 1000
    private const val ID_BULLETINS_IMPORTANT = 1001
    private const val ID_NEWS = 3000
    private const val ID_MAINT_BASE = 2000

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MAINT, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_IMPORTANT, ctx.getString(R.string.notif_channel_important), NotificationManager.IMPORTANCE_HIGH))
    }

    /** Maintenance reminders – cheap, safe to call often. */
    fun checkMaintenance(ctx: Context) {
        val s = Repo.state.value
        val lang = java.util.Locale.getDefault().language
        val sent = mutableSetOf<String>()
        s.aircraft.forEachIndexed { i, a ->
            val lines = mutableListOf<String>()
            var urgent = false
            for (d in Maintenance.compute(a, s.completions)) {
                if (d.status == DueStatus.DONE || d.status == DueStatus.NO_INTERVAL || d.status == DueStatus.NO_DATA) continue
                val over = d.status == DueStatus.OVERDUE || d.status == DueStatus.DUE ||
                    (d.remainingHours != null && d.remainingHours <= 0) || (d.remainingDays != null && d.remainingDays <= 0)
                val near = (d.remainingHours != null && d.remainingHours <= s.remindHours) ||
                    (d.remainingDays != null && d.remainingDays <= s.remindDays)
                if (!over && !near) continue
                val level = if (over) "over" else "near"
                val key = "${a.id}|${d.task.id}|${d.dueHours ?: ""}|${d.dueDate ?: ""}|$level"
                if (key in s.notifiedDue) continue
                sent += key
                val title = d.task.title.get(lang)
                lines += when {
                    over -> ctx.getString(R.string.notif_due_over, title)
                    d.remainingHours != null && d.remainingHours <= s.remindHours -> ctx.getString(R.string.notif_due_hours, title, fmtH(d.remainingHours))
                    else -> ctx.getString(R.string.notif_due_days, title, (d.remainingDays ?: 0).toInt())
                }
                if (over) urgent = true
            }
            if (lines.isNotEmpty()) {
                notify(ctx, ID_MAINT_BASE + i, if (urgent) CH_IMPORTANT else CH_MAINT,
                    ctx.getString(R.string.notif_maintenance_title, a.registration), lines.joinToString("\n"), MainActivity.TAB_SERVICE)
            }
        }
        if (sent.isNotEmpty()) Repo.updateLocal { st ->
            // forget keys of tasks that were done since (new due point = new key)
            st.copy(notifiedDue = (st.notifiedDue + sent).toList().takeLast(500).toSet())
        }
    }

    /** New bulletins and announcements – after the lists were downloaded. */
    fun checkFeeds(ctx: Context) {
        val s = Repo.state.value
        if (s.notifyBulletins) {
            val feed = Repo.feed.value
            val applicable = s.aircraft.flatMap { Repo.bulletinsFor(it, feed) }.associateBy { it.id }
            val fresh = applicable.keys - s.notifiedBulletinIds
            if (fresh.isNotEmpty()) {
                // first run after install: remember silently, do not announce the whole history
                if (s.notifiedBulletinIds.isNotEmpty()) {
                    val list = fresh.mapNotNull { applicable[it] }.filter { it.kind != "DOC" }
                    val important = list.filter { it.kind == "AD" || it.mandatory == true }
                    val other = list - important.toSet()
                    if (important.isNotEmpty()) notify(ctx, ID_BULLETINS_IMPORTANT, CH_IMPORTANT,
                        ctx.getString(R.string.notif_important_bulletins, important.size), important.joinToString("\n") { "${it.id}: ${it.title}" }, MainActivity.TAB_BULLETINS)
                    if (other.isNotEmpty()) notify(ctx, ID_BULLETINS, CH_MAINT,
                        ctx.getString(R.string.notif_new_bulletins, other.size), other.joinToString("\n") { "${it.id}: ${it.title}" }, MainActivity.TAB_BULLETINS)
                }
                Repo.updateLocal { it.copy(notifiedBulletinIds = it.notifiedBulletinIds + fresh) }
            }
        }
        if (s.notifyNews) {
            val news = Repo.news.value.messages
            val freshNews = news.filter { it.id !in s.notifiedNewsIds }
            if (freshNews.isNotEmpty()) {
                // only recent announcements (last 14 days or undated) – not the whole history after installing
                val since = java.time.LocalDate.now().minusDays(14).toString()
                freshNews.filter { it.date.isBlank() || it.date >= since }.take(3).forEachIndexed { i, n ->
                    notify(ctx, ID_NEWS + i, CH_IMPORTANT, n.title, n.text.ifBlank { n.title }, MainActivity.TAB_HOME)
                }
                Repo.updateLocal { it.copy(notifiedNewsIds = it.notifiedNewsIds + freshNews.map { n -> n.id }) }
            }
        }
    }

    private fun notify(ctx: Context, id: Int, channel: String, title: String, text: String, tab: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_plane)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (channel == CH_IMPORTANT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
