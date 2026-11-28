package dev.xiaoman.api

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 住院阶段。提醒是阶段的产物，不是散落的定时器。 */
enum class PatientStage(val label: String) {
    ADMITTED("入院"),
    PREOP("术前"),
    POSTOP("术后"),
    DISCHARGED("出院"),
    ARCHIVED("归档"),
}

@Serializable
data class BedStay(val bed: String, val from: String, val to: String? = null)

@Serializable
data class Surgery(val date: String, val name: String, val recordId: String? = null)

/** 排期但还没做的手术。和 [Surgery] 分开：一个是可以改的计划，一个是既成事实。 */
@Serializable
data class PlannedSurgery(val date: String, val name: String = "", val order: String? = null)

/** 会诊：回复 ≠ 结束，可能还有后续检查要跟。 */
@Serializable
data class Consult(
    val dept: String,
    val requestedOn: String,
    /** requested / replied / closed */
    val status: String = "requested",
    val repliedOn: String? = null,
    val followUp: String? = null,
)

@Serializable
data class PatientNote(val on: String, val text: String, val remindOn: String? = null)

/**
 * 一个患者的一次住院。身份是 [id]，床号和姓名只是属性——调床、同名都不会串人。
 *
 * 诊断存这里（列表要显示），但不进手术记录正文。
 */
@Serializable
data class Patient(
    val id: String,
    val name: String,
    val bed: String,
    val diagnosis: String = "",
    val stage: PatientStage = PatientStage.ADMITTED,
    val admittedOn: String,
    val bedHistory: List<BedStay> = emptyList(),
    val surgery: Surgery? = null,
    /** 已排期未做：日期、术式、台次（"第1台"）。改了就是改期，不动历史。 */
    val plannedSurgery: PlannedSurgery? = null,
    /** 拟出院日，口头给的。 */
    val plannedDischarge: String? = null,
    val dischargedOn: String? = null,
    /** 保守治疗：不打算做手术。这类人不进工作台的「待手术」。 */
    val conservative: Boolean = false,
    /** 肿瘤标记：置 true 才会推 MDT 提醒。 */
    val tumor: Boolean = false,
    /** 待排 / 已排 11-22 / 已讨论 */
    val mdt: String? = null,
    val consults: List<Consult> = emptyList(),
    /** 术前检查是否完善——只记"齐了没有"，不拆项。 */
    val preopDone: Boolean = false,
    /** 用户特别强调的单项检查，例如"等增强 CT"。 */
    val preopExtra: List<String> = emptyList(),
    val notes: List<PatientNote> = emptyList(),
    /** 最后一次出现在查房单上的日期，归档判据。 */
    val lastSeenOnRoster: String? = null,
    val archivedOn: String? = null,
    val archiveReason: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    /** 术后第几天；未手术或日期不合法时为 null。手术当天算第 0 天。 */
    fun postOpDay(today: LocalDate): Int? {
        val day = surgery?.date?.toDateOrNull() ?: return null
        val diff = ChronoUnit.DAYS.between(day, today).toInt()
        return if (diff < 0) null else diff
    }

    /** 列表右侧的阶段字样：术前 / 术后D3 / 入院D1。 */
    fun stageLabel(today: LocalDate): String = when (stage) {
        PatientStage.POSTOP -> postOpDay(today)?.let { "术后D$it" } ?: "术后"
        PatientStage.PREOP -> "术前"
        PatientStage.ADMITTED -> admittedOn.toDateOrNull()?.let {
            "入院D${ChronoUnit.DAYS.between(it, today).toInt().coerceAtLeast(0)}"
        } ?: "入院"
        PatientStage.DISCHARGED -> "已出院"
        PatientStage.ARCHIVED -> "已归档"
    }

    /** 归档/恢复时搬动床位历史：旧床位封口，新床位开口。 */
    fun movedTo(bed: String, on: String): Patient {
        if (bed == this.bed) return this
        // 老档案可能没有床位历史：先替当前床位补一条开口记录，再封口，历史才不会断。
        val base = if (bedHistory.any { it.to == null && it.bed == this.bed }) bedHistory
        else bedHistory + BedStay(this.bed, admittedOn, on)
        val closed = base.map { if (it.to == null) it.copy(to = on) else it }
        return copy(bed = bed, bedHistory = closed + BedStay(bed, on), updatedAt = on)
    }
}

/** 事件流：谁、什么时候、凭什么改的。只追加，不改写。 */
@Serializable
data class PatientEvent(
    /** yyyy-MM-ddTHH:mm:ss */
    val at: String,
    val type: String,
    val patientId: String,
    val detail: String = "",
    /** roster / photo / dictation / record / manual / migration */
    val source: String = "manual",
)

interface PatientStore {
    /** 在院（含术前、术后），不含归档。 */
    suspend fun all(): List<Patient>

    suspend fun archived(): List<Patient>

    suspend fun find(id: String): Patient?

    suspend fun findByBed(bed: String): Patient?

    suspend fun upsert(patient: Patient): Patient

    suspend fun archive(id: String, reason: String, on: String): Patient?

    suspend fun restore(id: String): Patient?

    /** 彻底删除患者档案与相关事件，不可恢复。 */
    suspend fun purge(id: String): Boolean

    suspend fun events(patientId: String? = null): List<PatientEvent>

    suspend fun log(event: PatientEvent)
}

/**
 * 缺口：患者卡片上的 `⚠N` 就是它的长度。列表和详情共用这一个函数，避免两处算法漂移。
 */
object PatientGaps {
    fun of(patient: Patient, today: LocalDate): List<String> = buildList {
        if (!patient.preopDone && (patient.stage == PatientStage.ADMITTED || patient.stage == PatientStage.PREOP)) {
            add("术前检查未完善")
        }
        if (patient.consults.any { it.status == "requested" }) add("会诊未回")
        if (patient.tumor && (patient.mdt.isNullOrBlank() || patient.mdt.startsWith("待"))) add("MDT 未排")
        patient.preopExtra.forEach { add("待：$it") }
    }
}
