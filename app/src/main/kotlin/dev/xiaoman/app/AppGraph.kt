package dev.xiaoman.app

import android.content.Context
import android.util.Log
import dev.xiaoman.agent.XiaomanAgent
import dev.xiaoman.api.Agent
import dev.xiaoman.api.ChatMessage
import dev.xiaoman.api.ChatRequest
import dev.xiaoman.api.ModelConfig
import dev.xiaoman.api.ModelRole
import dev.xiaoman.api.Patient
import dev.xiaoman.api.PatientEvent
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
import dev.xiaoman.app.settings.McpSettingsStore
import dev.xiaoman.mcp.McpCatalog
import dev.xiaoman.mcp.McpConfig
import dev.xiaoman.mcp.McpPlatform
import dev.xiaoman.mcp.McpSelection
import dev.xiaoman.mcp.McpToolProvider
import dev.xiaoman.mcp.ServerStatus
import dev.xiaoman.mcp.UserChannelApprovals
import dev.xiaoman.memory.FileMemoryStore
import dev.xiaoman.sentinel.PolicySentinel
import dev.xiaoman.skills.DirectorySkillRepository
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
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`, optional `mcp.json` (MCP servers,
 * HTTP only; keys are written in the file because Android has no environment); bundled skills are
 * extracted to `<files>/bundled-skills/`.
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
    val mcpFile = File(externalRoot, "mcp.json")
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

    /** 患者档案：在院 + 归档，放在 tasks 同级的 patients/ 目录。 */
    val patientsDir = File(externalRoot, "patients")
    val patientStore by lazy { FilePatientStore(patientsDir) }

    /** Generated surgical records and the user's own templates. */
    val recordsDir = File(externalRoot, "records")
    val templatesDir = File(externalRoot, "surgical-templates")
    val recordStore by lazy { FileRecordStore(recordsDir, templatesDir) }

    val taskScheduler by lazy { TaskDigestScheduler(app, taskStore, notifier) }

    /** Services ticked on the 服务 screen; keys live in the encrypted settings file. */
    val mcpSettings: McpSettingsStore by lazy { McpSettingsStore.create(app, settings.secret) }

    private var cachedMcp: Pair<McpSelection, McpToolProvider?>? = null

    /** MCP config of [selection] plus the HTTP servers of the optional `mcp.json` (stdio needs a desktop). */
    fun mcpConfig(selection: McpSelection): McpConfig {
        val ticked = McpCatalog.builtIn.toConfig(selection, McpPlatform.ANDROID)
        val file = McpConfig.load(mcpFile, emptyMap())
        val fileServers = file.servers.filter { it.enabled && it.url != null && ticked.servers.none { t -> t.name == it.name } }
        (ticked.warnings + file.warnings).forEach { Log.w(TAG, "mcp: $it") }
        return McpConfig(ticked.servers + fileServers, ticked.warnings + file.warnings)
    }

    /** The provider for [selection], rebuilt (and the old one closed) when the ticked services change. */
    @Synchronized
    private fun mcpFor(selection: McpSelection): McpToolProvider? {
        cachedMcp?.let { (s, provider) -> if (s == selection) return provider }
        cachedMcp?.second?.close()
        val config = mcpConfig(selection)
        val provider = if (config.servers.isEmpty()) null else McpToolProvider(config, UserChannelApprovals(session))
        cachedMcp = selection to provider
        return provider
    }

    /** Connects to the services of [selection] once, for the 测试连接 button; never kept. */
    suspend fun testMcp(selection: McpSelection): List<ServerStatus> {
        val config = mcpConfig(selection)
        if (config.servers.isEmpty()) return emptyList()
        return McpToolProvider(config, { false }).use { it.status(refresh = true) }
    }

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

    private var cachedAgent: Pair<Pair<AppSettings, McpSelection>, Agent>? = null

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
        val key = current to mcpSettings.current
        cachedAgent?.let { (k, agent) -> if (k == key) return agent }
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
            externalTools = mcpFor(key.second),
            tasks = taskStore,
            roster = rosterStore,
            records = recordStore,
            patients = patientStore,
        )
        cachedAgent = key to agent
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
