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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
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
    var picked by remember { mutableStateOf<PickedPhoto?>(null) }
    val photoScope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoScope.launch {
            val bytes = withContext(Dispatchers.IO) { ImagePrep.load(context, uri) }
            val preview = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            if (bytes == null || preview == null) {
                Toast.makeText(context, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            } else {
                picked = PickedPhoto(bytes, preview)
            }
        }
    }

    /** Sends the draft plus an optional photo. The photo is dropped once the turn starts. */
    fun submitTurn(text: String, photo: PickedPhoto?): AgentSession.Submit {
        val images = photo?.let { listOf(ContentPart.Image(it.bytes, ImagePrep.MIME)) }.orEmpty()
        val prompt = if (text.isBlank() && photo != null) context.getString(R.string.photo_default_prompt) else text
        val result = if (images.isEmpty()) session.submit(prompt) else session.submit(prompt, images)
        if (result == AgentSession.Submit.Started || result == AgentSession.Submit.Answered) {
            draft = ""
            picked = null
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
        ChatHeader(ready = settings.configured, busy = busy,
            attention = approval != null || question != null)
        // The accessibility banner only matters when a screen-reading operator model is configured.
        if (service == null && settings.operator.configured) {
            Banner(stringResource(R.string.banner_accessibility), stringResource(R.string.banner_accessibility_action)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!settings.configured) Banner(stringResource(R.string.banner_models), stringResource(R.string.banner_models_action), onOpenSettings)
        if (!notificationsGranted.granted) Banner(stringResource(R.string.banner_notifications), stringResource(R.string.banner_notifications_action), notificationsGranted.request)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isEmpty()) item { ChatWelcome { draft = it } }
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
                hasPhoto = picked != null,
                onPick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onSend = { submitTurn(it, picked) },
                onStop = session::stop,
            )
        }
    }
    picked?.let { photo ->
        PhotoConfirm(photo, onSend = { submitTurn(draft, photo) }, onDismiss = { picked = null })
    }
}

/** Shown before a photo leaves the phone: the one place a ward photo can leak a patient's identity. */
@Composable
private fun PhotoConfirm(photo: PickedPhoto, onSend: () -> Unit, onDismiss: () -> Unit) {
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
        confirmButton = { TextButton(onClick = onSend) { Text(stringResource(R.string.photo_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.photo_cancel)) } },
    )
}

@Composable
private fun ChatInput(
    text: String,
    onChange: (String) -> Unit,
    busy: Boolean,
    answering: Boolean,
    hasPhoto: Boolean,
    onPick: () -> Unit,
    onSend: (String) -> AgentSession.Submit,
    onStop: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSend = (text.isNotBlank() || hasPhoto) && (!busy || answering)
    fun send() {
        if (!canSend) return
        when (onSend(text)) {
            AgentSession.Submit.Busy, AgentSession.Submit.Empty -> Unit
            else -> { onChange(""); focus.clearFocus(); keyboard?.hide() }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onPick, enabled = !busy, modifier = Modifier.padding(bottom = 2.dp).size(44.dp)) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = stringResource(R.string.chat_attach),
                    tint = if (hasPhoto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = text, onValueChange = onChange, modifier = Modifier.weight(1f),
                placeholder = { Text(if (answering) "告诉我就好…" else "说说你想做什么…", style = MaterialTheme.typography.bodyMedium) },
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
