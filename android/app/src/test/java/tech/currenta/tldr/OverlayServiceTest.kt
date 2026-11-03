package tech.currenta.tldr

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** Exercises the real native popup controls without making any backend requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayServiceTest {
    private lateinit var service: OverlayService
    private lateinit var root: LinearLayout

    @Before fun setup() {
        ShadowSettings.setCanDrawOverlays(true)
        service = Robolectric.buildService(OverlayService::class.java).create().get()
        root = field("root") as LinearLayout
        set("currentJob", "test-job")
        set("job", JSONObject("""{"id":"test-job","status":"completed","summary":{"title":"A test video","short_summary":"A short summary.","key_points":["First point"]}}"""))
        set("report", JSONObject("""{"note":"Check the sources.","claims":[{"claim":"A claim to inspect","verdict":"Unverified","explanation":"Evidence is incomplete.","sources":[{"title":"Research source","url":"https://example.org/research","excerpt":"The available evidence."}]}]}"""))
        set("expanded", true)
        render()
    }
    @After fun cleanup() { service.onDestroy(); shadowOf(Looper.getMainLooper()).idle() }
    private fun field(name: String): Any? = OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
    private fun set(name: String, value: Any?) { OverlayService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value) }
    private fun render() { OverlayService::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(service); shadowOf(Looper.getMainLooper()).idle() }
    private fun views(v: View = root): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun text(label: String): TextView = views().filterIsInstance<TextView>().first { it.text.toString() == label }
    private fun hasText(label: String) = views().filterIsInstance<TextView>().any { it.text.toString() == label }

    @Test fun claimExplanationIsRevealedAndSurvivesMinimize() {
        text("Check").performClick()
        assertFalse(hasText("Evidence is incomplete."))
        text("A claim to inspect").parent.let { (it as View).performClick() }
        assertTrue(hasText("Evidence is incomplete."))
        views().first { it.contentDescription == "Minimize popup" }.performClick()
        assertEquals(false, field("expanded"))
        root.getChildAt(0).performClick()
        assertEquals("Check", field("resultTab"))
        assertTrue(hasText("Evidence is incomplete."))
    }

    @Test fun fullReportOpensTheSameJobOnCheckTab() {
        text("Check").performClick(); text("Open full report").performClick()
        val intent = shadowOf(service).nextStartedActivity
        assertEquals("test-job", intent.getStringExtra("job_id"))
        assertEquals("Check", intent.getStringExtra("result_tab"))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(false, field("expanded"))
    }

    @Test fun sourceOpensTheOriginalPageAndMinimizes() {
        text("Check").performClick()
        (text("A claim to inspect").parent as View).performClick()
        views().filterIsInstance<TextView>().first { it.text.toString().startsWith("Research source") }.performClick()
        val intent = shadowOf(service).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/research", intent.data.toString())
        assertEquals(false, field("expanded"))
    }

    @Test fun copyUsesQuickSummaryAndIgnoresLegacyKeyPoints() {
        assertFalse(hasText("Key points")); assertFalse(hasText("•  First point"))
        views().first { it.contentDescription == "Copy summary" }.performClick()
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("A short summary.", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test fun uncheckedClaimsOfferAnActionAndNewSummaryClearsOldResults() {
        set("report", null); render(); text("Check").performClick()
        assertTrue(hasText("Not independently checked.")); assertTrue(hasText("Check claims"))
        text("New summary").performClick()
        assertNull(field("currentJob")); assertNull(field("report"))
        assertTrue(hasText("Summarize")); assertEquals("Summary", field("resultTab"))
    }
}
