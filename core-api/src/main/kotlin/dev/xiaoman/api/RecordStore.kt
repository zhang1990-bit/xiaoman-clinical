package dev.xiaoman.api

/**
 * Local storage for generated documents (surgical records) and the templates they are built from.
 *
 * Everything stays on the phone: [save] writes one UTF-8 `.txt` under the app's external files
 * folder, so the user can pull it with a file manager or a USB cable. Nothing is uploaded.
 */
interface RecordStore {
    /** Writes [content] as `<title>.txt` and returns the absolute path of the file. */
    suspend fun save(title: String, content: String): String

    /** File names already saved, newest first. */
    suspend fun list(): List<String>

    /** Full text of one saved record, or null when it is gone. */
    suspend fun read(name: String): String?

    /** Template names the user dropped into the template folder. */
    suspend fun templates(): List<String>

    /** Full text of one template, or null when it does not exist. Aliases are honoured. */
    suspend fun template(name: String): String?

    /**
     * 写入/更新一份术式模板：`<name>.md`，带 `aliases` / `version` / `updated` 头。
     * 已存在时版本号 +1，并把上一版另存为 `<name>.bak`（不进模板列表）。返回文件绝对路径。
     */
    suspend fun saveTemplate(name: String, content: String, aliases: List<String>): String
}
