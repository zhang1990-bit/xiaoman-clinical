package dev.xiaoman.tasks

import dev.xiaoman.api.ChatLogStore
import dev.xiaoman.api.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime

/** 按月分文件的对话日志，一行一条，只追加。 */
class FileChatLog(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatLogStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    override suspend fun append(role: String, text: String) = locked {
        if (!root.isDirectory) root.mkdirs()
        val now = LocalDateTime.now()
        val month = "${now.year}-${now.monthValue.toString().padStart(2, '0')}"
        val turn = ChatTurn(role, text, now.withNano(0).toString())
        File(root, "$month.jsonl").appendText(json.encodeToString(ChatTurn.serializer(), turn) + "\n", Charsets.UTF_8)
    }

    override suspend fun recent(limit: Int): List<ChatTurn> = locked {
        val all = mutableListOf<ChatTurn>()
        files().sorted().forEach { file ->
            file.readLines(Charsets.UTF_8).forEach { line ->
                if (line.isNotBlank()) {
                    runCatching { json.decodeFromString(ChatTurn.serializer(), line) }.getOrNull()?.let { all += it }
                }
            }
        }
        all.takeLast(limit)
    }

    override suspend fun purgeBefore(before: String): Int = locked {
        val cutoff = before.replace("-", "").take(6)
        var removed = 0
        files().forEach { file ->
            val month = file.nameWithoutExtension.replace("-", "").take(6)
            if (month.isNotEmpty() && month < cutoff) {
                removed += file.readLines(Charsets.UTF_8).count { it.isNotBlank() }
                file.delete()
            }
        }
        removed
    }

    override suspend fun stats(): Pair<Long, String?> = locked {
        val list = files()
        val bytes = list.sumOf { it.length() }
        val oldest = list.sorted().firstOrNull()?.readLines(Charsets.UTF_8)?.firstOrNull { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(ChatTurn.serializer(), it) }.getOrNull()?.at?.take(10) }
        bytes to oldest
    }

    private fun files(): List<File> =
        root.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.toList().orEmpty()

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }
}
