package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.CareTask
import dev.xiaoman.api.PatientSlot
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The daily HIS ward table: the model only reads bed/name pairs, saving and diffing happen in code. */
class RosterToolTest {
    private fun task(bed: String = "12", name: String = "李xx", item: String = "术前检查") = CareTask(
        id = "t-$bed-$name", bed = bed, name = name, patientKey = name, item = item,
        until = "2026-12-31", createdAt = "2026-11-01",
    )

    private fun sync(beds: String, apply: Boolean = false) =
        ToolCall("c1", AgentTools.TASK_ROSTER, """{"beds":$beds,"apply":$apply}""")

    @Test
    fun firstPhotoIsSavedAndMovesTheTask() = runTest {
        val h = Harness()
        h.tasks.items += task(bed = "12")
        h.chat.tools(sync("""[{"bed":"15","name":"李xx"}]""", apply = true)).reply("记住了，15 床")

        h.agent.handle("今天的名单", h.events, h.stop)

        assertEquals("15", h.tasks.items.single().bed)
        assertEquals(listOf("李xx"), h.roster.items.map { it.name })
        val tool = h.chat.requests[1].messages.last().text
        assertTrue("\"saved\":1" in tool, tool)
        assertTrue("待办跟床｜12床 李xx → 15床" in tool, tool)
        assertTrue("已改" in tool, tool)
        assertEquals("记住了，15 床", h.events.of<AgentEvent.Reply>().single().text)
    }

    @Test
    fun checkOnlyReportsWithoutChangingTheBed() = runTest {
        val h = Harness()
        h.tasks.items += task()
        h.chat.tools(sync("""[{"bed":"15","name":"李xx"}]""", apply = false)).reply("12 床去 15 床了")

        h.agent.handle("核对一下", h.events, h.stop)

        assertEquals("12", h.tasks.items.single().bed)
        assertTrue("待改" in h.chat.requests[1].messages.last().text)
    }

    @Test
    fun secondDayReportsNewDischargedAndMoved() = runTest {
        val h = Harness()
        h.roster.items += listOf(PatientSlot("12", "李xx", "2026-11-14"), PatientSlot("5", "王xx", "2026-11-14"))
        h.chat.tools(sync("""[{"bed":"15","name":"李xx"},{"bed":"19","name":"马xx"}]""")).reply("收到")

        h.agent.handle("今天的名单", h.events, h.stop)

        val tool = h.chat.requests[1].messages.last().text
        assertTrue("新入｜19床 马xx" in tool, tool)
        assertTrue("出院或转出｜5床 王xx" in tool, tool)
        assertTrue("调床｜李xx 12床 → 15床" in tool, tool)
        assertEquals(listOf("李xx", "马xx"), h.roster.items.map { it.name })
    }

    @Test
    fun sameBedOtherNameIsReportedNotFixed() = runTest {
        val h = Harness()
        h.tasks.items += task(bed = "8", name = "李xx")
        h.chat.tools(sync("""[{"bed":"8","name":"王xx"}]""", apply = true)).reply("8 床换人了")

        h.agent.handle("今天的名单", h.events, h.stop)

        assertEquals("8", h.tasks.items.single().bed)
        val tool = h.chat.requests[1].messages.last().text
        assertTrue("同床不同姓｜8床" in tool, tool)
    }

    @Test
    fun askingWithoutAPhotoAnswersFromTheSavedRoster() = runTest {
        val h = Harness()
        h.roster.items += listOf(PatientSlot("2", "李xx", "2026-11-15"), PatientSlot("19", "马xx", "2026-11-15"))
        h.tasks.items += task(bed = "2", name = "李xx", item = "术前检查")
        h.chat.tools(ToolCall("c1", AgentTools.TASK_ROSTER, """{}""")).reply("2 床李xx，术前检查")

        h.agent.handle("12 床是谁", h.events, h.stop)

        val tool = h.chat.requests[1].messages.last().text
        assertTrue("\"count\":2" in tool, tool)
        assertTrue("2床 李xx" in tool, tool)
        assertTrue("withTasks" in tool, tool)
    }

    @Test
    fun rosterImportBuildsPatientFiles() = runTest {
        val h = Harness()
        h.chat.tools(sync("""[{"bed":"12","name":"李xx"},{"bed":"13","name":"王xx"}]""")).reply("13 位")

        h.agent.handle("这是今天的在院患者", h.events, h.stop)

        assertEquals(listOf("12", "13"), h.patients.all().map { it.bed })
        assertTrue("\"patientsCreated\":2" in h.chat.requests.last().messages.last().text)
    }

    @Test
    fun secondImportArchivesWhoDisappeared() = runTest {
        val h = Harness()
        h.patients.items += dev.xiaoman.api.Patient(
            id = "p-0009", name = "王xx", bed = "13", admittedOn = "2026-11-18",
            createdAt = "2026-11-18", updatedAt = "2026-11-18", lastSeenOnRoster = "2026-11-19",
        )
        h.chat.tools(sync("""[{"bed":"12","name":"李xx"}]""")).reply("收到")

        h.agent.handle("今天的名单", h.events, h.stop)

        assertEquals(listOf("李xx"), h.patients.all().map { it.name })
        assertEquals(listOf("王xx"), h.patients.archived().map { it.name })
    }

    @Test
    fun patientCreatedByDictationIsNotArchivedByAnOldPhoto() = runTest {
        val h = Harness()
        h.patients.items += dev.xiaoman.api.Patient(
            id = "p-0005", name = "新来的", bed = "99", admittedOn = "2026-11-20",
            createdAt = "2026-11-20", updatedAt = "2026-11-20", lastSeenOnRoster = null,
        )
        h.chat.tools(sync("""[{"bed":"12","name":"李xx"}]""")).reply("收到")

        h.agent.handle("补发一张昨天的名单", h.events, h.stop)

        assertEquals(2, h.patients.all().size)
        assertTrue(h.patients.archived().isEmpty())
    }

    @Test
    fun noPhotoAndNoSavedRosterIsAnError() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.TASK_ROSTER, """{"beds":[]}""")).reply("还没有名单")

        h.agent.handle("我管几个病号", h.events, h.stop)

        assertTrue("\"error\"" in h.chat.requests[1].messages.last().text)
    }
}
