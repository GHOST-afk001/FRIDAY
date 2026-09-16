package com.friday.assistant

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.View
import com.friday.assistant.runtime.FridayUiState
import kotlin.math.cos
import kotlin.math.sin

/** Live canvas HUD built around the supplied 768x1376 reference layout. */
class FridayReferenceHudView(context: Context) : View(context) {
    interface Actions { fun onGeminiTap(); fun onAssistantTap(); fun onAutomationTap(); fun onOrbTap() }
    var actions: Actions? = null
    private var state = FridayUiState()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0L
    private var battery = 0
    private var ramUsed = 0L
    private var ramTotal = 0L
    private var storageUsed = 0L
    private var storageTotal = 0L
    private var cpu = 0f
    private val bg = Color.rgb(5, 2, 3)
    private val panel = Color.rgb(18, 7, 8)
    private val line = Color.rgb(110, 36, 40)
    private val bright = Color.rgb(229, 91, 95)
    private val orange = Color.rgb(255, 138, 50)
    private val soft = Color.rgb(200, 108, 112)
    private val white = Color.rgb(245, 220, 221)

    init {
        isClickable = true
        handler.post(object : Runnable {
            override fun run() { sample(); tick++; invalidate(); handler.postDelayed(this, 1000L) }
        })
    }

