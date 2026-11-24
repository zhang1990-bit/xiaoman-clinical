package dev.xiaoman.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.ImagePrep
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import dev.xiaoman.api.ContentPart
import dev.xiaoman.hands.XiaomanAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked photo after downscaling: [bytes] go to the model, [preview] is shown in the confirm dialog. */
private class PickedPhoto(val bytes: ByteArray, val preview: ImageBitmap)

/** 一次最多挂几张。再多，模型读图也会开始漏。 */
private const val MAX_ATTACHED = 6

@Composable
fun ChatScreen(graph: AppGraph, onOpenSettings: () -> Unit) {
    val session = graph.session
    val items by session.items.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val status by session.status.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val approval by session.approval.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val service by XiaomanAccessibilityService.instance.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val notificationsGranted = rememberNotificationPermission()
    var draft by rememberSaveable { mutableStateOf("") }
    // 一次可以挂多张：拍三张条码、或一张名单加一张条码，都不用分批发。
    var attached by remember { mutableStateOf<List<PickedPhoto>>(emptyList()) }
    var confirming by remember { mutableStateOf<PickedPhoto?>(null) }
    val uiScope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            val bytes = withContext(Dispatchers.IO) { ImagePrep.load(context, uri) }
            val preview = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            if (bytes == null || preview == null) {
                Toast.makeText(context, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            } else {
                confirming = PickedPhoto(bytes, preview)
            }
        }
    }

    /** Sends the draft plus an optional photo. The photo is dropped once the turn starts. */
    fun submitTurn(text: String, photos: List<PickedPhoto>): AgentSession.Submit {
        val images = photos.map { ContentPart.Image(it.bytes, ImagePrep.MIME) }
        val prompt = if (text.isBlank() && photos.isNotEmpty()) context.getString(R.string.photo_default_prompt) else text
        val result = if (images.isEmpty()) session.submit(prompt) else session.submit(prompt, images)
        if (result == AgentSession.Submit.Started || result == AgentSession.Submit.Answered) {
            draft = ""
            attached = emptyList()
        }
        return result
    }
    val rows = remember(items) { chatTimeline(items) }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }
    LaunchedEffect(approval?.id) {
        if (approval != null) { focus.clearFocus(); keyboard?.hide() }
    }
    Column(Modifier.fillMaxSize()) {
        ChatHeader(
            ready = settings.configured, busy = busy,
            attention = approval != null || question != null,
            onTitleLongPress = {
                uiScope.launch {
                    val sent = graph.taskScheduler.pushTest()
                    val text = when {
                        sent == null -> context.getString(R.string.digest_test_blocked)
                        sent == 0 -> context.getString(R.string.digest_test_none)
                        else -> context.getString(R.string.digest_test_sent, sent)
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            },
        )
        if (!settings.configured) Banner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
        if (!notificationsGranted.granted) Banner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isEmpty()) item { ChatHero() }
            items(rows, key = { it.id }) { row ->
                when (row) {
                    is TimelineRow.Message -> ChatMessage(row.item)
                    is TimelineRow.Process -> ProcessCard(row.entries)
                }
            }
        }
        if (approval != null) {
            ApprovalCard(approval!!, session)
        } else {
            if (busy) TaskStatus(status ?: "正在想办法…", session::stop)
            ChatInput(
                text = draft,
                onChange = { draft = it },
                busy = busy,
                answering = question != null,
                photos = attached,
                onPick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemovePhoto = { index -> attached = attached.filterIndexed { i, _ -> i != index } },
                onSend = { submitTurn(it, attached) },
                onStop = session::stop,
            )
        }
    }
    confirming?.let { photo ->
        PhotoConfirm(
            photo,
            onAttach = {
                if (attached.size < MAX_ATTACHED) attached = attached + photo
                confirming = null
            },
            onDismiss = { confirming = null },
        )
    }
}

/**
 * Shown the moment a photo is picked: the one place a ward photo can leak a patient's identity.
 * Confirming only attaches the picture to the input box - it still waits for the send button.
 */
@Composable
private fun PhotoConfirm(photo: PickedPhoto, onAttach: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.photo_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(
                    bitmap = photo.preview, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(stringResource(R.string.photo_confirm_body), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAttach) { Text(stringResource(R.string.photo_attach)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.photo_cancel)) } },
    )
}

@Composable
private fun ChatInput(
    text: String,
    onChange: (String) -> Unit,
    busy: Boolean,
    answering: Boolean,
    photos: List<PickedPhoto>,
    onPick: () -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onSend: (String) -> AgentSession.Submit,
    onStop: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    // Attaching a picture is an invitation to say what it is for: open the keyboard for the user.
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty()) runCatching { field.requestFocus() }
    }
    val canSend = (text.isNotBlank() || photos.isNotEmpty()) && (!busy || answering)
    fun send() {
        if (!canSend) return
        when (onSend(text)) {
            AgentSession.Submit.Busy, AgentSession.Submit.Empty -> Unit
            else -> { onChange(""); focus.clearFocus(); keyboard?.hide() }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth()) {
            // 挂着的图片：横向排开，每张单独可撤；后面跟着「+」接着加。
            if (photos.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    photos.forEachIndexed { index, photo ->
                        Box {
                            Image(
                                bitmap = photo.preview, contentDescription = null,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xCC2B3441),
                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(16.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "×", color = Color.White, fontSize = 11.sp,
                                        modifier = Modifier.clickable { onRemovePhoto(index) },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        if (photos.size >= MAX_ATTACHED) "最多 $MAX_ATTACHED 张" else "共 ${photos.size} 张",
                        fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onPick, enabled = !busy, modifier = Modifier.padding(bottom = 2.dp).size(44.dp)) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = stringResource(R.string.chat_attach),
                        tint = if (photos.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = text, onValueChange = onChange, modifier = Modifier.weight(1f).focusRequester(field),
                    placeholder = {
                        Text(
                            when {
                                answering -> "告诉我就好…"
                                photos.isNotEmpty() -> "这几张图要做什么？可留空"
                                else -> "说说你想做什么…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    shape = MaterialTheme.shapes.large, textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 1, maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { if (busy && !answering) onStop() else send() },
                    enabled = (busy && !answering) || canSend, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                    Icon(if (busy && !answering) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = stringResource(if (busy && !answering) R.string.chat_stop else R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

private class NotificationPermission(val granted: Boolean, val request: () -> Unit)

/** POST_NOTIFICATIONS on Android 13+: asked once automatically, then via the banner. */
@Composable
private fun rememberNotificationPermission(): NotificationPermission {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return NotificationPermission(true) {}
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = check() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return NotificationPermission(granted) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}


/** 首页只留一句话。以前那些「选一本笔记本」式的示例卡片，对临床是噪音。 */
@Composable
private fun ChatHero() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "手稳一点，",
            fontSize = 27.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground, lineHeight = 38.sp,
        )
        Row {
            Text("剩下的", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp)
            Text("交给它", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp, color = Color(0xFF0E7A6B))
            Text("。", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "说完就记下，明天早上它会提醒你。",
            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
