package dev.xiaoman.tasks

import dev.xiaoman.api.PatientSlot
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileRosterStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("xiaoman-roster").toFile()

    private fun slot(bed: String, name: String) = PatientSlot(bed, name, "2026-11-15")

    @Test
    fun savesAndReadsBackTheWholeTable() = runBlocking {
        val dir = tempDir()
        val store = FileRosterStore(dir)

        store.replace(listOf(slot("2", "李xx"), slot("A63", "张xx")))

        val reloaded = FileRosterStore(dir).all()
        assertEquals(listOf("2" to "李xx", "A63" to "张xx"), reloaded.map { it.bed to it.name })
        assertTrue(File(dir, "roster.json").isFile)
    }

    @Test
    fun replacingOverwritesInsteadOfAppending() = runBlocking {
        val store = FileRosterStore(tempDir())
        store.replace(listOf(slot("2", "李xx"), slot("3", "王xx")))
        store.replace(listOf(slot("19", "马xx")))
        assertEquals(listOf("马xx"), store.all().map { it.name })
    }

    @Test
    fun missingFileMeansEmptyRoster() = runBlocking {
        assertTrue(FileRosterStore(tempDir()).all().isEmpty())
    }

    @Test
    fun corruptFileIsMovedAsideNotCrashedOn() = runBlocking {
        val dir = tempDir()
        File(dir, "roster.json").writeText("{not json", Charsets.UTF_8)

        assertTrue(FileRosterStore(dir, clock = { 42L }).all().isEmpty())
        assertTrue(File(dir, "roster.corrupt-42.json").isFile)
    }
}
