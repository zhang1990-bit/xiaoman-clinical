package dev.xiaoman.api

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CareTaskTest {
    private fun task(
        anchor: TaskAnchor? = null,
        anchorDate: String? = null,
        rule: TaskRule = TaskRule(),
        until: String = "2026-12-31",
        createdAt: String = "2026-11-14",
        silentDate: String? = null,
        state: TaskState = TaskState.ACTIVE,
    ) = CareTask(
        id = "t1",
        bed = "12",
        name = "李xx",
        patientKey = "李xx",
        item = "术前检查",
        rule = rule,
        anchor = anchor,
        anchorDate = anchorDate,
        state = state,
        until = until,
        createdAt = createdAt,
        silentDate = silentDate,
    )

    @Test
    fun `daily task without anchor is due from the created day`() {
        val t = task()
        assertFalse(t.isDue(LocalDate.parse("2026-11-13")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-14")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-20")))
    }

    @Test
    fun `surgery anchor waits until the anchor date is known`() {
        val t = task(anchor = TaskAnchor(AnchorKind.SURGERY, offsetDays = 3))
        assertFalse(t.isDue(LocalDate.parse("2026-11-20")))
    }

    @Test
    fun `surgery anchor starts three days after the operation`() {
        val t = task(anchor = TaskAnchor(AnchorKind.SURGERY, offsetDays = 3), anchorDate = "2026-11-14")
        assertFalse(t.isDue(LocalDate.parse("2026-11-16")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-17")))
    }

    @Test
    fun `discharge anchor starts on the discharge day`() {
        val t = task(anchor = TaskAnchor(AnchorKind.DISCHARGE), anchorDate = "2026-11-18")
        assertFalse(t.isDue(LocalDate.parse("2026-11-17")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-18")))
    }

    @Test
    fun `date anchor starts on that date`() {
        val t = task(anchor = TaskAnchor(AnchorKind.DATE, date = "2026-11-20"))
        assertFalse(t.isDue(LocalDate.parse("2026-11-19")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-20")))
    }

    @Test
    fun `monthly task only rings on the listed days`() {
        val t = task(rule = TaskRule(TaskRuleKind.MONTHLY, listOf(10, 20, 30)))
        assertTrue(t.isDue(LocalDate.parse("2026-11-10")))
        assertFalse(t.isDue(LocalDate.parse("2026-11-11")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-20")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-30")))
    }

    @Test
    fun `silent today skips only that day`() {
        val t = task(silentDate = "2026-11-14")
        assertFalse(t.isDue(LocalDate.parse("2026-11-14")))
        assertTrue(t.isDue(LocalDate.parse("2026-11-15")))
    }

    @Test
    fun `past until is not due and counts as stale`() {
        val t = task(until = "2026-11-15")
        assertFalse(t.isDue(LocalDate.parse("2026-11-16")))
        assertTrue(t.isStale(LocalDate.parse("2026-11-16")))
    }

    @Test
    fun `done task never rings and is not stale`() {
        val t = task(state = TaskState.DONE, until = "2026-11-15")
        assertFalse(t.isDue(LocalDate.parse("2026-11-14")))
        assertFalse(t.isStale(LocalDate.parse("2026-11-20")))
    }

    @Test
    fun `day number counts from the created day`() {
        assertEquals(1, task().dayNumber(LocalDate.parse("2026-11-14")))
        assertEquals(3, task().dayNumber(LocalDate.parse("2026-11-16")))
    }
}
