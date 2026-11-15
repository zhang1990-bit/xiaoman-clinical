package dev.xiaoman.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatientRosterTest {
    private fun slot(bed: String, name: String) = PatientSlot(bed, name, "2026-11-15")

    @Test
    fun normalizesBedsSortsAndDropsEmptyRows() {
        val roster = PatientRoster.normalize(
            listOf(slot("02", "李西萍"), slot("A63", "张殿坡"), slot("19", "马凤"), slot("", "无名"), slot("11", " ")),
            "2026-11-15",
        )
        assertEquals(listOf("2", "19", "A63"), roster.map { it.bed })
        assertEquals("2026-11-15", roster.first().seenAt)
    }

    @Test
    fun secondDayDiffReportsNewDischargedAndMoved() {
        val yesterday = listOf(slot("12", "李xx"), slot("5", "王xx"), slot("9", "赵xx"))
        val today = listOf(slot("15", "李xx"), slot("5", "王xx"), slot("19", "马凤"))

        val diff = PatientRoster.diff(yesterday, today)

        assertEquals(listOf("马凤"), diff.added.map { it.name })
        assertEquals(listOf("赵xx"), diff.removed.map { it.name })
        assertEquals(1, diff.moved.size)
        assertEquals("12" to "15", diff.moved.single().fromBed to diff.moved.single().toBed)
        assertEquals(1, diff.unchanged)
    }

    @Test
    fun aMovedPatientIsNotCountedAsDischargedAndAdmitted() {
        val diff = PatientRoster.diff(listOf(slot("12", "李xx")), listOf(slot("15", "李xx")))
        assertTrue(diff.added.isEmpty() && diff.removed.isEmpty())
        assertEquals(listOf("李xx"), diff.moved.map { it.name })
    }

    @Test
    fun firstPhotoEverIsAllNew() {
        val diff = PatientRoster.diff(emptyList(), listOf(slot("2", "李西萍"), slot("3", "倪成英")))
        assertEquals(2, diff.added.size)
        assertTrue(diff.removed.isEmpty() && diff.moved.isEmpty())
        assertEquals(0, diff.unchanged)
    }

    @Test
    fun unchangedDayIsQuiet() {
        val same = listOf(slot("12", "李xx"), slot("5", "王xx"))
        val diff = PatientRoster.diff(same, same)
        assertTrue(diff.empty)
        assertEquals(2, diff.unchanged)
    }
}
