package dev.xiaoman.api

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatientTest {
    private fun patient(
        bed: String = "12",
        stage: PatientStage = PatientStage.ADMITTED,
        surgery: Surgery? = null,
        admittedOn: String = "2026-11-17",
    ) = Patient(
        id = "p-0007", name = "李xx", bed = bed, stage = stage, admittedOn = admittedOn,
        surgery = surgery, createdAt = admittedOn, updatedAt = admittedOn,
    )

    @Test
    fun stageLabelUsesShortForm() {
        val today = LocalDate.of(2026, 11, 20)
        assertEquals("术前", patient(stage = PatientStage.PREOP).stageLabel(today))
        assertEquals("入院D3", patient().stageLabel(today))
        assertEquals("术后D2", patient(stage = PatientStage.POSTOP, surgery = Surgery("2026-11-18", "胆囊切除")).stageLabel(today))
        assertEquals("术后D0", patient(stage = PatientStage.POSTOP, surgery = Surgery("2026-11-20", "胆囊切除")).stageLabel(today))
    }

    @Test
    fun postOpDayIsNullBeforeSurgery() {
        val today = LocalDate.of(2026, 11, 20)
        assertNull(patient().postOpDay(today))
        assertNull(patient(surgery = Surgery("2026-11-25", "未来")).postOpDay(today))
        assertEquals(3, patient(surgery = Surgery("2026-11-17", "x")).postOpDay(today))
    }

    @Test
    fun movingBedsClosesTheOldStay() {
        val p = patient(bed = "15").movedTo("12", "2026-11-19")
        assertEquals("12", p.bed)
        assertEquals(2, p.bedHistory.size)
        assertEquals("15", p.bedHistory.first().bed)
        assertEquals("2026-11-19", p.bedHistory.first().to)
        assertNull(p.bedHistory.last().to)
    }

    @Test
    fun gapsCoverPreopConsultMdtAndExtraChecks() {
        val today = LocalDate.of(2026, 11, 20)
        val p = patient(stage = PatientStage.PREOP).copy(
            tumor = true,
            mdt = "待排",
            consults = listOf(Consult("心内科", "2026-11-18")),
            preopExtra = listOf("增强 CT"),
        )
        val gaps = PatientGaps.of(p, today)
        assertTrue("术前检查未完善" in gaps)
        assertTrue("会诊未回" in gaps)
        assertTrue("MDT 未排" in gaps)
        assertTrue("待：增强 CT" in gaps)
    }

    @Test
    fun finishedPreopAndAnsweredConsultLeaveNoGap() {
        val today = LocalDate.of(2026, 11, 20)
        val p = patient(stage = PatientStage.POSTOP).copy(
            preopDone = true,
            consults = listOf(Consult("心内科", "2026-11-18", status = "replied", repliedOn = "2026-11-19")),
            surgery = Surgery("2026-11-19", "胆囊切除"),
        )
        assertEquals(emptyList(), PatientGaps.of(p, today))
    }
}
