package com.cannonadestudio.tomarkpilot.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class DueStatus { OVERDUE, DUE, SOON, OK, DONE, NO_INTERVAL }

data class DueInfo(
    val task: TaskDef,
    val last: CompletionRecord?,
    val dueHours: Double?,
    val dueDate: LocalDate?,
    val remainingHours: Double?,
    val remainingDays: Long?,
    val status: DueStatus,
    val isCustom: Boolean,
)

object Maintenance {
    const val SOON_HOURS = 10.0
    const val SOON_DAYS = 30L

    /** Task definition with the owner's interval overrides applied. */
    fun effective(t: TaskDef, a: Aircraft): TaskDef {
        val o = a.taskOverrides[t.id] ?: return t
        return t.copy(
            intervalHours = o.intervalHours ?: t.intervalHours,
            intervalMonths = o.intervalMonths ?: t.intervalMonths,
        )
    }

    fun tasks(a: Aircraft): List<Pair<TaskDef, Boolean>> {
        val type = Repo.type(a.typeId) ?: return emptyList()
        return type.tasks.map { it to false } + a.customTasks.map { it to true }
    }

    fun compute(
        a: Aircraft,
        records: List<CompletionRecord>,
        today: LocalDate = LocalDate.now(),
        includeHidden: Boolean = false,
    ): List<DueInfo> =
        tasks(a)
            .filter { (t, _) -> includeHidden || a.taskOverrides[t.id]?.hidden != true }
            .map { (t, custom) ->
                compute(effective(t, a), a, records.filter { it.aircraftId == a.id && it.taskId == t.id }, today, custom)
            }
            .sortedWith(compareBy({ it.status.ordinal }, { it.remainingHours ?: Double.MAX_VALUE }, { it.remainingDays ?: Long.MAX_VALUE }))

    fun compute(t: TaskDef, a: Aircraft, records: List<CompletionRecord>, today: LocalDate, custom: Boolean): DueInfo {
        val last = records.maxWithOrNull(compareBy({ it.hours }, { it.epochDay }))
        val tol = t.toleranceHours ?: 0.0

        // One-off item (e.g. 25 h inspection after delivery / new engine).
        if (t.oneTimeAtHours != null) {
            if (last != null) return DueInfo(t, last, null, null, null, null, DueStatus.DONE, custom)
            val rem = t.oneTimeAtHours - a.hours
            return DueInfo(t, null, t.oneTimeAtHours, null, rem, null, statusOf(rem, null, tol), custom)
        }

        if (t.intervalHours == null && t.intervalMonths == null) {
            return DueInfo(t, last, null, null, null, null, DueStatus.NO_INTERVAL, custom)
        }

        var dueHours: Double? = null
        if (t.intervalHours != null) {
            val base = when {
                last == null -> 0.0
                // SB_SD4-03-2023: next due is counted from the previous DUE time when the
                // inspection was done within tolerance – tolerances do not accumulate.
                last.dueAtHours != null && t.toleranceHours != null &&
                    abs(last.hours - last.dueAtHours) <= t.toleranceHours -> last.dueAtHours
                else -> last.hours
            }
            dueHours = base + t.intervalHours
        }

        var dueDate: LocalDate? = null
        if (t.intervalMonths != null) {
            val ref = last?.epochDay ?: a.referenceEpochDay
            dueDate = ref?.let { LocalDate.ofEpochDay(it).plusMonths(t.intervalMonths.toLong()) }
        }

        val remH = dueHours?.let { it - a.hours }
        val remD = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
        return DueInfo(t, last, dueHours, dueDate, remH, remD, statusOf(remH, remD, tol), custom)
    }

    private fun statusOf(remH: Double?, remD: Long?, tol: Double): DueStatus {
        if ((remH != null && remH < -tol) || (remD != null && remD < 0)) return DueStatus.OVERDUE
        if ((remH != null && remH <= 0.0) || (remD != null && remD == 0L)) return DueStatus.DUE
        if ((remH != null && remH <= SOON_HOURS) || (remD != null && remD <= SOON_DAYS)) return DueStatus.SOON
        return DueStatus.OK
    }

    fun needsAttention(d: DueInfo) = d.status == DueStatus.OVERDUE || d.status == DueStatus.DUE || d.status == DueStatus.SOON
}
