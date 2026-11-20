package dev.xiaoman.agent

import dev.xiaoman.api.PatientStage
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** patient_list / patient_update / patient_archive：口述建档、手术节点、归档。 */
class PatientToolTest {
    @Test
    fun dictationCreatesPatientsWithSequentialIds() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx","diagnosis":"胆囊结石"}"""))
            .tools(ToolCall("c2", AgentTools.PATIENT_UPDATE, """{"bed":"19","name":"马xx"}"""))
            .reply("记下了")

        h.agent.handle("新入 12 床李xx 胆囊结石，19 床马xx", h.events, h.stop)

        assertEquals(listOf("p-0001", "p-0002"), h.patients.items.map { it.id })
        assertEquals("胆囊结石", h.patients.items.first().diagnosis)
    }

    @Test
    fun surgeryDateMovesToPostopAndClosesPreop() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx"}"""))
            .tools(ToolCall("c2", AgentTools.PATIENT_UPDATE, """{"bed":"12","surgeryDate":"2026-11-19","surgeryName":"腹腔镜胆囊切除术"}"""))
            .reply("术后第一天")

        h.agent.handle("12 床昨天做了胆囊切除", h.events, h.stop)

        val p = h.patients.items.single()
        assertEquals(PatientStage.POSTOP, p.stage)
        assertTrue(p.preopDone)
        assertEquals("腹腔镜胆囊切除术", p.surgery?.name)
        assertTrue(h.patients.events.any { it.type == "patient.updated" })
    }

    @Test
    fun listReportsGapCount() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"19","name":"马xx","tumor":true,"mdt":"待排"}"""))
            .tools(ToolCall("c2", AgentTools.PATIENT_LIST, """{}"""))
            .reply("一位，两个缺口")

        h.agent.handle("现在管几个", h.events, h.stop)

        val tool = h.chat.requests.last().messages.last().text
        assertTrue("\"count\":1" in tool, tool)
        assertTrue("MDT 未排" in tool || "\"gaps\":2" in tool, tool)
    }

    @Test
    fun newPatientGetsPreopAndConsultReminders() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx"}""")).reply("建好了")

        h.agent.handle("新入 12 床李xx", h.events, h.stop)

        val auto = h.tasks.items.filter { it.source == "patient" }.map { it.item }
        assertTrue("术前检查是否完善" in auto, auto.toString())
        assertTrue("是否需请会诊" in auto, auto.toString())
    }

    @Test
    fun confirmingPreopClosesTheReminder() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx"}"""))
            .reply("建好了")
        h.chat.tools(ToolCall("c2", AgentTools.PATIENT_UPDATE, """{"bed":"12","preopDone":true}"""))
            .reply("好")

        h.agent.handle("新入 12 床李xx", h.events, h.stop)
        h.agent.handle("12 床检查齐了", h.events, h.stop)

        val preop = h.tasks.items.first { it.item == "术前检查是否完善" }
        assertEquals(dev.xiaoman.api.TaskState.DONE, preop.state)
    }

    @Test
    fun admittingFromAPhotoCreatesSeveralAndSkipsDuplicates() = runTest {
        val h = Harness()
        h.chat.tools(
            ToolCall("c1", AgentTools.PATIENT_ADMIT, """{"beds":[{"bed":"12","name":"李xx","diagnosis":"胆囊结石"},{"bed":"A65","name":"赵xx"}]}"""),
        ).reply("两人")
        h.agent.handle("今天新入这两个", h.events, h.stop)

        assertEquals(2, h.patients.all().size)

        h.chat.tools(
            ToolCall("c2", AgentTools.PATIENT_ADMIT, """{"beds":[{"bed":"加65床","name":"赵xx","diagnosis":"胆管狭窄"}]}"""),
        ).reply("已存在")
        h.agent.handle("补一张", h.events, h.stop)

        assertEquals(2, h.patients.all().size)
        assertEquals("胆管狭窄", h.patients.all().first { it.bed == "A65" }.diagnosis)
    }

    @Test
    fun archiveMovesPatientOutOfTheList() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx"}"""))
            .tools(ToolCall("c2", AgentTools.PATIENT_ARCHIVE, """{"bed":"12","reason":"出院"}"""))
            .reply("已归档")

        h.agent.handle("12 床出院了", h.events, h.stop)

        assertTrue(h.patients.all().isEmpty())
        assertEquals(1, h.patients.archived().size)
    }
}
