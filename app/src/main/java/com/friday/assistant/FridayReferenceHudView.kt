package com.friday.assistant

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
import kotlin.math.min
import kotlin.math.sin

/** Live recreation of the supplied red/orange FRIDAY reference HUD at 536x1115 design units. */
class FridayReferenceHudView(context: Context) : View(context) {
    interface Actions { fun onGeminiTap(); fun onAssistantTap(); fun onAutomationTap(); fun onOrbTap() }
    var actions: Actions? = null
    private var state = FridayUiState()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var frame = 0L
    private var battery = 0
    private var ramUsed = 0L
    private var ramTotal = 0L
    private var storageUsed = 0L
    private var storageTotal = 0L
    private var cpu = 0f
    private var gpu = 8
    private var temperature = 0f
    private var telemetry = runCatching { DeviceTelemetry.snapshot(context.applicationContext) }.getOrNull()
    private val ticker = object : Runnable { override fun run() { if (!isAttachedToWindow) return; sample(); frame++; invalidate(); handler.postDelayed(this, 250L) } }
    private val bg = Color.rgb(7, 2, 3)
    private val panel = Color.rgb(18, 5, 7)
    private val line = Color.rgb(104, 28, 35)
    private val red = Color.rgb(235, 77, 83)
    private val orange = Color.rgb(255, 142, 45)
    private val white = Color.rgb(244, 225, 225)
    private val muted = Color.rgb(180, 99, 104)
    init { isClickable = true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(ticker); handler.post(ticker) }
    override fun onDetachedFromWindow() { handler.removeCallbacks(ticker); super.onDetachedFromWindow() }
    fun render(value: FridayUiState) { state = value; invalidate() }
    override fun onDraw(c: Canvas) { super.onDraw(c); c.drawColor(Color.BLACK); val scale = min(width / 536f, height / 1115f); c.save(); c.scale(scale, scale); c.translate((width / scale - 536f) / 2f, (height / scale - 1115f) / 2f); drawReference(c); c.restore() }
    override fun onTouchEvent(e: MotionEvent): Boolean { if (e.action != MotionEvent.ACTION_UP) return true; val scale = min(width / 536f, height / 1115f); val x = (e.x / scale) - (width / scale - 536f) / 2f; val y = (e.y / scale) - (height / scale - 1115f) / 2f; when { x in 155f..385f && y in 335f..720f -> actions?.onOrbTap(); x in 160f..350f && y in 54f..88f -> actions?.onGeminiTap(); x in 14f..268f && y in 720f..1010f -> actions?.onAssistantTap(); x in 270f..522f && y in 720f..1010f -> actions?.onAutomationTap() }; return true }
    private fun drawReference(c: Canvas) {
        box(c,0f,0f,536f,1115f,bg,0f,null); box(c,2f,2f,534f,72f,panel,12f,line)
        text(c,"◢",16f,43f,27f,red,true); text(c,"ULTIMATE",64f,31f,17f,white,true); text(c,"J.A.R.VIS/ULTRON CORE AI",64f,46f,7f,muted,false)
        text(c,timeNow(),226f,34f,29f,red,false); text(c,dateNow(),219f,51f,8f,muted,false); text(c,"☁",379f,39f,26f,muted,false)
        text(c,"${if(temperature>0f) "%.0f".format(temperature) else "--"}°C",429f,28f,12f,white,false); text(c,"Weather",429f,42f,7f,muted,false); text(c,"Mode",429f,52f,7f,muted,false); text(c,"●",519f,22f,9f,red,true)
        line(c,0f,78f,536f,78f,line,1f); text(c,"GEMINI CORE • ${if(geminiReady()) "ACTIVE" else "OFFLINE"}",55f,72f,9f,if(geminiReady())orange else muted,true)
        box(c,14f,86f,522f,330f,panel,13f,line); text(c,"SYSTEM HEALTH",28f,108f,14f,white,false); text(c,"•••",489f,108f,12f,muted,true)
        healthRow(c,132f,"BATTERY","${battery}%",battery/100f,0); healthRow(c,180f,"RAM","${percent(ramUsed,ramTotal)}% of ${ramTotal}GB",ratio(ramUsed,ramTotal),1); healthRow(c,228f,"STORAGE","${storagePercent()}% used",ratio(storageUsed,storageTotal),2); healthRow(c,276f,"CPU","${cpu.toInt()}% usage",cpu/100f,3); healthRow(c,324f,"GPU","$gpu% usage",gpu/100f,4)
        box(c,14f,337f,522f,720f,Color.BLACK,12f,line); text(c,"DATA",25f,354f,6f,muted,true); text(c,"DATARE",462f,354f,6f,muted,true)
        drawMicroText(c,25f,368f,arrayOf("APRO RAEOY","BDAKMSYKNOD","DATA SYNTHNSS","GREBCOS TRAES","GEMINE.UNAPOSED")); drawMicroText(c,410f,368f,arrayOf("SYSTEMPPBUEERD","MIRS DESD","BATTERY PROCESS","GATV USABES","DATA VUSSRSS"))
        drawOrb(c,268f,525f); text(c,"GEMINI 3.6",218f,532f,18f,red,false); text(c,"CORE",237f,554f,18f,red,false); text(c,"PROTOCOL",220f,576f,18f,red,false); text(c,state.stage.take(20),232f,593f,7f,if(state.healthy)muted else red,true)
        drawMicroText(c,25f,642f,arrayOf("GRU, 23AI","BATA MAAGOATOR","COPNCTC OO","GEMTIC 6SN","GATAXOX")); drawMicroText(c,410f,642f,arrayOf("DATA USABES","DISIERNEY URYNSS","SONBSSYBBST8","S8L URD","PROCESOSSS"))
        box(c,14f,726f,268f,1008f,panel,12f,line); box(c,286f,726f,522f,848f,panel,12f,line); box(c,286f,856f,522f,1008f,panel,12f,line)
        text(c,"MESSAGES / NOTIFICATIONS",27f,748f,13f,white,false); text(c,"•••",245f,748f,11f,muted,true)
        appIcon(c,30f,759f,0); appIcon(c,87f,759f,1); appIcon(c,144f,759f,2); appIcon(c,201f,759f,3)
        message(c,792f,"John","Hey Messages 😅","1:59 AM",0); message(c,839f,"Kartis","I am a Message.","1:33 PM",1); message(c,886f,"Email","Tharks Message ...","4:59 AM",2); message(c,933f,"Instagram","Weet you!","1:39 AM",3)
        text(c,"CURRENT TASK",300f,748f,13f,white,false); text(c,"•••",489f,748f,11f,muted,true); taskRow(c,770f,"Prevess obsin messages","27.0400",0); taskRow(c,790f,"Whataling contrel imrossjies","",1); taskRow(c,810f,"Datalarogo 0o hided","",2); taskRow(c,830f,"Wriepriecy inicts","",3); drawTaskGraph(c,364f,777f,508f,833f)
        text(c,"ACTIVE LOG",300f,877f,13f,white,false); text(c,"•••",489f,877f,11f,muted,true); logRow(c,897f,"System eventriced system events",0); logRow(c,915f,"Battemy control events",1); logRow(c,933f,"Active atosiity events",2); logRow(c,951f,"Mining controltoastom",3); logRow(c,969f,"Charging control changed",4); logRow(c,987f,"System cemon meaating success",5)
        for(y in 1009 until 1115 step 8) line(c,0f,y.toFloat(),536f,y.toFloat(),Color.rgb(45,13,16),4f); for(x in -80 until 620 step 18) line(c,x.toFloat(),1115f,(x+115).toFloat(),1009f,Color.rgb(35,10,12),5f)
    }
    private fun healthRow(c:Canvas,y:Float,name:String,value:String,amount:Float,index:Int){val icon=when(index){0->"▯";1->"▤";2->"▰";3->"▣";else->"▥"};text(c,icon,29f,y+10f,22f,red,true);text(c,name,80f,y+3f,11f,white,false);text(c,value,80f,y+17f,8f,muted,false);drawBars(c,220f,y-6f,458f,24f,amount,index);text(c,if(index==1)"${ramTotal}GB" else value.substringBefore(' '),465f,y+10f,9f,muted,false);line(c,220f,y+25f,509f,y+25f,line,1f)}
    private fun drawBars(c:Canvas,left:Float,top:Float,right:Float,height:Float,amount:Float,seed:Int){val n=50;val w=(right-left)/n;for(i in 0 until n){val wave=(sin(i*(0.58f+seed*0.07f)+frame*0.045f)+1f)/2f;val h=height*(0.18f+0.72f*wave)*(0.2f+0.8f*amount.coerceIn(0.04f,1f));box(c,left+i*w,top+height-h,left+i*w+w*.58f,top+height,if(i%9==0)orange else line,0f,null)}}
    private fun drawOrb(c:Canvas,cx:Float,cy:Float){val active=state.stage.contains("LISTEN",true)||state.stage.contains("HEARD",true)||state.stage.contains("THINK",true)||state.stage.contains("SPEAK",true)||state.stage.contains("EXECUT",true);val pulse=1f+(if(active).06f else .025f)*sin(frame/5f);val r=174f*pulse;paint.setShadowLayer(if(active)26f else 12f,0f,0f,orange);paint.style=Paint.Style.STROKE;for(i in 0..26){val rr=r*(.50f+i/56f);paint.color=if(i%4==0)orange else red;paint.alpha=(18+i*5).coerceAtMost(150);paint.strokeWidth=if(i%5==0)2.2f else .65f;c.drawCircle(cx,cy,rr,paint)};paint.clearShadowLayer();paint.alpha=255;for(i in 0 until 260){val a=Math.toRadians((i*137.5+frame*(if(active)1.5 else .45)).toDouble()).toFloat();val rr=r*(.46f+(i%39)/52f);val x=cx+cos(a)*rr;val y=cy+sin(a)*rr;val len=4f+(i%17);val a2=a+.045f+(i%7)*.008f;line(c,x,y,cx+cos(a2)*(rr+len),cy+sin(a2)*(rr+len),if(i%7==0)orange else red,if(i%7==0)1.4f else .55f)};paint.style=Paint.Style.FILL;paint.color=Color.rgb(9,2,3);c.drawCircle(cx,cy,90f*pulse,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=orange;paint.alpha=220;c.drawCircle(cx,cy,92f*pulse,paint);paint.alpha=255;paint.style=Paint.Style.FILL}
    private fun appIcon(c:Canvas,x:Float,y:Float,index:Int){val colors=intArrayOf(Color.rgb(30,192,184),Color.rgb(62,194,90),Color.rgb(180,62,142),Color.LTGRAY);box(c,x,y,x+42f,y+42f,colors[index],9f,null);text(c,arrayOf("●","◉","◎","✉")[index],x+12f,y+27f,18f,Color.WHITE,true);if(index==1)text(c,"1",x+35f,y+6f,7f,red,true)}
    private fun message(c:Canvas,y:Float,who:String,msg:String,time:String,index:Int){line(c,27f,y+36f,260f,y+36f,line,1f);text(c,"●",34f,y+22f,18f,if(index%2==0)orange else red,true);text(c,who,58f,y+14f,10f,white,false);text(c,short(msg),58f,y+28f,8f,muted,false);text(c,time,205f,y+17f,7f,muted,false)}
    private fun taskRow(c:Canvas,y:Float,value:String,time:String,index:Int){text(c,"▣",300f,y+4f,8f,red,true);text(c,short(value),314f,y+4f,7f,muted,false);if(time.isNotBlank())text(c,time,475f,y+4f,7f,muted,false)}
    private fun drawTaskGraph(c:Canvas,left:Float,top:Float,right:Float,bottom:Float){path.reset();for(i in 0..26){val x=left+(right-left)*i/26f;val y=bottom-(bottom-top)*(.12f+.72f*((sin(i*.51f+frame*.05f)+1f)/2f));if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=red;c.drawPath(path,paint);paint.style=Paint.Style.FILL}
    private fun logRow(c:Canvas,y:Float,value:String,index:Int){text(c,"●",302f,y+4f,8f,arrayOf(orange,Color.rgb(72,167,205),red,Color.rgb(91,183,103),Color.rgb(212,177,80),Color.rgb(119,91,177))[index],true);text(c,short(value),316f,y+4f,7f,muted,false)}
    private fun drawMicroText(c:Canvas,x:Float,y:Float,values:Array<String>){values.forEachIndexed{i,v->text(c,v,x,y+i*9f,5f,muted,false)}}
    private fun box(c:Canvas,l:Float,t:Float,r:Float,b:Float,color:Int,radius:Float,stroke:Int?){paint.style=if(stroke!=null)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=1f;paint.color=stroke?:color;paint.alpha=255;c.drawRoundRect(RectF(l,t,r,b),radius,radius,paint);paint.style=Paint.Style.FILL}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Float){paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.color=color;paint.alpha=255;c.drawLine(x1,y1,x2,y2,paint);paint.style=Paint.Style.FILL}
    private fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean){paint.style=Paint.Style.FILL;paint.color=color;paint.alpha=255;paint.textSize=size;paint.typeface=android.graphics.Typeface.create("sans-serif",if(bold)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL);c.drawText(value,x,y,paint)}
    private fun sample(){telemetry=runCatching{DeviceTelemetry.snapshot(context.applicationContext)}.getOrElse{telemetry};telemetry?.let{battery=it.batteryPercent;ramUsed=it.ramUsedGb;ramTotal=it.ramTotalGb;storageUsed=it.storageUsedGb;storageTotal=it.storageTotalGb;temperature=it.batteryTempC};cpu=readCpu()}
    private fun readCpu():Float=runCatching{val first=java.io.File("/proc/stat").bufferedReader().use{it.readLine()};val p=first.trim().split(Regex("\\s+"));if(p.size<5)return 0f;val total=p.drop(1).take(7).sumOf{it.toLongOrNull()?:0L};val idle=(p.getOrNull(4)?.toLongOrNull()?:0L)+(p.getOrNull(5)?.toLongOrNull()?:0L);if(total<=0L)0f else ((total-idle).toFloat()/total*100f).coerceIn(0f,100f)}.getOrDefault(0f)
    private fun timeNow()=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date())
    private fun dateNow()=java.text.SimpleDateFormat("EEE, dd MMM yyyy",java.util.Locale.getDefault()).format(java.util.Date())
    private fun percent(used:Long,total:Long)=if(total>0)((used*100f/total).toInt()).coerceIn(0,100)else 0
    private fun ratio(used:Long,total:Long)=if(total>0)(used.toFloat()/total).coerceIn(0f,1f)else .08f
    private fun storagePercent()=if(storageTotal>0)((storageUsed*100f/storageTotal).toInt()).coerceIn(0,100)else 0
    private fun geminiReady()=runCatching{!SecureApiKeyStore(context.applicationContext).read().isNullOrBlank()}.getOrDefault(false)
    private fun short(v:String)=v.replace(Regex("\\s+")," ").take(27)
}
