package dev.xiaoman.api

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkbenchTest {
    private val today = LocalDate.of(2026, 11, 24)
    private val tomorrow = "2026-11-25"

    private fun p(
        bed: String,
        name: String = "李xx",
        stage: PatientStage = PatientStage.PREOP,
        surgery: Surgery? = null,
        planned: PlannedSurgery? = null,
        consults: List<Consult> = emptyList(),
        discharge: String? = null,
    ) = Patient(
        id = "p-$bed", name = name, bed = bed, stage = stage, admittedOn = "2026-11-20",
        surgery = surgery, plannedSurgery = planned, consults = consults, plannedDischarge = discharge,
        createdAt = "2026-11-20", updatedAt = "2026-11-20",
    )

    @Test
    fun tomorrowSurgeryOnlyCountsTheScheduledDay() {
        val board = Workbench.build(
            listOf(
                p("12", planned = PlannedSurgery(tomorrow, "腹腔镜胆囊切除", "第1台")),
                p("20", planned = PlannedSurgery("2026-11-28", "胆囊切除")),
            ),
            today,
        )
        assertEquals(listOf("12"), board.of(Workbench.Kind.TOMORROW).map { it.patient.bed })
        // 待手术包含明天那台（还没做），也包含没排期的；明日手术是其中明天的子集
        assertEquals(listOf("12", "20"), board.of(Workbench.Kind.TO_OPERATE).map { it.patient.bed })
    }

    @Test
    fun conservativePatientsStayOutOfToOperate() {
        val board = Workbench.build(
            listOf(
                p("20"),
                p("21").copy(conservative = true),
                p("22", consults = listOf(Consult("心内科", "2026-11-24", status = "requested"))).copy(conservative = true),
            ),
            today,
        )
        assertEquals(listOf("20"), board.of(Workbench.Kind.TO_OPERATE).map { it.patient.bed })
        // 保守治疗不影响会诊
        assertEquals(listOf("22"), board.of(Workbench.Kind.CONSULT).map { it.patient.bed })
    }

    @Test
    fun unscheduledPreopCountsAsToOperate() {
        val board = Workbench.build(listOf(p("20")), today)
        assertEquals("待排期", board.of(Workbench.Kind.TO_OPERATE).single().flag)
    }

    @Test
    fun bloodDrawRunsOnOddDaysAfterSurgery() {
        fun post(day: Int, bed: String) = p(
            bed, stage = PatientStage.POSTOP,
            surgery = Surgery(today.minusDays(day.toLong()).toString(), "胆囊切除"),
        )
        val board = Workbench.build(listOf(post(1, "29"), post(2, "10"), post(3, "21"), post(5, "08")), today)
        assertEquals(listOf("08", "21", "29"), board.of(Workbench.Kind.BLOOD).map { it.patient.bed })
    }

    @Test
    fun consultSplitsIntoRequestAndReply() {
        val board = Workbench.build(
            listOf(
                p("19", consults = listOf(Consult("心内科", "2026-11-24", status = "toRequest"))),
                p("A65", consults = listOf(Consult("消化内科", "2026-11-21", status = "requested"))),
            ),
            today,
        )
        val consults = board.of(Workbench.Kind.CONSULT)
        assertEquals(2, consults.size)
        assertTrue(consults.any { it.flag == "待提请" })
        assertTrue(consults.any { it.flag == "等回复" && it.note.contains("3 天") }, consults.toString())
    }

    @Test
    fun dischargeCoversTodayAndTomorrow() {
        val board = Workbench.build(
            listOf(p("43", stage = PatientStage.POSTOP, discharge = "2026-11-24"), p("44", discharge = tomorrow)),
            today,
        )
        assertEquals(2, board.of(Workbench.Kind.DISCHARGE).size)
    }

    @Test
    fun archivedPatientsNeverShowUp() {
        val board = Workbench.build(listOf(p("05", stage = PatientStage.ARCHIVED)), today)
        assertTrue(board.groups.values.all { it.isEmpty() })
    }
}
