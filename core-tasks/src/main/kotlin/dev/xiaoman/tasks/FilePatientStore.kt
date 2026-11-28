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
import java.time.LocalDate

/**
 * [PatientStore]，目录结构：
 *
 * - `patients.json`         在院患者（只有这些人，写入更轻）
 * - `archive/2026-11.json`  归档患者，按月分片
 * - `backup/patients-*.json` 每日快照，滚动保留 [KEEP_BACKUPS] 天
 * - `events.jsonl`          事件流，只追加
 *
 * 为什么分片：归档会无限累积，混在一个文件里等于每次改一条都要重写全部历史。
 * 为什么备份：手机丢、文件坏，这套数据没有第二份。
 */
class FilePatientStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : PatientStore {
    private val file = File(root, "patients.json")
    private val archiveDir = File(root, "archive")
    private val backupDir = File(root, "backup")
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
        readActive().filter { it.stage != PatientStage.ARCHIVED }
            .sortedWith(compareBy({ bedRank(it.bed) }, { it.name }))
    }

    override suspend fun archived(): List<Patient> = locked {
        readArchive().sortedByDescending { it.archivedOn ?: it.updatedAt }
    }

    override suspend fun find(id: String): Patient? = locked {
        readActive().firstOrNull { it.id == id } ?: readArchive().firstOrNull { it.id == id }
    }

    override suspend fun findByBed(bed: String): Patient? = locked {
        val wanted = bed.trim()
        readActive().firstOrNull { it.stage != PatientStage.ARCHIVED && it.bed == wanted }
    }

    override suspend fun upsert(patient: Patient): Patient = locked {
        val active = readActive()
        if (patient.stage == PatientStage.ARCHIVED) {
            writeActive(active.filterNot { it.id == patient.id })
            writeShard(patient)
        } else {
            removeFromShards(patient.id)
            val index = active.indexOfFirst { it.id == patient.id }
            val next = if (index >= 0) active.toMutableList().also { it[index] = patient } else (active + patient).toMutableList()
            writeActive(next)
        }
        patient
    }

    override suspend fun archive(id: String, reason: String, on: String): Patient? = locked {
        val active = readActive()
        val index = active.indexOfFirst { it.id == id }
        if (index < 0) return@locked null
        val next = active[index].copy(
            stage = PatientStage.ARCHIVED,
            archivedOn = on,
            archiveReason = reason,
            dischargedOn = active[index].dischargedOn ?: on,
            updatedAt = stamp(),
        )
        writeActive(active.filterNot { it.id == id })
        writeShard(next)
        next
    }

    override suspend fun restore(id: String): Patient? = locked {
        val shards = readArchive()
        val target = shards.firstOrNull { it.id == id } ?: return@locked null
        val next = target.copy(
            stage = if (target.surgery != null) PatientStage.POSTOP else PatientStage.ADMITTED,
            archivedOn = null,
            archiveReason = null,
            dischargedOn = null,
            updatedAt = stamp(),
        )
        removeFromShards(id)
        val active = readActive()
        writeActive(active.filterNot { it.id == id } + next)
        next
    }

    override suspend fun purge(id: String): Boolean = locked {
        val active = readActive()
        val inActive = active.any { it.id == id }
        val inArchive = readArchive().any { it.id == id }
        if (!inActive && !inArchive) return@locked false
        if (inActive) writeActive(active.filterNot { it.id == id })
        removeFromShards(id)
        // 只留一条无内容的痕迹：这条不是丢了，是被清了。注意这里不能调 events()——同一把锁不可重入。
        val marker = PatientEvent(stampIso(), "patient.purged", id, "", "manual")
        rewriteEvents(readEvents().filterNot { it.patientId == id } + marker)
        true
    }

    override suspend fun events(patientId: String?): List<PatientEvent> = locked {
        readEvents().filter { patientId == null || it.patientId == patientId }
    }

    override suspend fun log(event: PatientEvent) = locked { appendLine(event) }

    // ---------- 维护 ----------

    /**
     * 把老的「归档患者混在 patients.json 里」搬进月度分片。幂等，返回搬走的人数。
     */
    suspend fun migrateArchivedToShards(): Int = locked {
        val active = readActive()
        val archived = active.filter { it.stage == PatientStage.ARCHIVED }
        if (archived.isEmpty()) return@locked 0
        writeActive(active.filterNot { it.stage == PatientStage.ARCHIVED })
        archived.forEach { writeShard(it) }
        archived.size
    }

    /**
     * 每天一份全量快照（在院 + 归档），滚动保留 [KEEP_BACKUPS] 天。
     * 当天已有就不重写；返回快照文件，失败返回 null。
     */
    suspend fun backupDaily(keepDays: Int = KEEP_BACKUPS): File? = locked {
        if (!backupDir.isDirectory && !backupDir.mkdirs()) return@locked null
        val day = LocalDate.now().toString()
        val target = File(backupDir, "patients-$day.json")
        if (!target.isFile) {
            val all = readActive() + readArchive()
            if (all.isEmpty()) return@locked null
            writeAtomically(target, json.encodeToString(Snapshot.serializer(), Snapshot(patients = all)))
        }
        pruneBackups(keepDays)
        target
    }

    /** 备份目录占了多大、最早哪天。 */
    suspend fun backupStats(): Pair<Long, String?> = locked {
        val files = backupFiles()
        files.sumOf { it.length() } to files.minOfOrNull { it.name }
    }

    private fun pruneBackups(keepDays: Int) {
        val cutoff = "patients-${LocalDate.now().minusDays(keepDays.toLong())}.json"
        backupFiles().filter { it.name < cutoff }.forEach { it.delete() }
    }

    private fun backupFiles(): List<File> =
        backupDir.listFiles { f -> f.isFile && f.name.startsWith("patients-") && f.name.endsWith(".json") }
            ?.toList().orEmpty()

    // ---------- 读写 ----------

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun stamp(): String = LocalDate.now().toString()

    private fun stampIso(): String = java.time.LocalDateTime.now().withNano(0).toString()

    private fun bedRank(bed: String): Int = bed.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    private fun readActive(): List<Patient> = readSnapshot(file)

    private fun writeActive(patients: List<Patient>) {
        writeAtomically(file, json.encodeToString(Snapshot.serializer(), Snapshot(patients = patients)))
    }

    private fun readSnapshot(source: File): List<Patient> {
        if (!source.isFile) return emptyList()
        return try {
            json.decodeFromString(Snapshot.serializer(), source.readText(Charsets.UTF_8)).patients
        } catch (e: Exception) {
            runCatching { source.copyTo(File(source.parentFile, "${source.nameWithoutExtension}.corrupt-${clock()}.json"), overwrite = true) }
            emptyList()
        }
    }

    private fun shardFor(month: String): File = File(archiveDir, "$month.json")

    private fun monthOf(patient: Patient): String =
        (patient.archivedOn ?: patient.updatedAt ?: LocalDate.now().toString()).take(7)

    private fun readArchive(): List<Patient> =
        archiveDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.flatMap { readSnapshot(it) }
            .orEmpty()

    private fun removeFromShards(id: String) {
        archiveDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { shard ->
            val list = readSnapshot(shard)
            if (list.any { it.id == id }) {
                val kept = list.filterNot { it.id == id }
                if (kept.isEmpty()) shard.delete() else writeAtomically(shard, json.encodeToString(Snapshot.serializer(), Snapshot(patients = kept)))
            }
        }
    }

    private fun writeShard(patient: Patient) {
        val shard = shardFor(monthOf(patient))
        val list = readSnapshot(shard)
        val index = list.indexOfFirst { it.id == patient.id }
        val next = if (index >= 0) list.toMutableList().also { it[index] = patient } else list + patient
        writeAtomically(shard, json.encodeToString(Snapshot.serializer(), Snapshot(patients = next)))
    }

    /** 先写临时文件并 fsync，再原子替换；断电最多丢最后一次写，不会写坏。 */
    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create ${it.path}") }
        val temp = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
        val text = keep.joinToString("") { compact.encodeToString(PatientEvent.serializer(), it) + "\n" }
        writeAtomically(eventsFile, text)
    }

    companion object {
        /** 备份保留天数。一天一份，七天足够覆盖"周一发现上周五改坏了"。 */
        const val KEEP_BACKUPS = 7
    }
}
