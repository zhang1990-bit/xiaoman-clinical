package dev.xiaoman.agent

import dev.xiaoman.api.ChatRole
import dev.xiaoman.api.ContentPart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageTurnTest {
    private val jpeg = byteArrayOf(9, 8, 7)

    @Test
    fun imageGoesWithTheTurnAndLeavesOnlyANoteInHistory() = runTest {
        val h = Harness()
        h.chat.reply("图里三行待办：12 床换药、5 床出院、14 床会诊").reply("好")

        h.agent.handleWithImages("记一下这张图", listOf(ContentPart.Image(jpeg, "image/jpeg")), h.events, h.stop)

        val user = h.chat.requests[0].messages.last()
        assertEquals(ChatRole.USER, user.role)
        assertEquals("记一下这张图", user.text)
        val image = user.parts.filterIsInstance<ContentPart.Image>().single()
        assertEquals("image/jpeg", image.mime)
        assertTrue(image.dataUri().startsWith("data:image/jpeg;base64,"))

        // The next turn must not carry the bytes again, only a note that a picture was sent.
        h.agent.handle("还有吗", h.events, h.stop)
        val history = h.chat.requests[1].messages
        assertTrue(history.none { message -> message.parts.any { it is ContentPart.Image } })
        assertTrue(history.any { it.text.contains("（附图 1 张）") })
    }

    @Test
    fun textOnlyHandleSendsNoImage() = runTest {
        val h = Harness()
        h.chat.reply("好")

        h.agent.handle("12 床换药", h.events, h.stop)

        val user = h.chat.requests[0].messages.last()
        assertTrue(user.parts.none { it is ContentPart.Image })
    }
}
