package dev.xiaoman.tasks

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileRecordStoreTest {
    private fun store(): Pair<FileRecordStore, File> {
        val root = Files.createTempDirectory("xiaoman-records").toFile()
        return FileRecordStore(File(root, "records"), File(root, "templates")) to root
    }

    @Test
    fun savesUtf8TxtAndReturnsAbsolutePath() = runBlocking {
        val (s, root) = store()
        val path = s.save("20261118_开腹肝癌切除", "患者取仰卧位。\n\n取右肋缘下切口。")

        val file = File(path)
        assertTrue(file.isFile)
        assertTrue(path.startsWith(root.absolutePath))
        assertEquals("患者取仰卧位。\n\n取右肋缘下切口。\n", file.readText(Charsets.UTF_8))
        assertEquals(listOf("20261118_开腹肝癌切除.txt"), s.list())
    }

    @Test
    fun dirtyTitleIsSanitised() = runBlocking {
        val (s, _) = store()
        val path = s.save("20261118/肝癌:切除*", "正文")
        assertTrue(File(path).name.endsWith(".txt"))
        assertTrue("/" !in File(path).name && ":" !in File(path).name && "*" !in File(path).name)
    }

    @Test
    fun secondSaveWithSameTitleDoesNotOverwrite() = runBlocking {
        val (s, _) = store()
        s.save("同名", "第一版")
        val second = s.save("同名", "第二版")
        assertTrue(second.endsWith("同名-2.txt"))
        assertEquals(listOf("同名.txt", "同名-2.txt").sorted(), s.list().sorted())
    }

    @Test
    fun emptyTitleFallsBackAndTrimIsApplied() = runBlocking {
        val (s, _) = store()
        val path = s.save("   ", "  正文带空格  ")
        assertEquals("手术记录.txt", File(path).name)
        assertEquals("正文带空格\n", File(path).readText(Charsets.UTF_8))
    }

    @Test
    fun saveTemplateWritesFrontmatterAndBumpsVersion() = runBlocking {
        val (s, _) = store()
        val first = s.saveTemplate("解剖性肝切除", "## 步骤\n阻断→缺血线→离断", listOf("肝段切除", "肝切除"))
        assertTrue(first.endsWith("解剖性肝切除.md"))
        val v1 = File(first).readText(Charsets.UTF_8)
        assertTrue("aliases: [肝段切除, 肝切除]" in v1, v1)
        assertTrue("version: 1" in v1)

        val second = s.saveTemplate("解剖性肝切除", "## 步骤\n阻断两次→缺血线→离断", emptyList())
        val v2 = File(second).readText(Charsets.UTF_8)
        assertTrue("version: 2" in v2, v2)
        assertTrue(File(second).parentFile!!.resolve("解剖性肝切除.bak").isFile, "旧版要留 .bak")
        assertEquals(listOf("解剖性肝切除"), s.templates(), ".bak 不能出现在模板列表里")
    }

    @Test
    fun templateIsFoundByAlias() = runBlocking {
        val (s, _) = store()
        s.saveTemplate("腹腔镜胆囊切除术", "## 步骤\n四孔", listOf("LC", "胆囊切除"))
        assertEquals("腹腔镜胆囊切除术", s.templates().single())
        assertTrue(s.template("LC")!!.contains("四孔"))
        assertTrue(s.template("胆囊切除")!!.contains("四孔"))
    }

    @Test
    fun templatesAreListedAndRead() = runBlocking {
        val (s, root) = store()
        val dir = File(root, "templates").apply { mkdirs() }
        File(dir, "解剖性肝切除.md").writeText("## 体位\n仰卧", Charsets.UTF_8)
        File(dir, "忽略我.docx").writeText("x")

        assertEquals(listOf("解剖性肝切除"), s.templates())
        assertEquals("## 体位\n仰卧", s.template("解剖性肝切除"))
        assertNull(s.template("不存在的术式"))
    }
}
