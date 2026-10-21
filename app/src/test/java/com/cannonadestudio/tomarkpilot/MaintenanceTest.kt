package com.cannonadestudio.tomarkpilot

import com.cannonadestudio.tomarkpilot.data.Aircraft
import com.cannonadestudio.tomarkpilot.data.CompletionRecord
import com.cannonadestudio.tomarkpilot.data.DueStatus
import com.cannonadestudio.tomarkpilot.data.L10n
import com.cannonadestudio.tomarkpilot.data.Maintenance
import com.cannonadestudio.tomarkpilot.data.TaskDef
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MaintenanceTest {
    private val today = LocalDate.of(2026, 10, 20)
    private val t100 = TaskDef("insp_100h", L10n.same("100h"), "airframe", intervalHours = 100.0, toleranceHours = 5.0)
    private val annual = TaskDef("annual", L10n.same("annual"), "airframe", intervalMonths = 12)
    private val once25 = TaskDef("insp_25h", L10n.same("25h"), "airframe", oneTimeAtHours = 25.0, toleranceHours = 1.25)

    private fun ac(h: Double, ref: LocalDate? = null) =
        Aircraft("a", "sd4_rtc", "OM-TEST", hours = h, referenceEpochDay = ref?.toEpochDay())

    private fun rec(task: String, h: Double, due: Double?, day: LocalDate = today) =
        CompletionRecord("r$h", "a", task, day.toEpochDay(), h, due)

    @Test fun inToleranceCountsFromPreviousDueTime() {
        // 100 h inspection due at 200 h done early at 196 h (within ±5) -> next due at 300 h, not 296 h
        val d = Maintenance.compute(t100, ac(250.0), listOf(rec("insp_100h", 196.0, 200.0)), today, false)
        assertEquals(300.0, d.dueHours!!, 0.001)
        assertEquals(DueStatus.OK, d.status)
    }

    @Test fun outsideToleranceCountsFromCompletion() {
        val d = Maintenance.compute(t100, ac(250.0), listOf(rec("insp_100h", 180.0, 200.0)), today, false)
        assertEquals(280.0, d.dueHours!!, 0.001)
    }

    @Test fun withinToleranceAfterDueIsDueNotOverdue() {
        val d = Maintenance.compute(t100, ac(303.0), listOf(rec("insp_100h", 200.0, 200.0)), today, false)
        assertEquals(DueStatus.DUE, d.status)
    }

    @Test fun beyondToleranceIsOverdue() {
        val d = Maintenance.compute(t100, ac(306.0), listOf(rec("insp_100h", 200.0, 200.0)), today, false)
        assertEquals(DueStatus.OVERDUE, d.status)
    }

    @Test fun soonWithinTenHours() {
        val d = Maintenance.compute(t100, ac(292.0), listOf(rec("insp_100h", 200.0, 200.0)), today, false)
        assertEquals(DueStatus.SOON, d.status)
    }

    @Test fun calendarFromReferenceDate() {
        val d = Maintenance.compute(annual, ac(10.0, LocalDate.of(2025, 11, 1)), emptyList(), today, false)
        assertEquals(LocalDate.of(2026, 11, 1), d.dueDate)
        assertEquals(DueStatus.SOON, d.status)
    }

    @Test fun calendarOverdue() {
        val d = Maintenance.compute(annual, ac(10.0), listOf(rec("annual", 5.0, null, LocalDate.of(2025, 9, 1))), today, false)
        assertEquals(DueStatus.OVERDUE, d.status)
    }

    @Test fun oneTimeDoneStaysDone() {
        val d = Maintenance.compute(once25, ac(400.0), listOf(rec("insp_25h", 25.0, 25.0)), today, false)
        assertEquals(DueStatus.DONE, d.status)
    }

    @Test fun noIntervalReported() {
        val t = TaskDef("brs", L10n.same("brs"), "rescue")
        assertEquals(DueStatus.NO_INTERVAL, Maintenance.compute(t, ac(1.0), emptyList(), today, false).status)
    }

    @Test fun neverLoggedOnUsedAircraftIsNoDataNotOverdue() {
        // aircraft added at 1022 h with no inspection records: no false "overdue by 922 h"
        val d = Maintenance.compute(t100, ac(1022.0), emptyList(), today, false)
        assertEquals(DueStatus.NO_DATA, d.status)
    }

    @Test fun neverLoggedOnNewAircraftCountsFromZero() {
        val d = Maintenance.compute(t100, ac(40.0), emptyList(), today, false)
        assertEquals(100.0, d.dueHours!!, 0.001)
        assertEquals(DueStatus.OK, d.status)
    }

    @Test fun firstCheckLongPastIsTreatedAsDone() {
        assertEquals(DueStatus.DONE, Maintenance.compute(once25, ac(800.0), emptyList(), today, false).status)
    }

    @Test fun itemsDueFollowTheirOwnHourStep() {
        val oil = com.cannonadestudio.tomarkpilot.data.TaskItem(L10n.same("oil"))
        val plugs = com.cannonadestudio.tomarkpilot.data.TaskItem(L10n.same("plugs"), everyHours = 200.0)
        val gear = com.cannonadestudio.tomarkpilot.data.TaskItem(L10n.same("gearbox"), everyHours = 1000.0)
        val t = t100.copy(items = listOf(oil, plugs, gear))
        assertEquals(listOf(oil), Maintenance.itemsDue(t, 1100.0))
        assertEquals(listOf(oil, plugs), Maintenance.itemsDue(t, 1200.0))
        // drifted schedule: 1088 h covers the 1000 h step (988 → 1088)
        assertEquals(listOf(oil, plugs, gear), Maintenance.itemsDue(t, 1088.0))
    }
}
