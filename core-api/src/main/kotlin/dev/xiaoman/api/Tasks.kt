package dev.xiaoman.api

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One thing the user asked to be reminded about, e.g. "12 床李xx 术前检查，每天提醒，直到排手术".
 *
 * Deliberately small: bed/name identify the patient for display, [patientKey] is the stable key so a
 * bed move only rewrites [bed]. Nothing here holds orders, doses or test results.
 */
@Serializable
data class CareTask(
    val id: String,
    /** Display bed number; may change when the patient moves. */
    val bed: String,
    /** Display name, e.g. 李xx. */
    val name: String,
    /** Stable patient key, default is the name; a bed move never changes it. */
    val patientKey: String,
    /** 患者档案 id（p-0007）。P0 起逐步回填；为空时详情页按床号兜底匹配。 */
    val patientId: String? = null,
    /** manual = 用户口述产生；patient = 由患者阶段自动生成（可随条件满足自动关闭）。 */
    val source: String? = null,
    /** What to confirm, e.g. 术前检查 / 会诊结果 / 出院首页. */
    val item: String,
    /** The user's own words kept as-is; never expanded into advice. */
    val note: String = "",
    val rule: TaskRule = TaskRule(),
    /** Empty for "remind now"; otherwise the task waits until the anchor happens. */
    val anchor: TaskAnchor? = null,
    /** The day the anchor actually happened (surgery day / discharge day), yyyy-MM-dd. */
    val anchorDate: String? = null,
    val state: TaskState = TaskState.ACTIVE,
    /** Last day this task may still be pushed, yyyy-MM-dd. */
    val until: String,
    val createdAt: String,
    /** Set when the user says "看过了/知道了": skip the rest of that day only. */
    val silentDate: String? = null,
    val pushCount: Int = 0,
    /** 最后一次推送的日期与当天次数，用来卡「每天最多推几次」。 */
    val pushDate: String? = null,
    val pushToday: Int = 0,
    val closedAt: String? = null,
    val closeReason: String? = null,
) {
    val display: String get() = "${bed}床 $name｜$item"

    /** True when this task should be pushed at a checkpoint on [today]. */
    fun isDue(today: LocalDate): Boolean {
        if (state != TaskState.ACTIVE) return false
        val end = until.toDateOrNull() ?: return false
        if (today.isAfter(end)) return false
        if (silentDate == today.toString()) return false
        if (pushDate == today.toString() && pushToday >= rule.maxPerDay.coerceAtLeast(1)) return false
        return when (rule.kind) {
            TaskRuleKind.MONTHLY -> rule.days.isEmpty() || today.dayOfMonth in rule.days
            TaskRuleKind.DAILY -> startDate()?.let { start ->
                if (today.isBefore(start)) return false
                val every = rule.everyDays.coerceAtLeast(1)
                every == 1 || (ChronoUnit.DAYS.between(start, today) % every).toInt() == 0
            } ?: false
            // 点事件：当天推；当天没开机，次日补一次，之后不再纠缠
            TaskRuleKind.ONCE -> startDate()?.let { start ->
                today == start || (today == start.plusDays(1) && pushCount == 0)
            } ?: false
        }
    }

    /** Expired and never confirmed: shown separately instead of being dropped. */
    fun isStale(today: LocalDate): Boolean {
        if (state == TaskState.DONE) return false
        val end = until.toDateOrNull() ?: return false
        return today.isAfter(end)
    }

    /** First day this task becomes pushable: created day, or anchor day + offset. */
    fun startDate(): LocalDate? {
        val a = anchor ?: return createdAt.toDateOrNull()
        return when (a.kind) {
            AnchorKind.DATE -> a.date?.toDateOrNull()
            else -> anchorDate?.toDateOrNull()?.plusDays(a.offsetDays.toLong())
        }
    }

    /** Whole days since the task was created, 1-based, for "第 3 天". */
    fun dayNumber(today: LocalDate): Int {
        val start = createdAt.toDateOrNull() ?: return 1
        return (ChronoUnit.DAYS.between(start, today) + 1).toInt().coerceAtLeast(1)
    }
}

@Serializable
data class TaskRule(
    val kind: TaskRuleKind = TaskRuleKind.DAILY,
    /** Days of month for [TaskRuleKind.MONTHLY], e.g. 10, 20, 30. */
    val days: List<Int> = emptyList(),
    /** 每隔几天推一次（隔天换药 = 2）。1 表示每天。 */
    val everyDays: Int = 1,
    /** 每天最多推几次。默认 4（跟推送时点一致）；患者档案里的自动提醒设 1。 */
    val maxPerDay: Int = 4,
)

@Serializable
/** ONCE：只在起始日推一次（次日补一次机会），用于「术后第 N 天开医嘱」这种点事件。 */
enum class TaskRuleKind { DAILY, MONTHLY, ONCE }

@Serializable
data class TaskAnchor(
    val kind: AnchorKind,
    /** Days after the anchor day, e.g. 3 for 术后 3 天. */
    val offsetDays: Int = 0,
    /** Anchor day when [kind] is [AnchorKind.DATE], yyyy-MM-dd. */
    val date: String? = null,
)

@Serializable
enum class AnchorKind { SURGERY, DISCHARGE, DATE }

@Serializable
enum class TaskState {
    /** Waiting for its anchor (surgery or discharge) to happen. */
    WAITING,
    ACTIVE,
    DONE,
    EXPIRED,
}

/** Push checkpoints, device local time. */
object TaskCheckpoints {
    val HOURS: List<Int> = listOf(8, 11, 14, 17)
}

/** Storage for [CareTask]; the app implements it over a JSON file. */
interface TaskStore {
    suspend fun all(): List<CareTask>

    suspend fun add(tasks: List<CareTask>): List<CareTask>

    /** Replaces the task with the same id; null when it does not exist. */
    suspend fun update(task: CareTask): CareTask?

    /** Replaces several tasks; implementations should write the file once. */
    suspend fun updateAll(tasks: List<CareTask>): List<CareTask> = tasks.mapNotNull { update(it) }

    /** 真删除（患者被彻底清除时用），返回删掉的条数。 */
    suspend fun remove(ids: Set<String>): Int

    suspend fun find(id: String): CareTask?
}

fun String.toDateOrNull(): LocalDate? =
    runCatching { LocalDate.parse(trim()) }.getOrNull()
