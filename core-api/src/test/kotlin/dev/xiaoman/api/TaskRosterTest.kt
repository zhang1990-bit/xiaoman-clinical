package dev.xiaoman.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskRosterTest {
    private fun task(
        id: String = "t1",
        bed: String = "12",
        name: String = "李xx",
        item: String = "术前检查",
        state: TaskState = TaskState.ACTIVE,
    ) = CareTask(
        id = id, bed = bed, name = name, patientKey = name, item = item,
        state = state, until = "2026-12-31", createdAt = "2026-11-14",
    )

    private fun slots(vararg pairs: Pair<String, String>) = pairs.map { BedSlot(it.first, it.second) }

    @Test
    fun bedNumberIsNormalized() {
        assertEquals("12", TaskRoster.normalizeBed("12床"))
        assertEquals("12", TaskRoster.normalizeBed(" 012号"))
        assertEquals("12A", TaskRoster.normalizeBed("12a"))
        assertEquals("0", TaskRoster.normalizeBed("0"))
        // 加床：HIS 印 A65，口述说「加65床」「+65」，必须落到同一个键
        assertEquals("A65", TaskRoster.normalizeBed("A65"))
        assertEquals("A65", TaskRoster.normalizeBed("a65"))
        assertEquals("A65", TaskRoster.normalizeBed("+65"))
        assertEquals("A65", TaskRoster.normalizeBed("加65"))
        assertEquals("A65", TaskRoster.normalizeBed("加65床"))
        assertEquals("A65", TaskRoster.normalizeBed(" A065 "))
        assertEquals("A9", TaskRoster.normalizeBed("加9床"))
        // 普通床不能被误判成加床
        assertEquals("65", TaskRoster.normalizeBed("65"))
        assertEquals("12A", TaskRoster.normalizeBed("12A"))
    }

    @Test
    fun maskedRosterNameMatchesByPrefix() {
        assertTrue(TaskRoster.nameMatches("李xx", "李**"))
        assertTrue(TaskRoster.nameMatches("李建国", "李建*"))
        assertTrue(!TaskRoster.nameMatches("李xx", "王**"))
        assertTrue(!TaskRoster.nameMatches("李xx", "李建*"), "prefix longer than the known name never matches")
    }

    @Test
    fun patientInAnotherBedIsOneMoveEvenWithSeveralTasks() {
        val tasks = listOf(
            task("t1", "12", "李xx", "术前检查"),
            task("t2", "12", "李xx", "换药"),
            task("t3", "5", "王xx", "出院首页"),
        )
        val report = TaskRoster.compare(tasks, slots("15" to "李xx", "5" to "王xx"))

        assertEquals(1, report.moves.size)
        val move = report.moves.single()
        assertEquals("12", move.fromBed)
        assertEquals("15", move.toBed)
        assertEquals(listOf("t1", "t2"), move.ids)
        assertTrue(report.conflicts.isEmpty() && report.missing.isEmpty())
        assertEquals(3, report.checked)
    }

    @Test
    fun sameBedIsNotReported() {
        val report = TaskRoster.compare(listOf(task()), slots("12" to "李xx"))
        assertTrue(report.clear)
    }

    @Test
    fun sameBedOtherNameIsAConflict() {
        val report = TaskRoster.compare(listOf(task()), slots("12" to "王xx", "7" to "赵xx"))
        assertTrue(report.moves.isEmpty())
        val conflict = report.conflicts.single()
        assertEquals("王xx", conflict.hisName)
        assertEquals("同床不同姓", conflict.reason)
    }

    @Test
    fun patientMissingFromRosterIsReportedNotMoved() {
        val report = TaskRoster.compare(listOf(task(bed = "9", name = "赵xx")), slots("12" to "李xx"))
        assertTrue(report.moves.isEmpty() && report.conflicts.isEmpty())
        assertEquals("赵xx", report.missing.single().name)
        assertEquals("9", report.missing.single().bed)
    }

    @Test
    fun twoPeopleSharingTheNameAreAConflict() {
        val report = TaskRoster.compare(listOf(task()), slots("12" to "李xx", "15" to "李xx"))
        assertEquals(1, report.conflicts.size)
        assertEquals("同名多人，认不出是哪个", report.conflicts.single().reason)
    }

    @Test
    fun closedTasksAreIgnoredAndEmptyRosterReportsNothing() {
        assertTrue(TaskRoster.compare(listOf(task(state = TaskState.DONE)), slots("15" to "李xx")).clear)
        val empty = TaskRoster.compare(listOf(task()), emptyList())
        assertTrue(empty.clear)
        assertEquals(0, empty.checked)
    }
}
