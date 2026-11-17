package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** record_save / template_read: the model writes a record only when asked, and reads templates first. */
class RecordToolTest {
    private val body = "患者取仰卧位。术区以碘伏常规消毒，铺无菌巾。\n\n取右肋缘下切口，逐层进腹。"

    private fun save(title: String = "20261118_开腹肝癌切除") = ToolCall(
        "c1", AgentTools.RECORD_SAVE, """{"title":"$title","content":"${body.replace("\n", "\\n")}"}""",
    )

    @Test
    fun savingWritesTheFileAndReturnsThePath() = runTest {
        val h = Harness()
        h.chat.tools(save()).reply("已存到 records 目录")

        h.agent.handle("出", h.events, h.stop)

        val tool = h.chat.requests[1].messages.last().text
        assertTrue("/fake/records/20261118_开腹肝癌切除.txt" in tool, tool)
        assertEquals(body, h.records.files.values.single())
        assertEquals("已存到 records 目录", h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun tooShortContentIsRejected() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.RECORD_SAVE, """{"title":"x","content":"太短"}""")).reply("再写全一点")
        h.agent.handle("出", h.events, h.stop)
        assertTrue("\"error\"" in h.chat.requests[1].messages.last().text)
        assertTrue(h.records.files.isEmpty())
    }

    @Test
    fun templateListAndRead() = runTest {
        val h = Harness()
        h.records.templateFiles["解剖性肝切除"] = "## 骨架\n阻断→缺血线→离断"

        h.chat.tools(ToolCall("c1", AgentTools.TEMPLATE_READ, "{}")).reply("有 1 个模板")
        h.agent.handle("写手术记录", h.events, h.stop)
        val list = h.chat.requests[1].messages.last().text
        assertTrue("解剖性肝切除" in list, list)

        h.chat.tools(ToolCall("c2", AgentTools.TEMPLATE_READ, """{"name":"解剖性肝切除"}""")).reply("按模板写")
        h.agent.handle("用这个模板", h.events, h.stop)
        val read = h.chat.requests[3].messages.last().text
        assertTrue("阻断→缺血线→离断" in read, read)
    }

    @Test
    fun unknownTemplateIsAnErrorNotSilence() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.TEMPLATE_READ, """{"name":"不存在的"}""")).reply("没有这个模板")
        h.agent.handle("用不存在的模板", h.events, h.stop)
        assertTrue("\"error\"" in h.chat.requests[1].messages.last().text)
    }
}
