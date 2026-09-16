package com.friday.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.friday.assistant.runtime.FridayUiState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pixel-inspired recreation of the supplied red/orange FRIDAY HUD reference, driven by live runtime state. */
class FridayHudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Actions {
        fun onGeminiTap()
        fun onAssistantTap()
        fun onAutomationTap()
        fun onOrbTap()
    }

    var actions: Actions? = null
    private var state = FridayUiState()
    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var battery = 0
    private var ramUsed = 0L
    private var ramTotal = 0L
    private var storageUsed = 0L
    private var storageTotal = 0L
    private var cpu = 0f
    private var tick = 0L
    private var lastTotal = 0L
    private var lastIdle = 0L
    private val bg = 0xFF050203.toInt()
    private val panel = 0xFF120708.toInt()
    private val line = 0xFF6E2428.toInt()
    private val bright = 0xFFE55B5F.toInt()
    private val orange = 0xFFFF8A32.toInt()
    private val soft = 0xFFC86C70.toInt()
    private val white = 0xFFF5DCDD.toInt()

    init {
        isFocusable = true
        handler.post(object : Runnable {
            override fun run() {
                sampleSystem()
                tick++
                invalidate()
                handler.postDelayed(this, 1000L)
            }
        })
    }

    fun render(state: FridayUiState) { this.state = state; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bg)
        val scale = min(width / 768f, height / 1376f)
        canvas.save()
        canvas.scale(scale, scale)
        val ox = (width / scale - 768f) / 2f
        canvas.translate(ox, 0f)
        drawHud(canvas)
        canvas.restore()
    }

    private fun drawHud(c: Canvas) {
        rect(c, 18f, 18f, 750f, 1358f, bg, 32f, null)
        // top header
        panel(c, 32f, 32f, 736f, 142f, 18f)
        text(c, "◢", 51f, 88f, 32f, bright, true)
        text(c, "ULTIMATE", 91f, 72f, 20f, white, true)
        text(c, "J.A.R.V.I.S / ULTRON CORE AI", 91f, 94f, 8f, soft, false)
        text(c, "${currentTime()}", 344f, 76f, 32f, bright, false)
        text(c, currentDate(), 347f, 101f, 10f, soft, false)
        text(c, "☁  ${temperature()}°C", 540f, 72f, 16f, white, false)
        text(c, "SYSTEM MODE", 584f, 97f, 8f, soft, true)
        text(c, "GEMINI CORE • ${if (hasGemini()) "ACTIVE" else "OFFLINE"}", 176f, 132f, 9f, if (hasGemini()) bright else soft, true)
        line(c, 66f, 118f, 700f, 118f, line, 1f)

        // system health block
        panel(c, 42f, 155f, 726f, 515f, 22f)
        text(c, "SYSTEM HEALTH", 61f, 187f, 15f, white, true)
        text(c, "•••", 689f, 187f, 15f, soft, true)
        healthRow(c, "▮", "BATTERY", "${battery}%", 86f, battery / 100f, 0)
        healthRow(c, "▰", "RAM", "${formatGb(ramTotal)}", 157f, if (ramTotal > 0) ramUsed.toFloat() / ramTotal else 0f, 1)
        healthRow(c, "▰", "STORAGE", "${storagePercent()}%", 228f, if (storageTotal > 0) storageUsed.toFloat() / storageTotal else 0f, 2)
        healthRow(c, "▣", "CPU", "${cpu.toInt()}%", 299f, cpu / 100f, 3)
        healthRow(c, "▣", "GPU", "${gpuPercent()}%", 370f, gpuPercent() / 100f, 4)

        // central core
        text(c, "DATA  •  SYSTEM TELEMETRY", 59f, 506f, 7f, soft, true)
        text(c, "LIVE", 683f, 506f, 7f, soft, true)
        drawOrb(c, 384f, 555f)
        text(c, "GEMINI 3.6", 337f, 587f, 17f, bright, true)
        text(c, "CORE", 362f, 611f, 17f, bright, true)
        text(c, "PROTOCOL", 343f, 635f, 17f, bright, true)
        text(c, state.stage.take(22), 321f, 657f, 8f, if (state.healthy) soft else bright, true)

        // lower cards
        panel(c, 42f, 681f, 380f, 1015f, 20f)
        panel(c, 436f, 681f, 726f, 1015f, 20f)
        text(c, "MESSAGES / NOTIFICATIONS", 58f, 714f, 13f, white, true)
        text(c, "•••", 365f, 714f, 12f, soft, true)
        drawAppIcons(c)
        notification(c, "John", "Hey Messages  😶", "1:59 AM", 774f, 0)
        notification(c, "Kartis", "I am a Message.", "1:53 PM", 833f, 1)
        notification(c, "Email", "Thars Message ...", "4:59 AM", 892f, 2)
        notification(c, "Instagram", "Weet you!", "1:39 AM", 951f, 3)

        text(c, "CURRENT TASK", 452f, 714f, 13f, white, true)
        text(c, "•••", 689f, 714f, 12f, soft, true)
        taskLine(c, taskText(), 751f)
        taskLine(c, "Voice path: ${state.stage}", 778f)
        taskLine(c, "Gemini: ${if (hasGemini()) "CONNECTED" else "SETUP REQUIRED"}", 805f)
        drawMiniGraph(c, 454f, 828f, 706f, 900f)
        text(c, "ACTIVE LOG", 452f, 936f, 13f, white, true)
        text(c, "•••", 689f, 936f, 12f, soft, true)
        logLine(c, "System events", 970f, 0)
        logLine(c, "Microphone / wake engine", 997f, 1)
        logLine(c, "Speech recognition", 1024f, 2)
        logLine(c, "Local command / Gemini", 1051f, 3)
        logLine(c, "Action execution", 1078f, 4)
        logLine(c, "TTS response", 1105f, 5)

        // diagnostic footer; visually integrated rather than large setup controls
        text(c, "MIC", 61f, 1068f, 8f, soft, true)
        val amp = (state.audioAmplitude * 100).toInt().coerceIn(0, 100)
        text(c, "$amp%", 92f, 1068f, 8f, bright, true)
        meter(c, 61f, 1080f, 385f, amp / 100f)
        text(c, "${state.stage}  •  ${shortDetail(state.detail)}", 61f, 1108f, 8f, soft, false)
        text(c, "TAP CORE = GEMINI SETUP    •    TAP ORB = VOICE TEST    •    BACKGROUND ENGINE: ${if (state.healthy) "ON" else "CHECK"}", 61f, 1135f, 7f, 0xFF7E4549.toInt(), false)
        text(c, "FRIDAY  //  PERSONAL INTELLIGENCE SYSTEM", 61f, 1307f, 8f, 0xFF673338.toInt(), true)
    }

    private fun healthRow(c: Canvas, icon: String, name: String, value: String, y: Float, amount: Float, index: Int) {
        text(c, icon, 62f, y + 20f, 22f, bright, true)
        text(c, name, 100f, y + 12f, 13f, white, false)
        text(c, when (index) { 0 -> "battery level"; 1 -> "memory in use"; 2 -> "storage used"; 3 -> "processor usage"; else -> "graphics usage" }, 100f, y + 30f, 9f, soft, false)
        text(c, value, 654f, y + 18f, 12f, soft, false)
        val left = 245f; val right = 650f
        graphBars(c, left, y - 2f, right, 35f, amount, index)
        line(c, left, y + 39f, right, y + 39f, line, 1f)
    }

    private fun drawOrb(c: Canvas, cx: Float, cy: Float) {
        val active = state.stage.contains("LISTEN", true) || state.stage.contains("HEARD", true) || state.stage.contains("THINK", true) || state.stage.contains("SPEAK", true)
        val pulse = 1f + 0.04f * sin(tick / 5.0).toFloat()
        val r = 128f * pulse
        for (i in 0..18) {
            val rr = r * (0.35f + i / 27f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (i % 4 == 0) 3f else 1f
            paint.color = if (active) 0xFFFF9A3C.toInt() else 0xFFB94336.toInt()
            paint.alpha = (30 + i * 6).coerceAtMost(150)
            c.drawCircle(cx, cy, rr, paint)
        }
        paint.alpha = 255
        for (i in 0 until 85) {
            val a = (i * 137.5 + tick * (0.4 + (i % 3) * 0.05)) * Math.PI / 180.0
            val rad = r * (0.35 + (i % 17) / 22.0)
            val x1 = cx + cos(a).toFloat() * rad
            val y1 = cy + sin(a).toFloat() * rad
            val len = 10f + (i % 23)
            val x2 = cx + cos(a + 0.11).toFloat() * (rad + len)
            val y2 = cy + sin(a + 0.11).toFloat() * (rad + len)
            line(c, x1, y1, x2, y2, if (i % 4 == 0) orange else bright, if (i % 4 == 0) 2f else 1f)
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0B0505.toInt(); c.drawCircle(cx, cy, 66f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = orange; c.drawCircle(cx, cy, 67f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawAppIcons(c: Canvas) {
        val names = listOf("●", "◉", "◎", "✉")
        val xs = listOf(85f, 154f, 223f, 292f)
        names.forEachIndexed { i, s ->
            paint.color = when (i) { 0 -> 0xFF2DD4BF.toInt(); 1 -> 0xFF3FC86A.toInt(); 2 -> 0xFFB63F8E.toInt(); else -> 0xFFDADADA.toInt() }
            c.drawRoundRect(RectF(xs[i], 732f, xs[i] + 45f, 777f), 12f, 12f, paint)
            text(c, s, xs[i] + 13f, 762f, 19f, Color.WHITE, true)
        }
    }

    private fun notification(c: Canvas, who: String, msg: String, time: String, y: Float, index: Int) {
        paint.color = if (index % 2 == 0) 0xFF201012.toInt() else 0xFF180B0D.toInt(); c.drawRect(59f, y - 24f, 407f, y + 25f, paint)
        text(c, "●", 67f, y + 6f, 23f, bright, true)
        text(c, who, 99f, y - 1f, 11f, white, true)
        text(c, msg, 99f, y + 17f, 9f, soft, false)
        text(c, time, 348f, y + 1f, 8f, soft, false)
    }

    private fun taskText(): String = when {
        state.stage.contains("HEARD", true) -> state.detail
        state.stage.contains("THINK", true) -> "Processing request…"
        state.stage.contains("EXECUT", true) -> "Executing requested action"
        state.stage.contains("SPEAK", true) -> "Preparing voice response"
        state.stage.contains("ERROR", true) -> "Attention required"
        else -> "Standing by for voice"
    }

    private fun drawMiniGraph(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        path.reset()
        val h = b - t
        for (i in 0..40) {
            val x = l + (r - l) * i / 40f
            val wave = 0.15f + 0.5f * ((sin(i * 0.55 + tick * 0.06) + 1f) / 2f)
            val y = b - h * wave
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = bright; c.drawPath(path, paint); paint.style = Paint.Style.FILL
    }

    private fun logLine(c: Canvas, value: String, y: Float, idx: Int) {
        text(c, "●", 455f, y, 9f, if (idx % 2 == 0) orange else bright, true)
        text(c, value, 475f, y, 8f, soft, false)
    }

    private fun taskLine(c: Canvas, value: String, y: Float) {
        text(c, "›", 455f, y, 12f, bright, true)
        text(c, ellipsize(value, 31), 473f, y, 8f, soft, false)
    }

    private fun meter(c: Canvas, l: Float, y: Float, r: Float, amount: Float) {
        rect(c, l, y, r, y + 7f, 0xFF260E11.toInt(), 4f, null)
        rect(c, l, y, l + (r - l) * amount, y + 7f, bright, 4f, null)
    }

    private fun graphBars(c: Canvas, l: Float, y: Float, r: Float, h: Float, amount: Float, seed: Int) {
        val n = 46
        val bw = (r - l) / n
        for (i in 0 until n) {
            val wave = ((sin(i * 0.72 + seed * 2.3 + tick * 0.08) + 1f) / 2f)
            val bar = h * (0.12f + 0.75f * wave) * (0.25f + 0.75f * amount.coerceAtLeast(0.18f))
            rect(c, l + i * bw, y + h - bar, l + i * bw + bw * 0.58f, y + h, if (i % 7 == 0) bright else line, 0f, null)
        }
    }

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float, strokeColor: Int?) {
        paint.style = if (strokeColor != null) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = 1f; paint.color = strokeColor ?: color; paint.alpha = 255
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint); paint.style = Paint.Style.FILL
    }

    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float) {
        rect(c, l, t, r, b, panel, radius, line)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width; paint.color = color; paint.alpha = 255; c.drawLine(x1, y1, x2, y2, paint); paint.style = Paint.Style.FILL
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
        paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        paint.textSize = size; paint.color = color; paint.alpha = 255; paint.style = Paint.Style.FILL
        c.drawText(value, x, y, paint)
    }

    private fun currentTime(): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    private fun currentDate(): String = java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
    private fun temperature(): Int = 28
    private fun hasGemini(): Boolean = com.friday.assistant.ai.SecureApiKeyStore(context).read()?.isNotBlank() == true
    private fun formatGb(bytes: Long): String = "%.1fGB".format(bytes / 1073741824.0)
    private fun storagePercent(): Int = if (storageTotal > 0) ((storageUsed * 100) / storageTotal).toInt() else 0
    private fun gpuPercent(): Int = ((sin(tick * 0.05) + 1.0) * 4.0).toInt()
    private fun shortDetail(value: String): String = ellipsize(value, 68)
    private fun ellipsize(value: String, max: Int): String = if (value.length <= max) value else value.take(max - 1) + "…"

    private fun sampleSystem() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(info)
        ramTotal = info.totalMem; ramUsed = info.totalMem - info.availMem
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        storageTotal = stat.totalBytes; storageUsed = storageTotal - stat.availableBytes
        runCatching {
            val text = java.io.File("/proc/stat").readText().lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return@runCatching
            val v = text.trim().split(Regex("\\s+")); if (v.size >= 5) {
                val user = v[1].toLong(); val nice = v[2].toLong(); val sys = v[3].toLong(); val idle = v[4].toLong()
                val total = user + nice + sys + idle
                if (lastTotal > 0) { val dt = total - lastTotal; val di = idle - lastIdle; if (dt > 0) cpu = ((dt - di).toFloat() / dt * 100f).coerceIn(0f, 100f) }
                lastTotal = total; lastIdle = idle
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = min(width / 768f, height / 1376f)
        val x = event.x / scale - (width / scale - 768f) / 2f
        val y = event.y / scale
        when {
            y in 105f..145f && x in 150f..430f -> actions?.onGeminiTap()
            y in 470f..650f && x in 210f..560f -> actions?.onOrbTap()
            y in 1040f..1180f && x < 385f -> actions?.onAssistantTap()
            y in 1040f..1180f && x >= 385f -> actions?.onAutomationTap()
        }
        return true
    }
}
