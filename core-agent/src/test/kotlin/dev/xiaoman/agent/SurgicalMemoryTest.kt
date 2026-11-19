package dev.xiaoman.agent

import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "记不住"的根治：磁盘上的记录每轮都写进系统提示，旧稿能读回来，历史窗口加宽。 */
class SurgicalMemoryTest {
    @Test
    fun savedRecordsRideAlongInEverySystemPrompt() = runTest {
        val h = Harness()
        h.records.files["/fake/records/20261119_1_开腹肝癌切除.txt"] = "第一台正文"
        h.chat.reply("好")

        h.agent.handle("写第三台", h.events, h.stop)

        val system = h.chat.requests.single().messages.first().text
        assertTrue(Prompts.RECORDS_HEADER in system, system.takeLast(400))
        assertTrue("20261119_1_开腹肝癌切除.txt" in system)
        assertTrue("record_read" in system, "提示里要指明怎么把旧稿读回来")
    }

    @Test
    fun noRecordsMeansNoSection() = runTest {
        val h = Harness()
        h.chat.reply("好")
        h.agent.handle("写第一台", h.events, h.stop)
        assertTrue(Prompts.RECORDS_HEADER !in h.chat.requests.single().messages.first().text)
    }

    @Test
    fun oldRecordCanBeReadBackByTool() = runTest {
        val h = Harness()
        h.records.files["/fake/records/20261119_1_开腹肝癌切除.txt"] = "第一台正文，含植入物清单"
        h.chat.tools(ToolCall("c1", AgentTools.RECORD_READ, """{"name":"20261119_1_开腹肝癌切除"}""")).reply("读到了")

        h.agent.handle("上一台怎么写的", h.events, h.stop)

        val tool = h.chat.requests[1].messages.last().text
        assertTrue("第一台正文，含植入物清单" in tool, tool)
    }

    @Test
    fun listShowsWhatIsAlreadyOnDisk() = runTest {
        val h = Harness()
        h.records.files["/fake/records/20261119_1_开腹肝癌切除.txt"] = "a"
        h.records.files["/fake/records/20261119_2_腹腔镜胆囊切除.txt"] = "b"
        h.chat.tools(ToolCall("c1", AgentTools.RECORD_LIST, "{}")).reply("今天两台")

        h.agent.handle("今天做了几台", h.events, h.stop)

        val tool = h.chat.requests[1].messages.last().text
        assertTrue("\"count\":2" in tool, tool)
        assertTrue("20261119_2_腹腔镜胆囊切除.txt" in tool, tool)
    }

    @Test
    fun historyWindowIsWideEnoughForThreeOperations() {
        assertEquals(40, XiaomanAgent.HISTORY_LIMIT)
    }
}
