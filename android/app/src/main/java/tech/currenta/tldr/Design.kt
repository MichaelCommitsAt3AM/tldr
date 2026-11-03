package tech.currenta.tldr

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.view.WindowCompat

/** Shared by Compose and the native overlay so both surfaces use the same palette. */
data class NeutralPalette(val paper: Int, val ink: Int, val muted: Int, val wash: Int, val rule: Int, val primary: Int, val onPrimary: Int)

fun neutralPalette(dark: Boolean) = if (dark) NeutralPalette(
    0xff191919.toInt(), 0xffe8e8e8.toInt(), 0xffababab.toInt(), 0xff282828.toInt(),
    0xff3c3c3c.toInt(), 0xffdedede.toInt(), 0xff202020.toInt()
) else NeutralPalette(
    0xfffafafa.toInt(), 0xff252525.toInt(), 0xff696969.toInt(), 0xfff0f0f0.toInt(),
    0xffdedede.toInt(), 0xff303030.toInt(), 0xffffffff.toInt()
)

@Composable
fun UtilityTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val p = neutralPalette(dark)
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            @Suppress("DEPRECATION")
            window.statusBarColor = p.paper
            @Suppress("DEPRECATION")
            window.navigationBarColor = p.paper
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = Color(p.primary), onPrimary = Color(p.onPrimary),
        primaryContainer = Color(p.wash), onPrimaryContainer = Color(p.ink),
        secondary = Color(p.primary), onSecondary = Color(p.onPrimary),
        secondaryContainer = Color(p.wash), onSecondaryContainer = Color(p.ink),
        tertiary = Color(p.primary), onTertiary = Color(p.onPrimary),
        tertiaryContainer = Color(p.wash), onTertiaryContainer = Color(p.ink),
        background = Color(p.paper), onBackground = Color(p.ink),
        surface = Color(p.paper), onSurface = Color(p.ink),
        surfaceVariant = Color(p.wash), onSurfaceVariant = Color(p.muted),
        surfaceContainer = Color(p.wash), surfaceContainerLow = Color(p.paper),
        surfaceContainerHigh = Color(p.wash), surfaceContainerHighest = Color(p.wash),
        surfaceContainerLowest = Color(p.paper), surfaceBright = Color(p.paper), surfaceDim = Color(p.wash),
        surfaceTint = Color.Transparent, outline = Color(p.muted), outlineVariant = Color(p.rule)
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp)),
        typography = Typography(
            headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp)
        ), content = content
    )
}

internal fun JSONObject.string(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
internal fun JSONObject.jobTitle(): String = optJSONObject("summary")?.string("title")?.takeIf(String::isNotBlank)
    ?: optJSONObject("metadata")?.string("title")?.takeIf(String::isNotBlank) ?: "Video summary"
internal val terminalStatuses = setOf("completed", "failed", "cancelled")
internal fun statusLabel(status: String): String = when (status) {
    "queued" -> "Waiting in queue"; "fetching_metadata" -> "Finding video"; "downloading" -> "Downloading audio"
    "extracting_audio" -> "Preparing audio"; "transcribing" -> "Transcribing speech"; "summarizing" -> "Writing summary"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
internal fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(selected = selected == option, onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size), icon = {}) {
                Text(option, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
