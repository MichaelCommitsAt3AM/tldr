package tech.currenta.tldr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Only completed exchanges are sent as bounded follow-up context for this video. */
internal fun questionHistory(turns: List<JSONObject>, before: Int): JSONArray = JSONArray().apply {
    turns.take(before).filter { it.string("answer").isNotBlank() }.takeLast(6).forEach { turn ->
        val claim = turn.string("claim")
        val question = if (claim.isBlank()) turn.string("question") else "Regarding the claim: $claim\nQuestion: ${turn.string("question")}"
        put(JSONObject().put("role", "user").put("content", question.take(6000)))
        put(JSONObject().put("role", "assistant").put("content", turn.string("answer").take(6000)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompletedResult(
    job: JSONObject,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onQuestion: suspend (String, JSONArray, String?) -> String,
    onCheck: suspend () -> JSONObject,
    onError: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val jobId = job.string("id")
    var draft by rememberSaveable(jobId) { mutableStateOf("") }
    var claimContext by rememberSaveable(jobId) { mutableStateOf("") }
    val composerFocus = remember { FocusRequester() }
    var turnJson by rememberSaveable(jobId) { mutableStateOf("[]") }
    val turns = remember(turnJson) { JSONArray(turnJson).objects() }
    var asking by remember(jobId) { mutableStateOf(false) }
    var pendingIndex by remember(jobId) { mutableStateOf(-1) }
    var checking by remember(jobId) { mutableStateOf(false) }
    var report by remember(jobId) { mutableStateOf(job.optJSONObject("fact_check")) }
    val scroll = rememberScrollState()
    fun updateTurn(index: Int, question: String, claim: String, answer: String = "", error: String = "") {
        val updated = JSONArray(turnJson)
        updated.put(index, JSONObject().put("question", question).put("claim", claim).put("answer", answer).put("error", error))
        turnJson = updated.toString()
    }
    fun send(retry: Int? = null) {
        if (asking) return
        val asked = if (retry != null) turns[retry].string("question") else draft.trim()
        if (asked.isBlank()) return
        val index = retry ?: turns.size
        val claim = if (retry != null) turns[retry].string("claim") else claimContext
        val history = questionHistory(turns, index)
        updateTurn(index, asked, claim)
        if (retry == null) draft = ""
        asking = true
        pendingIndex = index
        keyboard?.hide()
        scope.launch {
            try {
                val answer = onQuestion(asked, history, claim.takeIf(String::isNotBlank))
                updateTurn(index, asked, claim, answer = answer.ifBlank { "No answer was returned. Try asking again." })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { updateTurn(index, asked, claim, error = e.message ?: "Could not answer. Try again.") }
            finally { asking = false }
        }
    }
    LaunchedEffect(selectedTab) { scroll.scrollTo(0) }
    LaunchedEffect(claimContext) {
        if (claimContext.isNotBlank()) { withFrameNanos { }; composerFocus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(turnJson, asking) {
        if (turns.isNotEmpty()) { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(jobMetadata(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(job.jobTitle(), style = MaterialTheme.typography.headlineSmall)
            if (selectedTab == "Summary") Text("Based on the video transcript", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val tabs = listOf("Summary", "Check")
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEach { tab -> Tab(selected = selectedTab == tab, onClick = { onSelectTab(tab) }, text = { Text(tab, style = MaterialTheme.typography.titleMedium) }) }
            }
            if (selectedTab == "Check") {
                if (report == null) Text("Compare claims with external sources.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (report == null) Button(shape = MaterialTheme.shapes.small, enabled = !checking, onClick = {
                    checking = true
                    scope.launch {
                        try { report = onCheck(); job.put("fact_check", report) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { onError(e.message ?: "Fact-check failed. Try again.") }
                        finally { checking = false }
                    }
                }) { Text(if (checking) "Checking claims…" else "Check claims") }
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                report?.let { FactCheckContent(it) { claim -> claimContext = claim } } ?: Text("Not independently checked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val summary = job.optJSONObject("summary") ?: JSONObject()
                // Old saved analyses remain readable without generating another summary.
                val text = summary.string("short_summary").ifBlank { summary.string("summary") }
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summary", text))
                        scope.launch { snackbar.showSnackbar("Summary copied") }
                    }) { Icon(Icons.Outlined.ContentCopy, "Copy summary") }
                    IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share summary")) }) { Icon(Icons.Outlined.Share, "Share summary") }
                    if (job.string("url").isNotBlank()) IconButton(onClick = { openSource(context, job.string("url")) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open original video") }
                }
            }
            if (turns.isNotEmpty()) {
                HorizontalDivider()
                Text("Conversation", style = MaterialTheme.typography.titleMedium)
                turns.forEachIndexed { index, turn ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (turn.string("claim").isNotBlank()) Text("About: ${turn.string("claim")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(turn.string("question"))
                        }
                    }
                    when {
                        turn.string("answer").isNotBlank() -> SelectionContainer { Text(turn.string("answer"), style = MaterialTheme.typography.bodyLarge) }
                        asking && index == pendingIndex -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Thinking…", style = MaterialTheme.typography.bodySmall) }
                        else -> {
                            Text(turn.string("error").ifBlank { "Answer interrupted. Try again." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            TextButton(enabled = !asking, onClick = { send(index) }) { Text("Retry answer") }
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 3.dp,
        ) {
            Column {
            if (claimContext.isNotBlank()) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("About this claim", style = MaterialTheme.typography.labelSmall)
                    Text(claimContext, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { claimContext = "" }) { Icon(Icons.Outlined.Close, "Clear claim context") }
            }
            TextField(draft, { draft = it.take(1000) }, modifier = Modifier.fillMaxWidth().focusRequester(composerFocus),
                placeholder = { Text(if (claimContext.isBlank()) "Ask about this video" else "Ask about this claim", style = MaterialTheme.typography.bodyMedium) },
                supportingText = if (draft.length > 900) { { Text("${draft.length}/1000") } } else null,
                maxLines = 4, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = {
                    FilledIconButton(onClick = { send() }, enabled = draft.isNotBlank() && !asking, shape = CircleShape, modifier = Modifier.padding(end = 6.dp).size(48.dp)) {
                        Icon(Icons.Outlined.ArrowUpward, "Send question")
                    }
                },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
            )
            }
        }
    }
}
