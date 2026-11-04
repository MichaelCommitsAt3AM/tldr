package tech.currenta.tldr

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.*
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    companion object { @Volatile var isRunning = false; private set }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var manager: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private val preferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme") render()
        if (key == "access_session" && Settings(this).token.isEmpty()) {
            needsSignIn = true
            render()
        }
    }
    private var dismissTarget: TextView? = null
    private var expanded = false
    private var currentJob: String? = null
    private var job: JSONObject? = null
    private var report: JSONObject? = null
    private var link = ""
    private var status = ""
    private var error = ""
    private var processing = false
    private var checking = false
    private var cancelling = false
    private var needsSignIn = false
    private var summaryReady = false
    private var completionPulse: ObjectAnimator? = null
    private var resultTab = "Summary"
    private val expandedClaims = mutableSetOf<Int>()
    private var task: Job? = null
    private val dark get() = when (preferences.getString("theme", "System")) {
        "Dark" -> true; "Light" -> false
        else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val palette get() = neutralPalette(dark)
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int = 8, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun label(value: String, size: Float = 14f, muted: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (muted) palette.muted else palette.ink)
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun action(text: String, primary: Boolean = false, onClick: () -> Unit) = label(text, bold = primary).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(10), dp(4), dp(10), dp(4))
        setTextColor(if (primary) palette.onPrimary else palette.ink)
        background = shape(if (primary) palette.primary else palette.paper, stroke = if (primary) null else palette.rule)
        isFocusable = true; setOnClickListener { onClick() }
    }
    private fun iconAction(resource: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(palette.ink)
        background = shape(palette.paper); setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description; setOnClickListener { onClick() }
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun add(container: LinearLayout, view: View, space: Int = 12) {
        container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(space) })
    }
    private fun divider(container: LinearLayout) { add(container, View(this).apply { setBackgroundColor(palette.rule) }); container.getChildAt(container.childCount - 1).layoutParams.height = dp(1) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("overlay", "Floating shortcut", NotificationManager.IMPORTANCE_LOW))
        startForeground(11, Notification.Builder(this, "overlay").setContentTitle("TLDR floating shortcut is active").setSmallIcon(R.drawable.ic_quick_tile).setOngoing(true).build())
        if (!AndroidSettings.canDrawOverlays(this)) { stopSelf(); return }
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        root = column().apply { elevation = dp(4).toFloat() }
        params = WindowManager.LayoutParams(dp(52), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(180)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        manager.addView(root, params)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        isRunning = true; OverlayTileService.refresh(this); render()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (::root.isInitialized) render() }
    private fun setExpanded(value: Boolean) {
        expanded = value
        if (value) summaryReady = false
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if (value) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        render()
    }
    private fun showTarget() {
        if (dismissTarget != null) return
        val target = label("×", 30f).apply { gravity = Gravity.CENTER; setTextColor(palette.onPrimary); background = shape(palette.primary, 40); contentDescription = "Drag here to close TLDR" }
        val targetParams = WindowManager.LayoutParams(dp(64), dp(64), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(54) }
        manager.addView(target, targetParams); dismissTarget = target
    }
    private fun hideTarget() { dismissTarget?.let { manager.removeView(it) }; dismissTarget = null }
    private fun isOverTarget(x: Float, y: Float): Boolean {
        val target = dismissTarget ?: return false
        val pos = IntArray(2); target.getLocationOnScreen(pos)
        return hypot(x - (pos[0] + target.width / 2f), y - (pos[1] + target.height / 2f)) < dp(58)
    }
    private fun draggable(view: View) {
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && hypot(dx, dy) > dp(8)) { dragging = true; showTarget() }
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, max(0, resources.displayMetrics.widthPixels - root.width))
                        params.y = (startY + dy.toInt()).coerceIn(0, max(0, resources.displayMetrics.heightPixels - root.height - dp(40)))
                        manager.updateViewLayout(root, params)
                        dismissTarget?.alpha = if (isOverTarget(event.rawX, event.rawY)) 1f else .7f
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    val dismiss = dragging && isOverTarget(event.rawX, event.rawY); hideTarget()
                    if (dismiss) stopSelf() else if (!dragging) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { hideTarget(); true }
                else -> false
            }
        }
        view.setOnClickListener { setExpanded(!expanded) }
    }
    private fun render() = render(pulse = false)

    private fun render(pulse: Boolean) {
        if (!::root.isInitialized) return
        completionPulse?.cancel()
        completionPulse = null
        root.scaleX = 1f
        root.scaleY = 1f
        val oldScroll = root.findViewWithTag<ScrollView>("content")?.scrollY ?: 0
        root.removeAllViews()
        if (!expanded) {
            root.setPadding(0, 0, 0, 0); root.background = shape(palette.primary, 40); params.width = dp(52)
            val bubble = ImageView(this).apply {
                setImageResource(R.drawable.ic_quick_tile)
                setColorFilter(palette.onPrimary)
                setPadding(dp(13), dp(13), dp(13), dp(13))
                contentDescription = when {
                    checking -> "TLDR: checking claims"
                    processing -> "TLDR: processing"
                    summaryReady -> "TLDR: summary ready. Tap to open."
                    else -> "Open or drag TLDR"
                }
            }
            root.addView(bubble, LinearLayout.LayoutParams(dp(52), dp(52))); draggable(bubble)
            clampAndUpdate()
            if (pulse) {
                completionPulse = ObjectAnimator.ofPropertyValuesHolder(
                    root,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, .78f, 1f, .78f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, .78f, 1f, .78f, 1f),
                ).apply {
                    duration = 1100L
                    start()
                }
            }
            return
        }
        params.width = min(dp(360), resources.displayMetrics.widthPixels - dp(24))
        root.background = shape(palette.paper, 12, palette.rule); root.setPadding(dp(16), dp(8), dp(16), dp(16))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = label("TL;DR", 16f, bold = true).apply { gravity = Gravity.CENTER_VERTICAL; contentDescription = "Drag to move, tap to minimize" }
        header.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f)); draggable(title)
        header.addView(action("−") { setExpanded(false) }.apply { contentDescription = "Minimize popup" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(action("×") { stopSelf() }.apply { contentDescription = "Close shortcut" }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(6) })
        root.addView(header)
        val content = column()
        val maxHeight = max(dp(140), min(dp(480), resources.displayMetrics.heightPixels - dp(220)))
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) maxHeight else min(maxHeight, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply { tag = "content"; isFillViewport = false; addView(content); isVerticalScrollBarEnabled = true }
        root.addView(scroll)
        if (error.isNotBlank()) add(content, label(error, 13f))
        when {
            needsSignIn -> add(content, action("Sign in to TLDR", true) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                stopSelf()
            })
            processing -> {
                add(content, label(status, bold = true)); progress(content)
                add(content, label("You can minimize this and continue browsing.", 12f, true))
                add(content, action(if (cancelling) "Stopping…" else "Cancel") { cancelAnalysis() }.apply { isEnabled = currentJob != null && !cancelling })
            }
            job?.string("status") == "completed" -> renderResult(content)
            job != null -> {
                add(content, label(status.ifBlank { "Analysis stopped" }))
                add(content, action(if (job?.string("status") in terminalStatuses) "Retry" else "Resume processing", true) { retryAnalysis() }); add(content, action("New summary") { newSummary() })
            }
            else -> renderInput(content)
        }
        clampAndUpdate()
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }
    private fun clampAndUpdate() {
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, max(0, metrics.widthPixels - params.width))
        params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - (if (expanded) min(dp(600), metrics.heightPixels - dp(80)) else dp(80))))
        manager.updateViewLayout(root, params)
    }
    private fun progress(container: LinearLayout) { add(container, ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(palette.primary) }) }
    private fun renderInput(container: LinearLayout) {
        add(container, label("Link or text", 12f, true))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = shape(palette.paper, 8, palette.muted) }
        val input = EditText(this).apply {
            hint = "Paste a link or text"; textSize = 16f; setTextColor(palette.ink); setHintTextColor(palette.muted)
            background = null; setPadding(dp(12), dp(8), dp(4), dp(8)); setText(link)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2; maxLines = 5
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { link = s.toString() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(action("Paste") { input.setText(getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()) })
        add(container, row, 6); add(container, action("Summarize", true) { analyze() })
    }
    private fun renderResult(container: LinearLayout) {
        val tabs = LinearLayout(this)
        listOf("Summary", "Check").forEach { tab ->
            tabs.addView(action(tab, resultTab == tab) { resultTab = tab; root.findViewWithTag<ScrollView>("content")?.scrollTo(0, 0); render() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (tab == "Check") leftMargin = dp(6) })
        }
        add(container, tabs)
        if (resultTab == "Check") {
            renderCheck(container)
        } else {
            add(container, label(job?.jobTitle().orEmpty(), 15f, bold = true))
            val summary = job?.optJSONObject("summary")
            val summaryText = summary?.string("short_summary")?.ifBlank { summary.string("summary") }.orEmpty()
            add(container, label(summaryText).apply { setTextIsSelectable(true) })
            val actions = LinearLayout(this)
            actions.addView(iconAction(R.drawable.ic_copy, "Copy summary") { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Summary", summaryText)); Toast.makeText(this, "Summary copied", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            actions.addView(iconAction(R.drawable.ic_share, "Share summary") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, summaryText)
                startActivity(Intent.createChooser(send, "Share summary").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); setExpanded(false)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
            if (job?.string("url")?.isNotBlank() == true) actions.addView(iconAction(R.drawable.ic_external_link, "Open original source") {
                openSource(this, job?.string("url").orEmpty()); setExpanded(false)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
            add(container, actions); add(container, action("Open summary") { openFull("Summary") })
        }
        add(container, action("New summary") { newSummary() }.apply { isEnabled = !checking })
    }
    private fun renderCheck(container: LinearLayout) {
        add(container, label("Check the claims", 15f, bold = true))
        if (checking) { add(container, label("Checking claims. You can minimize this popup.", 12f, true)); progress(container); return }
        val fact = report
        if (fact == null) {
            if (job?.optJSONObject("summary")?.optJSONArray("important_claims")?.length() == 0) {
                add(container, label("No factual claims to check were identified."))
                add(container, action("Open summary") { openFull("Summary") })
                return
            }
            add(container, label("Compare claims with external sources.", 12f, true))
            add(container, action("Check claims", true) { checkClaims() })
            add(container, label("Not independently checked.", 12f, true)); return
        }
        add(container, label(fact.string("note"), 12f, true))
        if (fact.optDouble("checked_at", 0.0) > 0) add(container, label("Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((fact.optDouble("checked_at") * 1000).toLong())), 12f, true))
        fact.optJSONArray("claims")?.objects()?.forEachIndexed { index, claim ->
            val count = claim.optJSONArray("sources")?.length() ?: 0
            val toggle = column().apply { setPadding(0, dp(10), 0, dp(10)); isFocusable = true; isClickable = true }
            toggle.addView(label(claim.string("claim"), bold = true))
            add(toggle, label("${claim.string("verdict")} · $count source${if (count == 1) "" else "s"} · ${if (index in expandedClaims) "Hide" else "Details"}", 12f, true), 6)
            toggle.contentDescription = "${claim.string("claim")}. ${claim.string("verdict")}. ${if (index in expandedClaims) "Collapse" else "Expand"} claim"
            toggle.setOnClickListener { if (!expandedClaims.add(index)) expandedClaims.remove(index); render() }
            add(container, toggle)
            if (index in expandedClaims) {
                add(container, label(claim.string("explanation")).apply { setTextIsSelectable(true) })
                claim.optJSONArray("sources")?.objects()?.forEach { source ->
                    val host = UriHost(source.string("url"))
                    add(container, action(source.string("title").ifBlank { host } + "\n" + host + " ↗") { openSource(this, source.string("url")); setExpanded(false) })
                    if (source.string("excerpt").isNotBlank()) add(container, label(source.string("excerpt"), 12f, true))
                }
                if (count == 0) add(container, label("No linked sources available.", 12f, true))
            }
            divider(container)
        }
        add(container, action("Open full report") { openFull("Check") })
    }
    private fun UriHost(url: String) = android.net.Uri.parse(url).host.orEmpty()
    private fun openFull(tab: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("job_id", currentJob).putExtra("result_tab", tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        setExpanded(false)
    }
    private fun newSummary() { if (checking) return; job = null; currentJob = null; report = null; link = ""; error = ""; status = ""; resultTab = "Summary"; expandedClaims.clear(); render() }
    private fun analyze() {
        if (processing) return
        if (link.isBlank()) { error = "Paste a link or text first."; render(); return }
        launchAnalysis { Api(this).call("/jobs", "POST", JSONObject().put("input", link.trim())) }
    }
    private fun retryAnalysis() {
        val id = currentJob ?: return
        launchAnalysis { if (job?.string("status") in terminalStatuses) Api(this).call("/jobs/$id/retry", "POST") else Api(this).call("/jobs/$id") }
    }
    private fun launchAnalysis(create: suspend () -> String) {
        summaryReady = false
        processing = true; cancelling = false; error = ""; status = "Starting analysis…"; report = null; expandedClaims.clear(); render()
        task = scope.launch {
            try {
                job = JSONObject(create()); currentJob = job?.string("id")
                while (job?.string("status") !in terminalStatuses) {
                    status = statusLabel(job?.string("status").orEmpty()); if (expanded) render()
                    delay(2000); job = JSONObject(Api(this@OverlayService).call("/jobs/$currentJob"))
                }
                report = job?.optJSONObject("fact_check")
                status = job?.string("error").orEmpty().ifBlank { statusLabel(job?.string("status").orEmpty()) }
                resultTab = "Summary"
                summaryReady = !expanded && job?.string("status") == "completed"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Connection failed. Try again." }
            finally { processing = false; if (scope.isActive) render(pulse = summaryReady) }
        }
    }
    private fun cancelAnalysis() {
        val id = currentJob ?: return
        if (cancelling) return
        cancelling = true; render()
        scope.launch {
            try { job = JSONObject(Api(this@OverlayService).call("/jobs/$id/cancel", "POST")); status = "Stopping when the current operation can be interrupted." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not cancel"; cancelling = false }
            if (expanded) render()
        }
    }
    private fun checkClaims() {
        val id = currentJob ?: return
        if (checking) return
        checking = true; error = ""; render()
        scope.launch {
            try { val result = JSONObject(Api(this@OverlayService).call("/jobs/$id/fact-check", "POST")); if (currentJob == id) { report = result; job?.put("fact_check", result) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (currentJob == id) error = e.message ?: "Fact-check failed. Try again." }
            finally { checking = false; if (expanded) render() }
        }
    }
    override fun onDestroy() {
        completionPulse?.cancel()
        scope.cancel(); preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        if (::manager.isInitialized) { hideTarget(); if (::root.isInitialized) manager.removeView(root) }
        isRunning = false; OverlayTileService.refresh(this); super.onDestroy()
    }
}
