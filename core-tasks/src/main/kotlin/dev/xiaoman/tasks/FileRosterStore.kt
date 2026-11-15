package dev.xiaoman.tasks

import dev.xiaoman.api.PatientSlot
import dev.xiaoman.api.RosterStore
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
 * [RosterStore] backed by one JSON file `<root>/roster.json`, next to `tasks.json`.
 *
 * The file is small (one line per patient) and is overwritten whole every time a new HIS table is
 * read. A corrupt file is moved aside rather than crashing the app: losing yesterday's table only
 * costs one "新入/出院" report, never a task.
 */
class FileRosterStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : RosterStore {
    private val file = File(root, "roster.json")
    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(val version: Int = 1, val patients: List<PatientSlot> = emptyList())

    override suspend fun all(): List<PatientSlot> = locked { read() }

    override suspend fun replace(slots: List<PatientSlot>) = locked { write(slots) }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun read(): List<PatientSlot> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(Snapshot.serializer(), file.readText(Charsets.UTF_8)).patients
        } catch (e: Exception) {
            runCatching { file.copyTo(File(root, "roster.corrupt-${clock()}.json"), overwrite = true) }
            emptyList()
        }
    }

    private fun write(slots: List<PatientSlot>) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create folder $root")
        val text = json.encodeToString(Snapshot.serializer(), Snapshot(patients = slots))
        val temp = File.createTempFile(".roster.", ".tmp", root)
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
}
