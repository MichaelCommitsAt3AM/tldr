package tech.currenta.tldr

import android.app.StatusBarManager
import android.content.*
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)
    private var incomingJob by mutableStateOf<String?>(null)
    private var incomingTab by mutableStateOf("Summary")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readIntent(intent)
        setContent { TLDRApp(incoming, incomingJob, incomingTab, { incoming = null }, { incomingJob = null }) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); readIntent(intent) }
    private fun readIntent(intent: Intent?) {
        incoming = intent?.getStringExtra(Intent.EXTRA_TEXT)
        incomingJob = intent?.getStringExtra("job_id")
        incomingTab = if (intent?.getStringExtra("result_tab") == "Check") "Check" else "Summary"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TLDRApp(incoming: String?, incomingJob: String?, incomingTab: String, clearIncoming: () -> Unit, clearJob: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val api = remember { Api(context) }
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(settings.theme) }
    var page by rememberSaveable { mutableStateOf("home") }
    var parentPage by rememberSaveable { mutableStateOf("home") }
    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf("Summary") }
    var savedJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf<JSONObject?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(true) }
    var historyError by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showResultMenu by remember { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("") }
    var tileStatus by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    suspend fun loadHistory() {
        try { history = JSONArray(api.call("/jobs")).objects(); historyError = "" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { historyError = e.message ?: "Could not load library" }
        finally { historyLoading = false }
    }
    fun refresh() { scope.launch { loadHistory() } }
    fun open(job: JSONObject, tab: String = "Summary") {
        if (page in listOf("home", "library")) parentPage = page
        current = job; savedJobId = job.string("id")
        showTranscript = false; error = ""; selectedTab = tab; page = "result"
    }
    fun analyze(text: String, shared: Boolean = false) { scope.launch {
        busy = true; error = ""
        val field = if (shared && Regex("https?://", RegexOption.IGNORE_CASE).containsMatchIn(text)) "url" else "input"
        try { open(JSONObject(api.call("/jobs", "POST", JSONObject().put(field, text.trim())))); refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not create summary" }
        finally { busy = false }
    } }
    fun retryAnalysis(id: String) { scope.launch {
        busy = true; error = ""
        try { open(JSONObject(api.call("/jobs/$id/retry", "POST"))); refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not retry analysis" }
        finally { busy = false }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = ""
            try { open(JSONObject(api.upload(uri))); refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Upload failed" }
            finally { busy = false }
        }
    }
    LaunchedEffect(page) {
        if (page == "library") { while (true) { loadHistory(); delay(5000) } }
    }
    LaunchedEffect(incoming) { if (!incoming.isNullOrBlank()) { url = incoming; clearIncoming(); analyze(incoming, shared = true) } }
    LaunchedEffect(incomingJob) {
        if (!incomingJob.isNullOrBlank()) {
            val id = incomingJob; val tab = incomingTab
            try { open(JSONObject(api.call("/jobs/$id")), tab) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not open summary" }
            finally { clearJob() }
        }
    }
    LaunchedEffect(Unit) {
        if (current == null && savedJobId != null && incomingJob == null) {
            try { current = JSONObject(api.call("/jobs/$savedJobId")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not restore summary"; page = "library" }
        }
    }
    val currentId = current?.string("id")
    val status = current?.string("status").orEmpty()
    LaunchedEffect(currentId, page, status) {
        if (page == "result" && currentId != null && status !in terminalStatuses) {
            while (current?.string("status") !in terminalStatuses) {
                delay(2000)
                try { current = JSONObject(api.call("/jobs/$currentId")); error = "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Connection interrupted. Retrying…"; delay(3000) }
            }
        }
    }
    BackHandler(page !in listOf("home", "library")) { page = parentPage; error = ""; refresh() }
    UtilityTheme(theme) {
        Scaffold(topBar = {
            Column {
                TopAppBar(title = { Text(if (page in listOf("home", "library")) "TL;DR" else if (page == "settings") "Settings" else "Summary", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { if (page !in listOf("home", "library")) IconButton(onClick = { page = parentPage; error = ""; refresh() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        if (page == "result" && status == "completed") {
                            Box {
                                IconButton(onClick = { showResultMenu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                                DropdownMenu(expanded = showResultMenu, onDismissRequest = { showResultMenu = false }) {
                                    DropdownMenuItem(text = { Text("View ${current?.sourceLabel() ?: "transcript"}") }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = { showResultMenu = false; showTranscript = true })
                                    DropdownMenuItem(
                                        text = { Text("Retry") },
                                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        enabled = !busy && currentId != null,
                                        onClick = {
                                            showResultMenu = false
                                            if (current?.string("url").isNullOrBlank() && current?.optJSONObject("metadata")?.string("platform") != "text") picker.launch("*/*")
                                            else currentId?.let { retryAnalysis(it) }
                                        },
                                    )
                                    DropdownMenuItem(text = { Text("Delete analysis") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { showResultMenu = false; confirmDelete = true })
                                    DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { showResultMenu = false; page = "settings" })
                                }
                            }
                        } else if (page != "settings") IconButton(onClick = { if (page in listOf("home", "library")) parentPage = page; page = "settings"; error = "" }) { Icon(Icons.Default.Tune, "Settings") }
                    })
                HorizontalDivider()
            }
        }, bottomBar = { if (page in listOf("home", "library")) {
            Column {
                HorizontalDivider()
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("home" to "Home", "library" to "Library").forEach { (key, label) ->
                        NavigationBarItem(selected = page == key, onClick = { page = key; error = "" },
                            icon = { Icon(if (key == "home") Icons.Default.Home else Icons.AutoMirrored.Filled.LibraryBooks, null) }, label = { Text(label) })
                    }
                }
            }
        } }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (error.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { error = "" }) { Text("Dismiss") }
                }
                when (page) {
                    "home" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("New summary", style = MaterialTheme.typography.headlineSmall)
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Link or text") }, placeholder = { Text("Paste a link or text to summarize") }, maxLines = 5, trailingIcon = {
                            IconButton(onClick = { url = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }) { Icon(Icons.Default.ContentPaste, "Paste") }
                        })
                        Button(shape = MaterialTheme.shapes.small, onClick = { analyze(url) }, enabled = url.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
                            Text(if (busy) "Submitting…" else "Summarize")
                        }
                        TextButton(onClick = { picker.launch("*/*") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Upload file") }
                        Text("Or share a link or text to TL;DR from another app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (settings.token.isBlank()) { HorizontalDivider(); Text("Pair this device to start creating summaries."); OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { parentPage = "home"; page = "settings" }) { Text("Set up connection") } }
                    }
                    "library" -> {
                        val filtered = history.filter { it.jobTitle().contains(query, true) || it.string("url").contains(query, true) }
                        LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("Library", style = MaterialTheme.typography.headlineSmall) }
                            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search summaries") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }) }
                            if (historyLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            if (historyError.isNotBlank()) item { Text(historyError, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh() }) { Text("Retry") } }
                            if (!historyLoading && filtered.isEmpty() && historyError.isBlank()) item { Text(if (query.isBlank()) "No summaries yet. Add a video from Home." else "No matching summaries.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(filtered, key = { it.string("id") }) { job ->
                                Column {
                                    Row(Modifier.fillMaxWidth().clickable { open(job) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text(job.jobTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                            Text(jobMetadata(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    "settings" -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        var base by remember { mutableStateOf(settings.base) }; var code by remember { mutableStateOf("") }
                        var connectionExpanded by remember { mutableStateOf(settings.token.isBlank()) }
                        Text("Preferences", style = MaterialTheme.typography.titleLarge)
                        
                        Spacer(Modifier.height(12.dp))
                        Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        ChoiceRow(listOf("System", "Light", "Dark"), theme) { theme = it; settings.theme = it }
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(6.dp)); Text("Floating shortcut", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("Keep a small shortcut over other apps.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(shape = MaterialTheme.shapes.small, onClick = { if (!AndroidSettings.canDrawOverlays(context)) context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) else context.startForegroundService(Intent(context, OverlayService::class.java)) }) { Text("Show shortcut") }
                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { context.stopService(Intent(context, OverlayService::class.java)) }) { Text("Hide") }
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            OutlinedButton(shape = MaterialTheme.shapes.small, onClick = {
                                context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                                    ComponentName(context, OverlayTileService::class.java), "TLDR",
                                    Icon.createWithResource(context, R.drawable.ic_quick_tile), context.mainExecutor
                                ) { result ->
                                    tileStatus = if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) "TLDR is in Quick Settings." else "Add TLDR from your Quick Settings editor."
                                }
                            }) { Text("Add to Quick Settings") }
                        }
                        if (tileStatus.isNotEmpty()) Text(tileStatus, style = MaterialTheme.typography.bodySmall)
                        Text("After granting permission, return and tap Show shortcut. You can also toggle it from Quick Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp)); HorizontalDivider()
                        Row(Modifier.fillMaxWidth().clickable { connectionExpanded = !connectionExpanded }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(3.dp))
                                Text(if (settings.token.isNotBlank()) "Device paired" else "Set up your device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (connectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (connectionExpanded) "Hide connection settings" else "Show connection settings")
                        }
                        if (connectionExpanded) {
                            OutlinedTextField(base, { base = it }, label = { Text("Backend HTTPS URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small)
                            Text("Generate a pairing code on your server. It expires after five minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(9) }, label = { Text("9-digit pairing code") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(shape = MaterialTheme.shapes.small, onClick = { scope.launch {
                                    try { settings.base = base; val result = JSONObject(api.call("/pair", "POST", JSONObject().put("code", code))); settings.token = result.getString("token"); code = ""; connectionStatus = "Paired and connected"; error = ""; refresh() }
                                    catch (e: Exception) { error = e.message ?: "Pairing failed" }
                                } }, enabled = code.length == 9) { Text("Pair device") }
                                OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { scope.launch { connectionStatus = "Checking…"; connectionStatus = try { settings.base = base.trim(); api.call("/jobs"); "Connected" } catch (e: Exception) { e.message ?: "Connection failed" } } }) { Text("Test connection") }
                            }
                            if (connectionStatus.isNotEmpty()) Text(connectionStatus, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider(); Spacer(Modifier.height(6.dp)); Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        var confirmClear by remember { mutableStateOf(false) }
                        if (!confirmClear) OutlinedButton(shape = MaterialTheme.shapes.small, onClick = { confirmClear = true }) { Text("Clear completed history") }
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmClear = false }) { Text("Keep") }
                            Button(shape = MaterialTheme.shapes.small, onClick = { scope.launch { try { api.call("/jobs", "DELETE"); confirmClear = false; refresh() } catch (e: Exception) { error = e.message.orEmpty() } } }) { Text("Delete history") }
                        }
                        Text("TLDR 0.1.0", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "result" -> Box(Modifier.weight(1f)) {
                        val item = current
                        if (item == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else key(currentId) {
                            if (status == "completed") {
                                val id = item.string("id")
                                CompletedResult(item, selectedTab, { selectedTab = it },
                                    onQuestion = { question, history, claim -> JSONObject(api.call("/jobs/$id/question", "POST", JSONObject().put("question", question).put("history", history).put("claim_context", claim))).string("answer") },
                                    onCheck = { JSONObject(api.call("/jobs/$id/fact-check", "POST")) },
                                    onError = { error = it }, snackbar = snackbar)
                            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(jobMetadata(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.jobTitle(), style = MaterialTheme.typography.headlineSmall)
                                if (status !in terminalStatuses) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(statusLabel(status))
                                    Text("You can leave this screen. Your summary will appear in Library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { scope.launch { try { current = JSONObject(api.call("/jobs/$currentId/cancel", "POST")) } catch (e: Exception) { error = e.message.orEmpty() } } }) { Text("Cancel") }
                                    if (item.optInt("cancel_requested") == 1) Text("Stopping when the current operation can be interrupted.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text(item.string("error").ifBlank { if (status == "cancelled") "Analysis cancelled." else "Analysis failed." })
                                    Button(shape = MaterialTheme.shapes.small, enabled = !busy && currentId != null, onClick = { currentId?.let { retryAnalysis(it) } }) { Text("Retry") }
                                    TextButton(onClick = { confirmDelete = true }) { Text("Delete analysis") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showTranscript && current?.string("status") == "completed") ModalBottomSheet(onDismissRequest = { showTranscript = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(current?.sourceLabel()?.replaceFirstChar { it.uppercase() } ?: "Transcript", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { showTranscript = false }) { Icon(Icons.Default.Close, "Close source") }
                }
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Text(current?.optJSONObject("transcript")?.string("text").orEmpty().ifBlank { "No source text available." }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete analysis?") }, text = { Text("This removes the summary, transcript, and claim report.") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }, confirmButton = { TextButton(onClick = {
                confirmDelete = false; scope.launch { try { api.call("/jobs/$currentId", "DELETE"); current = null; savedJobId = null; page = "library"; refresh() } catch (e: Exception) { error = e.message.orEmpty() } }
            }) { Text("Delete") } })
    }
}

internal fun jobMetadata(job: JSONObject): String {
    val meta = job.optJSONObject("metadata")
    val duration = meta?.optInt("duration", 0) ?: 0
    val platform = meta?.string("extractor", meta.string("platform")).orEmpty()
    val created = try { java.time.LocalDateTime.parse(job.string("created_at"), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { 0L }
    return listOfNotNull(platform.takeIf(String::isNotBlank), if (duration > 0) "%d:%02d".format(duration / 60, duration % 60) else null,
        if (created > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(created)) else null,
        statusLabel(job.string("status"))).joinToString(" · ")
}

internal fun openSource(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme !in listOf("http", "https")) return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { android.widget.Toast.makeText(context, "No browser available", android.widget.Toast.LENGTH_SHORT).show() }
}

