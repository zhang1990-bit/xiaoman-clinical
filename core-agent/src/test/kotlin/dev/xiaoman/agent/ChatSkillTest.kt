package dev.xiaoman.agent

import dev.xiaoman.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChatSkillTest {
    private fun workflow(name: String = "today-plan", body: String = "先列今日三件事，再询问提醒时间") =
        Skill(name = name, description = "排今日开工卡", channel = SkillChannel.CHAT,
            instructions = body, memoryRefs = listOf("偏好/工作", "不存在"), source = "test")

    @Test
    fun chatInstructionsAndReferencesEnterPromptButNotPhoneIndex() = runTest {
        val h = Harness(skills = FakeSkills(skill("taxi-home", "打车回家"), workflow()))
        h.memory.write(Note(title = "工作习惯", permalink = "偏好/工作", body = "上午先做难事"))
        h.chat.reply("好")
        h.agent.handle("开工", h.events, h.stop)
        val system = h.chat.requests.single().messages.first().text
        val phoneIndex = system.substringAfter(Prompts.SKILLS_HEADER).substringBefore(Prompts.CHAT_SKILLS_HEADER)
        assertTrue("taxi-home" in phoneIndex)
        assertFalse("today-plan" in phoneIndex)
        assertTrue("### today-plan：排今日开工卡" in system)
        assertTrue("先列今日三件事" in system)
        assertTrue("上午先做难事" in system)
        assertTrue(Prompts.CHAT_SKILLS_HINT in system)
    }

    @Test
    fun noPhoneModeKeepsChatWorkflowAndOmitsPhoneIndex() = runTest {
        val h = Harness(skills = FakeSkills(workflow(), skill("taxi-home")), config = AgentConfig(phoneTasks = false))
        h.chat.reply("好")
        h.agent.handle("开工", h.events, h.stop)
        val request = h.chat.requests.single()
        assertFalse(Prompts.SKILLS_HEADER in request.messages.first().text)
        assertFalse("taxi-home" in request.messages.first().text)
        assertTrue("先列今日三件事" in request.messages.first().text)
        assertFalse(request.tools.any { it.name == AgentTools.PHONE_TASK })
    }

    @Test
    fun phoneTaskRejectsChatBeforeOperatorOrDeviceRuns() = runTest {
        val h = Harness(skills = FakeSkills(workflow()))
        val result = h.agent.runPhoneTask("开工", "today-plan", h.events, h.stop)
        assertFalse(result.success)
        assertEquals(0, result.steps)
        assertTrue(Prompts.CHAT_SKILL_PHONE_REFUSED in result.summary)
        assertEquals(listOf<AgentEvent>(result), h.events.all)
        assertTrue(h.operator.requests.isEmpty())
        assertEquals(0, h.device.observations)
        assertTrue(h.device.performed.isEmpty())
    }

    @Test
    fun workflowBodiesHavePerSkillAndTotalLimits() = runTest {
        val skills = (0..4).map { workflow("plan-$it", "◆".repeat(5100) + "正文尾部") }
        val h = Harness(skills = FakeSkills(*skills.toTypedArray()))
        h.chat.reply("好")
        h.agent.handle("开工", h.events, h.stop)
        val system = h.chat.requests.single().messages.first().text
        assertEquals(8000, system.count { it == '◆' })
        assertFalse("正文尾部" in system)
        assertTrue("### plan-4：排今日开工卡" in system)
    }

    @Test
    fun listSkillsIncludesChannel() = runTest {
        val h = Harness(skills = FakeSkills(workflow(), skill("taxi-home")))
        h.chat.tools(ToolCall("s", AgentTools.LIST_SKILLS, "{}")).reply("好")
        h.agent.handle("技能", h.events, h.stop)
        val result = h.chat.requests.last().messages.last().text
        assertTrue("\"channel\":\"chat\"" in result)
        assertTrue("\"channel\":\"gui\"" in result)
    }
}
