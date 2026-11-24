package dev.xiaoman.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import dev.xiaoman.app.AppGraph
import kotlinx.coroutines.launch

/** 「更多」：技能、记忆、设置都收在这里，另加清理内存。 */
@Composable
fun MoreScreen(graph: AppGraph, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AppGraph.CleanupReport?>(null) }
    var cleaning by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)) {
            Text("更多", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Group {
                Entry("技能", "查房待办 · 手术记录") { onOpen("skills") }
                Entry("记忆", "患者信息 · 个人偏好") { onOpen("memory") }
                Entry("设置", "模型 · 通知 · 关于") { onOpen("settings") }
            }
            Spacer(Modifier.height(12.dp))
            Group {
                Entry(
                    "清理内存",
                    "删除一个月前的对话、归档患者与手术记录",
                    danger = true,
                ) {
                    scope.launch { report = graph.cleanupPreview() }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "临床秘书 · 数据只在这台手机上",
                fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    report?.let { preview ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("清理一个月前的内容") },
            text = {
                Column {
                    Text("只删 30 天前的记录，最近一个月全部保留。", fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Line("对话记录", "${preview.chatTurns} 条")
                    Line("归档患者", "${preview.patients} 位（档案与待办一起删）")
                    Line("手术记录 TXT", "${preview.records} 份")
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "保留：在院患者、未完成的待办、近 30 天的一切。",
                        fontSize = 11.sp, color = Color(0xFF0E7A6B),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    cleaning = true
                    scope.launch {
                        val real = graph.cleanup()
                        cleaning = false
                        report = null
                        Toast.makeText(
                            context,
                            "已清理：对话 ${real.chatTurns} 条、患者 ${real.patients} 位、记录 ${real.records} 份",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text("清理", color = Color(0xFFB3261E)) }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun Entry(title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                color = if (danger) Color(0xFFB3261E) else MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFC0CAD6))
    }
}
