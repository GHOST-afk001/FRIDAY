package com.friday.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.sin

/** Live black/green FRIDAY HUD: voice state, waveform, system telemetry and Gemini status. */
class FridayGreenHudView(context: Context) : View(context) {
    interface Actions { fun onGeminiTap(); fun onAssistantTap(); fun onAutomationTap(); fun onOrbTap() }
    var actions: Actions? = null
    private var state = FridayUiState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var frame = 0L
    private var battery = 0
    private var ramPercent = 0
    private var storagePercent = 0
    private var cpuPercent = 0
    private var temp = 0f
    private var gemini = false
    private val ticker = object : Runnable { override fun run() { sample(); frame++; invalidate(); handler.postDelayed(this, 250L) } }

    private val bg = Color.rgb(2, 7, 5)
    private val panel = Color.rgb(4, 16, 11)
    private val panel2 = Color.rgb(5, 22, 15)
    private val grid = Color.rgb(12, 65, 42)
    private val green = Color.rgb(0, 255, 135)
    private val green2 = Color.rgb(35, 190, 105)
    private val dim = Color.rgb(86, 145, 113)
    private val white = Color.rgb(220, 255, 235)

    init { isClickable = true; sample() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(ticker); super.onDetachedFromWindow() }
    fun render(value: FridayUiState) { state = value; invalidate() }
    fun setGeminiReady(value: Boolean) { gemini = value; invalidate() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = max(width / 768f, height / 1376f)
        val x = (event.x - (width - 768f * scale) / 2f) / scale
        val y = (event.y - (height - 1376f * scale) / 2f) / scale
        when {
            x in 250f..518f && y in 465f..700f -> actions?.onOrbTap()
            x in 80f..300f && y in 92f..155f -> actions?.onGeminiTap()
            x < 384f && y > 1180f -> actions?.onAssistantTap()
            x >= 384f && y > 1180f -> actions?.onAutomationTap()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.drawColor(Color.BLACK)
        val scale = max(width / 768f, height / 1376f)
        c.save(); c.scale(scale, scale); c.translate((width / scale - 768f) / 2f, (height / scale - 1376f) / 2f); drawHud(c); c.restore()
    }

    private fun drawHud(c: Canvas) {
        box(c, 0f, 0f, 768f, 1376f, bg, 0f, null)
        box(c, 22f, 20f, 746f, 150f, panel, 22f, grid)
        text(c, "FRIDAY", 48f, 62f, 25f, green, true)
        text(c, "PERSONAL INTELLIGENCE SYSTEM", 49f, 87f, 8f, dim, true)
        text(c, now(), 500f, 67f, 24f, white, true)
        text(c, "SYSTEM ONLINE", 500f, 91f, 8f, green2, true)
        text(c, "GEMINI CORE  •  ${if (gemini) "CONNECTED" else "OFFLINE"}", 49f, 127f, 9f, if (gemini) green else dim, true)
        line(c, 48f, 137f, 720f, 137f, grid, 1f)

        box(c, 40f, 168f, 728f, 680f, panel, 24f, grid)
        text(c, "NEURAL VOICE CORE", 60f, 198f, 13f, white, true)
        text(c, state.stage, 60f, 222f, 9f, green, true)
        drawCore(c, 384f, 470f)
        text(c, if (state.stage.contains("LISTEN", true)) "LISTENING" else if (state.stage.contains("SPEAK", true)) "SPEAKING" else "HANDS-FREE", 323f, 636f, 12f, green, true)
        text(c, short(state.detail), 115f, 661f, 8f, dim, false)
        meter(c, 110f, 690f, 658f, state.audioAmplitude.coerceIn(0f, 1f))
        text(c, "VOICE COMMANDS  •  NO TAP REQUIRED", 198f, 726f, 8f, dim, true)
        text(c, "GEMINI", 60f, 792f, 8f, dim, true); text(c, if (gemini) "BRAIN LIVE" else "ADD API KEY", 125f, 792f, 8f, if (gemini) green else dim, true)

        box(c, 40f, 870f, 365f, 1120f, panel, 18f, grid)
        box(c, 385f, 870f, 728f, 1120f, panel, 18f, grid)
        text(c, "SYSTEM TELEMETRY", 58f, 900f, 12f, white, true)
        metric(c, "BATTERY", "$battery%", 932f, battery / 100f)
        metric(c, "RAM", "$ramPercent%", 972f, ramPercent / 100f)
        metric(c, "STORAGE", "$storagePercent%", 1012f, storagePercent / 100f)
        metric(c, "CPU", "$cpuPercent%", 1052f, cpuPercent / 100f)
        metric(c, "TEMP", if (temp > 0) "%.1f°C".format(Locale.US, temp) else "--", 1092f, 0.5f)

        text(c, "LIVE ACTIVITY", 405f, 900f, 12f, white, true)
        activity(c, "VOICE ENGINE", "${state.stage}  •  ${short(state.detail)}", 935f)
        activity(c, "GEMINI BRAIN", if (gemini) "CONNECTED / READY" else "WAITING FOR KEY", 985f)
        activity(c, "BACKGROUND", "FOREGROUND SERVICE  •  ACTIVE", 1035f)
        activity(c, "ACTIONS", "LOCAL ANDROID EXECUTOR", 1085f)

        text(c, "MIC", 58f, 1180f, 8f, dim, true)
        text(c, "${(state.audioAmplitude * 100).toInt()}%", 90f, 1180f, 8f, green, true)
        meter(c, 58f, 1192f, 710f, state.audioAmplitude.coerceIn(0f, 1f))
        text(c, "●  HANDS-FREE     ●  GEMINI BRAIN     ●  BACKGROUND", 58f, 1230f, 8f, green2, true)
        text(c, "ASSISTANT SETTINGS", 58f, 1270f, 8f, dim, true)
        text(c, "FRIDAY // ALWAYS READY", 58f, 1312f, 9f, green, true)
        text(c, "TAP LEFT: ASSISTANT    •    TAP RIGHT: AUTOMATION", 58f, 1336f, 7f, dim, false)
    }

    private fun drawCore(c: Canvas, cx: Float, cy: Float) {
        val active = state.stage.contains("LISTEN", true) || state.stage.contains("HEARD", true) || state.stage.contains("THINK", true) || state.stage.contains("SPEAK", true) || state.stage.contains("EXECUT", true)
        val pulse = 1f + (if (active) 0.075f else 0.035f) * sin(frame / 6f)
        val radius = 132f * pulse
        for (i in 0..18) { paint.style = Paint.Style.STROKE; paint.strokeWidth = if (i % 4 == 0) 2f else 0.8f; paint.color = if (active) green else green2; paint.alpha = (25 + i * 7).coerceAtMost(150); c.drawCircle(cx, cy, radius * (0.34f + i / 29f), paint) }
        paint.alpha = 255; paint.style = Paint.Style.FILL; paint.color = Color.rgb(1, 13, 8); c.drawCircle(cx, cy, 72f * pulse, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = green; c.drawCircle(cx, cy, 73f * pulse, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until 80) { val a = (i * 4.5f + frame * if (active) 1.8f else 0.6f); val rad = Math.toRadians(a.toDouble()); val rr = radius * (0.72f + (i % 7) / 18f); c.drawCircle(cx + kotlin.math.cos(rad).toFloat() * rr, cy + kotlin.math.sin(rad).toFloat() * rr, if (i % 6 == 0) 2.2f else 1f, paint.apply { color = if (i % 6 == 0) green else green2 }) }
    }

    private fun metric(c: Canvas, name: String, value: String, y: Float, amount: Float) { text(c, name, 58f, y, 9f, dim, true); text(c, value, 270f, y, 9f, white, true); meter(c, 58f, y + 10f, 345f, amount.coerceIn(0f, 1f)) }
    private fun activity(c: Canvas, title: String, detail: String, y: Float) { text(c, "●", 405f, y, 10f, green, true); text(c, title, 424f, y, 8f, white, true); text(c, short(detail), 424f, y + 18f, 7f, dim, false) }
    private fun meter(c: Canvas, left: Float, y: Float, right: Float, amount: Float) { box(c, left, y, right, y + 7f, Color.rgb(7, 30, 20), 4f, null); box(c, left, y, left + (right - left) * amount, y + 7f, green, 4f, null) }
    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float, stroke: Int?) { paint.style = if (stroke == null) Paint.Style.FILL else Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = stroke ?: color; paint.alpha = 255; c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint); paint.style = Paint.Style.FILL }
    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) { paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.color = color; c.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) { paint.style = Paint.Style.FILL; paint.color = color; paint.alpha = 255; paint.textSize = size; paint.typeface = android.graphics.Typeface.create("sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL); c.drawText(value, x, y, paint) }
    private fun short(value: String): String = value.replace("\n", " ").take(48)
    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun sample() {
        battery = runCatching { val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager; bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100) }.getOrDefault(battery)
        val am = runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager }.getOrNull()
        if (am != null) { val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); ramPercent = ((1f - mi.availMem.toFloat() / mi.totalMem.toFloat()) * 100f).toInt().coerceIn(0, 100) }
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path); storagePercent = (100f - stat.availableBytes.toFloat() / stat.totalBytes.toFloat() * 100f).toInt().coerceIn(0, 100)
        temp = runCatching { val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)); (i?.getIntExtra("temperature", 0) ?: 0) / 10f }.getOrDefault(temp)
        cpuPercent = runCatching { val first = java.io.File("/proc/stat").bufferedReader().use { it.readLine() }; val p = first.trim().split(Regex("\\s+")); val total = p.drop(1).take(7).sumOf { it.toLongOrNull() ?: 0L }; val idle = p.getOrNull(4)?.toLongOrNull() ?: 0L; ((total - idle).toDouble() / total.coerceAtLeast(1) * 100.0).toInt().coerceIn(0, 100) }.getOrDefault(cpuPercent)
        gemini = runCatching { !SecureApiKeyStore(context.applicationContext).read().isNullOrBlank() }.getOrDefault(gemini)
    }
}
