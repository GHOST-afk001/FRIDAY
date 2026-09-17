package com.friday.assistant

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.DeviceTelemetry
import com.friday.assistant.runtime.FridayUiState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Full-screen live HUD based on the supplied red/orange 768x1376 reference. */
class FridayReferenceHudView(context: Context) : View(context) {
    interface Actions { fun onGeminiTap(); fun onAssistantTap(); fun onAutomationTap(); fun onOrbTap() }
    var actions: Actions? = null
    private var state = FridayUiState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private val frameTicker = object : Runnable { override fun run() { if (!isAttachedToWindow) return; sample(); frame++; invalidate(); handler.postDelayed(this, 250L) } }
    private var frame = 0L
    private var battery = 0
    private var ramUsed = 0L
    private var ramTotal = 0L
    private var storageUsed = 0L
    private var storageTotal = 0L
    private var temperature = 0f
    private var cpu = 0f
    private var previousTotal = 0L
    private var previousIdle = 0L
    private var telemetry = runCatching { DeviceTelemetry.snapshot(context.applicationContext) }.getOrNull()
    private val bg = Color.rgb(4, 2, 3)
    private val panel = Color.rgb(17, 6, 8)
    private val panel2 = Color.rgb(25, 9, 11)
    private val line = Color.rgb(107, 31, 37)
    private val red = Color.rgb(229, 72, 78)
    private val orange = Color.rgb(255, 137, 48)
    private val soft = Color.rgb(196, 99, 105)
    private val white = Color.rgb(244, 220, 221)
    init { isClickable = true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(frameTicker); handler.post(frameTicker) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(frameTicker); super.onDetachedFromWindow() }
    fun render(value: FridayUiState) { state = value; invalidate() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val scale = max(width / 768f, height / 1376f)
        val x = (event.x - (width - 768f * scale) / 2f) / scale
        val y = (event.y - (height - 1376f * scale) / 2f) / scale
        when { x in 250f..518f && y in 475f..675f -> actions?.onOrbTap(); x in 145f..520f && y in 100f..150f -> actions?.onGeminiTap(); x < 384f && y in 1160f..1280f -> actions?.onAssistantTap(); x >= 384f && y in 1160f..1280f -> actions?.onAutomationTap() }
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.BLACK)
        val scale = max(width / 768f, height / 1376f)
        canvas.save(); canvas.scale(scale, scale); canvas.translate((width / scale - 768f) / 2f, (height / scale - 1376f) / 2f); drawHud(canvas); canvas.restore()
    }
    private fun drawHud(c: Canvas) {
        box(c,0f,0f,768f,1376f,bg,0f,null); box(c,22f,20f,746f,143f,panel,22f,line)
        text(c,"◢",43f,78f,30f,red,true); text(c,"ULTIMATE",82f,67f,20f,white,true); text(c,"J.A.R.V.I.S / ULTRON CORE AI",82f,92f,9f,soft,false)
        text(c,clock(),330f,75f,33f,red,false); text(c,date(),334f,100f,10f,soft,false); text(c,"⌁  ${if(temperature>0f) "%.1f".format(temperature) else "--"}°C",540f,69f,15f,white,false); text(c,"SYSTEM MODE",590f,94f,8f,soft,true)
        text(c,"GEMINI CORE • ${if(geminiReady()) "ACTIVE" else "OFFLINE"}",173f,130f,9f,if(geminiReady()) orange else soft,true); line(c,58f,116f,711f,116f,line,1f)
        box(c,40f,155f,728f,509f,panel,22f,line); text(c,"SYSTEM HEALTH",58f,186f,15f,white,true); text(c,"•••",687f,186f,15f,soft,true)
        health(c,"▮","BATTERY","${battery}%",211f,battery/100f); health(c,"▰","RAM","${gb(ramTotal)}",274f,ratio(ramUsed,ramTotal)); health(c,"▰","STORAGE","${storagePercent()}%",337f,ratio(storageUsed,storageTotal)); health(c,"▣","CPU","${cpu.toInt()}%",400f,cpu/100f); health(c,"▣","GPU","--",463f,0.22f)
        text(c,"DATA  •  SYSTEM TELEMETRY",58f,500f,7f,soft,true); text(c,"LIVE",684f,500f,7f,orange,true); drawCore(c,384f,562f); text(c,"GEMINI 3.8",333f,598f,17f,orange,true); text(c,"CORE",360f,621f,17f,orange,true); text(c,"PROTOCOL",340f,644f,17f,orange,true); text(c,state.stage.take(24),314f,661f,8f,if(state.healthy) soft else red,true)
        box(c,40f,682f,421f,1157f,panel,20f,line); box(c,434f,682f,728f,1157f,panel,20f,line); text(c,"MESSAGES / NOTIFICATIONS",56f,713f,13f,white,true); text(c,"•••",365f,713f,12f,soft,true); icons(c)
        val voiceMessage=if(state.stage=="HEARD")state.detail else "FRIDAY hands-free engine"; notification(c,"VOICE",voiceMessage,"LIVE",778f,0); notification(c,"SYSTEM","Microphone + command path","LIVE",846f,1); notification(c,"AI CORE",if(geminiReady())"Gemini cloud brain connected" else "Gemini setup required","NOW",914f,2); notification(c,"FRIDAY","Waiting for your next command","NOW",982f,3)
        text(c,"CURRENT TASK",451f,713f,13f,white,true); text(c,"•••",688f,713f,12f,soft,true); task(c,taskText(),752f); task(c,"Voice: ${state.stage}",781f); task(c,"Network: ${telemetry?.network ?: "--"}",810f); task(c,"Gemini: ${if(geminiReady())"CONNECTED" else "OFFLINE"}",839f); graph(c,452f,858f,706f,914f)
        text(c,"ACTIVE LOG",451f,947f,13f,white,true); text(c,"•••",688f,947f,12f,soft,true); log(c,"Runtime heartbeat",980f,0); log(c,"Speech recognition",1008f,1); log(c,"Local command router",1036f,2); log(c,"Android action bridge",1064f,3); log(c,"TTS response",1092f,4)
        text(c,"MIC",59f,1190f,8f,soft,true); val amp=(state.audioAmplitude*100f).toInt().coerceIn(0,100); text(c,"$amp%",91f,1190f,8f,orange,true); meter(c,59f,1202f,406f,amp/100f); text(c,"${state.stage}  •  ${short(state.detail)}",59f,1228f,8f,soft,false); text(c,"VOICE: HANDS-FREE   •   CORE: LIVE   •   ACTIONS: LOCAL",59f,1252f,7f,soft,false); text(c,"FRIDAY  //  PERSONAL INTELLIGENCE SYSTEM",59f,1307f,8f,Color.rgb(102,48,54),true)
    }
    private fun drawCore(c:Canvas,cx:Float,cy:Float){val active=state.stage.contains("LISTEN",true)||state.stage.contains("HEARD",true)||state.stage.contains("THINK",true)||state.stage.contains("SPEAK",true)||state.stage.contains("EXECUT",true);val pulse=1f+(if(active)0.075f else 0.035f)*sin(frame/6f);val rotation=frame*if(active)1.8f else 0.7f;val radius=139f*pulse;paint.setShadowLayer(if(active)28f else 16f,0f,0f,orange);for(i in 0..22){val rr=radius*(0.30f+i/34f);paint.style=Paint.Style.STROKE;paint.strokeWidth=if(i%5==0)2.5f else 0.8f;paint.color=if(active)orange else red;paint.alpha=(24+i*5).coerceAtMost(150);c.drawCircle(cx,cy,rr,paint)};paint.clearShadowLayer();paint.alpha=255;for(i in 0 until 120){val a=Math.toRadians((i*137.507764+rotation).toDouble()).toFloat();val ring=radius*(0.34f+(i%21)/28f);val x1=cx+cos(a)*ring;val y1=cy+sin(a)*ring;val len=7f+(i%24);val a2=a+0.09f+((i%5)*0.01f);val x2=cx+cos(a2)*(ring+len);val y2=cy+sin(a2)*(ring+len);line(c,x1,y1,x2,y2,if(i%5==0)orange else red,if(i%5==0)1.8f else 0.8f)};paint.style=Paint.Style.FILL;paint.color=Color.rgb(9,3,4);c.drawCircle(cx,cy,71f*pulse,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=2.2f;paint.color=orange;c.drawCircle(cx,cy,72f*pulse,paint);paint.style=Paint.Style.FILL}
    private fun health(c:Canvas,icon:String,name:String,value:String,y:Float,amount:Float){text(c,icon,59f,y,20f,orange,true);text(c,name,97f,y-3f,12f,white,false);text(c,value,652f,y+2f,11f,soft,false);bars(c,235f,y-18f,643f,30f,amount);line(c,235f,y+17f,643f,y+17f,line,1f)}
    private fun icons(c:Canvas){val xs=floatArrayOf(67f,139f,211f,283f);val glyphs=arrayOf("●","◉","◎","✉");for(i in xs.indices){paint.style=Paint.Style.FILL;paint.color=if(i==1)Color.rgb(57,190,91)else if(i==2)Color.rgb(176,59,135)else if(i==3)Color.LTGRAY else Color.rgb(45,190,178);c.drawRoundRect(RectF(xs[i],731f,xs[i]+48f,779f),12f,12f,paint);text(c,glyphs[i],xs[i]+14f,763f,18f,Color.WHITE,true)}}
    private fun notification(c:Canvas,who:String,msg:String,time:String,y:Float,index:Int){paint.color=if(index%2==0)panel2 else Color.rgb(22,8,10);paint.style=Paint.Style.FILL;c.drawRoundRect(RectF(55f,y-25f,408f,y+26f),8f,8f,paint);text(c,"●",64f,y+6f,21f,orange,true);text(c,who,96f,y-2f,10f,white,true);text(c,short(msg),96f,y+17f,8f,soft,false);text(c,time,347f,y+1f,8f,soft,false)}
    private fun task(c:Canvas,value:String,y:Float){text(c,"›",452f,y,12f,orange,true);text(c,short(value),470f,y,8f,soft,false)}
    private fun graph(c:Canvas,left:Float,top:Float,right:Float,bottom:Float){path.reset();val h=bottom-top;for(i in 0..45){val x=left+(right-left)*i/45f;val wave=0.12f+0.72f*((sin(i*0.62f+frame*0.045f)+1f)/2f);val y=bottom-h*wave;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};paint.style=Paint.Style.STROKE;paint.strokeWidth=1.7f;paint.color=red;c.drawPath(path,paint);paint.style=Paint.Style.FILL}
    private fun log(c:Canvas,value:String,y:Float,idx:Int){text(c,"●",452f,y,8f,if(idx%2==0)orange else red,true);text(c,value,471f,y,8f,soft,false)}
    private fun meter(c:Canvas,left:Float,y:Float,right:Float,amount:Float){box(c,left,y,right,y+7f,Color.rgb(37,13,16),4f,null);box(c,left,y,left+(right-left)*amount,y+7f,orange,4f,null)}
    private fun bars(c:Canvas,left:Float,y:Float,right:Float,h:Float,amount:Float){val n=48;val bw=(right-left)/n;for(i in 0 until n){val wave=(sin(i*0.71f+frame*0.035f)+1f)/2f;val bar=h*(0.12f+0.76f*wave)*(0.25f+0.75f*amount.coerceIn(0.05f,1f));box(c,left+i*bw,y+h-bar,left+i*bw+bw*0.55f,y+h,if(i%8==0)orange else line,0f,null)}}
    private fun box(c:Canvas,left:Float,top:Float,right:Float,bottom:Float,color:Int,radius:Float,stroke:Int?){paint.style=if(stroke!=null)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=1f;paint.color=stroke?:color;paint.alpha=255;c.drawRoundRect(RectF(left,top,right,bottom),radius,radius,paint);paint.style=Paint.Style.FILL}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Float){paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.color=color;paint.alpha=255;c.drawLine(x1,y1,x2,y2,paint);paint.style=Paint.Style.FILL}
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean){paint.style=Paint.Style.FILL;paint.color=color;paint.alpha=255;paint.textSize=size;paint.typeface=if(bold)android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD)else android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL);c.drawText(value,x,y,paint)}
    private fun sample(){telemetry=runCatching{DeviceTelemetry.snapshot(context.applicationContext)}.getOrElse{telemetry};val current=telemetry;if(current!=null){battery=current.batteryPercent;ramUsed=current.ramUsedGb;ramTotal=current.ramTotalGb;storageUsed=current.storageUsedGb;storageTotal=current.storageTotalGb;temperature=current.batteryTempC};val cpuNow=readCpu();if(cpuNow>=0f)cpu=cpuNow}
    private fun readCpu():Float=runCatching{val first=java.io.File("/proc/stat").bufferedReader().use{it.readLine()};val parts=first.trim().split(Regex("\\s+"));if(parts.size<5)return 0f;val user=parts[1].toLong();val nice=parts[2].toLong();val system=parts[3].toLong();val idle=parts[4].toLong();val iowait=parts.getOrNull(5)?.toLongOrNull()?:0L;val irq=parts.getOrNull(6)?.toLongOrNull()?:0L;val softirq=parts.getOrNull(7)?.toLongOrNull()?:0L;val total=user+nice+system+idle+iowait+irq+softirq;if(previousTotal==0L){previousTotal=total;previousIdle=idle;return 0f};val totalDelta=total-previousTotal;val idleDelta=idle-previousIdle;previousTotal=total;previousIdle=idle;if(totalDelta<=0L)0f else ((totalDelta-idleDelta).toFloat()/totalDelta*100f).coerceIn(0f,100f)}.getOrDefault(0f)
    private fun geminiReady():Boolean=runCatching{SecureApiKeyStore(context).read()?.isNotBlank()==true}.getOrDefault(false)
    private fun clock():String=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
    private fun date():String=java.text.SimpleDateFormat("EEE, dd MMM",java.util.Locale.getDefault()).format(java.util.Date()).uppercase(java.util.Locale.getDefault())
    private fun gb(value:Long):String=if(value>0)"${value}GB"else"--"
    private fun ratio(a:Long,b:Long):Float=if(b<=0L)0f else(a.toFloat()/b.toFloat()).coerceIn(0f,1f)
    private fun storagePercent():Int=if(storageTotal<=0L)0 else((storageUsed.toFloat()/storageTotal)*100f).toInt().coerceIn(0,100)
    private fun short(value:String):String=value.replace(Regex("\\s+")," ").trim().take(43)
    private fun taskText():String=when{state.stage=="LISTENING"->"Listening for your command";state.stage=="HEARD"->"Processing: ${short(state.detail)}";state.stage.contains("THINK",true)->"Gemini reasoning";state.stage.contains("EXECUT",true)->"Executing Android action";state.stage.contains("SPEAK",true)->"Speaking response";else->"FRIDAY ready"}
}
