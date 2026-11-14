package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.LlmException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelErrorTest {
    @Test fun chatAccountLimitDoesNotSuggestRetrying() = runTest {
        val h = Harness()
        h.chat.fallback = {
            throw LlmException("raw secret", 429, providerCode = "SetLimitExceeded")
        }
        h.agent.handle("hi", h.events, h.stop)
        val error = h.events.of<AgentEvent.Failed>().single().message
        assertTrue("账号限额暂停" in error && "SetLimitExceeded" in error)
        assertFalse("稍后再试" in error || "secret" in error)
    }

    @Test fun accountLimitIsNotRetriedByOperatorAndExplainsRecovery() = runTest {
        val h = Harness()
        h.operator.fallback = {
            throw LlmException("raw body contains secret", 429, providerCode = "SetLimitExceeded")
        }
        val result = h.agent.runPhoneTask("query", null, h.events, h.stop)
        assertFalse(result.success)
        assertEquals(1, h.operator.requests.size)
        assertTrue("安心体验" in result.summary && "账户余额" in result.summary)
        assertFalse("secret" in result.summary)
    }

    @Test fun wrongModelNameIsShownWithTheProvidersOwnWords() = runTest {
        val h = Harness()
        h.chat.fallback = {
            throw LlmException(
                "HTTP 400 from https://api.deepseek.com/v1/chat/completions: Model Not Exist",
                400, providerCode = "invalid_request_error", providerMessage = "Model Not Exist",
            )
        }
        h.agent.handle("明天有两个出院要整", h.events, h.stop)
        val error = h.events.of<AgentEvent.Failed>().single().message
        assertTrue("400" in error && "invalid_request_error" in error, error)
        assertTrue("Model Not Exist" in error, error)
        assertTrue("模型名称不对" in error, error)
        assertTrue("重试无效" in error, error)
    }

    private fun quotaError(): Nothing = throw LlmException(
        "raw body contains secret", 429, providerCode = "QuotaExceeded.FreeTier",
    )

    @Test fun chatReportsProviderCodeWithoutRawBody() = runTest {
        val h = Harness()
        h.chat.fallback = { quotaError() }
        h.agent.handle("hi", h.events, h.stop)
        val error = h.events.of<AgentEvent.Failed>().single().message
        assertTrue("429" in error && "QuotaExceeded.FreeTier" in error)
        assertFalse("secret" in error)
    }

    @Test fun phoneTaskReportsProviderCodeWithoutRawBody() = runTest {
        val h = Harness()
        h.operator.fallback = { quotaError() }
        val result = h.agent.runPhoneTask("query", null, h.events, h.stop)
        assertFalse(result.success)
        assertTrue("429" in result.summary && "QuotaExceeded.FreeTier" in result.summary)
        assertFalse("secret" in result.summary)
    }
}
