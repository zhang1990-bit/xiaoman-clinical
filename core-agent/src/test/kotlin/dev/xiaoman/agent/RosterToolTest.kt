package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.CareTask
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HIS ward table photo path: the model only reads bed/name pairs, the diff happens in code. */
class RosterToolTest {
    private fun task(bed: String = "12", name: String = "李xx", item: String = "术前检查") = CareTask(
        id = "t-$bed-$name", bed = bed, name = name, patientKey = name, item = item,
        until = "2026-12-31", createdAt = "2026-11-01",
    )

    private fun roster(beds: String, apply: Boolean) =
        ToolCall("c1", AgentTools.TASK_ROSTER, """{"beds":$beds,"apply":$apply}""")

    @Test
    fun bedMoveIsAppliedWhenAsked() = runTest {
        val h = Harness()
        h.tasks.items += task()
        h.chat.tools(roster("""[{"bed":"15","name":"李xx"}]""", apply = true)).reply("已按 HIS 改好")

        h.agent.handle("核对一下床号", h.events, h.stop)

        assertEquals("15", h.tasks.items.single().bed)
        val tool = h.chat.requests[1].messages.last().text
        assertTrue("\"moves\":1" in tool, tool)
        assertTrue("\"applied\":true" in tool, tool)
        assertTrue("12床 李xx → 15床" in tool, tool)
        assertEquals("已按 HIS 改好", h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun checkOnlyReportsAndKeepsTheBed() = runTest {
        val h = Harness()
        h.tasks.items += task()
        h.chat.tools(roster("""[{"bed":"15","name":"李xx"}]""", apply = false)).reply("12 床现在是 15 床了")

        h.agent.handle("核对一下床号", h.events, h.stop)

        assertEquals("12", h.tasks.items.single().bed)
        assertTrue("待改床" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun sameBedOtherNameIsReportedNotFixed() = runTest {
        val h = Harness()
        h.tasks.items += task(bed = "8", name = "李xx")
        h.chat.tools(roster("""[{"bed":"8","name":"王xx"}]""", apply = true)).reply("8 床换人了，你看要不要关掉")

        h.agent.handle("核对一下床号", h.events, h.stop)

        assertEquals("8", h.tasks.items.single().bed)
        val tool = h.chat.requests[1].messages.last().text
        assertTrue("\"conflicts\":1" in tool, tool)
        assertTrue("同床不同姓" in tool, tool)
    }

    @Test
    fun emptyRosterIsAnErrorNotAnAllClear() = runTest {
        val h = Harness()
        h.tasks.items += task()
        h.chat.tools(roster("[]", apply = true)).reply("这张表没读出来，重拍一张")

        h.agent.handle("核对一下床号", h.events, h.stop)

        assertTrue("\"error\"" in h.chat.requests[1].messages.last().text)
        assertEquals("12", h.tasks.items.single().bed)
    }
}
