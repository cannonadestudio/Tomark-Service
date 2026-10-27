package com.cannonadestudio.tomarkpilot.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

/** NO_DATA: never logged and the hours/date cannot be worked out – the owner should enter the last time it was done. */
enum class DueStatus { OVERDUE, DUE, SOON, OK, NO_DATA, DONE, NO_INTERVAL }

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

    /** Task ids that used to live in every type (now from the engine catalogue and the aircraft options). */
    private val LEGACY = Regex("^(rotax_.*|prop|brs_.*)$")

    /** Task definition with the owner's interval overrides applied. */
    fun effective(t: TaskDef, a: Aircraft): TaskDef {
        val edited = a.itemEdits[t.id]?.let { t.copy(items = it) } ?: t
        val o = a.taskOverrides[t.id] ?: return edited
        return edited.copy(
            intervalHours = o.intervalHours ?: t.intervalHours,
            intervalMonths = o.intervalMonths ?: t.intervalMonths,
        )
    }

    fun isHidden(t: TaskDef, a: Aircraft) = a.taskOverrides[t.id]?.hidden ?: t.defaultHidden

    private fun l(pl: String, en: String, sk: String = en, fr: String = en, de: String = en, cs: String = sk) = L10n(pl, en, sk, fr, de, cs)

    private val AIRFRAME = l("Płatowiec", "Airframe", "Drak", "Cellule", "Zelle", "Drak")
    private val ENGINE = l("Silnik", "Engine", "Motor", "Moteur", "Motor", "Motor")

    private fun prefixed(t: L10n, p: L10n) = L10n(
        p.pl + ": " + t.pl, p.en + ": " + t.en, t.sk?.let { p.sk + ": " + it }, t.fr?.let { p.fr + ": " + it },
        t.de?.let { p.de + ": " + it }, t.cs?.let { p.cs + ": " + it },
    )

    /** "Silnik: Wymiana oleju" -> ("Silnik", "Wymiana oleju") – the checklist groups items by that prefix. */
    fun splitSection(title: String): Pair<String, String> {
        val i = title.indexOf(": ")
        return if (i in 1..24) title.substring(0, i) to title.substring(i + 2) else "" to title
    }

    private fun fmt(h: Double) = if (h % 1.0 == 0.0) h.toInt().toString() else h.toString()

    /**
     * The aircraft's programme: airframe tasks of the type + the chosen engine's inspections + propeller and
     * rescue-system items when fitted + the owner's own tasks. Airframe and engine inspections with the same
     * interval become one periodic inspection listing everything to do.
     */
    fun tasks(a: Aircraft): List<Pair<TaskDef, Boolean>> {
        val type = Repo.type(a.typeId) ?: return a.customTasks.map { it to true }
        val airframe = type.tasks.filterNot { LEGACY.matches(it.id) }.toMutableList()
        val engine = Repo.engineOf(a).tasks.toMutableList()
        val out = mutableListOf<TaskDef>()

        val engBase = engine.filter { it.intervalHours != null && it.intervalMonths != null && it.oneTimeAtHours == null && !it.id.endsWith("tbo") }
            .minByOrNull { it.intervalHours!! }
        val afBase = engBase?.let { e -> airframe.firstOrNull { it.intervalHours == e.intervalHours && it.intervalMonths != null && it.oneTimeAtHours == null } }
        if (engBase != null && afBase != null) {
            val h = fmt(engBase.intervalHours!!)
            val m = minOf(engBase.intervalMonths!!, afBase.intervalMonths!!)
            out += engBase.copy(
                title = l("Przegląd okresowy $h h / $m mies.", "Periodic inspection $h h / $m months", "Periodická prehliadka $h h / $m mes.",
                    "Visite périodique $h h / $m mois", "Regelinspektion $h h / $m Monate", "Periodická prohlídka $h h / $m měs."),
                category = "periodic",
                intervalMonths = m,
                items = (afBase.items.takeIf { it.isNotEmpty() }?.map { it.copy(title = prefixed(it.title, AIRFRAME)) } ?: listOf(
                    TaskItem(l("Płatowiec: przegląd okresowy wg instrukcji obsługi producenta", "Airframe: periodic inspection per the maintenance manual",
                        "Drak: periodická prehliadka podľa príručky údržby", "Cellule : visite selon le manuel d'entretien",
                        "Zelle: Inspektion nach Wartungshandbuch", "Drak: periodická prohlídka dle příručky údržby"), verified = afBase.verified),
                )) + engBase.items.map { it.copy(title = prefixed(it.title, ENGINE)) },
                itemsSource = listOf(afBase.itemsSource, engBase.itemsSource).filter { it.isNotBlank() }.joinToString(" · "),
                aliases = engBase.aliases + afBase.id + afBase.aliases,
                source = listOf(afBase.source, engBase.source).filter { it.isNotBlank() }.joinToString(" · "),
                mandatory = true,
            )
            airframe.remove(afBase); engine.remove(engBase)
        }
        out += airframe
        out += engine
        // Propeller: obligatory only for in-flight adjustable propellers; optional otherwise.
        val prop = a.propInspection ?: (a.propeller == "inflight")
        if (prop) out += TaskDef(
            id = "prop",
            title = l("Przegląd śmigła (wg instrukcji producenta śmigła)", "Propeller inspection (per propeller manual)", "Prehliadka vrtule",
                "Inspection de l'hélice", "Propellerinspektion", "Prohlídka vrtule"),
            category = "propeller", intervalMonths = 12, mandatory = a.propeller == "inflight",
            source = "Instrukcja producenta śmigła / propeller manual", verified = false,
            note = l("Ustaw interwał zgodnie z instrukcją swojego śmigła.", "Set the interval from your propeller's manual."),
        )
        if (a.rescueSystem) {
            val y = a.rescueMonths / 12.0
            val ys = if (a.rescueMonths % 12 == 0) (a.rescueMonths / 12).toString() else String.format(java.util.Locale.US, "%.1f", y)
            out += TaskDef(
                id = "brs_repack",
                title = l("System ratunkowy – resurs / przepakowanie (co $ys lat)", "Rescue system – service life / repack (every $ys years)",
                    "Záchranný systém – prebalenie (každých $ys rokov)", "Parachute – repliage (tous les $ys ans)",
                    "Rettungssystem – Packintervall ($ys Jahre)", "Záchranný systém – přebalení (každých $ys let)"),
                category = "rescue", intervalMonths = a.rescueMonths, mandatory = true,
                source = "Instrukcja producenta systemu ratunkowego / rescue system manual", verified = true,
                aliases = listOf("brs_rocket"),
            )
        }
        return out.map { it to false } + a.customTasks.map { it to true }
    }

    fun recordsFor(t: TaskDef, a: Aircraft, records: List<CompletionRecord>) =
        records.filter { it.aircraftId == a.id && (it.taskId == t.id || it.taskId in t.aliases) }

    fun compute(
        a: Aircraft,
        records: List<CompletionRecord>,
        today: LocalDate = LocalDate.now(),
        includeHidden: Boolean = false,
    ): List<DueInfo> =
        tasks(a)
            .filter { (t, _) -> includeHidden || !isHidden(t, a) }
            .map { (t, custom) -> compute(effective(t, a), a, recordsFor(t, a, records), today, custom) }
            .sortedWith(compareBy({ it.status.ordinal }, { it.remainingHours ?: Double.MAX_VALUE }, { it.remainingDays ?: Long.MAX_VALUE }))

    fun compute(t: TaskDef, a: Aircraft, records: List<CompletionRecord>, today: LocalDate, custom: Boolean): DueInfo {
        val last = records.maxWithOrNull(compareBy({ it.hours }, { it.epochDay }))
        val tol = t.toleranceHours ?: 0.0

        // One-off item (e.g. 25 h inspection after delivery / new engine).
        if (t.oneTimeAtHours != null) {
            if (last != null) return DueInfo(t, last, null, null, null, null, DueStatus.DONE, custom)
            // Aircraft well past it: surely done long ago, even if not logged – no false alarm.
            if (a.hours >= t.oneTimeAtHours * 2 + 10) return DueInfo(t, null, null, null, null, null, DueStatus.DONE, custom)
            val rem = t.oneTimeAtHours - a.hours
            return DueInfo(t, null, t.oneTimeAtHours, null, rem, null, statusOf(rem, null, tol), custom)
        }

        if (t.intervalHours == null && t.intervalMonths == null) {
            return DueInfo(t, last, null, null, null, null, DueStatus.NO_INTERVAL, custom)
        }

        var dueHours: Double? = null
        if (t.intervalHours != null) {
            dueHours = when {
                // Never logged: counted from zero only for a (nearly) new aircraft, otherwise unknown.
                last == null -> if (a.hours < t.intervalHours) t.intervalHours else null
                // SB_SD4-03-2023: next due is counted from the previous DUE time when the
                // inspection was done within tolerance – tolerances do not accumulate.
                last.dueAtHours != null && t.toleranceHours != null &&
                    abs(last.hours - last.dueAtHours) <= t.toleranceHours -> last.dueAtHours + t.intervalHours
                else -> last.hours + t.intervalHours
            }
        }

        var dueDate: LocalDate? = null
        if (t.intervalMonths != null) {
            val ref = last?.epochDay ?: a.referenceEpochDay
            dueDate = ref?.let { LocalDate.ofEpochDay(it).plusMonths(t.intervalMonths.toLong()) }
        }

        if (dueHours == null && dueDate == null) {
            return DueInfo(t, last, null, null, null, null, DueStatus.NO_DATA, custom)
        }
        val remH = dueHours?.let { it - a.hours }
        val remD = dueDate?.let { ChronoUnit.DAYS.between(today, it) }
        return DueInfo(t, last, dueHours, dueDate, remH, remD, statusOf(remH, remD, tol), custom)
    }

    /**
     * Items to do at the inspection due at [dueHours]: every-time items plus those whose own hour step
     * (e.g. 200 h, 1000 h) falls within this interval – works also when inspections drift (1088 h, 1188 h…).
     */
    fun itemsDue(t: TaskDef, dueHours: Double?): List<TaskItem> {
        if (t.items.isEmpty()) return emptyList()
        val base = t.intervalHours
        if (dueHours == null || base == null) return t.items.filter { it.everyHours == null }
        return t.items.filter { it.everyHours == null || floor(dueHours / it.everyHours) > floor((dueHours - base) / it.everyHours) }
    }

    private fun statusOf(remH: Double?, remD: Long?, tol: Double): DueStatus {
        if ((remH != null && remH < -tol) || (remD != null && remD < 0)) return DueStatus.OVERDUE
        if ((remH != null && remH <= 0.0) || (remD != null && remD == 0L)) return DueStatus.DUE
        if ((remH != null && remH <= SOON_HOURS) || (remD != null && remD <= SOON_DAYS)) return DueStatus.SOON
        return DueStatus.OK
    }

    fun needsAttention(d: DueInfo) = d.status == DueStatus.OVERDUE || d.status == DueStatus.DUE || d.status == DueStatus.SOON
}
