package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.api.CareTask
import dev.xiaoman.api.NoteIntent
import dev.xiaoman.api.NoteKind
import dev.xiaoman.api.Patient
import dev.xiaoman.api.PatientGaps
import dev.xiaoman.api.PatientStage
import dev.xiaoman.api.TaskState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 患者页（P0：只读）。
 *
 * 列表按床号排，阶段用色块，缺口用 `⚠N`；点开是时间轴与今日待办。
 * 不做任何写操作——归档、加提醒、记手术都还在对话里完成。
 */
@Composable
fun PatientScreen(graph: AppGraph, initialPatientId: String? = null, onConsumed: () -> Unit = {}) {
    val today = remember { LocalDate.now() }
    var inHospital by remember { mutableStateOf<List<Patient>>(emptyList()) }
    var archived by remember { mutableStateOf<List<Patient>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<CareTask>>(emptyList()) }
    var showArchive by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<Patient?>(null) }
    var loaded by remember { mutableStateOf(false) }

    val uiScope = rememberCoroutineScope()
    suspend fun reload() {
        inHospital = graph.patientStore.all()
        archived = graph.patientStore.archived()
        tasks = graph.taskStore.all().filter { it.state != TaskState.DONE }
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }
    // 从工作台点进来的人：数据到位后直接展开他的详情
    LaunchedEffect(initialPatientId, loaded) {
        val id = initialPatientId ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val target = (inHospital + archived).firstOrNull { it.id == id }
        if (target != null) {
            showArchive = target.stage == PatientStage.ARCHIVED
            selected = target
        }
        onConsumed()
    }
    var pendingPurge by remember { mutableStateOf<Patient?>(null) }
    var pendingIntent by remember { mutableStateOf<NoteIntent?>(null) }
    var pendingPatientId by remember { mutableStateOf<String?>(null) }
    var pendingArchive by remember { mutableStateOf<Patient?>(null) }

    // 侧滑返回：先退回列表，别把整个 App 关掉
    BackHandler(enabled = selected != null) { selected = null }

    selected?.let { patient ->
        PatientDetail(
            patient = patient,
            tasks = tasks.filter { it.patientId == patient.id || (it.patientId == null && it.bed == patient.bed && it.name == patient.name) },
            today = today,
            onBack = { selected = null },
            onComplete = { id -> uiScope.launch { graph.completeTaskFromPatientPage(id); reload() } },
            onArchive = { pendingArchive = patient },
            onRestore = {
                uiScope.launch {
                    graph.patientStore.restore(patient.id)
                    selected = null
                    reload()
                }
            },
            onPurge = { pendingPurge = patient },
            onSaveNote = { text ->
                uiScope.launch {
                    graph.addPatientNote(patient.id, text, false)
                    reload()
                    val classified = graph.classifyNote(text, patient)
                    pendingIntent = classified.takeIf { it.kind != NoteKind.OTHER }
                    pendingPatientId = patient.id
                }
            },
            intent = pendingIntent.takeIf { pendingPatientId == patient.id },
            onApplyIntent = {
                val target = pendingIntent
                if (target != null) {
                    uiScope.launch {
                        graph.applyNoteIntent(patient.id, target)
                        pendingIntent = null
                        pendingPatientId = null
                        reload()
                    }
                }
            },
            onDismissIntent = { pendingIntent = null; pendingPatientId = null },
            onTomorrow = { on ->
                uiScope.launch {
                    graph.setTomorrowSurgery(patient.id, on)
                    selected = graph.patientStore.find(patient.id)
                    reload()
                }
            },
        )
        return
    }

    val source = if (showArchive) archived else inHospital
    val rows = remember(source, filter) {
        when (filter) {
            "pre" -> source.filter { it.stage == PatientStage.PREOP || it.stage == PatientStage.ADMITTED }
            "post" -> source.filter { it.stage == PatientStage.POSTOP }
            "warn" -> source.filter { PatientGaps.of(it, today).isNotEmpty() }
            else -> source
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("患者", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${today.monthValue}-${today.dayOfMonth} · 在院 ${inHospital.size}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f), RoundedCornerShape(11.dp)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SegButton("当前住院 ${inHospital.size}", !showArchive, Modifier.weight(1f)) { showArchive = false }
                SegButton("归档 ${archived.size}", showArchive, Modifier.weight(1f)) { showArchive = true }
            }
            if (!showArchive) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChipMini("全部", filter == "all") { filter = "all" }
                    FilterChipMini("术前", filter == "pre") { filter = "pre" }
                    FilterChipMini("术后", filter == "post") { filter = "post" }
                    FilterChipMini("有缺口", filter == "warn") { filter = "warn" }
                }
            }
        }
        if (loaded && rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (inHospital.isEmpty() && !showArchive) "还没有患者。发一张查房单照片，或说「新入 12 床李xx」" else "没有符合条件的患者",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.id }) { p ->
                    PatientRow(p, today, archived = showArchive) { selected = p }
                }
            }
        }
    }

    pendingArchive?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingArchive = null },
            title = { Text("归档 ${target.bed} 床 ${target.name}？") },
            text = { Text("归档后移出在院列表，并发一条「整理出院首页」提醒（3 天后失效）。未完成的术后提醒会一起清掉。") },
            confirmButton = {
                TextButton(onClick = {
                    uiScope.launch {
                        graph.patientStore.archive(target.id, "患者页手动归档", today.toString())
                        pendingArchive = null
                        selected = null
                        reload()
                    }
                }) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { pendingArchive = null }) { Text("取消") } },
        )
    }

    pendingPurge?.let { target ->
        val taskCount = tasks.count { it.patientId == target.id }
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("完成病案首页") },
            text = {
                Column {
                    Text("确认后，这位患者的全部信息将从手机中删除，无法恢复。", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("· 患者档案 1 份（${target.bed}床 ${target.name}）", fontSize = 12.sp)
                    Text("· 相关待办 ${taskCount} 条", fontSize = 12.sp)
                    Text("· 相关事件与记忆一并清除", fontSize = 12.sp)
                    Text("· 出院首页提醒同时关闭", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    uiScope.launch {
                        graph.purgePatient(target.id)
                        pendingPurge = null
                        selected = null
                        reload()
                    }
                }) { Text("确认删除", color = Color(0xFFB3261E)) }
            },
            dismissButton = { TextButton(onClick = { pendingPurge = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SegButton(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(9.dp),
        color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (on) 1.dp else 0.dp,
    ) {
        Box(Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(
                text, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterChipMini(text: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface,
        border = if (on) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable { onClick() },
    ) {
        Box(Modifier.padding(horizontal = 11.dp, vertical = 5.dp)) {
            Text(
                text, fontSize = 11.5.sp,
                color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PatientRow(p: Patient, today: LocalDate, archived: Boolean, onClick: () -> Unit) {
    val gaps = PatientGaps.of(p, today)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = when {
                    archived -> MaterialTheme.colorScheme.surfaceVariant
                    p.stage == PatientStage.POSTOP -> Color(0xFFE6F4F1)
                    p.stage == PatientStage.PREOP -> Color(0xFFE9F0FB)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(42.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(p.bed, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = stageColor(p, archived))
                    Text("床", fontSize = 8.5.sp, color = stageColor(p, archived).copy(alpha = .7f))
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(7.dp))
                    StageTag(p, today, archived)
                }
                if (p.diagnosis.isNotBlank()) {
                    Text(
                        p.diagnosis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    metaLine(p, today, archived), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (gaps.isNotEmpty() && !archived) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFFFDF1E0)) {
                    Text(
                        "⚠ ${gaps.size}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFFA35A00), modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StageTag(p: Patient, today: LocalDate, archived: Boolean) {
    val (bg, fg) = when {
        archived -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        p.tumor && (p.mdt?.startsWith("待") != false) -> Color(0xFFFDF1E0) to Color(0xFFA35A00)
        p.stage == PatientStage.POSTOP -> Color(0xFFE6F4F1) to Color(0xFF0E7A6B)
        p.stage == PatientStage.PREOP -> Color(0xFFE9F0FB) to Color(0xFF1F5FBF)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            if (archived) (p.archivedOn ?: "").takeLast(5).replace("-", "-") + " 归档" else p.stageLabel(today),
            fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = fg,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun stageColor(p: Patient, archived: Boolean): Color = when {
    archived -> MaterialTheme.colorScheme.onSurfaceVariant
    p.stage == PatientStage.POSTOP -> Color(0xFF0E7A6B)
    p.stage == PatientStage.PREOP -> Color(0xFF1F5FBF)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun metaLine(p: Patient, today: LocalDate, archived: Boolean): String {
    val parts = mutableListOf<String>()
    if (p.admittedOn.isNotBlank()) parts += "${p.admittedOn.takeLast(5)} 入院"
    p.surgery?.let { parts += "${it.date.takeLast(5)} 手术" }
    if (archived) p.archiveReason?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
private fun PatientDetail(
    patient: Patient,
    tasks: List<CareTask>,
    today: LocalDate,
    onBack: () -> Unit,
    onComplete: (String) -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onSaveNote: (String) -> Unit,
    onTomorrow: (Boolean) -> Unit,
    intent: NoteIntent?,
    onApplyIntent: () -> Unit,
    onDismissIntent: () -> Unit,
) {
    val gaps = PatientGaps.of(patient, today)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 18.dp, vertical = 10.dp)) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("‹ 患者", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFE6F4F1), modifier = Modifier.size(46.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(patient.bed, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0E7A6B))
                        Text("床", fontSize = 9.sp, color = Color(0xFF0E7A6B).copy(alpha = .7f))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(patient.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        StageTag(patient, today, patient.stage == PatientStage.ARCHIVED)
                    }
                    Text(
                        patient.diagnosis.ifBlank { "（未填诊断）" },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            DetailCard("时间轴") { Timeline(patient, today) }
            DetailCard("今日待办") {
                if (tasks.isEmpty()) {
                    Text("今天没有待办", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    tasks.forEach { t ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.padding(top = 2.dp).size(15.dp)
                                    .background(Color.Transparent, RoundedCornerShape(5.dp))
                                    .then(Modifier),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.6.dp, MaterialTheme.colorScheme.outlineVariant),
                                    color = Color.Transparent,
                                ) { Box(Modifier.size(15.dp)) }
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.item, fontSize = 13.sp)
                                Text(
                                    (if (t.pushCount > 0) "已推 ${t.pushCount} 次 · " else "") + "截止 ${t.until}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(onClick = { onComplete(t.id) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("已完成", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            DetailCard("基本信息") {
                InfoRow("床位", "${patient.bed} 床")
                InfoRow("阶段", patient.stageLabel(today))
                InfoRow("缺口", if (gaps.isEmpty()) "无" else gaps.joinToString("、"), highlight = gaps.isNotEmpty())
                patient.surgery?.let { InfoRow("手术", "${it.date} ${it.name}") }
                InfoRow("编号", patient.id)
            }
        }
        val canSchedule = patient.surgery == null &&
            (patient.stage == PatientStage.PREOP || patient.stage == PatientStage.ADMITTED)
        var noteText by remember(patient.id) { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
        ) {
            // 识别结果先摆出来，点「就这样」才真的变成待办
            intent?.let { it ->
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = Color(0xFFE6F4F1),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "我理解成：${it.summary()}",
                            fontSize = 11.5.sp, color = Color(0xFF0E7A6B), modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissIntent, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("只记备注", fontSize = 11.sp)
                        }
                        TextButton(onClick = onApplyIntent, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("就这样", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            // 记一句：先落库，再分类
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("记一句：会诊、查血、换药…", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3,
                )
                TextButton(
                    onClick = {
                        if (noteText.isNotBlank()) {
                            onSaveNote(noteText.trim())
                            noteText = ""
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("记下", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canSchedule) {
                    Spacer(Modifier.width(12.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFEEEBFA)) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("明日手术", fontSize = 11.sp, color = Color(0xFF5B4BB7), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(2.dp))
                            Checkbox(
                                checked = patient.plannedSurgery?.date == LocalDate.now().plusDays(1).toString(),
                                onCheckedChange = { on -> onTomorrow(on) },
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (patient.stage == PatientStage.ARCHIVED) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp)) {
                    Text("恢复在院", fontSize = 12.5.sp)
                }
                Button(
                    onClick = onPurge,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                ) { Text("完成病案首页", fontSize = 12.5.sp) }
            } else {
                Text("记手术：在对话里说「X 床今天做了…」", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onArchive, shape = RoundedCornerShape(11.dp)) {
                    Text("归档", fontSize = 12.5.sp)
                }
            }
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Timeline(patient: Patient, today: LocalDate) {
    val rows = mutableListOf<Triple<String, String, String>>()
    patient.admittedOn.takeIf { it.isNotBlank() }?.let { rows += Triple(it.takeLast(5), "入院", "") }
    patient.consults.forEach { c ->
        rows += Triple(c.requestedOn.takeLast(5), "会诊 · ${c.dept}", if (c.status == "requested") "待回复" else c.followUp.orEmpty())
        c.repliedOn?.let { rows += Triple(it.takeLast(5), "会诊回复", c.followUp.orEmpty()) }
    }
    patient.surgery?.let { rows += Triple(it.date.takeLast(5), "手术 · ${it.name}", "") }
    patient.notes.forEach { rows += Triple(it.on.takeLast(5), "记录", it.text) }
    if (rows.isEmpty()) {
        Text("暂无事件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column {
        rows.forEachIndexed { index, (date, title, sub) ->
            Row(Modifier.padding(bottom = if (index == rows.lastIndex) 0.dp else 12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (index == rows.lastIndex) Color(0xFFA35A00) else Color(0xFF0E7A6B),
                        modifier = Modifier.padding(top = 4.dp).size(7.dp),
                    ) { Box(Modifier) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(date, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 1.dp))
                    if (sub.isNotBlank()) {
                        Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            color = if (highlight) Color(0xFFA35A00) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 仅供调试：把毫秒时间戳变成日期，UI 里暂时没用上。 */
internal fun dayOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
