package dev.xiaoman.api

/**
 * Memory is a folder of Markdown notes in the Basic Memory format, so it can be opened in Obsidian:
 *
 * ```
 * ---
 * title: 家
 * type: place
 * permalink: places/家
 * tags: [地址]
 * ---
 * # 家
 *
 * ## Observations
 * - [address] 北京市海淀区某某路 1 号 #常用 (用户 2026-10-30 告知)
 *
 * ## Relations
 * - lives_in [[北京]]
 * ```
 *
 * Observation lines are `- [category] content #tag ... (optional context)`.
 * Relation lines are `- relation_type [[Target Title]] (optional context)`.
 * Wiki links `[[...]]` anywhere in the body also count as `links_to` relations.
 */
data class Note(
    val title: String,
    /** Folder-relative path without `.md`, e.g. `places/家`. Unique key of the note. */
    val permalink: String,
    val type: String = "note",
    val tags: List<String> = emptyList(),
    /** Frontmatter keys other than title/type/permalink/tags, kept on rewrite. */
    val extraFrontmatter: Map<String, Any?> = emptyMap(),
    /** Markdown body after the frontmatter, including the Observations/Relations sections. */
    val body: String = "",
    val observations: List<Observation> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val updatedAtMillis: Long = 0,
)

data class Observation(val category: String, val content: String, val tags: List<String> = emptyList(), val context: String? = null)

data class Relation(val type: String, val target: String, val context: String? = null)

data class SearchHit(
    val permalink: String,
    val title: String,
    val snippet: String,
    val score: Double,
    /** `patient` / `preference` / `howto` ...; empty when the note carries none. */
    val kind: String = "",
    /** yyyy-MM-dd after which this note must not be injected; null means it never expires. */
    val validUntil: String? = null,
) {
    /** Expired notes stay on disk (they are the patient's history) but stop occupying the prompt. */
    fun expired(today: java.time.LocalDate): Boolean =
        validUntil?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull()?.isBefore(today) } ?: false
}

/** Frontmatter keys the clinical layer writes; unknown to Basic Memory itself, kept on rewrite. */
const val KIND_KEY = "kind"
const val VALID_UNTIL_KEY = "valid_until"

fun Note.kindOf(): String = (extraFrontmatter[KIND_KEY] as? String).orEmpty()

fun Note.validUntilOf(): String? = (extraFrontmatter[VALID_UNTIL_KEY] as? String)?.takeIf { it.isNotBlank() }

interface MemoryStore {
    /** Creates or replaces the note at [Note.permalink]; returns the stored note. */
    suspend fun write(note: Note): Note

    /** Looks a note up by permalink, then by exact title. */
    suspend fun read(identifier: String): Note?

    /** Full-text search over titles, observations and bodies; must handle Chinese text. */
    suspend fun search(query: String, limit: Int = 10): List<SearchHit>

    /** Adds one observation, creating the note (type `note`) when it does not exist. */
    suspend fun appendObservation(identifier: String, observation: Observation): Note

    /** Real deletion of the file and its index entries. Returns false when nothing was there. */
    suspend fun delete(identifier: String): Boolean

    suspend fun list(folder: String? = null): List<Note>
}
