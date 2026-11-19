package dev.xiaoman.agent

import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.ToolCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 分层记忆：患者类会过期、偏好类长期有效，过期条目不占提示词。 */
class MemoryLayersTest {
    // 2025-10-09 前后，让 valid_until 的过去/未来可判断
    private val clock = { 1_760_000_000_000L }
    private fun harness() = Harness(clock = clock)

    private fun note(permalink: String, title: String, validUntil: String?, kind: String = "patient") =
        Note(
            title = title, permalink = permalink,
            extraFrontmatter = buildMap {
                put("kind", kind)
                if (validUntil != null) put("valid_until", validUntil)
            },
            observations = listOf(Observation("status", "胆囊切除术后第 3 天")),
        )

    @Test
    fun expiredPatientNoteIsNotInjected() = runTest {
        val h = harness()
        h.memory.write(note("patients/李xx", "李xx", validUntil = "2025-10-01"))
        h.chat.reply("好")

        h.agent.handle("李xx 胆囊", h.events, h.stop)

        val system = h.chat.requests.single().messages.first().text
        assertTrue("李xx" !in system, "过期条目不该进提示词：${system.takeLast(600)}")
    }

    @Test
    fun livePatientNoteIsInjectedWithItsDeadline() = runTest {
        val h = harness()
        h.memory.write(note("patients/王xx", "王xx", validUntil = "2025-12-01"))
        h.chat.reply("好")

        h.agent.handle("王xx 胆囊", h.events, h.stop)

        val system = h.chat.requests.single().messages.first().text
        assertTrue("王xx" in system, system.takeLast(600))
        assertTrue("近期·至12-01" in system, system.takeLast(600))
    }

    @Test
    fun preferenceIsMarkedLongTerm() = runTest {
        val h = harness()
        h.memory.write(note("preferences/写法", "写法", validUntil = null, kind = "preference"))
        h.chat.reply("好")

        h.agent.handle("写法 胆囊", h.events, h.stop)

        assertTrue("长期·偏好" in h.chat.requests.single().messages.first().text)
    }

    @Test
    fun patientWriteGetsThirtyDaysByDefault() = runTest {
        val h = harness()
        h.chat.tools(
            ToolCall(
                "c1", AgentTools.MEMORY_WRITE,
                """{"title":"李xx","kind":"patient","observations":[{"category":"status","content":"12床 胆总管结石"}]}""",
            ),
        ).reply("记住了")

        h.agent.handle("记住 12 床李xx 胆总管结石", h.events, h.stop)

        val stored = h.memory.notes.values.single()
        assertEquals("patient", stored.extraFrontmatter["kind"])
        assertEquals("2025-11-08", stored.extraFrontmatter["valid_until"], "默认 30 天")
    }

    @Test
    fun preferenceWriteNeverExpires() = runTest {
        val h = harness()
        h.chat.tools(
            ToolCall(
                "c1", AgentTools.MEMORY_WRITE,
                """{"title":"写法","kind":"preference","observations":[{"category":"style","content":"切口长度不写"}]}""",
            ),
        ).reply("记住了")

        h.agent.handle("记住 切口长度不写", h.events, h.stop)

        assertNull(h.memory.notes.values.single().extraFrontmatter["valid_until"])
    }
}
