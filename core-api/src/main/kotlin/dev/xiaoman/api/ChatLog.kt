package dev.xiaoman.api

import kotlinx.serialization.Serializable

@Serializable
data class ChatTurn(val role: String, val text: String, val at: String)

/**
 * 对话落盘。以前历史只在内存里，退出就没了——"清理一个月前的内容"也就无从谈起。
 *
 * 按月一个文件（`chats/2026-11.jsonl`），只追加；读最近 N 条时从新到旧扫。
 */
interface ChatLogStore {
    suspend fun append(role: String, text: String)

    /** 最近的 [limit] 条，按时间正序返回（最早的在前）。 */
    suspend fun recent(limit: Int): List<ChatTurn>

    /** 删除早于 [before]（yyyy-MM-dd）的月份文件，返回删除的条数。 */
    suspend fun purgeBefore(before: String): Int

    /** 当前占用与最老一条的日期，给「清理内存」显示。 */
    suspend fun stats(): Pair<Long, String?>
}
