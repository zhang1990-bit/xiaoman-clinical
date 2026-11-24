package dev.xiaoman.api

import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 一句随手记，能被识别成什么。 */
enum class NoteKind { CONSULT, BLOOD, DRESSING, ORDER, OTHER }

/**
 * 分类结果。**只提议，不落库**——用户点「就这样」才变成待办。
 *
 * @param dept 会诊科室（CONSULT 时有）
 * @param dueDate 该提醒的截止日 yyyy-MM-dd；空表示"尽快"
 */
data class NoteIntent(
    val kind: NoteKind,
    val text: String,
    val dept: String? = null,
    val dueDate: String? = null,
) {
    /** 回显给用户的一句话。 */
    fun summary(): String = when (kind) {
        NoteKind.CONSULT -> "请${dept ?: "会诊"}会诊（待提请）"
        NoteKind.BLOOD -> "复查血" + (dueDate?.let { " · $it" } ?: "")
        NoteKind.DRESSING -> "换药（隔天一次）"
        NoteKind.ORDER -> "医嘱：$text"
        NoteKind.OTHER -> "只记备注"
    }
}

/**
 * 规则优先的分类器：常见四类当场判，判不出来才交给模型。
 *
 * 为什么不全交给模型：医生在病房站着打字，等 2 秒和等 0 秒是两个体验；而且分类错了会天天推。
 */
object NoteRules {
    private val DEPTS = listOf(
        "心内", "心外", "消化", "呼吸", "肾内", "内分泌", "神经内", "神外", "神内", "血液",
        "肿瘤", "放疗", "介入", "影像", "超声", "病理", "麻醉", "ICU", "重症", "感染",
        "风湿", "皮肤", "泌尿", "骨科", "普外", "肝胆", "营养", "康复", "疼痛", "心理",
    )

    fun classify(text: String, patient: Patient?, today: LocalDate): NoteIntent {
        val t = text.trim()
        val due = dueDate(t, patient, today)
        return when {
            t.contains("会诊") -> NoteIntent(NoteKind.CONSULT, t, dept = deptOf(t), dueDate = due)
            listOf("查血", "复查血", "抽血", "血常规", "生化", "凝血", "血象").any { t.contains(it) } ->
                NoteIntent(NoteKind.BLOOD, t, dueDate = due)
            listOf("换药", "切口", "敷料", "拆线").any { t.contains(it) } ->
                NoteIntent(NoteKind.DRESSING, t, dueDate = due)
            listOf("医嘱", "备血", "禁食", "抗菌", "补液", "停药", "开药").any { t.contains(it) } ->
                NoteIntent(NoteKind.ORDER, t, dueDate = due)
            else -> NoteIntent(NoteKind.OTHER, t, dueDate = due)
        }
    }

    fun deptOf(text: String): String? {
        val hit = DEPTS.firstOrNull { text.contains(it) } ?: return null
        return if (hit.endsWith("科") || hit == "ICU") hit else hit + "科"
    }

    /** 从「明天」「术后第3天」「3 天后」里读出日期。 */
    fun dueDate(text: String, patient: Patient?, today: LocalDate): String? {
        val t = text.replace(" ", "")
        Regex("""术后第?(\d+)天""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { n ->
            patient?.surgery?.date?.toDateOrNull()?.let { return it.plusDays(n.toLong()).toString() }
        }
        Regex("""(\d+)天后""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return today.plusDays(it.toLong()).toString()
        }
        return when {
            t.contains("大后天") -> today.plusDays(3).toString()
            t.contains("后天") -> today.plusDays(2).toString()
            t.contains("明天") || t.contains("明日") -> today.plusDays(1).toString()
            t.contains("今天") || t.contains("今日") || t.contains("尽快") -> today.toString()
            else -> null
        }
    }

    /** 模型返回的 JSON：只认 kind / dept / dueDate，别的字段忽略。 */
    fun parse(text: String, raw: String): NoteIntent? = runCatching {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(raw.substringAfter('{').let { "{" + it }).jsonObject
        val kind = when (obj["kind"]?.toString()?.trim('"')?.uppercase()) {
            "CONSULT" -> NoteKind.CONSULT
            "BLOOD" -> NoteKind.BLOOD
            "DRESSING" -> NoteKind.DRESSING
            "ORDER" -> NoteKind.ORDER
            else -> NoteKind.OTHER
        }
        val dept = obj["dept"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
        val due = obj["dueDate"]?.toString()?.trim('"')?.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
        NoteIntent(kind, text, dept, due)
    }.getOrNull()

    /** 术后第几天（给「术后第3天查血」这类兜底）。 */
    fun postOpDay(patient: Patient, today: LocalDate): Int? =
        patient.surgery?.date?.toDateOrNull()?.let { ChronoUnit.DAYS.between(it, today).toInt().takeIf { d -> d >= 0 } }
}
