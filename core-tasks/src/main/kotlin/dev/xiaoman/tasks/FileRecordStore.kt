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

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        val clean = sanitize(name.removeSuffix(".txt"))
        if (clean.isEmpty()) return@withContext null
        recordsDir.listFiles { f -> f.isFile }?.firstOrNull {
            it.nameWithoutExtension.equals(clean, ignoreCase = true) || it.name.equals(name, ignoreCase = true)
        }?.readText(Charsets.UTF_8)
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
        val files = templatesDir.listFiles { f -> f.isFile && (f.extension == "md" || f.extension == "txt") }.orEmpty()
        // 先按文件名命中，再按 aliases 命中：LC / 胆囊切除 都能落到同一份模板。
        val byFile = files.firstOrNull {
            it.nameWithoutExtension.equals(wanted, ignoreCase = true) || it.name.equals(wanted, ignoreCase = true)
        }
        val hit = byFile ?: files.firstOrNull { file ->
            aliasesOf(file).any { it.equals(wanted, ignoreCase = true) }
        }
        hit?.readText(Charsets.UTF_8)
    }

    override suspend fun saveTemplate(name: String, content: String, aliases: List<String>): String =
        withContext(Dispatchers.IO) {
            if (!templatesDir.isDirectory && !templatesDir.mkdirs()) error("无法创建目录 ${templatesDir.path}")
            val clean = sanitize(name).ifEmpty { "未命名模板" }
            val file = File(templatesDir, "$clean.md")
            var version = 1
            if (file.isFile && !isStub(file)) {
                version = (versionOf(file.readText(Charsets.UTF_8)) ?: 1) + 1
                // 上一版留一份 .bak（不带 .md，不会出现在模板列表里）
                file.copyTo(File(templatesDir, "$clean.bak"), overwrite = true)
            }
            val header = buildString {
                append("---\n")
                append("name: ").append(clean).append('\n')
                if (aliases.isNotEmpty()) {
                    append("aliases: [").append(aliases.joinToString(", ")).append("]\n")
                }
                append("version: ").append(version).append('\n')
                append("updated: ").append(java.time.LocalDate.now()).append('\n')
                append("---\n\n")
            }
            file.writeText(header + content.trim() + "\n", Charsets.UTF_8)
            file.absolutePath
        }

    /** 只有头没有正文的残档不值得留版本，直接覆盖。 */
    private fun isStub(file: File): Boolean = file.length() < 40

    private fun versionOf(text: String): Int? = text.lineSequence()
        .firstOrNull { it.startsWith("version:") }
        ?.removePrefix("version:")?.trim()?.toIntOrNull()

    private fun aliasesOf(file: File): List<String> = runCatching {
        val head = file.readLines(Charsets.UTF_8).take(12)
        val line = head.firstOrNull { it.trimStart().startsWith("aliases:") } ?: return emptyList()
        line.substringAfter("aliases:").trim().removePrefix("[").removeSuffix("]")
            .split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun sanitize(raw: String): String = raw.trim()
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "")
        .replace("..", "")
        .take(60)
        .trim()
}
