package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    CHAT(R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    WORKBENCH(R.string.nav_workbench, Icons.Filled.ViewModule),
    PATIENTS(R.string.nav_patients, Icons.Filled.Groups),
    MORE(R.string.nav_more, Icons.Filled.MoreHoriz),
}

@Composable
fun XiaomanRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var moreSub by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // 返回手势逐级退：子页 → 更多 → 聊天 → 桌面
    BackHandler(enabled = tab != Tab.CHAT || moreSub != null) {
        if (moreSub != null) moreSub = null else tab = Tab.CHAT
    }

    Scaffold(
        bottomBar = {
            if (!keyboardOpen && moreSub == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                tab = t
                                if (t != Tab.MORE) moreSub = null
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (tab) {
                Tab.CHAT -> ChatScreen(graph, onOpenSettings = { tab = Tab.MORE; moreSub = "settings" })
                Tab.WORKBENCH -> WorkbenchScreen(graph) { }
                Tab.PATIENTS -> PatientScreen(graph)
                Tab.MORE -> when (moreSub) {
                    null -> MoreScreen(graph) { moreSub = it }
                    "skills" -> SubPage("技能", { moreSub = null }) { SkillsScreen(graph) }
                    "memory" -> SubPage("记忆", { moreSub = null }) { MemoryScreen(graph) }
                    else -> SubPage("设置", { moreSub = null }) { SettingsScreen(graph) }
                }
            }
        }
    }
}

/** 「更多」里的二级页：给这些老页面补一个返回入口，否则进去出不来。 */
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}
