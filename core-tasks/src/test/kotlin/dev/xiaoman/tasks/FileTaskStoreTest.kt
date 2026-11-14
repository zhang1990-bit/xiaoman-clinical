package dev.xiaoman.tasks

import dev.xiaoman.api.CareTask
import dev.xiaoman.api.TaskState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileTaskStoreTest {
    private fun tempDir(): File = Files.createTempDirectory("xiaoman-tasks").toFile()

    private fun task(
        id: String = "t1",
        bed: String = "12",
        state: TaskState = TaskState.ACTIVE,
        until: String = "2026-12-31",
        createdAt: String = "2026-11-14",
        closedAt: String? = null,
    ) = CareTask(
        id = id,
        bed = bed,
        name = "李xx",
        patientKey = "李xx",
        item = "术前检查",
        state = state,
        until = until,
        createdAt = createdAt,
        closedAt = closedAt,
    )

    @Test
    fun `tasks survive a new store instance`() = runBlocking {
        val dir = tempDir()
        FileTaskStore(dir).add(listOf(task()))
        val reopened = FileTaskStore(dir)
        assertEquals(1, reopened.all().size)
        assertEquals("12", reopened.all().first().bed)
    }

    @Test
    fun `updating the bed keeps the patient key`() = runBlocking {
        val dir = tempDir()
        val store = FileTaskStore(dir)
        val saved = store.add(listOf(task())).first()
        val moved = saved.copy(bed = "15")
        store.update(moved)
        val reloaded = store.find(saved.id)
        assertNotNull(reloaded)
        assertEquals("15", reloaded.bed)
        assertEquals("李xx", reloaded.patientKey)
    }

    @Test
    fun `updateAll writes every task once`() = runBlocking {
        val dir = tempDir()
        val store = FileTaskStore(dir)
        val saved = store.add(listOf(task("t1"), task("t2")))
        store.updateAll(saved.map { it.copy(state = TaskState.DONE, closedAt = "2026-11-14") })
        assertTrue(store.all().all { it.state == TaskState.DONE })
    }

    @Test
    fun `archive moves only tasks that ended before the keep month`() = runBlocking {
        val dir = tempDir()
        val store = FileTaskStore(dir)
        store.add(
            listOf(
                task(id = "old", state = TaskState.DONE, closedAt = "2026-09-20", until = "2026-09-20"),
                task(id = "recent", state = TaskState.DONE, closedAt = "2026-10-20", until = "2026-10-20"),
                task(id = "active"),
            ),
        )
        val archived = store.archiveBefore(YearMonth.of(2026, 10))
        assertEquals(1, archived)
        val left = store.all().map { it.id }.toSet()
        assertEquals(setOf("recent", "active"), left)
        val md = File(dir, "archive/2026-09.md")
        assertTrue(md.isFile)
        assertTrue(md.readText(Charsets.UTF_8).contains("术前检查"))
    }

    @Test
    fun `a corrupt file does not throw and is kept aside`() = runBlocking {
        val dir = tempDir()
        dir.mkdirs()
        File(dir, "tasks.json").writeText("{not json", Charsets.UTF_8)
        val store = FileTaskStore(dir)
        assertEquals(0, store.all().size)
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("tasks.corrupt-") })
    }
}
