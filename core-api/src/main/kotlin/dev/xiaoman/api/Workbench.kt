package dev.xiaoman.api

import java.time.LocalDate

/**
 * 工作台：把在院患者分成五摞，一眼看完今天该关心谁。
 *
 * 纯函数，不碰存储——页面和对话里的「今天工作台怎么样」用同一套口径，不会两处对不上。
 */
object Workbench {
    enum class Kind(val title: String) {
        TO_OPERATE("待手术"),
        CONSULT("会诊"),
        BLOOD("血结果"),
        TOMORROW("明日手术"),
        DISCHARGE("出院"),
        CONSERVATIVE("保守治疗"),
        OTHER("其他"),
    }

    /** 一个患者可能同时出现在几摞里（比如既待会诊又明日手术），这是对的。 */
    data class Entry(val patient: Patient, val note: String, val flag: String = "")

    data class Board(val today: LocalDate, val groups: Map<Kind, List<Entry>>) {
        fun of(kind: Kind): List<Entry> = groups[kind].orEmpty()
        val total: Int get() = groups.values.sumOf { it.size }
    }

    /** 术后常规查血：D1 是术后医嘱里带的，D3/D5/D7 靠前一天的提醒。 */
    private val BLOOD_DAYS = setOf(1, 3, 5, 7)

    fun build(patients: List<Patient>, tasks: List<CareTask> = emptyList(), today: LocalDate): Board {
        val live = patients.filter { it.stage != PatientStage.ARCHIVED && it.stage != PatientStage.DISCHARGED }
        val tomorrow = today.plusDays(1)

        val toOperate = live.filter {
            it.surgery == null && it.stage != PatientStage.POSTOP && !it.conservative &&
                (it.plannedSurgery?.date?.toDateOrNull()?.isBefore(tomorrow) != true)
        }.sortedBy { it.bedRank() }.map { p ->
            val plan = p.plannedSurgery
            Entry(
                p,
                listOfNotNull(p.diagnosis.ifBlank { null }, plan?.name?.ifBlank { null }).joinToString(" · "),
                flag = if (plan == null) "待排期" else plan.date.takeLast(5) + " 手术",
            )
        }

        val consult = live.mapNotNull { p ->
            val pendingRequest = p.consults.filter { it.status == "toRequest" }.map { it.dept }
            val waiting = p.consults.filter { it.status == "requested" }
            when {
                pendingRequest.isNotEmpty() -> Entry(p, "待提请：" + pendingRequest.joinToString("、"), "待提请")
                waiting.isNotEmpty() -> Entry(
                    p,
                    waiting.joinToString("、") { c -> "${c.dept} · 已提请 ${daysSince(c.requestedOn, today)} 天" },
                    "等回复",
                )
                else -> null
            }
        }.sortedBy { it.patient.bedRank() }

        val blood = live.filter { p ->
            p.stage == PatientStage.POSTOP && p.postOpDay(today) in BLOOD_DAYS
        }.sortedBy { it.bedRank() }.map { p ->
            val day = p.postOpDay(today) ?: 0
            val note = if (day == 1) "今日常规查血" else "今日查血（前一日已开医嘱）"
            Entry(p, "$note · 术后第${day}天", "D$day")
        }

        val tomorrowOps = live.filter { it.plannedSurgery?.date == tomorrow.toString() }
            .sortedBy { it.bedRank() }
            .map { p ->
                Entry(
                    p,
                    listOfNotNull(p.plannedSurgery?.name?.ifBlank { null }, p.diagnosis.ifBlank { null }).joinToString(" · "),
                    p.plannedSurgery?.order?.ifBlank { null } ?: "明日",
                )
            }

        val discharge = live.filter {
            it.plannedDischarge?.toDateOrNull()?.let { d -> !d.isAfter(tomorrow) } == true ||
                it.dischargedOn != null
        }.sortedBy { it.bedRank() }.map { p ->
            val plan = p.plannedDischarge
            Entry(p, "拟 ${plan?.takeLast(5) ?: "今日"} 出院", if (plan == tomorrow.toString()) "明日出院" else "今日出院")
        }

        val conservative = live.filter { it.conservative }.sortedBy { it.bedRank() }.map { p ->
            Entry(
                p,
                listOfNotNull(p.diagnosis.ifBlank { null }, p.plannedDischarge?.let { "拟 ${it.takeLast(5)} 出院" })
                    .joinToString(" · "),
                "保守",
            )
        }

        // 其他：落在五类之外的在办提醒（术前检查、MDT、换药、医嘱……）
        val liveIds = live.associateBy { it.id }
        val other = tasks
            .filter { it.state != TaskState.DONE && it.patientId != null }
            .filter { it.patientId in liveIds.keys }
            .filter { categoryOf(it.item) == Kind.OTHER }
            .sortedBy { it.bedRankOf() }
            .mapNotNull { t ->
                val p = liveIds[t.patientId] ?: return@mapNotNull null
                Entry(p, t.item, if (t.pushCount > 0) "提醒" else "待办")
            }
            .distinctBy { it.patient.id to it.note }

        return Board(
            today,
            mapOf(
                Kind.TO_OPERATE to toOperate,
                Kind.CONSULT to consult,
                Kind.BLOOD to blood,
                Kind.TOMORROW to tomorrowOps,
                Kind.DISCHARGE to discharge,
                Kind.CONSERVATIVE to conservative,
                Kind.OTHER to other,
            ),
        )
    }

    /** 一条提醒落在哪一摞。落不进去的都算「其他」。 */
    fun categoryOf(item: String): Kind {
        val t = item.trim()
        return when {
            t.contains("会诊") -> Kind.CONSULT
            t.contains("复查血") || t.contains("血常规") || t.contains("查血") -> Kind.BLOOD
            t.contains("整理出院首页") -> Kind.DISCHARGE
            else -> Kind.OTHER
        }
    }

    private fun daysSince(date: String?, today: LocalDate): Int {
        val d = date?.toDateOrNull() ?: return 0
        return java.time.temporal.ChronoUnit.DAYS.between(d, today).toInt().coerceAtLeast(0)
    }
}

/** 床位排序：普通床按数字，加床排最后（A65 这种）。 */
fun CareTask.bedRankOf(): Int = bed.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

fun Patient.bedRank(): Int = bed.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
