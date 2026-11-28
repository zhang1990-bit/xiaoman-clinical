package dev.xiaoman.api

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 入院阶段的「该做什么」。纯函数，不碰存储——提醒是阶段的产物，不是散落的定时器。
 *
 * 只生成三类，都是每天 4 次推送：
 * 1. 术前检查未完善（手术或「齐了」就关）
 * 2. 要不要请会诊（只推 3 天，之后不再打扰）
 * 3. 跟踪会诊回复 / 后续检查、肿瘤的 MDT
 */
object PatientTasks {
    const val SOURCE_AUTO = "patient"

    data class Planned(val item: String, val until: String)

    private fun LocalDate.plusDaysText(days: Long): String = plusDays(days).toString()

    /** 现在应该存在哪些自动提醒。 */
    fun desired(patient: Patient, today: LocalDate): List<Planned> {
        if (patient.stage == PatientStage.ARCHIVED) return emptyList()
        val out = mutableListOf<Planned>()
        val admitted = patient.admittedOn.toDateOrNull() ?: today

        // 术前检查：从入院推到手术当天，最多 30 天
        if (!patient.preopDone && patient.surgery == null &&
            patient.stage != PatientStage.DISCHARGED && patient.stage != PatientStage.POSTOP
        ) {
            val until = admitted.plusDaysText(30)
            out += Planned("术前检查是否完善", until)
            patient.preopExtra.filter { it.isNotBlank() }.forEach { out += Planned("检查：$it", until) }
        }

        // 要不要请会诊：只在入院头 3 天问，问完不再纠缠
        val consultWindowEnds = admitted.plusDaysText(3)
        val noConsultYet = patient.consults.isEmpty()
        if (noConsultYet && !today.isAfter(LocalDate.parse(consultWindowEnds)) &&
            (patient.stage == PatientStage.ADMITTED || patient.stage == PatientStage.PREOP)
        ) {
            out += Planned("是否需请会诊", consultWindowEnds)
        }

        // 已开医嘱、还没提请：先催"请会诊"这件事本身
        patient.consults.filter { it.status == "toRequest" }.forEach { c ->
            out += Planned("请${c.dept}会诊", admitted.plusDaysText(3))
        }

        // 已提请未回复：跟着，直到回复
        patient.consults.filter { it.status == "requested" }.forEach { c ->
            val until = c.requestedOn.toDateOrNull()?.plusDaysText(14) ?: admitted.plusDaysText(14)
            out += Planned("会诊回复：${c.dept}", until)
        }

        // 回复了但有后续：继续跟
        patient.consults.filter { it.status != "requested" && !it.followUp.isNullOrBlank() }.forEach { c ->
            val until = c.repliedOn?.toDateOrNull()?.plusDaysText(14) ?: admitted.plusDaysText(14)
            out += Planned("会诊后续：${c.dept}", until)
        }

        // 肿瘤：MDT 未排/未讨论就一直提
        if (patient.tumor && (patient.mdt.isNullOrBlank() || patient.mdt.startsWith("待"))) {
            out += Planned("上 MDT", admitted.plusDaysText(30))
        }
        return out
    }

    /** 这条自动提醒是不是已经不需要了（条件满足，或已过期）。 */
    fun satisfied(patient: Patient, item: String, today: LocalDate): Boolean {
        val first = item.substringBefore("：").substringBefore(":")
        return when {
            first.startsWith("术前检查") -> patient.preopDone || patient.surgery != null
            first.startsWith("检查") -> patient.preopDone || patient.surgery != null
            first.startsWith("是否需请会诊") -> patient.consults.isNotEmpty()
            first.startsWith("请") && first.endsWith("会诊") ->
                patient.consults.none { it.status == "toRequest" }
            first.startsWith("会诊回复") -> patient.consults.none { it.status == "requested" }
            first.startsWith("会诊后续") -> patient.consults.none { it.status != "requested" && !it.followUp.isNullOrBlank() }
            first.startsWith("上 MDT") -> !patient.mdt.isNullOrBlank() && !patient.mdt.startsWith("待")
            first.startsWith("术后第") -> false
            else -> false
        }
    }

    /** 术后节点：锚在手术日上，手术日一变整体平移。 */
    data class PlannedPostOp(
        val item: String,
        /** 术后第几天开始推 */
        val offsetDays: Int,
        /** 术后第几天停止推（含宽限一天，避免关机错过就永远没了） */
        val untilOffset: Int,
        /** 间隔天数，换药是 2 */
        val everyDays: Int = 1,
        /** 只在当天推一次（血检），换药是持续任务 */
        val once: Boolean = false,
    )

    fun postOp(patient: Patient): List<PlannedPostOp> {
        if (patient.surgery == null) return emptyList()
        if (patient.stage == PatientStage.ARCHIVED || patient.stage == PatientStage.DISCHARGED) return emptyList()
        return listOf(
            PlannedPostOp("术后第2天：开立明日复查血医嘱", 2, 3, once = true),
            PlannedPostOp("术后第4天：开立明日复查血医嘱", 4, 5, once = true),
            PlannedPostOp("术后第6天：开立明日复查血医嘱", 6, 7, once = true),
            PlannedPostOp("术后换药（隔天一次）", 2, 14, everyDays = 2),
        )
    }

    fun postOpDay(patient: Patient, today: LocalDate): Int? = patient.surgery?.date?.toDateOrNull()?.let {
        ChronoUnit.DAYS.between(it, today).toInt().takeIf { d -> d >= 0 }
    }
}