    fun render(value: FridayUiState) { state = value; invalidate() }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action != android.view.MotionEvent.ACTION_UP) return true
        val sx = width / 768f
        val sy = height / 1376f
        val x = event.x / sx
        val y = event.y / sy
        when {
            y in 500f..680f -> actions?.onOrbTap()
            y in 110f..150f && x in 150f..470f -> actions?.onGeminiTap()
            y in 1170f..1250f && x < 380f -> actions?.onAssistantTap()
            y in 1170f..1250f && x >= 380f -> actions?.onAutomationTap()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(bg)
        val scale = minOf(width / 768f, height / 1376f)
        c.save(); c.scale(scale, scale)
        val ox = (width / scale - 768f) / 2f
        c.translate(ox, 0f)
        drawHud(c)
        c.restore()
    }

    private fun drawHud(c: Canvas) {
        box(c, 18f, 18f, 750f, 1358f, bg, 32f, null)
        box(c, 32f, 32f, 736f, 142f, panel, 18f, line)
        text(c, "◢", 51f, 88f, 32f, bright, true)
        text(c, "ULTIMATE", 91f, 72f, 20f, white, true)
        text(c, "J.A.R.V.I.S / ULTRON CORE AI", 91f, 94f, 8f, soft, false)
        text(c, timeNow(), 344f, 76f, 32f, bright, false)
        text(c, dateNow(), 347f, 101f, 10f, soft, false)
        text(c, "☁  ${temperature()}°C", 540f, 72f, 16f, white, false)
        text(c, "SYSTEM MODE", 584f, 97f, 8f, soft, true)
        text(c, "GEMINI CORE • ${if (hasGemini()) "ACTIVE" else "OFFLINE"}", 176f, 132f, 9f, if (hasGemini()) bright else soft, true)
        line(c, 66f, 118f, 700f, 118f, line, 1f)

        box(c, 42f, 155f, 726f, 515f, panel, 22f, line)
        text(c, "SYSTEM HEALTH", 61f, 187f, 15f, white, true)
        text(c, "•••", 689f, 187f, 15f, soft, true)
        health(c, "▮", "BATTERY", "${battery}%", 86f, battery / 100f)
        health(c, "▰", "RAM", "${gb(ramTotal)}", 157f, ratio(ramUsed, ramTotal))
        health(c, "▰", "STORAGE", "${storagePercent()}%", 228f, ratio(storageUsed, storageTotal))
        health(c, "▣", "CPU", "${cpu.toInt()}%", 299f, cpu / 100f)
        health(c, "▣", "GPU", "${gpu()}%", 370f, gpu() / 100f)

        text(c, "DATA  •  SYSTEM TELEMETRY", 59f, 506f, 7f, soft, true)
        text(c, "LIVE", 683f, 506f, 7f, soft, true)
        drawCore(c, 384f, 555f)
        text(c, "GEMINI 3.6", 337f, 587f, 17f, bright, true)
        text(c, "CORE", 362f, 611f, 17f, bright, true)
        text(c, "PROTOCOL", 343f, 635f, 17f, bright, true)
        text(c, state.stage.take(24), 321f, 657f, 8f, if (state.healthy) soft else bright, true)

        box(c, 42f, 681f, 422f, 1158f, panel, 20f, line)
        box(c, 436f, 681f, 726f, 1158f, panel, 20f, line)
        text(c, "MESSAGES / NOTIFICATIONS", 58f, 714f, 13f, white, true)
        text(c, "•••", 365f, 714f, 12f, soft, true)
        appIcons(c)
        notification(c, "VOICE", if (state.stage == "HEARD") state.detail else "FRIDAY background engine", "LIVE", 774f, 0)
        notification(c, "SYSTEM", "Microphone / wake engine", "LIVE", 833f, 1)
        notification(c, "AI CORE", if (hasGemini()) "Gemini cloud brain connected" else "Gemini setup required", "NOW", 892f, 2)
        notification(c, "FRIDAY", "Hands-free command path active", "NOW", 951f, 3)

        text(c, "CURRENT TASK", 452f, 714f, 13f, white, true)
        text(c, "•••", 689f, 714f, 12f, soft, true)
        task(c, taskText(), 751f)
        task(c, "Voice path: ${state.stage}", 778f)
        task(c, "Gemini: ${if (hasGemini()) "CONNECTED" else "SETUP REQUIRED"}", 805f)
        graph(c, 454f, 828f, 706f, 900f)
        text(c, "ACTIVE LOG", 452f, 936f, 13f, white, true)
        text(c, "•••", 689f, 936f, 12f, soft, true)
        log(c, "System events", 970f, 0)
        log(c, "Microphone / wake engine", 997f, 1)
        log(c, "Speech recognition", 1024f, 2)
        log(c, "Local command / Gemini", 1051f, 3)
        log(c, "Action execution", 1078f, 4)
        log(c, "TTS response", 1105f, 5)

        text(c, "MIC", 61f, 1190f, 8f, soft, true)
        val amp = (state.audioAmplitude * 100f).toInt().coerceIn(0, 100)
        text(c, "$amp%", 92f, 1190f, 8f, bright, true)
        meter(c, 61f, 1202f, 385f, amp / 100f)
        text(c, "${state.stage}  •  ${short(state.detail)}", 61f, 1229f, 8f, soft, false)
        text(c, "TAP CORE = GEMINI SETUP   •   TAP ORB = VOICE TEST", 61f, 1254f, 7f, Color.rgb(126,69,73), false)
        text(c, "FRIDAY  //  PERSONAL INTELLIGENCE SYSTEM", 61f, 1307f, 8f, Color.rgb(103,51,56), true)
    }

    private fun health(c: Canvas, icon: String, name: String, value: String, y: Float, amount: Float) {
        text(c, icon, 62f, y + 20f, 22f, bright, true)
        text(c, name, 100f, y + 12f, 13f, white, false)
        text(c, value, 654f, y + 18f, 12f, soft, false)
        bars(c, 245f, y - 2f, 650f, 35f, amount)
        line(c, 245f, y + 39f, 650f, y + 39f, line, 1f)
    }

    private fun drawCore(c: Canvas, cx: Float, cy: Float) {
        val active = state.stage.contains("LISTEN", true) || state.stage.contains("HEARD", true) || state.stage.contains("THINK", true) || state.stage.contains("SPEAK", true)
        val pulse = 1f + 0.04f * sin(tick.toFloat() / 5f)
        val r = 128f * pulse
        for (i in 0..18) {
            val rr = r * (0.35f + i / 27f)
            p.style = Paint.Style.STROKE; p.strokeWidth = if (i % 4 == 0) 3f else 1f
            p.color = if (active) Color.rgb(255,154,60) else Color.rgb(185,67,54)
            p.alpha = minOf(150, 30 + i * 6); c.drawCircle(cx, cy, rr, p)
        }
        p.alpha = 255
        for (i in 0 until 90) {
            val a = (i * 137.5f + tick.toFloat() * (0.4f + (i % 3) * 0.05f)) * (Math.PI.toFloat() / 180f)
            val rad = r * (0.35f + (i % 17) / 22f)
            val x1 = cx + cos(a) * rad; val y1 = cy + sin(a) * rad
            val len = 10f + (i % 23)
            val x2 = cx + cos(a + 0.11f) * (rad + len); val y2 = cy + sin(a + 0.11f) * (rad + len)
            line(c, x1, y1, x2, y2, if (i % 4 == 0) orange else bright, if (i % 4 == 0) 2f else 1f)
        }
        p.style = Paint.Style.FILL; p.color = Color.rgb(11,5,5); c.drawCircle(cx, cy, 66f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = orange; c.drawCircle(cx, cy, 67f, p); p.style = Paint.Style.FILL
    }

    private fun appIcons(c: Canvas) {
        val names = listOf("●", "◉", "◎", "✉")
        val xs = listOf(85f, 154f, 223f, 292f)
        names.forEachIndexed { i, s ->
            p.color = when (i) { 0 -> Color.rgb(45,212,191); 1 -> Color.rgb(63,200,106); 2 -> Color.rgb(182,63,142); else -> Color.LTGRAY }
            c.drawRoundRect(RectF(xs[i],732f,xs[i]+45f,777f),12f,12f,p); text(c,s,xs[i]+13f,762f,19f,Color.WHITE,true)
        }
    }

    private fun notification(c: Canvas, who: String, msg: String, time: String, y: Float, index: Int) {
        p.color = if (index % 2 == 0) Color.rgb(32,16,18) else Color.rgb(24,11,13); c.drawRect(59f,y-24f,407f,y+25f,p)
        text(c,"●",67f,y+6f,23f,bright,true); text(c,who,99f,y-1f,11f,white,true); text(c,short(msg),99f,y+17f,9f,soft,false); text(c,time,348f,y+1f,8f,soft,false)
    }

    private fun task(c: Canvas, value: String, y: Float) { text(c,"›",455f,y,12f,bright,true); text(c,short(value),473f,y,8f,soft,false) }

    private fun graph(c: Canvas,l:Float,t:Float,r:Float,b:Float) {
        path.reset(); val h=b-t
        for (i in 0..40) { val x=l+(r-l)*i/40f; val wave=0.15f+0.5f*((sin(i*0.55f+tick.toFloat()*0.06f)+1f)/2f); val y=b-h*wave; if(i==0)path.moveTo(x,y) else path.lineTo(x,y) }
        p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=bright;c.drawPath(path,p);p.style=Paint.Style.FILL
    }

    private fun log(c: Canvas,value:String,y:Float,idx:Int){text(c,"●",455f,y,9f,if(idx%2==0)orange else bright,true);text(c,value,475f,y,8f,soft,false)}
    private fun meter(c:Canvas,l:Float,y:Float,r:Float,a:Float){box(c,l,y,r,y+7f,Color.rgb(38,14,17),4f,null);box(c,l,y,l+(r-l)*a,y+7f,bright,4f,null)}
    private fun bars(c:Canvas,l:Float,y:Float,r:Float,h:Float,amount:Float){val n=46;val bw=(r-l)/n;for(i in 0 until n){val wave=(sin(i*0.72f+tick.toFloat()*0.08f)+1f)/2f;val bar=h*(0.12f+0.75f*wave)*(0.25f+0.75f*amount.coerceAtLeast(0.18f));box(c,l+i*bw,y+h-bar,l+i*bw+bw*0.58f,y+h,if(i%7==0)bright else line,0f,null)}}
    private fun box(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int,radius:Float,stroke:Int?){p.style=if(stroke!=null)Paint.Style.STROKE else Paint.Style.FILL;p.strokeWidth=1f;p.color=stroke?:color;p.alpha=255;c.drawRoundRect(RectF(l,t,r,b),radius,radius,p);p.style=Paint.Style.FILL}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Float){p.style=Paint.Style.STROKE;p.strokeWidth=width;p.color=color;p.alpha=255;c.drawLine(x1,y1,x2,y2,p);p.style=Paint.Style.FILL}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean){p.style=Paint.Style.FILL;p.color=color;p.textSize=size;p.typeface=if(bold)android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT;p.alpha=255;c.drawText(s,x,y,p)}

    private fun sample(){
        val bm=context.getSystemService(BatteryManager::class.java); battery=bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)
        val am=context.getSystemService(ActivityManager::class.java); val mem=ActivityManager.MemoryInfo();am.getMemoryInfo(mem);ramTotal=mem.totalMem;ramUsed=mem.totalMem-mem.availMem
        val fs=StatFs(context.filesDir.absolutePath);storageTotal=fs.totalBytes;storageUsed=fs.totalBytes-fs.availableBytes
        cpu=readCpu()
    }
    private fun readCpu():Float=runCatching{val first=java.io.File("/proc/stat").readLines().firstOrNull{it.startsWith("cpu ")}?:return@runCatching 0f;val v=first.trim().split(Regex("\\s+"));val user=v.getOrNull(1)?.toLongOrNull()?:0L;val nice=v.getOrNull(2)?.toLongOrNull()?:0L;val sys=v.getOrNull(3)?.toLongOrNull()?:0L;val idle=v.getOrNull(4)?.toLongOrNull()?:0L;val total=user+nice+sys+idle;val dt=(total-lastTotal).coerceAtLeast(1);val di=(idle-lastIdle).coerceAtLeast(0);lastTotal=total;lastIdle=idle;((dt-di).toFloat()/dt*100f).coerceIn(0f,100f)}.getOrDefault(0f)
    private fun ratio(a:Long,b:Long)=if(b>0)(a.toFloat()/b).coerceIn(0f,1f)else 0f
    private fun gb(v:Long)=if(v>0)String.format("%.1f GB",v/1073741824.0)else "--"
    private fun storagePercent()=if(storageTotal>0)(storageUsed*100/storageTotal).toInt()else 0
    private fun gpu()=0
    private fun temperature():String=runCatching{val b=context.getSystemService(BatteryManager::class.java);"${b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0,100)}"}.getOrDefault("--")
    private fun timeNow()=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
    private fun dateNow()=java.text.SimpleDateFormat("EEE, dd MMM yyyy",java.util.Locale.getDefault()).format(java.util.Date())
    private fun hasGemini()=!runCatching{com.friday.assistant.ai.SecureApiKeyStore(context).read()}.getOrNull().isNullOrBlank()
    private fun taskText()=when{state.stage.contains("HEARD",true)->state.detail;state.stage.contains("THINK",true)->"Processing request…";state.stage.contains("EXECUT",true)->"Executing requested action";state.stage.contains("SPEAK",true)->"Preparing voice response";state.stage.contains("ERROR",true)->"Attention required";else->"Standing by for voice"}
    private fun short(s:String)=s.replace("\n"," ").let{if(it.length>45)it.take(42)+"…"else it}
}
