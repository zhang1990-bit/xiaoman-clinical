package dev.xiaoman.tasks

import dev.xiaoman.api.CareTask
import dev.xiaoman.api.TaskState
import dev.xiaoman.api.TaskStore
import dev.xiaoman.api.toDateOrNull
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
import java.time.YearMonth

/**
 * [TaskStore] backed by one JSON file `<root>/tasks.json`.
 *
 * Kept as a plain file on purpose: a few dozen active tasks, readable and fixable by hand, and it
 * survives reboots because the schedule is rebuilt from it at boot (see the app's digest scheduler).
 * Closed tasks are moved to `<root>/archive/YYYY-MM.md` once a month, so the file never grows.
 */
class FileTaskStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : TaskStore {
    private val file = File(root, "tasks.json")
    private val archiveDir = File(root, "archive")
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(val version: Int = 1, val tasks: List<CareTask> = emptyList())

    override suspend fun all(): List<CareTask> = locked { read() }

    override suspend fun add(tasks: List<CareTask>): List<CareTask> = locked {
        if (tasks.isEmpty()) return@locked emptyList()
        val current = read() + tasks
        write(current)
        tasks
    }

    override suspend fun update(task: CareTask): CareTask? = locked {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index < 0) return@locked null
        current[index] = task
        write(current)
        task
    }

    override suspend fun updateAll(tasks: List<CareTask>): List<CareTask> = locked {
        if (tasks.isEmpty()) return@locked emptyList()
        val byId = tasks.associateBy { it.id }
        val current = read().map { byId[it.id] ?: it }
        write(current)
        tasks
    }

    override suspend fun find(id: String): CareTask? = locked { read().firstOrNull { it.id == id } }

    override suspend fun remove(ids: Set<String>): Int = locked {
        if (ids.isEmpty()) return@locked 0
        val current = read()
        val kept = current.filterNot { it.id in ids }
        val removed = current.size - kept.size
        if (removed > 0) write(kept)
        removed
    }

    /**
     * Moves closed or expired tasks whose last day is before the first day of [keepFrom] into
     * `archive/YYYY-MM.md`, grouped by the month they ended in. Returns how many were archived.
     */
    suspend fun archiveBefore(keepFrom: YearMonth): Int = locked {
        val cutoff = keepFrom.atDay(1)
        val all = read()
        // Only tasks that both ended and belong to a month before the keep month leave the file;
        // a task closed this month must stay, even though it is already DONE.
        val archiveable = all.filter { task ->
            (task.state == TaskState.DONE || task.state == TaskState.EXPIRED || task.isStale(cutoff)) &&
                endedMonth(task) < keepFrom
        }
        if (archiveable.isEmpty()) return@locked 0
        archiveable.groupBy { endedMonth(it) }.forEach { (month, tasks) ->
            appendArchive(month, tasks)
        }
        val archivedIds = archiveable.map { it.id }.toSet()
        write(all.filter { it.id !in archivedIds })
        archiveable.size
    }

    private fun endedMonth(task: CareTask): YearMonth {
        val raw = task.closedAt ?: task.until
        return raw.toDateOrNull()?.let { YearMonth.from(it) } ?: YearMonth.from(LocalDate.now())
    }

    private fun appendArchive(month: YearMonth, tasks: List<CareTask>) {
        archiveDir.mkdirs()
        val out = File(archiveDir, "${month}.md")
        val fresh = !out.exists()
        val text = buildString {
            if (fresh) {
                append("# ").append(month).append(" 归档\n\n")
                append("| 床号 | 姓名 | 事项 | 建立 | 结束 | 状态 | 备注 |\n")
                append("|---|---|---|---|---|---|---|\n")
            }
            tasks.sortedBy { it.bed }.forEach { t ->
                append("| ").append(t.bed).append(" | ").append(t.name).append(" | ")
                    .append(t.item.replace("|", "/")).append(" | ").append(t.createdAt).append(" | ")
                    .append(t.closedAt ?: t.until).append(" | ")
                    .append(if (t.state == TaskState.DONE) "已办" else "过期").append(" | ")
                    .append((t.closeReason ?: t.note).replace("|", "/").replace("\n", " ")).append(" |\n")
            }
        }
        out.appendText(text, Charsets.UTF_8)
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    /**
     * 每天一份快照，滚动保留 [keepDays] 天。待办丢了等于"明天早上没人提醒"，
     * 比患者档案更容易被忽略，所以一起备份。
     */
    suspend fun backupDaily(keepDays: Int = 7): File? = locked {
        val dir = File(root, "backup")
        if (!dir.isDirectory && !dir.mkdirs()) return@locked null
        val day = java.time.LocalDate.now().toString()
        val target = File(dir, "tasks-$day.json")
        val all = read()
        if (all.isEmpty()) return@locked null
        if (!target.isFile) target.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(tasks = all)), Charsets.UTF_8)
        val cutoff = "tasks-${java.time.LocalDate.now().minusDays(keepDays.toLong())}.json"
        dir.listFiles { f -> f.isFile && f.name.startsWith("tasks-") && f.name.endsWith(".json") }
            ?.filter { it.name < cutoff }?.forEach { it.delete() }
        target
    }

    private fun read(): List<CareTask> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(Snapshot.serializer(), file.readText(Charsets.UTF_8)).tasks
        } catch (e: Exception) {
            // A corrupt file must not crash the app: keep a copy for inspection and start clean.
            runCatching { file.copyTo(File(root, "tasks.corrupt-${clock()}.json"), overwrite = true) }
            emptyList()
        }
    }

    private fun write(tasks: List<CareTask>) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create folder $root")
        val text = json.encodeToString(Snapshot.serializer(), Snapshot(tasks = tasks))
        val temp = File.createTempFile(".tasks.", ".tmp", root)
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

    companion object {
        private const val DATE = "yyyy-MM-dd"
    }
}
