package dev.xiaoman.tasks

import dev.xiaoman.api.Patient
import dev.xiaoman.api.PatientEvent
import dev.xiaoman.api.PatientStage
import dev.xiaoman.api.Surgery
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePatientStoreTest {
    private fun dir(): File = Files.createTempDirectory("xiaoman-patients").toFile()

    private fun patient(id: String = "p-0001", bed: String = "12", name: String = "李xx") = Patient(
        id = id, name = name, bed = bed, admittedOn = "2026-11-17",
        createdAt = "2026-11-17", updatedAt = "2026-11-17",
    )

    @Test
    fun upsertSavesAndSortsByBed() = runBlocking {
        val dir = dir()
        val store = FilePatientStore(dir)
        store.upsert(patient("p-0002", bed = "9", name = "王xx"))
        store.upsert(patient("p-0001", bed = "12"))

        val reloaded = FilePatientStore(dir).all()
        assertEquals(listOf("9", "12"), reloaded.map { it.bed })
        assertTrue(File(dir, "patients.json").isFile)
    }

    @Test
    fun archiveHidesButKeepsAndRestoreBringsBack() = runBlocking {
        val store = FilePatientStore(dir())
        store.upsert(patient().copy(surgery = Surgery("2026-11-19", "胆囊切除")))

        store.archive("p-0001", "名单中未出现", "2026-11-20")
        assertTrue(store.all().isEmpty())
        assertEquals(1, store.archived().size)

        val restored = store.restore("p-0001")!!
        assertEquals(PatientStage.POSTOP, restored.stage)
        assertEquals(1, store.all().size)
    }

    @Test
    fun purgeRemovesPatientAndLeavesOneMarker() = runBlocking {
        val store = FilePatientStore(dir())
        store.upsert(patient())
        store.log(PatientEvent("2026-11-19T08:00:00", "patient.created", "p-0001", "12床", "roster"))
        store.log(PatientEvent("2026-11-19T09:00:00", "patient.updated", "p-0001", "术前", "dictation"))

        assertTrue(store.purge("p-0001"))
        assertTrue(store.all().isEmpty())

        val events = store.events()
        assertEquals(1, events.size)
        assertEquals("patient.purged", events.single().type)
    }

    @Test
    fun findByBedIgnoresArchived() = runBlocking {
        val store = FilePatientStore(dir())
        store.upsert(patient())
        store.archive("p-0001", "出院", "2026-11-20")
        assertNull(store.findByBed("12"))
    }

    @Test
    fun archivingMovesThePatientIntoAMonthlyShard() = runBlocking {
        val dir = dir()
        val store = FilePatientStore(dir)
        store.upsert(patient())
        store.archive("p-0001", "出院", "2026-11-20")

        val active = File(dir, "patients.json").readText(Charsets.UTF_8)
        assertTrue("p-0001" !in active, "在院文件里不该再有归档的人")
        val shard = File(dir, "archive/2026-11.json")
        assertTrue(shard.isFile, "归档要落在月度分片里")
        assertTrue("p-0001" in shard.readText(Charsets.UTF_8))
        assertEquals(1, FilePatientStore(dir).archived().size)
    }

    @Test
    fun restoringPullsThePatientBackOutOfTheShard() = runBlocking {
        val dir = dir()
        val store = FilePatientStore(dir)
        store.upsert(patient())
        store.archive("p-0001", "出院", "2026-11-20")

        val restored = store.restore("p-0001")
        assertNotNull(restored)
        assertEquals(1, FilePatientStore(dir).all().size)
        assertTrue(FilePatientStore(dir).archived().isEmpty())
    }

    @Test
    fun legacyArchivedPatientsAreMigratedIntoShards() = runBlocking {
        val dir = dir()
        // 模拟老版本：归档的人和在院的人混在一个文件里
        File(dir, "patients.json").writeText(
            """{"version":1,"patients":[
                {"id":"p-0001","name":"李xx","bed":"12","stage":"POSTOP","admittedOn":"2026-11-01","createdAt":"2026-11-01","updatedAt":"2026-11-10"},
                {"id":"p-0002","name":"王xx","bed":"05","stage":"ARCHIVED","admittedOn":"2026-10-01","archivedOn":"2026-10-20","createdAt":"2026-10-01","updatedAt":"2026-10-20"}
            ]}""".trimIndent(),
            Charsets.UTF_8,
        )
        val store = FilePatientStore(dir)
        assertEquals(1, store.migrateArchivedToShards())
        assertEquals(1, store.all().size)
        assertEquals(1, store.archived().size)
        assertTrue(File(dir, "archive/2026-10.json").isFile)
    }

    @Test
    fun dailyBackupIsWrittenOnceAndPrunesOldOnes() = runBlocking {
        val dir = dir()
        val store = FilePatientStore(dir)
        store.upsert(patient())
        val backup = store.backupDaily()
        assertNotNull(backup)
        assertTrue(backup.isFile)
        // 同一天再调不会重写
        val second = store.backupDaily()
        assertEquals(backup.absolutePath, second?.absolutePath)
        assertTrue(backup.readText(Charsets.UTF_8).contains("p-0001"))
    }

    @Test
    fun corruptFileStartsCleanWithoutCrashing() = runBlocking {
        val dir = dir()
        File(dir, "patients.json").writeText("{oops", Charsets.UTF_8)
        assertTrue(FilePatientStore(dir, clock = { 7L }).all().isEmpty())
        assertTrue(File(dir, "patients.corrupt-7.json").isFile)
    }
}
