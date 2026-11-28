package dev.xiaoman.app

import android.content.Context
import android.util.Log
import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.Agent
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.CareTask
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.Patient
import dev.xiaoman.api.PatientEvent
import dev.xiaoman.api.PatientNote
import dev.xiaoman.api.PatientStage
import dev.xiaoman.api.NoteIntent
import dev.xiaoman.api.NoteKind
import dev.xiaoman.api.NoteRules
import dev.xiaoman.api.PlannedSurgery
import dev.xiaoman.api.TaskRule
import dev.xiaoman.api.TaskRuleKind
import dev.xiaoman.api.TaskState
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.app.session.SessionTexts
import dev.xiaoman.app.settings.AppSettings
import dev.xiaoman.app.settings.SettingsStore
import dev.xiaoman.app.trace.RecordingDevice
import dev.xiaoman.app.trace.TraceWriter
import dev.xiaoman.hands.AccessibilityDevice
import dev.xiaoman.hands.MutableStopSignal
import dev.xiaoman.hands.XiaomanAccessibilityService
import dev.xiaoman.llm.LlmClientFactory
import dev.xiaoman.llm.LlmClients
import dev.xiaoman.memory.FileMemoryStore
import dev.xiaoman.sentinel.PolicySentinel
import dev.xiaoman.skills.DirectorySkillRepository
import dev.xiaoman.tasks.FileChatLog
import dev.xiaoman.tasks.FilePatientStore
import dev.xiaoman.tasks.FileRecordStore
import dev.xiaoman.tasks.FileRosterStore
import dev.xiaoman.tasks.FileTaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.time.YearMonth

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [XiaomanApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.xiaoman.app/files`):
 * `memory/`、`skills/`（用户技能，覆盖同名内置）、`traces/`、`tasks/`、`patients/`、`records/`、
 * `surgical-templates/`。内置技能解压到 `<files>/bundled-skills/`。
 */
class AppGraph(context: Context, private val llm: LlmClientFactory = LlmClients.default) {
    private val app: Context = context.applicationContext

    /** Process-wide scope; the session runs agent work here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val externalRoot: File = app.getExternalFilesDir(null) ?: File(app.filesDir, "external")
    val memoryDir = File(externalRoot, "memory")
    val skillsDir = File(externalRoot, "skills")
    val tracesDir = File(externalRoot, "traces")
    val bundledSkillsDir = File(app.filesDir, "bundled-skills")
    val tasksDir = File(externalRoot, "tasks")

    val settings: SettingsStore by lazy { SettingsStore.create(app) }

    val memory by lazy { FileMemoryStore(memoryDir) }

    val skills by lazy { DirectorySkillRepository(listOf(bundledSkillsDir, skillsDir)) }

    val notifier by lazy { Notifier(app) }

    val reminderScheduler by lazy { AlarmReminderScheduler(app) }

    /** Clinical tasks live in one JSON file, so a reboot never loses them; see [TaskDigestScheduler]. */
    val taskStore by lazy { FileTaskStore(tasksDir) }

    /** Today's ward table from the HIS photo, in the same folder as the tasks. */
    val rosterStore by lazy { FileRosterStore(tasksDir) }

    /** 对话落盘：按月一个 jsonl，退出重进接得上。 */
    val chatsDir = File(externalRoot, "chats")
    val chatLog by lazy { FileChatLog(chatsDir) }

    /** 患者档案：在院 + 归档，放在 tasks 同级的 patients/ 目录。 */
    val patientsDir = File(externalRoot, "patients")
    val patientStore by lazy { FilePatientStore(patientsDir) }

    /** Generated surgical records and the user's own templates. */
    val recordsDir = File(externalRoot, "records")
    val templatesDir = File(externalRoot, "surgical-templates")
    val recordStore by lazy { FileRecordStore(recordsDir, templatesDir) }

    val taskScheduler by lazy { TaskDigestScheduler(app, taskStore, notifier) }

    val runIds = RunIds()
    val uiVisible = MutableStateFlow(false)

    val traces by lazy {
        TraceWriter(
            root = tracesDir,
            secrets = { settings.current.secrets },
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) },
        )
    }

    val accessibilityDevice by lazy { AccessibilityDevice({ XiaomanAccessibilityService.instance.value }, app) }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    val sentinel by lazy { PolicySentinel(approvals = session, audit = traces, selfPackage = app.packageName) }

    val session: AgentSession by lazy {
        AgentSession(
            scope = scope,
            agent = ::agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            onTaskEnd = { sentinel.endTask(it) },
            texts = SessionTexts(
                notConfigured = app.getString(R.string.session_not_configured),
                busy = app.getString(R.string.session_busy),
                stopped = app.getString(R.string.session_stopped),
                approvedOnce = { app.getString(R.string.approval_resolved_allowed, it) },
                approvedTask = { app.getString(R.string.approval_resolved_task, it) },
                denied = { app.getString(R.string.approval_resolved_denied, it) },
            ),
            ready = { startup.await() },
        )
    }

    /** Bundled skills extracted and skills loaded once. */
    lateinit var startup: Deferred<Unit>
        private set

    private var cachedAgent: Pair<AppSettings, Agent>? = null

    /**
     * 老数据修正（幂等）：患者自动提醒每人每天只推 1 次；「术后第 N 天」这类点事件改成一次性。
     * 不做的话，历史任务会按老规则每天推 4 次、推到 until 为止。
     */
    private suspend fun normalizePatientTasks() {
        val fixed = taskStore.all().mapNotNull { task ->
            if (task.source != "patient") return@mapNotNull null
            var next = task.copy(rule = task.rule.copy(maxPerDay = 1))
            if (task.item.startsWith("术后第") && task.rule.kind == TaskRuleKind.DAILY) {
                next = next.copy(rule = next.rule.copy(kind = TaskRuleKind.ONCE))
            }
            next.takeIf { it != task }
        }
        if (fixed.isNotEmpty()) taskStore.updateAll(fixed)

        // 同一个患者、同一件事只留最早一条，其余关掉（历史造出来的重复）
        val today = java.time.LocalDate.now().toString()
        val dupes = taskStore.all()
            .filter { it.source == "patient" && it.state != TaskState.DONE && it.patientId != null }
            .groupBy { it.patientId to it.item }
            .values
            .filter { it.size > 1 }
            .flatMap { list -> list.drop(1) }
        if (dupes.isNotEmpty()) {
            taskStore.updateAll(dupes.map { it.copy(state = TaskState.DONE, closedAt = today, closeReason = "重复项") })
        }
    }

    /**
     * 一次性迁移：老版本的待办只有姓名，没有患者档案。
     * 按 patientKey 建患者、回填 patientId；改之前先备份 tasks.json，出问题能整体回滚。
     */
    private suspend fun migratePatients() {
        val store = patientStore
        if (store.all().isNotEmpty() || store.archived().isNotEmpty()) return
        // 先把已经导入过的查房单落成档案：用户发过的照片不该白拍。
        val slots = rosterStore.all()
        val open = taskStore.all().filter { it.state != TaskState.DONE }
        if (open.isEmpty() && slots.isEmpty()) return
        val source = File(tasksDir, "tasks.json")
        val backup = File(tasksDir, "tasks.pre-patients.json")
        if (source.isFile && !backup.exists()) runCatching { source.copyTo(backup, overwrite = false) }
        val today = java.time.LocalDate.now().toString()
        val names = open.map { it.patientKey }.distinct()
        val idByName = mutableMapOf<String, String>()
        names.forEachIndexed { index, name ->
            val first = open.first { it.patientKey == name }
            val id = "p-" + (index + 1).toString().padStart(4, '0')
            idByName[name] = id
            store.upsert(
                Patient(
                    id = id, name = first.name, bed = first.bed,
                    admittedOn = today, createdAt = today, updatedAt = today, lastSeenOnRoster = today,
                ),
            )
        }
        taskStore.updateAll(open.map { it.copy(patientId = idByName[it.patientKey]) })

        // 名单里有、待办里没有的人，也补上档案
        val today0 = java.time.LocalDate.now().toString()
        val known = idByName.keys.toMutableSet()
        var seq = names.size
        slots.filter { it.name !in known }.forEach { slot ->
            seq++
            store.upsert(
                Patient(
                    id = "p-" + seq.toString().padStart(4, '0'),
                    name = slot.name, bed = slot.bed,
                    admittedOn = slot.seenAt, createdAt = today0, updatedAt = today0,
                    lastSeenOnRoster = slot.seenAt,
                ),
            )
        }
        store.log(
            PatientEvent(
                java.time.LocalDateTime.now().withNano(0).toString(),
                "migration.v1", "-", "${names.size} 位患者，来自 ${open.size} 条待办", "migration",
            ),
        )
    }

    /**
     * One tiny request with the settings currently typed on screen, so the Settings screen can show
     * the provider's own error ("Model Not Exist") instead of a generic "模型服务暂时不可用".
     */
    /**
     * 患者页上点「已完成」：标记待办完成，并把结果写回患者档案。
     *
     * 三类有副作用：术前检查 → preopDone；请X会诊 → 会诊转已提请并排上「会诊回复」；
     * 会诊回复：X → 会诊转已回复。
     */
    suspend fun completeTaskFromPatientPage(taskId: String): Boolean {
        val task = taskStore.find(taskId) ?: return false
        val today = java.time.LocalDate.now()
        val patient = task.patientId?.let { patientStore.find(it) }
        val item = task.item

        if (patient != null) {
            var next = patient
            when {
                item.startsWith("术前检查") || item.startsWith("检查：") -> next = patient.copy(preopDone = true)
                item.startsWith("请") && item.endsWith("会诊") -> {
                    val dept = item.removePrefix("请").removeSuffix("会诊")
                    next = patient.copy(
                        consults = patient.consults.map {
                            if (it.dept == dept && it.status == "toRequest") {
                                it.copy(status = "requested", requestedOn = today.toString())
                            } else {
                                it
                            }
                        },
                    )
                }
                item.startsWith("会诊回复") -> {
                    val dept = item.substringAfter("：")
                    next = patient.copy(
                        consults = patient.consults.map {
                            if (it.dept == dept && it.status == "requested") {
                                it.copy(status = "replied", repliedOn = today.toString())
                            } else {
                                it
                            }
                        },
                    )
                }
            }
            if (next != patient) patientStore.upsert(next.copy(updatedAt = today.toString()))
        }

        taskStore.update(task.copy(state = TaskState.DONE, closedAt = today.toString(), closeReason = "患者页点完成"))

        if (patient != null && item.startsWith("请") && item.endsWith("会诊")) {
            val dept = item.removePrefix("请").removeSuffix("会诊")
            val id = "cr-${patient.id}-$dept"
            if (taskStore.find(id) == null) {
                taskStore.add(
                    listOf(
                        CareTask(
                            id = id, bed = patient.bed, name = patient.name, patientKey = patient.name,
                            patientId = patient.id, item = "会诊回复：$dept", note = "自动",
                            rule = TaskRule(), state = TaskState.ACTIVE,
                            until = today.plusDays(14).toString(), createdAt = today.toString(),
                            source = "patient",
                        ),
                    ),
                )
            }
        }
        return true
    }

    private val NOTE_CLASSIFY_PROMPT = """
        你是病历助手。把医生的一句话归类成 JSON，只输出 JSON，不要解释。
        字段：kind（CONSULT/BLOOD/DRESSING/ORDER/OTHER）、dept（会诊科室，没有就 null）、dueDate（yyyy-MM-dd，没有就 null）。
        今天：%s。
        规则：请会诊=CONSULT；查血/复查血/血常规=BLOOD；换药/切口/拆线=DRESSING；开医嘱/备血/禁食/停药=ORDER；其它=OTHER。
    """.trimIndent()

    /** 清理报告：删了什么、省了多少。 */
    data class CleanupReport(val chatTurns: Int, val patients: Int, val records: Int, val bytes: Long, val days: Int = 30)

    /** 先看会删什么（dryRun），再决定要不要真删。 */
    suspend fun cleanupPreview(): CleanupReport = cleanup(dryRun = true)

    suspend fun cleanup(): CleanupReport = cleanup(dryRun = false)

    /**
     * 清理一个月前的内容（2026-11-24 定）：
     * 对话日志、超过 30 天的归档患者（档案+待办+事件一起走）、30 天前的手术记录 TXT。
     * 在院患者、未完成待办、近 30 天的一切都不动。
     */
    private suspend fun cleanup(dryRun: Boolean): CleanupReport {
        val cutoff = java.time.LocalDate.now().minusDays(30)
        val cutoffText = cutoff.toString()
        var chatTurns = 0
        var patients = 0
        var records = 0
        var bytes = 0L

        chatLog.stats().let { (bytesNow, _) -> if (dryRun) bytes += bytesNow / 3 }
        if (!dryRun) chatTurns = chatLog.purgeBefore(cutoffText)

        patientStore.archived()
            .filter { (it.archivedOn ?: it.updatedAt) < cutoffText }
            .forEach { p ->
                patients++
                if (!dryRun) {
                    taskStore.remove(taskStore.all().filter { it.patientId == p.id }.map { it.id }.toSet())
                    patientStore.purge(p.id)
                }
            }

        recordsDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { f ->
            val stamp = f.name.take(8)
            if (stamp.length == 8 && stamp.all { it.isDigit() } && stamp < cutoffText.replace("-", "")) {
                records++
                bytes += f.length()
                if (!dryRun) f.delete()
            }
        }
        return CleanupReport(chatTurns, patients, records, bytes)
    }

    /**
     * 把一句随手记分类。规则先判，判不出来才问一次模型；模型失败就退回「只记备注」。
     * 注意：这里只返回建议，落库要等用户点「就这样」。
     */
    suspend fun classifyNote(text: String, patient: Patient?): NoteIntent {
        val today = java.time.LocalDate.now()
        val ruled = NoteRules.classify(text, patient, today)
        if (ruled.kind != NoteKind.OTHER) return ruled
        val config = settings.current
        if (!config.configured) return ruled
        return runCatching {
            val client = llm.create(config.chat.toModelConfig(ModelRole.CHAT))
            val response = client.complete(
                ChatRequest(
                    listOf(
                        ChatMessage.system(NOTE_CLASSIFY_PROMPT),
                        ChatMessage.user(text),
                    ),
                    tools = emptyList(),
                ),
            )
            NoteRules.parse(text, response.text) ?: ruled
        }.getOrDefault(ruled)
    }

    /** 用户点了「就这样」：把分类真正落实成待办 / 会诊。 */
    suspend fun applyNoteIntent(patientId: String, intent: NoteIntent): Boolean {
        val patient = patientStore.find(patientId) ?: return false
        val today = java.time.LocalDate.now()
        val until = intent.dueDate ?: today.toString()

        when (intent.kind) {
            NoteKind.CONSULT -> {
                val dept = intent.dept ?: "会诊"
                if (patient.consults.none { it.dept == dept }) {
                    patientStore.upsert(
                        patient.copy(
                            consults = patient.consults + dev.xiaoman.api.Consult(dept, today.toString(), status = "toRequest"),
                            updatedAt = today.toString(),
                        ),
                    )
                }
                taskStore.add(
                    listOf(
                        CareTask(
                            id = "cq-$patientId-$dept", bed = patient.bed, name = patient.name, patientKey = patient.name,
                            patientId = patientId, item = "请${dept}会诊", note = "详情页记的",
                            rule = TaskRule(), state = TaskState.ACTIVE,
                            until = today.plusDays(3).toString(), createdAt = today.toString(), source = "patient",
                        ),
                    ),
                )
            }
            NoteKind.BLOOD -> taskStore.add(
                listOf(
                    CareTask(
                        id = "bl-$patientId-$until", bed = patient.bed, name = patient.name, patientKey = patient.name,
                        patientId = patientId, item = "复查血", note = intent.text,
                        rule = TaskRule(), state = TaskState.ACTIVE,
                        until = until, createdAt = today.toString(), source = "patient",
                    ),
                ),
            )
            NoteKind.DRESSING -> taskStore.add(
                listOf(
                    CareTask(
                        id = "dr-$patientId-${today}", bed = patient.bed, name = patient.name, patientKey = patient.name,
                        patientId = patientId, item = "换药（隔天一次）", note = intent.text,
                        rule = TaskRule(everyDays = 2), state = TaskState.ACTIVE,
                        until = today.plusDays(14).toString(), createdAt = today.toString(), source = "patient",
                    ),
                ),
            )
            NoteKind.ORDER -> taskStore.add(
                listOf(
                    CareTask(
                        id = "or-$patientId-${intent.text.hashCode()}", bed = patient.bed, name = patient.name,
                        patientKey = patient.name, patientId = patientId, item = intent.text, note = "医嘱",
                        rule = TaskRule(), state = TaskState.ACTIVE,
                        until = until, createdAt = today.toString(), source = "manual",
                    ),
                ),
            )
            NoteKind.OTHER -> return false
        }
        patientStore.log(
            PatientEvent(
                java.time.LocalDateTime.now().withNano(0).toString(),
                "note.classified", patientId, intent.summary(), "manual",
            ),
        )
        return true
    }

    /** 详情页底部记一句：一定存进档案；勾了「提醒」就同时排一条待办。 */
    suspend fun addPatientNote(patientId: String, text: String, remind: Boolean): Boolean {
        val patient = patientStore.find(patientId) ?: return false
        val today = java.time.LocalDate.now()
        val clean = text.trim()
        if (clean.isEmpty()) return false
        patientStore.upsert(
            patient.copy(
                notes = patient.notes + PatientNote(today.toString(), clean),
                updatedAt = today.toString(),
            ),
        )
        if (remind) {
            taskStore.add(
                listOf(
                    CareTask(
                        id = "pn-${patientId}-${today}-${clean.hashCode()}",
                        bed = patient.bed, name = patient.name, patientKey = patient.name,
                        patientId = patientId, item = clean, note = "详情页记的",
                        rule = TaskRule(), state = TaskState.ACTIVE,
                        until = today.plusDays(3).toString(), createdAt = today.toString(),
                        source = "manual",
                    ),
                ),
            )
        }
        return true
    }

    /** 「明日手术」一键：待手术的患者点一下就是排期，再点取消。 */
    suspend fun setTomorrowSurgery(patientId: String, on: Boolean): Boolean {
        val patient = patientStore.find(patientId) ?: return false
        val today = java.time.LocalDate.now()
        val next = if (on) {
            patient.copy(
                plannedSurgery = PlannedSurgery(
                    date = today.plusDays(1).toString(),
                    name = patient.plannedSurgery?.name.orEmpty(),
                    order = patient.plannedSurgery?.order,
                ),
                stage = if (patient.stage == PatientStage.ADMITTED) PatientStage.PREOP else patient.stage,
                updatedAt = today.toString(),
            )
        } else {
            patient.copy(plannedSurgery = null, updatedAt = today.toString())
        }
        patientStore.upsert(next)
        patientStore.log(
            PatientEvent(
                java.time.LocalDateTime.now().withNano(0).toString(),
                if (on) "patient.scheduled" else "patient.unscheduled",
                patientId, if (on) "明日手术" else "取消明日手术", "manual",
            ),
        )
        return true
    }

    /** 完成病案首页：患者、待办、事件一并清除，不可恢复。 */
    suspend fun purgePatient(id: String): Boolean {
        if (patientStore.find(id) == null && patientStore.archived().none { it.id == id }) return false
        val ids = taskStore.all().filter { it.patientId == id }.map { it.id }.toSet()
        taskStore.remove(ids)
        return patientStore.purge(id)
    }

    suspend fun testChatModel(config: ModelConfig): Result<String> = runCatching {
        val reply = llm.create(config)
            .complete(ChatRequest(listOf(ChatMessage.user("你好"))))
            .text.trim()
        reply.ifBlank { "（回复为空，但连接是通的）" }
    }

    /** The agent for the current settings, rebuilt when they change; null until the chat model is configured. */
    @Synchronized
    fun agent(): Agent? {
        val current = settings.current
        if (!current.configured) return null
        cachedAgent?.let { (k, agent) -> if (k == current) return agent }
        // DeepSeek and other text-only providers cannot read the screen, so the operator model may be
        // left blank; phone_task is then not offered at all and the chat model's config is reused.
        // 临床秘书分支不读屏：phone_task 不提供，操作模型只保留字段以兼容旧配置。
        val phoneTasks = false
        val agent = XiaomanAgent(
            chat = llm.create(current.chat.toModelConfig(ModelRole.CHAT)),
            operator = llm.create((if (phoneTasks) current.operator else current.chat).toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig().copy(phoneTasks = phoneTasks),
            idFactory = runIds::next,
            // 临床版不接外部服务：MCP 模块留在仓库里，但不打包进 APK。
            externalTools = null,
            tasks = taskStore,
            roster = rosterStore,
            records = recordStore,
            patients = patientStore,
            chatLog = chatLog,
        )
        cachedAgent = current to agent
        return agent
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        notifier.createChannels()
        taskScheduler.scheduleNext()
        scope.launch(Dispatchers.IO) {
            // "上上个月及更早" closed tasks leave the active file once a month; cheap and idempotent.
            runCatching { taskStore.archiveBefore(YearMonth.now().minusMonths(1)) }
            runCatching { migratePatients() }
            runCatching { normalizePatientTasks() }
        }
        OverlayController(session, accessibilityDevice, uiVisible = uiVisible).start(scope)
        scope.launch(Dispatchers.Main.immediate) {
            session.busy.collect { busy -> if (busy) AgentService.start(app) }
        }
        scope.launch(Dispatchers.Main.immediate) { session.approval.collect(notifier::showApproval) }
        scope.launch(Dispatchers.Main.immediate) { session.question.collect(notifier::showQuestion) }
    }

    private suspend fun prepareSkills() {
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = "${BuildConfig.VERSION_CODE}-${info.lastUpdateTime}"
            if (BundledSkills.extract(app.assets, bundledSkillsDir, version)) Log.i(TAG, "skills: bundled skills extracted ($version)")
            skillsDir.mkdirs()
            memoryDir.mkdirs()
        } catch (e: IOException) {
            Log.w(TAG, "skills: cannot extract bundled skills: ${e.message}")
        }
        val result = skills.reload()
        logSkillProblems(result.skills.size, result.problems)
    }

    fun logSkillProblems(count: Int, problems: List<dev.xiaoman.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "Xiaoman"
    }
}
