package dev.xiaoman.agent

import dev.xiaoman.api.AgentEvent
import dev.xiaoman.api.AnchorKind
import dev.xiaoman.api.BedSlot
import dev.xiaoman.api.CareTask
import dev.xiaoman.api.MemoryStore
import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Patient
import dev.xiaoman.api.PatientEvent
import dev.xiaoman.api.PatientGaps
import dev.xiaoman.api.PatientRoster
import dev.xiaoman.api.PatientSlot
import dev.xiaoman.api.PatientStage
import dev.xiaoman.api.PatientStore
import dev.xiaoman.api.KIND_KEY
import dev.xiaoman.api.RecordStore
import dev.xiaoman.api.VALID_UNTIL_KEY
import dev.xiaoman.api.Relation
import dev.xiaoman.api.ReminderScheduler
import dev.xiaoman.api.RosterStore
import dev.xiaoman.api.Surgery
import dev.xiaoman.api.Skill
import dev.xiaoman.api.SkillRepository
import dev.xiaoman.api.TaskAnchor
import dev.xiaoman.api.TaskRoster
import dev.xiaoman.api.TaskRule
import dev.xiaoman.api.TaskRuleKind
import dev.xiaoman.api.TaskState
import dev.xiaoman.api.TaskStore
import dev.xiaoman.api.ToolCall
import dev.xiaoman.api.ToolProvider
import dev.xiaoman.api.toDateOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val DEFAULT_UNTIL_DAYS = 14L

/** 患者相关记忆默认 30 天后不再注入；偏好与经验永久保留。 */
private const val DEFAULT_PATIENT_MEMORY_DAYS = 30

internal class ToolArgumentException(message: String) : Exception(message)

