package dev.xiaoman.api

/**
 * Checks the tasks against the bed/name list read off a photo of the HIS ward table.
 *
 * The point is bed moves: the ward table is the truth about "who is in which bed today", so a task
 * whose patient now sits in another bed is rewritten, and anything that cannot be decided by name
 * alone is reported instead of guessed.
 *
 * Names may be masked on screen (`李*`, `李**`); a masked name matches by its visible prefix only.
 * Nothing here reads a chart, a diagnosis or a test result - only 床号 and 姓名.
 */
data class BedSlot(val bed: String, val name: String)

/** One patient whose tasks all move to [toBed]; grouped so a patient with three tasks is one line. */
data class RosterMove(
    val name: String,
    val fromBed: String,
    val toBed: String,
    val ids: List<String>,
    val items: List<String>,
)

/** Needs a human: the same bed holds another name, or two people share the name on the roster. */
data class RosterConflict(val bed: String, val taskName: String, val hisName: String, val reason: String, val item: String)

/** The patient is not on the roster at all: discharged, transferred out, or the photo missed them. */
data class RosterMissing(val bed: String, val name: String, val items: List<String>)

data class RosterReport(
    val moves: List<RosterMove> = emptyList(),
    val conflicts: List<RosterConflict> = emptyList(),
    val missing: List<RosterMissing> = emptyList(),
    /** How many tasks were compared, so "0 条" can be told apart from "没读到". */
    val checked: Int = 0,
) {
    val clear: Boolean get() = moves.isEmpty() && conflicts.isEmpty() && missing.isEmpty()
}

object TaskRoster {
    /** `12` / `12床` / `012号` / ` 12a ` all become `12` / `12A`. */
    /**
     * 床号归一化。加床有三种写法，必须落到同一个键上：
     *
     * `A65`（HIS 里就这么印）、`+65`、`加65`、`加65床` → 全部 `A65`
     *
     * 否则你说「加65床」时，系统会以为来了个新病人，跟 HIS 表里的 A65 对不上。
     */
    fun normalizeBed(raw: String): String {
        val cleaned = raw.trim().replace("床", "").trim()
        if (cleaned.isEmpty()) return ""
        val extraBed = cleaned.first().let { it == '+' || it == '＋' || it == '加' } ||
            (cleaned.length > 1 && (cleaned.first() == 'A' || cleaned.first() == 'a') && !cleaned[1].isLetter())
        if (extraBed) {
            val digits = cleaned.drop(1).filter { it in '0'..'9' }
            if (digits.isNotEmpty()) return "A" + digits.trimStart('0').ifEmpty { "0" }
        }
        val kept = cleaned.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }.uppercase()
        if (kept.isEmpty()) return cleaned
        val digits = kept.takeWhile { it in '0'..'9' }
        return if (digits.isEmpty()) kept else digits.trimStart('0').ifEmpty { "0" } + kept.drop(digits.length)
    }

    /** Exact match, or prefix match when the roster hides characters behind `*`. */
    fun nameMatches(taskName: String, hisName: String): Boolean {
        val a = taskName.filterNot { it.isWhitespace() }
        val b = hisName.filterNot { it.isWhitespace() }
        if (a.isEmpty() || b.isEmpty()) return false
        val mask = b.indexOfFirst { it == '*' || it == '＊' }
        return if (mask < 0) a == b else a.length >= mask && a.startsWith(b.take(mask))
    }

    /**
     * Compares the open tasks (ACTIVE and WAITING) with [roster].
     *
     * An empty roster means the photo gave nothing usable, so the report stays empty and [RosterReport.checked]
     * is 0 - the caller must not read that as "everything matches".
     */
    fun compare(tasks: List<CareTask>, roster: List<BedSlot>): RosterReport {
        val slots = roster.mapNotNull { slot ->
            val bed = normalizeBed(slot.bed)
            val name = slot.name.trim()
            if (bed.isEmpty() || name.isEmpty()) null else BedSlot(bed, name)
        }.distinctBy { it.bed to it.name }
        val open = tasks.filter { it.state == TaskState.ACTIVE || it.state == TaskState.WAITING }
        if (slots.isEmpty() || open.isEmpty()) return RosterReport(checked = 0)

        val moves = LinkedHashMap<String, MutableList<CareTask>>()
        val conflicts = mutableListOf<RosterConflict>()
        val missing = LinkedHashMap<String, MutableList<CareTask>>()
        for (task in open) {
            val bed = normalizeBed(task.bed)
            val hits = slots.filter { nameMatches(task.name, it.name) }
            if (hits.size > 1) {
                conflicts += RosterConflict(
                    bed, task.name, hits.joinToString("、") { "${it.bed}床" }, "同名多人，认不出是哪个", task.item,
                )
                continue
            }
            val hit = hits.firstOrNull()
            when {
                hit != null && hit.bed == bed -> Unit
                hit != null -> moves.getOrPut("${task.name}|$bed|${hit.bed}") { mutableListOf() } += task
                else -> {
                    val sitting = slots.firstOrNull { it.bed == bed }
                    if (sitting != null) {
                        conflicts += RosterConflict(bed, task.name, sitting.name, "同床不同姓", task.item)
                    } else {
                        missing.getOrPut("${task.name}|$bed") { mutableListOf() } += task
                    }
                }
            }
        }
        return RosterReport(
            moves = moves.map { (key, list) ->
                val parts = key.split("|")
                RosterMove(parts[0], parts[1], parts[2], list.map { it.id }, list.map { it.item })
            },
            conflicts = conflicts,
            missing = missing.map { (key, list) ->
                val parts = key.split("|")
                RosterMissing(parts[1], parts[0], list.map { it.item })
            },
            checked = open.size,
        )
    }
}
