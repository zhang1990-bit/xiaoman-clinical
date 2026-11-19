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

    /** Full text of one template, or null when it does not exist. */
    suspend fun template(name: String): String?
}