/** Runs the chat model's tool calls. Argument and runtime errors become `{"error": ...}` tool results. */
internal class ChatTools(
    private val memory: MemoryStore,
    private val skills: SkillRepository,
    private val clock: () -> Long,
    private val reminders: ReminderScheduler,
    private val external: ToolProvider?,
    private val phoneTasks: Boolean = true,
    private val phoneTask: suspend (goal: String, skill: String?) -> AgentEvent.PhoneTaskFinished,
    /** Clinical tasks; the tools are only offered when the app injects a store. */
    private val tasks: TaskStore? = null,
    /** Today's ward table, saved from the HIS photo; null disables the 新入/出院 diff but not the check. */
    private val roster: RosterStore? = null,
    /** Generated records and the user's templates; null hides record_save / template_read. */
    private val records: RecordStore? = null,
    /** 患者档案；null 时不提供 patient_* 三个工具。 */
    private val patients: PatientStore? = null,
) {
    suspend fun execute(call: ToolCall, taskId: String, emit: (AgentEvent) -> Unit): String = try {
        val args = parseArgs(call.argumentsJson)
        when (call.name) {
            AgentTools.MEMORY_SEARCH -> search(args)
            AgentTools.MEMORY_READ -> read(args)
            AgentTools.MEMORY_WRITE -> write(args, taskId, emit)
            AgentTools.MEMORY_FORGET -> forget(args, taskId, emit)
            AgentTools.LIST_SKILLS -> listSkills()
            AgentTools.REMINDER_SET -> reminder(args)
            AgentTools.TASK_ADD -> taskAdd(args)
            AgentTools.TASK_LIST -> taskList(args)
            AgentTools.TASK_DONE -> taskDone(args)
            AgentTools.TASK_UPDATE -> taskUpdate(args)
            AgentTools.TASK_ROSTER -> taskRoster(args)
            AgentTools.RECORD_SAVE -> recordSave(args)
            AgentTools.TEMPLATE_READ -> templateRead(args)
            AgentTools.TEMPLATE_SAVE -> templateSave(args)
            AgentTools.RECORD_LIST -> recordList()
            AgentTools.RECORD_READ -> recordRead(args)
            AgentTools.PATIENT_LIST -> patientList(args)
            AgentTools.PATIENT_UPDATE -> patientUpdate(args)
            AgentTools.PATIENT_ARCHIVE -> patientArchive(args)
            AgentTools.PHONE_TASK -> if (phoneTasks) phone(args) else error("这里不能操作手机，请用其他工具或告诉用户自己操作")
            else -> external(call, taskId, emit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolArgumentException) {
        error("参数错误：${e.message}")
    } catch (e: Exception) {
        error("工具执行失败：${e.message ?: e::class.simpleName}")
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    private fun parseArgs(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return try {
            JsonText.json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ToolArgumentException("arguments 不是合法的 JSON 对象")
    }

    private fun required(o: JsonObject, key: String): String =
        JsonText.string(o, key) ?: throw ToolArgumentException("缺少 $key")

    private suspend fun search(args: JsonObject): String {
        val query = required(args, "query")
        val limit = ((args["limit"] as? JsonPrimitive)?.intOrNull ?: 5).coerceIn(1, 20)
        val today = today()
        val includeExpired = (args["includeExpired"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "是") } == true
        val hits = memory.search(query, limit * 2).filter { includeExpired || !it.expired(today) }.take(limit)
        return buildJsonObject {
            putJsonArray("hits") {
                hits.forEach { h ->
                    addJsonObject {
                        put("permalink", h.permalink)
                        put("title", h.title)
                        put("snippet", h.snippet)
                        if (h.kind.isNotEmpty()) put("kind", h.kind)
                        if (h.validUntil != null) put("validUntil", h.validUntil)
                    }
                }
            }
            put("today", today.toString())
        }.toString()
    }

    private suspend fun read(args: JsonObject): String {
        val note = memory.read(required(args, "identifier")) ?: return buildJsonObject { put("found", false) }.toString()
        return buildJsonObject {
            put("found", true)
            put("title", note.title)
            put("permalink", note.permalink)
            put("type", note.type)
            putJsonArray("tags") { note.tags.forEach { add(it) } }
            putJsonArray("observations") { note.observations.forEach { add(NoteText.observationLine(it)) } }
            putJsonArray("relations") { note.relations.forEach { add(NoteText.relationLine(it)) } }
            put("body", note.body)
        }.toString()
    }

    private suspend fun write(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val title = required(args, "title")
        val observations = objects(args, "observations").map {
            Observation(required(it, "category"), required(it, "content"), JsonText.stringList(it, "tags"), JsonText.string(it, "context"))
        }
        val relations = objects(args, "relations").map {
            Relation(required(it, "type"), required(it, "target"), JsonText.string(it, "context"))
        }
        if (observations.isEmpty() && relations.isEmpty()) throw ToolArgumentException("observations 不能为空")

        val existing = memory.read(JsonText.string(args, "permalink") ?: title)
        val permalink = JsonText.string(args, "permalink") ?: existing?.permalink ?: title
        val mergedObs = ((existing?.observations ?: emptyList()) + observations).distinctBy { it.category to it.content }
        val mergedRels = ((existing?.relations ?: emptyList()) + relations).distinctBy { it.type to it.target }
        val kind = JsonText.string(args, "kind")?.trim()?.lowercase().orEmpty()
        val keepDays = (args["keepDays"] as? JsonPrimitive)?.intOrNull
        val frontmatter = (existing?.extraFrontmatter ?: emptyMap()).toMutableMap()
        if (kind.isNotEmpty()) frontmatter[KIND_KEY] = kind
        val effectiveKind = (frontmatter[KIND_KEY] as? String).orEmpty()
        val days = keepDays ?: when (effectiveKind) {
            "patient" -> DEFAULT_PATIENT_MEMORY_DAYS
            "" -> null
            else -> 0
        }
        when {
            days == null -> Unit // 没给 kind 也没给天数：沿用已有的有效期
            days <= 0 -> frontmatter.remove(VALID_UNTIL_KEY)
            else -> frontmatter[VALID_UNTIL_KEY] = today().plusDays(days.toLong()).toString()
        }
        val note = Note(
            title = title,
            permalink = permalink,
            type = JsonText.string(args, "type") ?: existing?.type ?: "note",
            tags = existing?.tags ?: emptyList(),
            extraFrontmatter = frontmatter,
            body = NoteText.body(title, mergedObs, mergedRels, existing?.body?.let { NoteText.preamble(it) } ?: ""),
            observations = mergedObs,
            relations = mergedRels,
            updatedAtMillis = clock(),
        )
        val stored = memory.write(note)
        emit(AgentEvent.MemoryUpdated(taskId, stored.permalink))
        return buildJsonObject {
            put("ok", true)
            put("permalink", stored.permalink)
            put("created", existing == null)
            put("observations", mergedObs.size)
        }.toString()
    }

    private fun objects(args: JsonObject, key: String): List<JsonObject> = when (val e = args[key]) {
        null -> emptyList()
        is JsonArray -> e.map { it as? JsonObject ?: throw ToolArgumentException("$key 的每一项必须是对象") }
        is JsonObject -> listOf(e)
        else -> if (e is JsonPrimitive && e.content == "null") emptyList() else throw ToolArgumentException("$key 必须是数组")
    }

    private suspend fun forget(args: JsonObject, taskId: String, emit: (AgentEvent) -> Unit): String {
        val identifier = required(args, "identifier")
        val permalink = memory.read(identifier)?.permalink ?: identifier
        val deleted = memory.delete(identifier)
        if (deleted) emit(AgentEvent.MemoryUpdated(taskId, permalink))
        return buildJsonObject { put("deleted", deleted) }.toString()
    }

    private suspend fun listSkills(): String {
        val list = skills.list()
        return listSkillsJson(list)
    }

    private fun listSkillsJson(list: List<Skill>): String = buildJsonObject {
        putJsonArray("skills") {
            list.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("description", s.description)
                    put("channel", s.channel.wire)
                    put("apps", buildJsonArray { s.apps.forEach { add(it) } })
                    put("hasFlow", s.flow != null)
                }
            }
        }
    }.toString()

    private fun reminder(args: JsonObject): String {
        val text = required(args, "text")
        val raw = required(args, "at")
        val atMillis = ReminderTimes.parseAtMillis(raw)
            ?: throw ToolArgumentException("at 不是合法的 ISO-8601 本地时间，应为 2026-11-03T08:00 这样的形式")
        if (atMillis <= clock()) throw ToolArgumentException("提醒时间 $raw 已经过去，请向用户确认一个将来的时间")
        reminders.schedule(text, atMillis)
        return buildJsonObject {
            put("ok", true)
            put("text", text)
            put("at", raw)
        }.toString()
    }

    private fun today(): LocalDate =
        Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    private suspend fun taskAdd(args: JsonObject): String {
        val store = tasks ?: return error("待办功能没有启用")
        val today = today()
        val items = objects(args, "items")
        if (items.isEmpty()) throw ToolArgumentException("items 不能为空")
        val stamp = clock()
        val created = items.mapIndexed { index, o ->
            val bed = required(o, "bed").trim()
            val name = required(o, "name").trim()
            val item = required(o, "item").trim()
            val until = (JsonText.string(o, "until") ?: today.plusDays(DEFAULT_UNTIL_DAYS).toString())
                .toDateOrNull() ?: throw ToolArgumentException("until 应是 yyyy-MM-dd")
            val kind = when (JsonText.string(o, "rule")?.lowercase()) {
                "monthly", "每月" -> TaskRuleKind.MONTHLY
                else -> TaskRuleKind.DAILY
            }
            val days = (o["days"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                .orEmpty()
            CareTask(
                id = "t-$stamp-$index",
                bed = bed,
                name = name,
                patientKey = JsonText.string(o, "patientKey") ?: name,
                item = item,
                note = JsonText.string(o, "note").orEmpty(),
                rule = TaskRule(kind, days),
                anchor = anchorOf(o),
                state = TaskState.ACTIVE,
                until = until.toString(),
                createdAt = today.toString(),
            )
        }
        store.add(created)
        return result("已记录", created, today)
    }

    private fun anchorOf(o: JsonObject): TaskAnchor? = when (JsonText.string(o, "anchor")?.lowercase()) {
        "surgery", "术后" -> TaskAnchor(AnchorKind.SURGERY, intOf(o, "offsetDays") ?: 0)
        "discharge", "出院" -> TaskAnchor(AnchorKind.DISCHARGE, intOf(o, "offsetDays") ?: 0)
        "date" -> JsonText.string(o, "date")?.toDateOrNull()?.let { TaskAnchor(AnchorKind.DATE, 0, it.toString()) }
        else -> null
    }

    private fun intOf(o: JsonObject, key: String): Int? = (o[key] as? JsonPrimitive)?.intOrNull

    private suspend fun taskList(args: JsonObject): String {
        val store = tasks ?: return error("待办功能没有启用")
        val today = today()
        val scope = (JsonText.string(args, "scope") ?: "today").lowercase()
        val bed = JsonText.string(args, "bed")
        val all = store.all()
        val selected = when (scope) {
            "all" -> all.filter { it.state != TaskState.DONE }
            "expired" -> all.filter { it.isStale(today) }
            "bed" -> all.filter { it.state != TaskState.DONE && (bed == null || it.bed == bed) }
            else -> all.filter { it.isDue(today) }
        }.sortedWith(compareBy({ it.bed.toIntOrNull() ?: Int.MAX_VALUE }, { it.item }))
        val label = when {
            selected.isEmpty() -> "没有待办"
            scope == "expired" -> "过期未确认"
            else -> "待办"
        }
        return result(label, selected, today)
    }

    private suspend fun taskDone(args: JsonObject): String {
        val store = tasks ?: return error("待办功能没有启用")
        val today = today()
        val reason = JsonText.string(args, "reason") ?: "已办"
        val id = JsonText.string(args, "id")
        if (id != null) {
            val t = store.find(id) ?: return error("找不到待办 $id")
            val closed = t.copy(state = TaskState.DONE, closedAt = today.toString(), closeReason = reason)
            store.update(closed)
            return result("已关闭", listOf(closed), today)
        }
        val bed = JsonText.string(args, "bed") ?: throw ToolArgumentException("需要 id 或 bed")
        val keyword = JsonText.string(args, "keyword")
        val hits = store.all().filter {
            it.bed == bed && it.state != TaskState.DONE && (keyword == null || it.item.contains(keyword))
        }
        if (hits.isEmpty()) return result("没有匹配的待办", emptyList(), today)
        val closed = hits.map { it.copy(state = TaskState.DONE, closedAt = today.toString(), closeReason = reason) }
        store.updateAll(closed)
        return result("已关闭", closed, today)
    }

    private suspend fun taskUpdate(args: JsonObject): String {
        val store = tasks ?: return error("待办功能没有启用")
        val today = today()
        val id = JsonText.string(args, "id")
        val fromBed = JsonText.string(args, "fromBed")
        val bed = JsonText.string(args, "bed")
        val targets = when {
            id != null -> listOfNotNull(store.find(id))
            fromBed != null -> store.all().filter { it.bed == fromBed }
            bed != null -> store.all().filter { it.bed == bed }
            else -> throw ToolArgumentException("需要 id、fromBed 或 bed")
        }
        if (targets.isEmpty()) return result("没有匹配的待办", emptyList(), today)
        val until = JsonText.string(args, "until")?.toDateOrNull()?.toString()
        val item = JsonText.string(args, "item")
        val anchorDate = JsonText.string(args, "anchorDate")?.toDateOrNull()?.toString()
        val state = JsonText.string(args, "state")?.lowercase()
        val silent = JsonText.string(args, "silentToday")?.let { it == "true" || it == "是" } == true
        val updated = targets.map { t ->
            var next = t
            if (bed != null) next = next.copy(bed = bed)
            if (until != null) next = next.copy(until = until)
            if (item != null) next = next.copy(item = item)
            if (anchorDate != null) next = next.copy(anchorDate = anchorDate)
            if (silent) next = next.copy(silentDate = today.toString())
            when (state) {
                "done" -> next = next.copy(state = TaskState.DONE, closedAt = today.toString(), closeReason = "手动关闭")
                "active" -> next = next.copy(state = TaskState.ACTIVE, closedAt = null, closeReason = null)
            }
            next
        }
        store.updateAll(updated)
        return result("已更新", updated, today)
    }

    /**
     * Reads the bed/name table the model pulled off a photo of the HIS screen.
     *
     * With [beds][readSlots] it becomes today's saved roster, reports 新入 / 出院 / 调床 against the
     * previous one and reconciles the open tasks. Without them it just answers from the saved roster
     * ("12 床是谁" / "现在管几个").
     *
     * Only a bed move of the same person is applied ([apply]); a bed that now holds another name and
     * a name that appears twice are reported so the user decides, never guessed.
     */
    private suspend fun taskRoster(args: JsonObject): String {
        val store = tasks ?: return error("待办功能没有启用")
        val today = today()
        val read = readSlots(args["beds"])
        if (read.isEmpty()) return savedRosterAnswer(store, today)

        val previous = roster?.all().orEmpty()
        val slots = PatientRoster.normalize(read.map { PatientSlot(it.bed, it.name, today.toString()) }, today.toString())
        val diff = PatientRoster.diff(previous, slots)
        roster?.replace(slots)
        val patientsTouched = syncPatientsFromRoster(slots, today)

        val apply = (args["apply"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "是") } == true
        val all = store.all()
        val report = TaskRoster.compare(all, slots.map { BedSlot(it.bed, it.name) })
        if (apply && report.moves.isNotEmpty()) {
            val byId = all.associateBy { it.id }
            store.updateAll(report.moves.flatMap { move -> move.ids.mapNotNull { byId[it]?.copy(bed = move.toBed) } })
        }
        return buildJsonObject {
            put("ok", true)
            put("saved", slots.size)
            put("added", diff.added.size)
            put("discharged", diff.removed.size)
            put("movedBeds", diff.moved.size)
            put("checkedTasks", report.checked)
            put("patientsCreated", patientsTouched.first)
            put("patientsArchived", patientsTouched.second)
            put("patientsInHospital", patients?.let { it.all().size } ?: 0)
            putJsonArray("detail") {
                diff.added.forEach { add("新入｜${it.bed}床 ${it.name}") }
                diff.removed.forEach { add("出院或转出｜${it.bed}床 ${it.name}") }
                diff.moved.forEach { add("调床｜${it.name} ${it.fromBed}床 → ${it.toBed}床") }
                report.moves.forEach { move ->
                    add("待办跟床｜${move.fromBed}床 ${move.name} → ${move.toBed}床｜${move.items.joinToString("、")}｜" + if (apply) "已改" else "待改")
                }
                report.conflicts.forEach { c ->
                    add("同床不同姓｜${c.bed}床｜待办记的是 ${c.taskName}，HIS 是 ${c.hisName}｜${c.item}｜未改，要问用户")
                }
                report.missing.forEach { m ->
                    add("待办里有人不在名单｜${m.bed}床 ${m.name}｜${m.items.joinToString("、")}｜未改，问是否已出院")
                }
            }
            // A half-read table must not silently look like "everyone was discharged".
            if (previous.size >= 4 && slots.size * 2 < previous.size) {
                put("warning", "这次只读到 ${slots.size} 人，上一次 ${previous.size} 人，图可能没拍全（新入/出院数不可信，请重拍）")
            }
            if (report.clear && diff.empty) put("summary", "名单没变化，待办床号全部对得上")
        }.toString()
    }

    /**
     * 把当天的查房单落成患者档案：新出现的建档、换床的改床、名单里没有的归档。
     *
     * 只归档"之前出现在名单上、今天却不见了"的人。纯口述建的患者（lastSeenOnRoster 为空）
     * 不会因为一张旧照片被误归档——这一点比"当天没出现就归档"更重要。
     *
     * @return 新建数 to 归档数
     */
    private suspend fun syncPatientsFromRoster(slots: List<PatientSlot>, today: LocalDate): Pair<Int, Int> {
        val store = patients ?: return 0 to 0
        val existing = store.all() + store.archived()
        val byName = existing.groupBy { it.name }
        val bedOf = { p: Patient -> TaskRoster.normalizeBed(p.bed) }
        var created = 0
        var archived = 0

        slots.forEach { slot ->
            val candidates = byName[slot.name].orEmpty()
            val hit = candidates.firstOrNull { bedOf(it) == slot.bed } ?: candidates.firstOrNull()
            if (hit == null) {
                store.upsert(
                    Patient(
                        id = nextPatientId(store),
                        name = slot.name,
                        bed = slot.bed,
                        admittedOn = today.toString(),
                        createdAt = today.toString(),
                        updatedAt = today.toString(),
                        lastSeenOnRoster = today.toString(),
                    ),
                )
                created++
            } else {
                var next = hit.copy(lastSeenOnRoster = today.toString(), updatedAt = today.toString())
                if (next.stage == PatientStage.ARCHIVED) {
                    next = next.copy(stage = PatientStage.ADMITTED, archivedOn = null, archiveReason = null)
                }
                if (next.bed != slot.bed) next = next.movedTo(slot.bed, today.toString())
                store.upsert(next)
            }
        }

        val seen = slots.map { it.name }.toSet()
        existing.filter { it.stage != PatientStage.ARCHIVED && it.name !in seen }
            .filter { it.lastSeenOnRoster != null && it.lastSeenOnRoster != today.toString() }
            .forEach {
                store.archive(it.id, "名单中未出现", today.toString())
                archived++
            }
        return created to archived
    }

    /** Answers from the saved roster alone; no photo, no reconciliation. */
    private suspend fun savedRosterAnswer(store: TaskStore, today: LocalDate): String {
        val saved = roster?.all().orEmpty()
        if (saved.isEmpty()) throw ToolArgumentException("还没有名单，也没有读到床号和姓名；先说一句或发一张 HIS 名单")
        val open = store.all().filter { it.state != TaskState.DONE }
        return buildJsonObject {
            put("ok", true)
            put("count", saved.size)
            put("savedAt", saved.map { it.seenAt }.maxOrNull())
            putJsonArray("patients") { saved.forEach { add("${it.bed}床 ${it.name}") } }
            putJsonArray("withTasks") {
                saved.forEach { slot ->
                    val mine = open.filter { it.bed == slot.bed }
                    if (mine.isNotEmpty()) add("${slot.bed}床 ${slot.name}｜" + mine.joinToString("；") { taskLine(it, today) })
                }
            }
            putJsonArray("withoutBed") {
                open.filter { task -> saved.none { it.bed == task.bed } }
                    .forEach { add("${it.bed}床 ${it.name}｜${it.item}｜名单里没有这个床位") }
            }
        }.toString()
    }

    /**
     * Writes the confirmed record to a `.txt` on the phone. The "ask first, save later" rule lives in
     * the tool description and the skill; here we only refuse empty input.
     */
    private suspend fun recordSave(args: JsonObject): String {
        val store = records ?: return error("保存功能没有启用")
        val title = JsonText.string(args, "title")?.trim().orEmpty()
        val content = JsonText.string(args, "content")?.trim().orEmpty()
        if (title.isEmpty()) throw ToolArgumentException("缺少 title")
        if (content.length < 20) throw ToolArgumentException("content 太短，先把记录正文写全")
        val path = store.save(title, content)
        return buildJsonObject {
            put("ok", true)
            put("path", path)
            put("chars", content.length)
            put("hint", "告诉用户文件路径即可，不要再重复贴全文")
        }.toString()
    }

    private suspend fun templateSave(args: JsonObject): String {
        val store = records ?: return error("模板功能没有启用")
        val name = JsonText.string(args, "name")?.trim().orEmpty()
        val content = JsonText.string(args, "content")?.trim().orEmpty()
        if (name.isEmpty()) throw ToolArgumentException("缺少 name（术式名）")
        if (content.length < 20) throw ToolArgumentException("content 太短，模板要写成可复用的骨架")
        val aliases = JsonText.string(args, "aliases")
            ?.split(",", "，", "、", ";", "；")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val path = store.saveTemplate(name, content, aliases)
        return buildJsonObject {
            put("ok", true)
            put("path", path)
            put("aliases", aliases.size)
            put("hint", "告诉用户已归档，并说明以后按哪个术式名/别名会命中这份模板")
        }.toString()
    }

    // ---------- 患者 ----------

    private suspend fun patientList(args: JsonObject): String {
        val store = patients ?: return error("患者功能没有启用")
        val archived = JsonText.string(args, "scope")?.contains("归档") == true
        val list = if (archived) store.archived() else store.all()
        val today = today()
        return buildJsonObject {
            put("ok", true)
            put("scope", if (archived) "archived" else "inHospital")
            put("count", list.size)
            putJsonArray("patients") {
                list.forEach { p ->
                    val gaps = PatientGaps.of(p, today)
                    addJsonObject {
                        put("id", p.id)
                        put("bed", p.bed)
                        put("name", p.name)
                        put("stage", p.stageLabel(today))
                        put("diagnosis", p.diagnosis)
                        put("gaps", gaps.size)
                        put("line", "${p.bed}床 ${p.name}｜${p.stageLabel(today)}｜${p.diagnosis}｜缺 ${gaps.size}")
                    }
                }
            }
            put("today", today.toString())
        }.toString()
    }

    private suspend fun patientUpdate(args: JsonObject): String {
        val store = patients ?: return error("患者功能没有启用")
        val today = today()
        val idIn = JsonText.string(args, "id")?.trim()
        val bedIn = JsonText.string(args, "bed")?.trim()?.takeIf { it.isNotEmpty() }?.let { TaskRoster.normalizeBed(it) }
        val existing = idIn?.takeIf { it.isNotEmpty() }?.let { store.find(it) }
            ?: bedIn?.takeIf { it.isNotEmpty() }?.let { store.findByBed(it) }

        val name = JsonText.string(args, "name")?.trim()
        var next = existing ?: run {
            if (name.isNullOrEmpty() || bedIn.isNullOrEmpty()) {
                throw ToolArgumentException("找不到这个患者；新建需要同时给 bed 和 name")
            }
            Patient(
                id = nextPatientId(store),
                name = name,
                bed = bedIn,
                admittedOn = today.toString(),
                createdAt = today.toString(),
                updatedAt = today.toString(),
                // 没上过名单：别因为一张没拍到他的照片就被归档
                lastSeenOnRoster = null,
            )
        }
        name?.takeIf { it.isNotEmpty() }?.let { next = next.copy(name = it) }
        bedIn?.takeIf { it.isNotEmpty() && it != next.bed }?.let { next = next.movedTo(it, today.toString()) }
        JsonText.string(args, "diagnosis")?.let { next = next.copy(diagnosis = it) }
        (args["tumor"] as? JsonPrimitive)?.let { p ->
            (p.booleanOrNull ?: (p.content == "是")) .let { next = next.copy(tumor = it) }
        }
        JsonText.string(args, "mdt")?.let { next = next.copy(mdt = it) }
        (args["preopDone"] as? JsonPrimitive)?.let { p ->
            (p.booleanOrNull ?: (p.content == "是")).let { next = next.copy(preopDone = it) }
        }
        val surgeryDate = JsonText.string(args, "surgeryDate")?.takeIf { it.length >= 8 }
        if (surgeryDate != null) {
            val surgeryName = JsonText.string(args, "surgeryName") ?: next.surgery?.name.orEmpty()
            next = next.copy(
                surgery = Surgery(surgeryDate, surgeryName, next.surgery?.recordId),
                stage = PatientStage.POSTOP,
                preopDone = true,
            )
        }
        JsonText.string(args, "stage")?.let { raw ->
            when (raw.lowercase()) {
                "admitted", "入院" -> next = next.copy(stage = PatientStage.ADMITTED)
                "preop", "术前" -> next = next.copy(stage = PatientStage.PREOP)
                "postop", "术后" -> next = next.copy(stage = PatientStage.POSTOP)
            }
        }
        next = next.copy(updatedAt = today.toString())
        val saved = store.upsert(next)
        store.log(
            PatientEvent(
                java.time.LocalDateTime.now().withNano(0).toString(),
                if (existing == null) "patient.created" else "patient.updated",
                saved.id,
                listOfNotNull(name, bedIn, surgeryDate).joinToString(" "),
                "dictation",
            ),
        )
        val gaps = PatientGaps.of(saved, today)
        return buildJsonObject {
            put("ok", true)
            put("id", saved.id)
            put("line", "${saved.bed}床 ${saved.name}｜${saved.stageLabel(today)}｜${saved.diagnosis}")
            put("gaps", gaps.size)
            putJsonArray("gapDetail") { gaps.forEach { add(it) } }
            put("created", existing == null)
        }.toString()
    }

    private suspend fun patientArchive(args: JsonObject): String {
        val store = patients ?: return error("患者功能没有启用")
        val today = today()
        val target = JsonText.string(args, "id")?.trim()?.takeIf { it.isNotEmpty() }?.let { store.find(it) }
            ?: JsonText.string(args, "bed")?.trim()?.takeIf { it.isNotEmpty() }?.let { store.findByBed(it) }
            ?: return error("找不到这个患者，先用 patient_list 看 id")
        val reason = JsonText.string(args, "reason")?.trim().orEmpty().ifEmpty { "手动归档" }
        val archived = store.archive(target.id, reason, today.toString())
            ?: return error("归档失败：${target.id}")
        store.log(PatientEvent(java.time.LocalDateTime.now().withNano(0).toString(), "patient.archived", archived.id, reason, "manual"))
        return buildJsonObject {
            put("ok", true)
            put("id", archived.id)
            put("line", "${archived.bed}床 ${archived.name} 已归档（$reason）")
            put("hint", "归档后可恢复；如需彻底删除要用「完成病案首页」")
        }.toString()
    }

    private suspend fun nextPatientId(store: PatientStore): String {
        val used = (store.all() + store.archived()).mapNotNull {
            it.id.removePrefix("p-").toIntOrNull()
        }
        return "p-" + ((used.maxOrNull() ?: 0) + 1).toString().padStart(4, '0')
    }

    private suspend fun recordList(): String {
        val store = records ?: return error("记录功能没有启用")
        val names = store.list()
        return buildJsonObject {
            put("ok", true)
            put("count", names.size)
            putJsonArray("records") { names.forEach { add(it) } }
            if (names.isEmpty()) put("hint", "还没有生成过记录；这一台是今天第一台")
        }.toString()
    }

    private suspend fun recordRead(args: JsonObject): String {
        val store = records ?: return error("记录功能没有启用")
        val name = JsonText.string(args, "name")?.trim().orEmpty()
        if (name.isEmpty()) throw ToolArgumentException("缺少 name，先用 record_list 看有哪些")
        val text = store.read(name) ?: return error("找不到记录：$name，先用 record_list 看有哪些")
        return buildJsonObject {
            put("ok", true)
            put("name", name)
            put("chars", text.length)
            put("content", text)
        }.toString()
    }

    private suspend fun templateRead(args: JsonObject): String {
        val store = records ?: return error("模板功能没有启用")
        val name = JsonText.string(args, "name")?.trim().orEmpty()
        if (name.isEmpty()) {
            val all = store.templates()
            return buildJsonObject {
                put("ok", true)
                put("count", all.size)
                putJsonArray("templates") { all.forEach { add(it) } }
                if (all.isEmpty()) put("hint", "模板文件夹是空的，直接按规范写，并告诉用户可以放模板进去")
            }.toString()
        }
        val text = store.template(name) ?: return error("没有找到模板：$name，先不传 name 列一次清单")
        return buildJsonObject {
            put("ok", true)
            put("name", name)
            put("content", text)
        }.toString()
    }

    private fun readSlots(raw: kotlinx.serialization.json.JsonElement?): List<BedSlot> {
        val array = raw as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val o = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val bed = JsonText.string(o, "bed")?.trim().orEmpty()
            val name = JsonText.string(o, "name")?.trim().orEmpty()
            if (bed.isEmpty() || name.isEmpty() || bed == "null" || name == "null") null else BedSlot(bed, name)
        }
    }

    private fun result(label: String, tasks: List<CareTask>, today: LocalDate): String = buildJsonObject {
        put("ok", true)
        put("message", label)
        put("count", tasks.size)
        putJsonArray("tasks") {
            tasks.forEach { t ->
                addJsonObject {
                    put("id", t.id)
                    put("line", taskLine(t, today))
                }
            }
        }
    }.toString()

    private fun taskLine(t: CareTask, today: LocalDate): String = buildString {
        append(t.bed).append("床 ").append(t.name).append("｜").append(t.item)
        append("｜第").append(t.dayNumber(today)).append("天")
        if (t.state == TaskState.DONE) append("｜已办")
        if (t.isStale(today)) append("｜已过期未确认") else if (t.pushCount > 0) append("｜已推").append(t.pushCount).append("次")
        val anchor = t.anchor
        if (anchor != null && t.anchorDate == null) {
            append("｜等")
            append(
                when (anchor.kind) {
                    AnchorKind.SURGERY -> "手术"
                    AnchorKind.DISCHARGE -> "出院"
                    AnchorKind.DATE -> "指定日期"
                },
            )
        }
    }

    private suspend fun external(call: ToolCall, taskId: String, emit: (AgentEvent) -> Unit): String {
        val result = external?.call(taskId, call.name, call.argumentsJson) ?: return error("未知工具 ${call.name}")
        emit(AgentEvent.ToolCalled(taskId, call.name, result.ok, result.summary))
        return result.text
    }

    private suspend fun phone(args: JsonObject): String {
        val finished = phoneTask(required(args, "goal"), JsonText.string(args, "skill"))
        return buildJsonObject {
            put("success", finished.success)
            put("summary", finished.summary)
            put("steps", finished.steps)
        }.toString()
    }
}
