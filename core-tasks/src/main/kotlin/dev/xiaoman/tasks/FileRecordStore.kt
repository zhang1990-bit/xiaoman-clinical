package dev.xiaoman.tasks

import dev.xiaoman.api.RecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [RecordStore] backed by two folders under the app's external files directory:
 *
 * - `records/`  - generated `.txt`, one per operation
 * - `surgical-templates/` - whatever the user drops in (`.md` or `.txt`), read on demand
 *
 * Titles are sanitised because the model writes them: path separators, `..` and characters Windows
 * dislikes are replaced, and an existing file is never overwritten silently - a suffix is added.
 */
class FileRecordStore(
    private val recordsDir: File,
    private val templatesDir: File,
) : RecordStore {

    override suspend fun save(title: String, content: String): String = withContext(Dispatchers.IO) {
        if (!recordsDir.isDirectory && !recordsDir.mkdirs()) error("无法创建目录 ${recordsDir.path}")
        val base = sanitize(title).ifEmpty { "手术记录" }
        var file = File(recordsDir, "$base.txt")
        var n = 2
        while (file.exists()) {
            file = File(recordsDir, "$base-$n.txt")
            n++
        }
        file.writeText(content.trim() + "\n", Charsets.UTF_8)
        file.absolutePath
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        recordsDir.listFiles { f -> f.isFile && f.extension.equals("txt", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            .orEmpty()
    }

    override suspend fun templates(): List<String> = withContext(Dispatchers.IO) {
        templatesDir.listFiles { f -> f.isFile && (f.extension == "md" || f.extension == "txt") }
            ?.sortedBy { it.name }
            ?.map { it.nameWithoutExtension }
            .orEmpty()
    }

    override suspend fun template(name: String): String? = withContext(Dispatchers.IO) {
        val wanted = sanitize(name)
        if (wanted.isEmpty()) return@withContext null
        templatesDir.listFiles { f -> f.isFile }?.firstOrNull {
            it.nameWithoutExtension.equals(wanted, ignoreCase = true) ||
                it.name.equals(wanted, ignoreCase = true)
        }?.readText(Charsets.UTF_8)
    }

    private fun sanitize(raw: String): String = raw.trim()
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "")
        .replace("..", "")
        .take(60)
        .trim()
}
