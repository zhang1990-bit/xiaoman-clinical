package dev.xiaoman.api

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatientTasksTest {
    private val today = LocalDate.of(2026, 11, 20)

    private fun patient(
        stage: PatientStage = PatientStage.ADMITTED,
        admittedOn: String = "2026-11-20",
        preopDone: Boolean = false,
        tumor: Boolean = false,
        mdt: String? = null,
        consults: List<Consult> = emptyList(),
        surgery: Surgery? = null,
        preopExtra: List<String> = emptyList(),
    ) = Patient(
        id = "p-0001", name = "李xx", bed = "12", stage = stage, admittedOn = admittedOn,
        preopDone = preopDone, tumor = tumor, mdt = mdt, consults = consults, surgery = surgery,
        preopExtra = preopExtra, createdAt = admittedOn, updatedAt = admittedOn,
    )

    @Test
    fun newAdmissionAsksForPreopCheckAndConsultThreeDays() {
        val items = PatientTasks.desired(patient(), today).map { it.item }
        assertTrue("术前检查是否完善" in items)
        assertTrue("是否需请会诊" in items)
        assertFalse("上 MDT" in items)
    }

    @Test
    fun consultQuestionStopsAfterThreeDays() {
        val late = patient(admittedOn = "2026-11-15")
        assertFalse(PatientTasks.desired(late, today).any { it.item == "是否需请会诊" })
        // 术前检查仍然跟着，直到手术或确认齐了
        assertTrue(PatientTasks.desired(late, today).any { it.item == "术前检查是否完善" })
    }

    @Test
    fun tumorWithoutMdtAsksForMdt() {
        assertTrue(PatientTasks.desired(patient(tumor = true), today).any { it.item == "上 MDT" })
        assertFalse(PatientTasks.desired(patient(tumor = true, mdt = "已排 11-22"), today).any { it.item == "上 MDT" })
    }

    @Test
    fun pendingConsultIsTrackedAndFollowUpKeepsTracking() {
        val pending = patient(consults = listOf(Consult("心内科", "2026-11-19")))
        assertTrue(PatientTasks.desired(pending, today).any { it.item == "会诊回复：心内科" })
        assertTrue(PatientTasks.satisfied(pending, "会诊回复：心内科", today).not())

        val replied = patient(
            consults = listOf(Consult("心内科", "2026-11-18", status = "replied", repliedOn = "2026-11-19", followUp = "动态心电图")),
        )
        assertTrue(PatientTasks.satisfied(replied, "会诊回复：心内科", today))
        assertTrue(PatientTasks.desired(replied, today).any { it.item == "会诊后续：心内科" })
    }

    @Test
    fun surgeryClosesPreopReminders() {
        val done = patient(surgery = Surgery("2026-11-20", "胆囊切除"), preopDone = true)
        assertTrue(PatientTasks.satisfied(done, "术前检查是否完善", today))
        assertEquals(emptyList(), PatientTasks.desired(done, today).filter { it.item.startsWith("术前") })
    }

    @Test
    fun extraChecksBecomeTheirOwnReminder() {
        val p = patient(preopExtra = listOf("增强 CT"))
        assertTrue(PatientTasks.desired(p, today).any { it.item == "检查：增强 CT" })
    }
}
