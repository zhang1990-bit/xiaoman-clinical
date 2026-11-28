package dev.xiaoman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import dev.xiaoman.app.AppGraph
import dev.xiaoman.api.Patient
import dev.xiaoman.api.Workbench
import java.time.LocalDate

/**
 * 工作台：五摞人，一眼看完今天该管谁。
 *
 * 全部只读——点一行进患者详情，动作都在那里做。
 */
@Composable
fun WorkbenchScreen(graph: AppGraph, onOpenPatient: (String) -> Unit) {
    val today = remember { LocalDate.now() }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("workbench", Context.MODE_PRIVATE) }
    var collapsed by remember { mutableStateOf(prefs.getStringSet("collapsed", emptySet())?.toSet().orEmpty()) }
    val expandedKinds = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    var patients by remember { mutableStateOf<List<Patient>>(emptyList()) }
    LaunchedEffect(Unit) { patients = graph.patientStore.all() }
    val board = remember(patients) { Workbench.build(patients, today) }

    fun toggle(kind: Workbench.Kind) {
        val next = if (kind.name in collapsed) collapsed - kind.name else collapsed + kind.name
        collapsed = next
        prefs.edit().putStringSet("collapsed", next).apply()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("工作台", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${today.monthValue}-${today.dayOfMonth} · 在院 ${patients.size}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Workbench.Kind.entries.forEach { kind ->
                item(key = kind.name) {
                    BucketCard(
                        kind = kind,
                        entries = board.of(kind),
                        today = today,
                        collapsed = kind.name in collapsed,
                        onToggle = { toggle(kind) },
                        expanded = expandedKinds[kind.name] == true,
                        onExpandToggle = { expandedKinds[kind.name] = !(expandedKinds[kind.name] ?: false) },
                        onOpen = onOpenPatient,
                    )
                }
            }
        }
    }
}

private fun kindColor(kind: Workbench.Kind): Pair<Color, Color> = when (kind) {
    Workbench.Kind.TO_OPERATE -> Color(0xFF1F5FBF) to Color(0xFFE9F0FB)
    Workbench.Kind.CONSULT -> Color(0xFFA35A00) to Color(0xFFFDF1E0)
    Workbench.Kind.BLOOD -> Color(0xFF0E7A6B) to Color(0xFFE6F4F1)
    Workbench.Kind.TOMORROW -> Color(0xFF5B4BB7) to Color(0xFFEEEBFA)
    Workbench.Kind.DISCHARGE -> Color(0xFF6B7A8C) to Color(0xFFF0F1F4)
}

@Composable
private fun BucketCard(
    kind: Workbench.Kind,
    entries: List<Workbench.Entry>,
    today: LocalDate,
    collapsed: Boolean,
    onToggle: () -> Unit,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val (fg, bg) = kindColor(kind)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }
                    .padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = if (collapsed) 12.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = fg, modifier = Modifier.size(7.dp)) { Box(Modifier) }
                Spacer(Modifier.width(9.dp))
                Text(kind.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${entries.size} 人", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (collapsed) "展开" else "收起",
                    fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (collapsed) return@Column
            if (entries.isEmpty()) {
                Text(
                    "今天没有", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
                )
            } else {
                entries.let { if (expanded) it else it.take(4) }.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(e.patient.id) }
                            .padding(horizontal = 13.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = RoundedCornerShape(9.dp), color = bg, modifier = Modifier.width(38.dp).height(30.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(e.patient.bed, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.patient.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (e.note.isNotBlank()) {
                                Text(
                                    e.note, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                        }
                        if (e.flag.isNotBlank()) {
                            Surface(shape = RoundedCornerShape(5.dp), color = bg) {
                                Text(
                                    e.flag, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = fg,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                if (entries.size > 4) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onExpandToggle() }
                            .padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (expanded) "收起" else "还有 ${entries.size - 4} 人 · 点这里全部展开",
                            fontSize = 11.sp, color = fg, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
