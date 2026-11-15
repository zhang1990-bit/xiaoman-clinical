package dev.xiaoman.api

import kotlinx.serialization.Serializable

/**
 * One patient as printed on the HIS ward table: 床号 + 姓名 and the day that table was shot.
 *
 * Nothing else is kept on purpose - the photo also shows 登记号, 病案号, 年龄, 住院天数, and none of
 * those belong on a phone. [seenAt] is what makes "出院了" detectable: a patient missing from today's
 * table has left, without anyone having to say so.
 */
@Serializable
data class PatientSlot(
    val bed: String,
    val name: String,
    /** yyyy-MM-dd of the last table that contained this bed+name. */
    val seenAt: String,
)

/** Same patient, different bed. */
data class PatientMove(val name: String, val fromBed: String, val toBed: String)

/** What yesterday's table and today's table disagree about. */
data class RosterDiff(
    /** On today's table, not on the previous one. */
    val added: List<PatientSlot> = emptyList(),
    /** On the previous table, gone today: discharged or transferred out. */
    val removed: List<PatientSlot> = emptyList(),
    val moved: List<PatientMove> = emptyList(),
    /** Beds whose patient did not change - the boring majority. */
    val unchanged: Int = 0,
) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && moved.isEmpty()
}

/** The saved ward table; replaced wholesale every time a new photo is read. */
interface RosterStore {
    suspend fun all(): List<PatientSlot>

    suspend fun replace(slots: List<PatientSlot>)
}

object PatientRoster {
    /** Drops empty rows, normalises bed numbers and merges duplicates, keeping the first. */
    fun normalize(slots: List<PatientSlot>, seenAt: String): List<PatientSlot> = slots
        .mapNotNull { slot ->
            val bed = TaskRoster.normalizeBed(slot.bed)
            val name = slot.name.trim()
            if (bed.isEmpty() || name.isEmpty()) null else PatientSlot(bed, name, seenAt)
        }
        .distinctBy { it.bed to it.name }
        .sortedWith(compareBy({ it.bed.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it.bed }))

    /**
     * Compares two tables. A name that only changed beds is a [RosterDiff.moved] entry, never a
     * discharge plus an admission - that would double-count the same patient.
     */
    fun diff(previous: List<PatientSlot>, current: List<PatientSlot>): RosterDiff {
        if (previous.isEmpty()) {
            return RosterDiff(added = current, unchanged = 0)
        }
        val oldNames = previous.map { it.name }
        val newNames = current.map { it.name }
        val moved = current.mapNotNull { now ->
            val before = previous.firstOrNull { it.name == now.name && it.bed != now.bed } ?: return@mapNotNull null
            PatientMove(now.name, before.bed, now.bed)
        }
        val added = current.filter { it.name !in oldNames }
        val removed = previous.filter { it.name !in newNames }
        val unchanged = current.count { now -> previous.any { it.bed == now.bed && it.name == now.name } }
        return RosterDiff(added = added, removed = removed, moved = moved, unchanged = unchanged)
    }
}
