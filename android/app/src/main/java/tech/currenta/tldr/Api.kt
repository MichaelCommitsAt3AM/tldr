package tech.currenta.tldr

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import org.json.JSONArray
import java.security.KeyStore
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var base: String
        get() = prefs.getString("base", "https://tldr.currenta.tech")!!
        set(value) { require(value.startsWith("https://")); prefs.edit().putString("base", value.trimEnd('/')).apply() }
    var theme: String
        get() = prefs.getString("theme", "System")!!
        set(value) { prefs.edit().putString("theme", value).apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("tldr-device", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("tldr-device", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    var token: String
        get() = try {
            val bytes = Base64.decode(prefs.getString("secret", ""), Base64.DEFAULT)
            if (bytes.size < 13) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
        } catch (_: Exception) { "" }
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            prefs.edit().putString("secret", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
        }
}

class Api(private val context: Context) {
    private val settings = Settings(context)
    // Only used when the system resolver fails; no change to phone-wide DNS settings.
    private val fallbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
        return try { Dns.SYSTEM.lookup(hostname) } catch (original: UnknownHostException) {
            try {
                val encoded = URLEncoder.encode(hostname, "UTF-8")
                val connection = URL("https://1.1.1.1/dns-query?name=$encoded&type=A").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000; setRequestProperty("Accept", "application/dns-json")
                }
                val answers = JSONObject(connection.getInputStream().bufferedReader().use { it.readText() }).optJSONArray("Answer") ?: JSONArray()
                val addresses = (0 until answers.length()).mapNotNull { i ->
                    val record = answers.optJSONObject(i) ?: return@mapNotNull null
                    val parts = record.optString("data").split('.')
                    if (record.optInt("type") != 1 || parts.size != 4) return@mapNotNull null
                    val octets = parts.map { it.toIntOrNull() }
                    if (octets.any { it == null || it !in 0..255 }) return@mapNotNull null
                    InetAddress.getByAddress(hostname, octets.map { it!!.toByte() }.toByteArray())
                }
                addresses.ifEmpty { throw original }
            } catch (_: Exception) { throw original }
        }
        }
    }
    private val client = OkHttpClient.Builder().dns(fallbackDns).connectTimeout(15, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    suspend fun call(path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(settings.base + "/api/v1" + path).header("Authorization", "Bearer ${settings.token}")
        when (method) {
            "POST" -> request.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "DELETE" -> request.delete()
        }
        client.newCall(request.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception(try { JSONObject(text).getString("detail") } catch (_: Exception) { "Server error (${response.code})" })
            text
        }
    }
    suspend fun upload(uri: Uri): String = withContext(Dispatchers.IO) {
        val streamBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) { context.contentResolver.openInputStream(uri)?.use { input -> input.copyTo(sink.outputStream()) } ?: error("Cannot read selected file") }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "media", streamBody).build()
        val request = Request.Builder().url(settings.base + "/api/v1/uploads").header("Authorization", "Bearer ${settings.token}").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Exception(try { JSONObject(text).getString("detail") } catch (_: Exception) { "Upload failed (${response.code})" })
            text
        }
    }
}
