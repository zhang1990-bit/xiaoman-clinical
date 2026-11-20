package dev.xiaoman.tasks

import dev.xiaoman.api.Patient
import dev.xiaoman.api.PatientEvent
import dev.xiaoman.api.PatientStage
import dev.xiaoman.api.PatientStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [PatientStore] backed by two files under `patients/`:
 *
 * - `patients.json`  - 全部患者（在院 + 归档），整文件原子写
 * - `events.jsonl`   - 只追加的事件流
 *
 * 患者只在几十到几百的量级，一次读全量比维护索引简单得多；写用"临时文件 + fsync + rename"，
 * 断电最多丢最后一次写，不会写坏。
 */
class FilePatientStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : PatientStore {
    private val file = File(root, "patients.json")
    private val eventsFile = File(root, "events.jsonl")
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** JSONL 一行一条，绝不能格式化——换行会把一条事件劈成两行。 */
    private val compact = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(val version: Int = 1, val patients: List<Patient> = emptyList())

    override suspend fun all(): List<Patient> = locked {
        read().filter { it.stage != PatientStage.ARCHIVED }
            .sortedWith(compareBy({ bedRank(it.bed) }, { it.name }))
    }

    override suspend fun archived(): List<Patient> = locked {
        read().filter { it.stage == PatientStage.ARCHIVED }
            .sortedByDescending { it.archivedOn ?: it.updatedAt }
    }

    override suspend fun find(id: String): Patient? = locked { read().firstOrNull { it.id == id } }

    override suspend fun findByBed(bed: String): Patient? = locked {
        val wanted = bed.trim()
        read().firstOrNull { it.stage != PatientStage.ARCHIVED && it.bed == wanted }
    }

    override suspend fun upsert(patient: Patient): Patient = locked {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.id == patient.id }
        if (index >= 0) current[index] = patient else current += patient
        write(current)
        patient
    }

    override suspend fun archive(id: String, reason: String, on: String): Patient? = locked {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@locked null
        val next = current[index].copy(
            stage = PatientStage.ARCHIVED,
            archivedOn = on,
            archiveReason = reason,
            dischargedOn = current[index].dischargedOn ?: on,
            updatedAt = stamp(),
        )
        current[index] = next
        write(current)
        next
    }

    override suspend fun restore(id: String): Patient? = locked {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@locked null
        val next = current[index].copy(
            stage = if (current[index].surgery != null) PatientStage.POSTOP else PatientStage.ADMITTED,
            archivedOn = null,
            archiveReason = null,
            dischargedOn = null,
            updatedAt = stamp(),
        )
        current[index] = next
        write(current)
        next
    }

    override suspend fun purge(id: String): Boolean = locked {
        val current = read()
        if (current.none { it.id == id }) return@locked false
        write(current.filterNot { it.id == id })
        // 只留一条无内容的痕迹：这条不是丢了，是被清了。注意这里不能调 events()——同一把锁不可重入。
        val marker = PatientEvent(stampIso(), "patient.purged", id, "", "manual")
        rewriteEvents(readEvents().filterNot { it.patientId == id } + marker)
        true
    }

    override suspend fun events(patientId: String?): List<PatientEvent> = locked { readEvents().filter { patientId == null || it.patientId == patientId } }

    override suspend fun log(event: PatientEvent) = locked { appendLine(event) }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun stamp(): String = java.time.LocalDate.now().toString()

    private fun stampIso(): String = java.time.LocalDateTime.now().withNano(0).toString()

    private fun bedRank(bed: String): Int = bed.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    private fun read(): List<Patient> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(Snapshot.serializer(), file.readText(Charsets.UTF_8)).patients
        } catch (e: Exception) {
            runCatching { file.copyTo(File(root, "patients.corrupt-${clock()}.json"), overwrite = true) }
            emptyList()
        }
    }

    private fun write(patients: List<Patient>) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create folder $root")
        val text = json.encodeToString(Snapshot.serializer(), Snapshot(patients = patients))
        val temp = File.createTempFile(".patients.", ".tmp", root)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun readEvents(): List<PatientEvent> {
        if (!eventsFile.isFile) return emptyList()
        return eventsFile.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching { compact.decodeFromString(PatientEvent.serializer(), line) }.getOrNull()
        }
    }

    private fun appendLine(event: PatientEvent) {
        if (!root.isDirectory) root.mkdirs()
        eventsFile.appendText(compact.encodeToString(PatientEvent.serializer(), event) + "\n", Charsets.UTF_8)
    }

    private fun rewriteEvents(keep: List<PatientEvent>) {
        if (!root.isDirectory) root.mkdirs()
        val text = keep.joinToString("") { compact.encodeToString(PatientEvent.serializer(), it) + "\n" }
        val temp = File.createTempFile(".events.", ".tmp", root)
        try {
            temp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), eventsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), eventsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}
