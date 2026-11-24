package dev.xiaoman.api

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteRulesTest {
    private val today = LocalDate.of(2026, 11, 25)
    private val patient = Patient(
        id = "p-1", name = "李xx", bed = "12", stage = PatientStage.POSTOP,
        admittedOn = "2026-11-18", surgery = Surgery("2026-11-20", "胆囊切除"),
        createdAt = "2026-11-18", updatedAt = "2026-11-20",
    )

    @Test
    fun consultPicksTheDepartment() {
        val intent = NoteRules.classify("请心内科会诊，评估心律", patient, today)
        assertEquals(NoteKind.CONSULT, intent.kind)
        assertEquals("心内科", intent.dept)
        assertTrue(intent.summary().contains("待提请"))
    }

    @Test
    fun bloodDrawReadsRelativeDates() {
        assertEquals("2026-11-26", NoteRules.classify("明天查血", patient, today).dueDate)
        assertEquals("2026-11-23", NoteRules.classify("术后第3天复查血", patient, today).dueDate)
        assertEquals(NoteKind.BLOOD, NoteRules.classify("后天复查血常规", patient, today).kind)
    }

    @Test
    fun dressingAndOrdersAreSeparated() {
        assertEquals(NoteKind.DRESSING, NoteRules.classify("右侧切口换药", patient, today).kind)
        assertEquals(NoteKind.ORDER, NoteRules.classify("术前备血，禁食", patient, today).kind)
    }

    @Test
    fun smallTalkStaysANote() {
        val intent = NoteRules.classify("精神好，引流不多", patient, today)
        assertEquals(NoteKind.OTHER, intent.kind)
        assertEquals("只记备注", intent.summary())
    }

    @Test
    fun modelJsonIsParsedLeniently() {
        val intent = NoteRules.parse("请消化内科会诊", """好的 {"kind":"CONSULT","dept":"消化内科","dueDate":"2026-11-26"}""")
        assertEquals(NoteKind.CONSULT, intent!!.kind)
        assertEquals("消化内科", intent.dept)
        assertEquals("2026-11-26", intent.dueDate)
        assertNull(NoteRules.parse("x", "不是 JSON"))
    }
}
