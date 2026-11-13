package dev.doxi.strata

import android.app.*
import android.content.*
import android.os.*
import com.splats.brush.*
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** A dedicated process makes Cancel actually release the native threads and GPU buffers. */
class TrainingService : Service(), BrushProgressListener {
    private lateinit var project: File
    private var started = false
    @Volatile private var ending = false
    private var total = 0
    private var exportPrefix=""
    private var lastWrite = 0L
    @Volatile private var lastProgress = System.currentTimeMillis()
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var logReader: java.lang.Process? = null
    private var thermal: PowerManager.OnThermalStatusChangedListener? = null
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this,1,Intent(this,TrainingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"training").setSmallIcon(R.drawable.ic_strata).setContentTitle("Strata · local reconstruction").setContentText(message).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Cancel",cancel).build()).build()
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finish("cancelled","Cancelled. Captured images and completed exports are kept."); return START_NOT_STICKY }
        if (started || intent == null) return START_NOT_STICKY
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("training","Local reconstruction",NotificationManager.IMPORTANCE_LOW))
        startForeground(41,notification("Preparing the GPU…"))
        try {
            project=Store.dir(this,intent.getStringExtra("id") ?: "")
            check(File(project,"dataset.zip").isFile) { "Missing captured dataset" }
            started=true
            val preset=intent.getStringExtra("preset") ?: "detail"
            val config = when(preset) {
                "compat" -> BrushConfig(totalTrainIters=5000,maxSplats=150000,maxResolution=768,maxSceneBatchCacheSize="256MiB",growthStopIter=3000,shDegree=0,ssimWeight=0f,meanNoiseWeight=0f,lrScale=.001,lrOpac=.005,lrMean=1e-5)
                "quick" -> BrushConfig(totalTrainIters=4000,maxSplats=150000,maxResolution=768,maxSceneBatchCacheSize="256MiB",growthStopIter=2800,shDegree=2)
                "max" -> BrushConfig(totalTrainIters=24000,maxSplats=800000,maxResolution=1440,maxSceneBatchCacheSize="768MiB",growthStopIter=16000,shDegree=3)
                else -> BrushConfig(totalTrainIters=12000,maxSplats=400000,maxResolution=1024,maxSceneBatchCacheSize="512MiB",growthStopIter=8000,shDegree=3)
            }
            total=config.totalTrainIters
            config.exportPath=File(project,"output").apply { mkdirs() }.absolutePath
            exportPrefix="scene_${System.currentTimeMillis()}_"
            config.exportName=exportPrefix+"{iter}.ply"
            // Only export the final model: avoid repeatedly stalling mobile GPUs and filling storage.
            config.exportEvery=total; config.evalEvery=total; config.evalSplitEvery=null
            Store.atomic(File(project,"state.json"),JSONObject().put("phase","starting").put("message","Loading images and compiling GPU shaders…").put("preset",preset).put("total",total).put("iter",0).put("pid",android.os.Process.myPid()))
            val pm=getSystemService(POWER_SERVICE) as PowerManager
            wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"strata:training").also { it.acquire(4*60*60*1000L) }
            thermal=PowerManager.OnThermalStatusChangedListener { level -> if(level>=PowerManager.THERMAL_STATUS_CRITICAL) finish("failed","Device reached its critical thermal limit. Let it cool, then try Quick.") }
            pm.addThermalStatusListener(thermal!!)
            // The library's Rust errors are logged but its JNI API has no error callback.
            // Read only this process's log and surface known engine failures to the UI.
            thread(name="strata-engine-log",isDaemon=true) {
                try {
                    logReader=Runtime.getRuntime().exec(arrayOf("logcat","--pid=${android.os.Process.myPid()}","-v","brief","-T","1"))
                    val log=File(project,"engine.log")
                    logReader!!.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        if(log.length()<2_000_000) log.appendText(line+"\n")
                        if(line.contains("Training stream error:") || line.contains("Failed to parse configuration JSON") || line.contains("panicked at")) {
                            finish("failed","GPU engine: "+line.takeLast(500))
                        }
                    } }
                } catch (_:Exception) { /* Watchdog and service liveness cover devices restricting logcat. */ }
            }
            thread(name="strata-launch") {
                try {
                    System.loadLibrary("brush_aar")
                    val input=TrainingData.prepare(project)
                    BrushEngine.setProgressListener(this)
                    // File descriptor input avoids a second full ZIP copy in the Java heap.
                    BrushEngine.start(this,android.net.Uri.fromFile(input),config)
                } catch(e:Throwable) { finish("failed",e.message ?: "Could not initialize the mobile GPU engine") }
            }
            handler.postDelayed(object:Runnable {
                override fun run() {
                    if(ending) return
                    if(System.currentTimeMillis()-lastProgress>10*60*1000L) finish("failed","The GPU engine stopped reporting progress for 10 minutes. Try Quick; engine.log contains local diagnostic details.")
                    else handler.postDelayed(this,10000)
                }
            },10000)
        } catch(e:Exception) { finish("failed",e.message ?: "Could not start training") }
        return START_NOT_STICKY
    }
    override fun onProgress(iter: Int, total: Int, elapsedMs: Long) {
        lastProgress=System.currentTimeMillis()
        if(ending || lastProgress-lastWrite<500 && iter<total-1) return
        lastWrite=lastProgress
        synchronized(this) {
            if(ending) return
            Store.atomic(File(project,"state.json"),Store.state(project).put("phase","training").put("iter",iter).put("total",total).put("elapsedMs",elapsedMs).put("updated",lastProgress).put("message","Optimizing Gaussian positions, shapes, colors, and opacity"))
        }
        if(iter%250==0) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(41,notification("${iter*100/total}% · $iter / $total steps"))
    }
    override fun onEvalResult(iter: Int, psnr: Float, ssim: Float) { /* No held-out dataset is claimed for captured scenes. */ }
    override fun onTrainingComplete() {
        val final=File(project,"output").listFiles()?.filter { it.name.startsWith(exportPrefix) && it.extension=="ply" }?.maxByOrNull { it.lastModified() }
        if(final==null) finish("failed","Training ended without a PLY export. Inspect engine.log.")
        else try { Store.validateModel(project,final);finish("complete","Reconstruction complete. Gaussian parameters are finite and the PLY is ready.") }
        catch(e:Exception) { finish("failed","Invalid reconstruction: ${e.message} Original photos and poses are safe in the dataset ZIP. Try Compatibility or a GPU server.") }
    }
    private fun finish(phase:String,message:String) {
        synchronized(this) {
            if(ending) return
            ending=true
            if(::project.isInitialized) Store.atomic(File(project,"state.json"),Store.state(project).put("phase",phase).put("message",message).put("updated",System.currentTimeMillis()))
        }
        handler.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        if(!ending && ::project.isInitialized) Store.atomic(File(project,"state.json"),Store.state(project).put("phase","failed").put("message","Android stopped reconstruction. Your dataset is still saved."))
        ending=true; logReader?.destroy(); handler.removeCallbacksAndMessages(null)
        thermal?.let { (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(it) }
        wake?.let { if(it.isHeld) it.release() }
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
