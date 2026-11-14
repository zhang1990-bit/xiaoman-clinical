package dev.xiaoman.app.settings

import android.content.Context
import dev.xiaoman.api.ModelRole
import org.robolectric.RuntimeEnvironment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val key = "ark-0123456789abcdef"

    private fun store() = SettingsStore(
        context.getSharedPreferences("plain-test", Context.MODE_PRIVATE),
        context.getSharedPreferences("secret-test", Context.MODE_PRIVATE),
    )

    private val sample = AppSettings(
        chat = ModelSettings(model = "doubao-chat", apiKey = key, temperature = 0.3),
        operator = ModelSettings(baseUrl = "https://example.com/v1", model = "doubao-vision", apiKey = key),
        maxSteps = 12,
        learnFromSuccess = false,
    )

    @Test fun `defaults point at DeepSeek and are not configured until a key is entered`() {
        val s = store().current
        assertEquals("https://api.deepseek.com/v1", s.chat.baseUrl)
        assertEquals("deepseek-flash", s.chat.model)
        assertFalse(s.configured)
    }

    @Test fun `blank temperature falls back to the app default, not the provider default`() {
        val blank = SettingsForm.ModelForm(apiKey = key).toSettings()!!
        assertEquals(ModelSettings.DEFAULT_TEMPERATURE, blank.toModelConfig(ModelRole.CHAT).temperature)

        val typed = SettingsForm.ModelForm(apiKey = key, temperature = "0.7").toSettings()!!
        assertEquals(0.7, typed.toModelConfig(ModelRole.CHAT).temperature)
    }

    @Test fun `a chat model alone is enough, the operator model is optional`() {
        val chatOnly = AppSettings(chat = ModelSettings(model = "deepseek-chat", apiKey = key))
        assertTrue(chatOnly.configured)
        assertFalse(chatOnly.operator.configured)
    }

    @Test fun `round trip keeps every field and keys stay out of the plain file`() {
        store().save(sample)
        val loaded = store().current
        assertEquals(sample, loaded)
        assertTrue(loaded.configured)
        val plain = context.getSharedPreferences("plain-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(key in plain)
        val secret = context.getSharedPreferences("secret-test", Context.MODE_PRIVATE).all.values.joinToString()
        assertTrue(key in secret)
    }

    @Test fun `toString never shows the api key`() {
        assertFalse(key in sample.toString())
        assertFalse(key in sample.chat.toString())
        assertFalse(key in sample.chat.toModelConfig(dev.xiaoman.api.ModelRole.CHAT).toString())
        assertFalse(key in SettingsForm.of(sample).toString())
        assertTrue("***" in sample.chat.toString())
    }

    @Test fun `form parses and validates numbers`() {
        val form = SettingsForm.of(sample)
        assertEquals(sample, form.toSettings())
        assertNull(form.copy(maxSteps = "0").toSettings())
        assertNull(form.copy(chat = form.chat.copy(temperature = "hot")).toSettings())
        assertNull(form.copy(chat = form.chat.copy(temperature = "")).toSettings()?.chat?.temperature)
    }
}
