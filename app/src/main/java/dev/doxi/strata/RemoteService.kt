package dev.doxi.strata

import android.app.*
import android.content.*
import android.os.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.concurrent.thread

class RemoteService:Service() {
    companion object {
        @Volatile var observedProject=""
            private set
        @Volatile var lastContact=0L
            private set
    }
    private lateinit var project:File
    private var connection:ServerHttp?=null
    @Volatile private var stopped=false
    private var running=false
    private var lastWrite=0L
    private var worker:Thread?=null
    private var wake:PowerManager.WakeLock?=null
    private var lastState=""
    private var serverCreated=0.0
    @Volatile private var workerTelemetry=JSONObject()
    private fun rememberWorker(record:JSONObject) {
        val details=RemoteJobStatus.details(record)
        workerTelemetry=JSONObject().put("workerHeartbeat",details.getDouble("workerHeartbeat")).put("engine",details.getString("engine")).put("engineElapsed",details.getDouble("engineElapsed"))
    }
    override fun onBind(intent:Intent?)=null
    private fun notification(text:String)=Notification.Builder(this,"remote").setSmallIcon(R.drawable.ic_strata).setContentTitle("Strata · GPU server").setContentText(text).setContentIntent(PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).addAction(Notification.Action.Builder(null,"Cancel",PendingIntent.getService(this,3,Intent(this,RemoteService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)).build()).build()
    @Synchronized private fun state(message:String,phase:String="remote",extra:JSONObject?=null) {
        if(stopped && phase!="cancelled") return
        val s=JSONObject().put("phase",phase).put("message",message)
        val telemetry=workerTelemetry
        for(key in listOf("workerHeartbeat","engine","engineElapsed")) if(telemetry.has(key)) s.put(key,telemetry.get(key))
        extra?.keys()?.forEach { s.put(it,extra.get(it)) }
        if(serverCreated>0) s.put("serverCreated",serverCreated)
        val signature=s.toString()
        if(signature==lastState) return
        s.put("updated",System.currentTimeMillis())
        if(lastContact>0) s.put("lastContact",lastContact)
        Store.atomic(File(project,"state.json"),s)
        lastState=signature
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(42,notification(message.take(120)))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {
            stopped=true;connection?.stop();worker?.interrupt()
            if(::project.isInitialized) {
                state("Cancelling server job…","cancelled")
                thread { try { val remote=JSONObject(File(project,"remote.json").readText());val profile=SecureServer(this).decode(remote.getString("profile"));ServerHttp(profile).json("/v1/jobs/${remote.getString("id")}/cancel","POST");Store.atomic(File(project,"remote.json"),remote.put("complete",true)) } catch(_:Exception) {} finally { stopSelf() } }
            } else stopSelf()
            return START_NOT_STICKY
        }
        if(running || intent==null) return START_NOT_STICKY
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("remote","GPU server jobs",NotificationManager.IMPORTANCE_LOW))
        startForeground(42,notification("Connecting to your server…"))
        try {
            project=Store.dir(this,intent.getStringExtra("id") ?: "");running=true
            observedProject=project.name;lastContact=0L
            wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"strata:remote").also { it.acquire(4*60*60*1000L) }
            worker=thread(name="strata-remote") {
                try {
                    val config=SecureServer(this)
                    val resume=intent.getBooleanExtra("resume",false)
                    val recover=intent.getBooleanExtra("recover",false)
                    val continueSaved=intent.getBooleanExtra("continueSaved",false)
                    val remote=if(resume) JSONObject(File(project,"remote.json").readText()) else JSONObject().put("id",UUID.randomUUID().toString()).put("profile",config.snapshot()).put("mode",intent.getStringExtra("mode") ?: "splat").put("preset",intent.getStringExtra("preset") ?: "detail").put("submitted",false)
                    Store.atomic(File(project,"remote.json"),remote)
                    val profile=config.decode(remote.getString("profile"));val http=ServerHttp(profile);connection=http
                    val id=remote.getString("id");val health=http.json("/v1/health");require(health.optString("service")=="strata") { "This is not a Strata server" }
                    val polling=RemotePollPolicy()
                    fun status():JSONObject {
                        while(true) {
                            check(!stopped) { "Cancelled" }
                            try { return http.json("/v1/jobs/$id",readTimeoutMs=30000).also {lastContact=System.currentTimeMillis();rememberWorker(it)} }
                            catch(e:Exception) {
                                if(stopped) throw e
                                val delay=polling.retryDelay(e) ?: throw e
                                state("Connection interrupted. Retrying in ${delay/1000}s; the server job is kept.",extra=JSONObject().put("stage","reconnecting").put("lastContact",lastContact))
                                Thread.sleep(delay)
                            }
                        }
                    }
                    val caps=health.getJSONObject("capabilities");val mode=remote.getString("mode")
                    require(recover || if(continueSaved) caps.optBoolean("splat") else (mode=="mesh" || caps.optBoolean("splat")) && (mode=="splat" || caps.optBoolean("mesh"))) { "The server does not have the requested reconstruction engines configured" }
                    var result:JSONObject
                    if(recover||continueSaved) {
                        require(caps.optBoolean(if(recover) "recovery" else "resume_saved")){"Update the deployed server to 0.3.3 or newer before using saved-work recovery"}
                        state(if(recover) "Checking saved server files; no GPU reconstruction starts" else "Requesting continuation from saved work",extra=JSONObject().put("stage","reconnecting"))
                        result=http.json("/v1/jobs/$id/"+(if(recover) "recover" else "resume?checkpoint_step="+intent.getLongExtra("expectedCheckpoint",-1)),"POST")
                        remote.put("complete",false);Store.atomic(File(project,"remote.json"),remote)
                    } else if(!remote.optBoolean("submitted")) {
                        val existing=try {status()} catch(e:ServerError) {if(e.status==404)null else throw e}
                        if(existing!=null) {require(existing.optString("phase")!="uploading") { "The server is still receiving this upload. Wait, then Resume/download." };result=existing}
                        else {Store.validateDataset(File(project,"dataset.zip"));result=http.upload(File(project,"dataset.zip"),id,remote.getString("mode"),remote.getString("preset")) { sent,total->progress("Uploading original dataset",sent,total) };lastContact=System.currentTimeMillis()}
                        remote.put("submitted",true);Store.atomic(File(project,"remote.json"),remote)
                    } else result=status()
                    serverCreated=RemoteJobStatus.milliseconds(result.optDouble("created",0.0))
                    rememberWorker(result)
                    while(result.optString("phase") !in listOf("complete","failed","cancelled")) {
                        check(!stopped) { "Cancelled" }
                        val message=result.optString("message","Working on your server")
                        val warning=result.optString("monitorWarning")
                        state(message+if(warning.isEmpty()) "" else " · $warning",extra=RemoteJobStatus.details(result))
                        val delay=polling.observe(result.optString("phase"),result.optString("stage"),message)
                        Thread.sleep(delay);result=status()
                    }
                    if(result.has("recovery")){remote.put("recovery",result.getJSONObject("recovery"));Store.atomic(File(project,"remote.json"),remote)}
                    when(result.getString("phase")) {
                        "failed"->{Store.atomic(File(project,"remote.json"),remote.put("complete",true));state(result.optString("message","Server reconstruction stopped"),"failed",RemoteJobStatus.details(result));return@thread}
                        "cancelled"->{Store.atomic(File(project,"remote.json"),remote.put("complete",true));state("Server job cancelled","cancelled");return@thread}
                    }
                    val output=File(project,"output").apply { mkdirs() };val artifacts=result.getJSONArray("artifacts")
                    for(i in 0 until artifacts.length()) {
                        val kind=artifacts.getString(i)
                        val name=when(kind) { "splat"->"remote_${id}.ply";"glb"->"mesh.glb";"obj"->"mesh.obj";"stl"->"mesh.stl";"points"->"dense-points.ply";else->error("Unknown server artifact") }
                        val destination=File(output,name)
                        val pending=File(output,"$name.pending")
                        try {
                            http.download("/v1/jobs/$id/artifacts/$kind",pending) { count,total->progress("Downloading $kind",count,total) }
                            if(kind=="splat") PlyFile.inspect(pending)
                            if(kind=="glb") pending.inputStream().use { input->val header=ByteArray(12);java.io.DataInputStream(input).readFully(header);val bytes=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);require(String(header,0,4,Charsets.US_ASCII)=="glTF" && bytes.getInt(4)==2 && bytes.getInt(8).toLong()==pending.length()) { "Server returned an invalid GLB" } }
                            check(pending.renameTo(destination)) { "Could not publish downloaded $kind" }
                            if(kind=="splat") Store.validateModel(project,destination)
                        } finally { pending.delete() }
                    }
                    val completion=RemoteJobStatus.completion(result)
                    remote.put("complete",true).put("result",completion);Store.atomic(File(project,"remote.json"),remote)
                    state(completion.getString("message"),"complete",completion)
                } catch(e:Exception) { if(!stopped) state("Server job: ${e.message}. The original dataset is kept. Resume/download only reconnects; Recover saved outputs inspects existing files.","failed") }
                finally { stopSelf() }
            }
        } catch(e:Exception) { if(::project.isInitialized) state(e.message ?: "Could not start server transfer","failed");stopSelf() }
        return START_NOT_STICKY
    }
    private fun progress(label:String,count:Long,total:Long) {
        val now=System.currentTimeMillis();if(now-lastWrite<700 && count!=total) return;lastWrite=now
        state(if(total>0) "$label · ${count*100/total}%" else "$label · ${count/1048576} MiB",extra=JSONObject().put("stage",if(label.startsWith("Downloading")) "downloading" else "uploading").put("transferBytes",count).put("transferTotal",total))
    }
    override fun onDestroy() { connection?.stop();worker?.interrupt();wake?.let { if(it.isHeld) it.release() };observedProject="";stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy() }
}
