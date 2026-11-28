package dev.xiaoman.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * 手术记录写进「内部存储 / Download / 手术txt」。
 *
 * 为什么不是根目录 `/手术txt`：Android 11 起，往根目录写要么申请「所有文件访问权限」
 * （设置里手动开、权限极大），要么走 SAF 让用户授权一个目录。MediaStore 的 Download 集合
 * 是唯一免权限、且在文件管理器和数据线里都直接可见的位置。
 */
class PublicRecordPublisher(private val context: Context) {

    suspend fun publish(displayName: String, content: String): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$displayName.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
        }.isSuccess
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        runCatching { resolver.update(uri, values, null, null) }
        "内部存储/Download/$FOLDER/$displayName.txt"
    }

    companion object {
        const val FOLDER = "手术txt"
    }
}
