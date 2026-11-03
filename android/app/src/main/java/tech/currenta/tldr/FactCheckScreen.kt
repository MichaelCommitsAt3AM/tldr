package tech.currenta.tldr

import android.net.Uri
import android.text.Html
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun sourceText(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
internal fun compactVerdict(value: String) = when (value) {
    "Misleading / missing context" -> "Missing context"
    "Not independently checkable" -> "Not checkable"
    else -> value.ifBlank { "Unverified" }
}

internal fun checkOverview(claims: List<JSONObject>): String = claims.joinToString("\n\n") { claim ->
    val finding = when (claim.string("verdict")) {
        "Supported" -> "is supported by the available evidence"
        "Contradicted" -> "is contradicted by the available evidence"
        "Misleading / missing context" -> "is misleading or missing context"
        "Not independently checkable" -> "could not be independently checked"
        else -> "remains unverified"
    }
    listOf("The claim “${claim.string("claim")}” $finding.", claim.string("explanation").trim())
        .filter(String::isNotBlank).joinToString(" ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FactCheckContent(report: JSONObject, onAskClaim: (String) -> Unit) {
    val context = LocalContext.current
    val claims = report.optJSONArray("claims")?.objects().orEmpty()
    val checked = (report.optDouble("checked_at", 0.0) * 1000).toLong()
    val reportKey = report.toString()
    var selectedIndex by rememberSaveable(reportKey) { mutableStateOf<Int?>(null) }
    var showAbout by rememberSaveable(reportKey) { mutableStateOf(false) }
    var showDetails by rememberSaveable(reportKey) { mutableStateOf(false) }
    val checkedLabel = if (checked <= 0) "" else if (DateUtils.isToday(checked)) "Today" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(checked))
    Text(listOfNotNull("${claims.size} claim${if (claims.size == 1) "" else "s"} checked", checkedLabel.takeIf { it.isNotBlank() }).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionContainer {
        Text(report.string("overview").ifBlank {
            if (claims.isEmpty()) "No independently checkable claims were identified." else checkOverview(claims)
        },
            style = MaterialTheme.typography.bodyLarge)
    }
    if (claims.isNotEmpty()) OutlinedButton(onClick = { showDetails = true }, shape = MaterialTheme.shapes.small) { Text("View details") }
    if (showDetails) ModalBottomSheet(onDismissRequest = { showDetails = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Check details", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { showDetails = false }) { Icon(Icons.Outlined.Close, "Close check details") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                claims.forEachIndexed { index, claim ->
                    val sources = claim.optJSONArray("sources")?.length() ?: 0
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "View claim details") { showDetails = false; selectedIndex = index }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(claim.string("claim"), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(compactVerdict(claim.string("verdict")), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                                }
                                Text("$sources source${if (sources == 1) "" else "s"}", Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    TextButton(onClick = { showAbout = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("About this check", style = MaterialTheme.typography.bodySmall) }
    selectedIndex?.let { index ->
        val claim = claims.getOrNull(index) ?: return@let
        ModalBottomSheet(onDismissRequest = { selectedIndex = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Claim details", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { selectedIndex = null }) { Icon(Icons.Outlined.Close, "Close claim details") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SelectionContainer { Text(claim.string("claim"), style = MaterialTheme.typography.titleMedium) }
                    Text(claim.string("verdict").ifBlank { "Unverified" }, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(claim.string("explanation"), style = MaterialTheme.typography.bodyLarge) }
                    HorizontalDivider()
                    Text("Sources", style = MaterialTheme.typography.titleMedium)
                    val sources = claim.optJSONArray("sources")?.objects().orEmpty()
                    if (sources.isEmpty()) Text("No linked sources available.", style = MaterialTheme.typography.bodySmall)
                    sources.forEachIndexed { sourceIndex, source ->
                        var showExcerpt by rememberSaveable(reportKey, index, sourceIndex) { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { openSource(context, source.string("url")) }, contentPadding = PaddingValues(0.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(sourceText(source.string("title")).ifBlank { source.string("url") }, style = MaterialTheme.typography.bodyMedium)
                                    Text(Uri.parse(source.string("url")).host.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open source", Modifier.size(18.dp))
                            }
                            if (source.string("excerpt").isNotBlank()) {
                                TextButton(onClick = { showExcerpt = !showExcerpt }, contentPadding = PaddingValues(0.dp)) {
                                    Text(if (showExcerpt) "Hide excerpt" else "Show excerpt", style = MaterialTheme.typography.bodySmall)
                                }
                                if (showExcerpt) SelectionContainer { Text(sourceText(source.string("excerpt")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = { onAskClaim(claim.string("claim")); selectedIndex = null }, shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp)) { Text("Ask about this claim") }
            }
        }
    }
    if (showAbout) ModalBottomSheet(onDismissRequest = { showAbout = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("About this check", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { showAbout = false }) { Icon(Icons.Outlined.Close, "Close check information") }
            }
            Text(report.string("note").ifBlank { "Based on available search results and retrieved pages. Consult the sources for full context." })
            Text("Verdicts apply to individual claims, rather than to the entire video.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (checked > 0) Text("Checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(checked)), style = MaterialTheme.typography.bodySmall)
        }
    }
}
