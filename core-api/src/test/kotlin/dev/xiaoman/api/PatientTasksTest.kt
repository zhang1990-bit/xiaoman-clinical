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
    fun consultMovesFromRequestToTracking() {
        val toRequest = patient(consults = listOf(Consult("心内科", "2026-11-20", status = "toRequest")))
        assertTrue(PatientTasks.desired(toRequest, today).any { it.item == "请心内科会诊" })
        assertFalse(PatientTasks.desired(toRequest, today).any { it.item == "会诊回复：心内科" })
        assertFalse(PatientTasks.satisfied(toRequest, "请心内科会诊", today))

        val requested = patient(consults = listOf(Consult("心内科", "2026-11-20", status = "requested")))
        assertTrue(PatientTasks.satisfied(requested, "请心内科会诊", today), "提请完成后这条就该消失")
        assertTrue(PatientTasks.desired(requested, today).any { it.item == "会诊回复：心内科" })
    }

    @Test
    fun surgeryClosesPreopReminders() {
        val done = patient(surgery = Surgery("2026-11-20", "胆囊切除"), preopDone = true)
        assertTrue(PatientTasks.satisfied(done, "术前检查是否完善", today))
        assertEquals(emptyList(), PatientTasks.desired(done, today).filter { it.item.startsWith("术前") })
    }

    @Test
    fun postOpScheduleHangsOffTheSurgeryDate() {
        val p = patient(stage = PatientStage.POSTOP, surgery = Surgery("2026-11-20", "胆囊切除"))
        val plans = PatientTasks.postOp(p)
        assertEquals(listOf(2, 4, 6), plans.filter { it.item.contains("复查血") }.map { it.offsetDays })
        val dressing = plans.single { it.item.contains("换药") }
        assertEquals(2, dressing.offsetDays)
        assertEquals(2, dressing.everyDays, "隔天一次")
    }

    @Test
    fun noSurgeryMeansNoPostOpSchedule() {
        assertTrue(PatientTasks.postOp(patient()).isEmpty())
        assertTrue(PatientTasks.postOp(patient(stage = PatientStage.ARCHIVED, surgery = Surgery("2026-11-18", "x"))).isEmpty())
    }

    @Test
    fun everyOtherDayRuleSkipsOddDays() {
        val start = "2026-11-22"
        val task = CareTask(
            id = "po", bed = "12", name = "李xx", patientKey = "李xx", item = "换药",
            rule = TaskRule(everyDays = 2), anchor = TaskAnchor(AnchorKind.SURGERY, 2),
            anchorDate = "2026-11-20", until = "2026-12-04", createdAt = start,
        )
        assertTrue(task.isDue(LocalDate.parse("2026-11-22")))
        assertFalse(task.isDue(LocalDate.parse("2026-11-23")))
        assertTrue(task.isDue(LocalDate.parse("2026-11-24")))
        assertFalse(task.isDue(LocalDate.parse("2026-11-21")), "手术第二天之前不推")
    }

    @Test
    fun onceRuleFiresOnItsDayAndOneGraceDay() {
        val task = CareTask(
            id = "once", bed = "12", name = "李xx", patientKey = "李xx", item = "术后第2天复查血",
            rule = TaskRule(kind = TaskRuleKind.ONCE, maxPerDay = 1),
            anchor = TaskAnchor(AnchorKind.SURGERY, 2), anchorDate = "2026-11-20",
            until = "2026-11-23", createdAt = "2026-11-20",
        )
        assertTrue(task.isDue(LocalDate.parse("2026-11-22")), "当天")
        assertFalse(task.isDue(LocalDate.parse("2026-11-21")), "术后第 2 天之前不推")
        assertTrue(task.isDue(LocalDate.parse("2026-11-23")), "当天没推过，次日补一次")
        assertFalse(task.isDue(LocalDate.parse("2026-11-24")), "补过就不再纠缠")
    }

    @Test
    fun maxPerDayStopsExtraPushes() {
        val task = CareTask(
            id = "cap", bed = "12", name = "李xx", patientKey = "李xx", item = "术前检查",
            rule = TaskRule(maxPerDay = 1), until = "2099-01-01", createdAt = "2026-11-20",
            pushDate = "2026-11-22", pushToday = 1,
        )
        assertFalse(task.isDue(LocalDate.parse("2026-11-22")), "今天已经推过一次")
        assertTrue(task.isDue(LocalDate.parse("2026-11-23")), "换天照常推")
    }

    @Test
    fun labOrderOnlyFiresOnItsOwnDay() {
        val task = CareTask(
            id = "po2", bed = "12", name = "李xx", patientKey = "李xx", item = "术后第2天复查血",
            rule = TaskRule(), anchor = TaskAnchor(AnchorKind.SURGERY, 2),
            anchorDate = "2026-11-20", until = "2026-11-23", createdAt = "2026-11-20",
        )
        assertFalse(task.isDue(LocalDate.parse("2026-11-21")))
        assertTrue(task.isDue(LocalDate.parse("2026-11-22")))
        assertTrue(task.isDue(LocalDate.parse("2026-11-23")), "宽限一天，关机也不至于永远错过")
        assertFalse(task.isDue(LocalDate.parse("2026-11-24")))
    }

    @Test
    fun extraChecksBecomeTheirOwnReminder() {
        val p = patient(preopExtra = listOf("增强 CT"))
        assertTrue(PatientTasks.desired(p, today).any { it.item == "检查：增强 CT" })
    }
}
