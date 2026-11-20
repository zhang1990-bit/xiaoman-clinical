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
    fun surgeryDateSchedulesThePostOpChain() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx"}""")).reply("好")
        h.agent.handle("新入 12 床", h.events, h.stop)

        h.chat.tools(
            ToolCall("c2", AgentTools.PATIENT_UPDATE, """{"bed":"12","surgeryDate":"2026-11-20","surgeryName":"腹腔镜胆囊切除术"}"""),
        ).reply("排上了")
        h.agent.handle("12 床今天做了胆囊切除", h.events, h.stop)

        val post = h.tasks.items.filter { it.source == "patient" && it.anchorDate == "2026-11-20" }
        assertEquals(4, post.size, post.map { it.item }.toString())
        assertTrue(post.any { it.item.contains("术后第2天") && it.until == "2026-11-23" })
        assertTrue(post.any { it.item.contains("换药") && it.rule.everyDays == 2 })
        // 术前检查那条应当自动关掉
        assertEquals(
            dev.xiaoman.api.TaskState.DONE,
            h.tasks.items.single { it.item == "术前检查是否完善" }.state,
        )
    }

    @Test
    fun movingTheSurgeryDateShiftsTheChainInsteadOfDuplicating() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx","surgeryDate":"2026-11-20"}""")).reply("好")
        h.agent.handle("12 床今天做了", h.events, h.stop)

        h.chat.tools(ToolCall("c2", AgentTools.PATIENT_UPDATE, """{"bed":"12","surgeryDate":"2026-11-21"}""")).reply("改了")
        h.agent.handle("手术改到明天", h.events, h.stop)

        val post = h.tasks.items.filter { it.source == "patient" && it.item.contains("术后第") }
        assertEquals(3, post.size, post.map { "${it.item}@${it.anchorDate}" }.toString())
        assertTrue(post.all { it.anchorDate == "2026-11-21" })
    }

    @Test
    fun archivingClearsPostOpTasksAndLeavesAHomepageReminder() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx","surgeryDate":"2026-11-20"}""")).reply("好")
        h.agent.handle("12 床做了", h.events, h.stop)

        h.chat.tools(ToolCall("c2", AgentTools.PATIENT_ARCHIVE, """{"bed":"12","reason":"出院"}""")).reply("归档了")
        h.agent.handle("12 床出院了", h.events, h.stop)

        val auto = h.tasks.items.filter { it.patientId == "p-0001" }
        assertTrue(auto.none { it.state == dev.xiaoman.api.TaskState.ACTIVE && it.item.contains("换药") }, "术后节点要清掉")
        val homepage = auto.single { it.item == "整理出院首页" }
        assertEquals(dev.xiaoman.api.TaskState.ACTIVE, homepage.state)
        assertTrue(homepage.until > homepage.createdAt, "出院首页提醒只在 3 天内有效")
    }

    @Test
    fun patientComingBackClosesTheHomepageReminder() = runTest {
        val h = Harness()
        h.chat.tools(ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx"}""")).reply("好")
        h.agent.handle("新入 12 床", h.events, h.stop)
        h.chat.tools(ToolCall("c2", AgentTools.PATIENT_ARCHIVE, """{"bed":"12","reason":"名单未出现"}""")).reply("归档")
        h.agent.handle("12 床不在了", h.events, h.stop)

        h.chat.tools(
            ToolCall("c3", AgentTools.TASK_ROSTER, """{"beds":[{"bed":"12","name":"李xx"}]}"""),
        ).reply("又出现了")
        h.agent.handle("今天的名单", h.events, h.stop)

        assertEquals(1, h.patients.all().size)
        assertEquals(
            dev.xiaoman.api.TaskState.DONE,
            h.tasks.items.single { it.item == "整理出院首页" }.state,
        )
    }

    @Test
    fun dayCloseListsThenAppliesDecisions() = runTest {
        val h = Harness()
        h.tasks.items += dev.xiaoman.api.CareTask(
            id = "t-1", bed = "12", name = "李xx", patientKey = "李xx", item = "术前检查",
            until = "2099-01-01", createdAt = "1970-01-01",
        )
        h.chat.tools(ToolCall("c1", AgentTools.DAY_CLOSE, "{}")).reply("一条没弄")

        h.agent.handle("收工", h.events, h.stop)

        val list = h.chat.requests.last().messages.last().text
        assertTrue("\"count\":1" in list, list)
        assertTrue("术前检查" in list, list)

        h.chat.tools(ToolCall("c2", AgentTools.DAY_CLOSE, """{"decisions":[{"id":"t-1","action":"done"}]}""")).reply("办完了")
        h.agent.handle("12 床检查做了", h.events, h.stop)

        assertEquals(dev.xiaoman.api.TaskState.DONE, h.tasks.items.single().state)
    }

    @Test
    fun dayCloseCanSnoozeUntilTomorrow() = runTest {
        val h = Harness()
        h.tasks.items += dev.xiaoman.api.CareTask(
            id = "t-2", bed = "8", name = "王xx", patientKey = "王xx", item = "会诊回复",
            until = "2099-01-01", createdAt = "1970-01-01",
        )
        h.chat.tools(ToolCall("c1", AgentTools.DAY_CLOSE, """{"decisions":[{"id":"t-2","action":"later"}]}""")).reply("明天再说")

        h.agent.handle("延后", h.events, h.stop)

        val task = h.tasks.items.single()
        assertEquals(dev.xiaoman.api.TaskState.ACTIVE, task.state, "延后不是办完")
        assertTrue(task.silentDate != null, "今天不再推")
    }

    @Test
    fun askingForAConsultCreatesTheToDoNotJustArecord() = runTest {
        val h = Harness()
        h.chat.tools(
            ToolCall("c1", AgentTools.PATIENT_UPDATE, """{"bed":"12","name":"李xx","consult":"心内科"}"""),
        ).reply("已加")

        h.agent.handle("12 床请心内科会诊", h.events, h.stop)

        val patient = h.patients.items.single()
        assertEquals("toRequest", patient.consults.single().status)
        assertTrue(
            h.tasks.items.any { it.item == "请心内科会诊" },
            h.tasks.items.map { it.item }.toString(),
        )
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
