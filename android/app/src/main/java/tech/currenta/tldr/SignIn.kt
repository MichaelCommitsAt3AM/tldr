package tech.currenta.tldr

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

@Composable
internal fun SignInGate(code: String?, clearCode: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val settings = remember { Settings(context) }
    val api = remember { Api(context) }
    var signedIn by remember { mutableStateOf(settings.token.isNotEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
            if (name == "access_session") signedIn = settings.token.isNotEmpty()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        val verifier = prefs.getString("login_verifier", null)
        if (verifier != null && !signedIn) {
            busy = true
            error = ""
            try {
                val result = JSONObject(api.call("/auth/exchange", "POST", JSONObject().put("code", code).put("verifier", verifier)))
                context.stopService(Intent(context, OverlayService::class.java))
                settings.token = result.getString("access_token")
                prefs.edit().remove("login_verifier").apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed. Start again."
            } finally {
                busy = false
            }
        }
        clearCode()
    }
    LaunchedEffect(signedIn) {
        if (signedIn) {
            try {
                api.call("/auth/status")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The API clears rejected sessions. Transient errors leave the session intact.
            }
        }
    }
    if (signedIn) {
        content()
    } else {
        UtilityTheme(settings.theme) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("TL;DR", style = MaterialTheme.typography.headlineMedium)
                    Text("Sign in to access your summaries.")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy, onClick = {
                        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                        val verifier = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, flags)
                        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), flags)
                        prefs.edit().putString("login_verifier", verifier).apply()
                        val url = Uri.parse(settings.base + "/api/v1/auth/browser").buildUpon()
                            .appendQueryParameter("challenge", challenge).build()
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url))
                            error = ""
                        } catch (_: android.content.ActivityNotFoundException) {
                            error = "Install a browser to sign in."
                        }
                    }) { Text(if (busy) "Signing in…" else "Sign in with Cloudflare") }
                }
            }
        }
    }
}
