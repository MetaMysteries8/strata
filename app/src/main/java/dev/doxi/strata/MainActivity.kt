package dev.doxi.strata

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var exportFile:File?=null
    private val main=Handler(Looper.getMainLooper())
    private var viewing:String?=null
    @Volatile private var wantsModalBrowser=false
    @Volatile private var modalRequest=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web=WebView(this)
        web.setBackgroundColor(0xff111613.toInt())
        web.settings.apply { javaScriptEnabled=true; domStorageEnabled=true; allowFileAccess=false; allowContentAccess=false; mediaPlaybackRequiresUserGesture=true; mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW }
        web.addJavascriptInterface(Bridge(),"Strata")
        web.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest) = request.url.host!="strata.local"
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse {
                val uri=request.url
                if(uri.scheme!="https" || uri.host!="strata.local") return denied()
                return try {
                    val path=uri.path?.removePrefix("/") ?: "app.html"
                    if(path.contains("..")) return denied()
                    if(path=="thumb.jpg") {
                        val dir=Store.dir(this@MainActivity,uri.getQueryParameter("id") ?: "")
                        val thumb=File(dir,"capture/images").listFiles()?.filter { it.extension=="jpg" }?.minByOrNull { it.name } ?: return denied()
                        WebResourceResponse("image/jpeg",null,thumb.inputStream())
                    } else if(path=="model.ply" || path.startsWith("scans/")) {
                        val segments=path.split('/')
                        val ident=if(path=="model.ply") uri.getQueryParameter("id") ?: "" else segments.getOrNull(1) ?: ""
                        val kind=if(path=="model.ply") "model.ply" else segments.getOrNull(2)
                        val dir=Store.dir(this@MainActivity,ident)
                        val model=when(kind) { "model.ply"->Store.model(dir);"mesh.glb"->Store.mesh(dir);else->null } ?: return denied()
                        WebResourceResponse(if(kind=="mesh.glb") "model/gltf-binary" else "application/octet-stream",null,200,"OK",mapOf("Content-Length" to model.length().toString()),model.inputStream())
                    } else {
                        val mime=when(path.substringAfterLast('.')) { "html"->"text/html"; "js"->"application/javascript"; "css"->"text/css"; else->"application/octet-stream" }
                        WebResourceResponse(mime,"UTF-8",assets.open(path))
                    }
                } catch (_:Exception) { denied() }
            }
        }
        val root=android.widget.FrameLayout(this)
        root.addView(web,android.widget.FrameLayout.LayoutParams(-1,-1))
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        setContentView(root)
        web.loadUrl("https://strata.local/app.html")
    }
    private fun denied()=WebResourceResponse("text/plain","UTF-8",404,"Not found",emptyMap(),ByteArrayInputStream("Not found".toByteArray()))
    private fun error(e:Throwable) { runOnUiThread { AlertDialog.Builder(this).setTitle("Strata").setMessage(e.message ?: "Something went wrong").setPositiveButton("OK",null).show() } }
    private fun refresh() { if(viewing==null) web.evaluateJavascript("window.refresh?.()",null) }
    private fun serverResult(json:JSONObject) { runOnUiThread { if(!isDestroyed) web.evaluateJavascript("window.onServerResult?.(${json})",null) } }
    private fun modalResult(json:JSONObject) { runOnUiThread { if(!isDestroyed) web.evaluateJavascript("window.onModalResult?.(${json})",null) } }
    private fun modalStatus(state:JSONObject) {
        val safe=JSONObject();for(key in listOf("service","protocol","version","auth","deployment","loginReady","code","hasProfile","hasConnection")) if(state.has(key)) safe.put(key,state.get(key))
        modalResult(JSONObject().put("ok",true).put("state",safe))
    }
    private fun phoneModal(action:String) {
        if(action=="cancel") {
            wantsModalBrowser=false
            thread {try {PhoneModal.client(this).json("/v1/cancel","POST");modalStatus(PhoneModal.health(this))}catch(e:Exception){modalResult(JSONObject().put("ok",false).put("message",e.message))}}
            return
        }
        if(modalRequest) return
        modalRequest=true
        thread(name="strata-modal") { try {
            val client=PhoneModal.client(this)
            when(action) {
                "signin"->{wantsModalBrowser=true;client.json("/v1/auth/start","POST")}
                "deploy"->client.json("/v1/deploy","POST")
                "update"->client.json("/v1/release/update-deploy","POST")
                "cancel"->{wantsModalBrowser=false;client.json("/v1/cancel","POST")}
                "logout"->{wantsModalBrowser=false;client.json("/v1/auth/logout","POST")}
                "verify"->client.json("/v1/auth/verify","POST")
                "login-page"->wantsModalBrowser=true
                "connect"->{
                    val endpoint=PhoneModal.connection(client.json("/v1/connection"))
                    val health=ServerHttp(endpoint).json("/v1/health")
                    require(health.optString("service")=="strata" && health.optInt("protocol")==1) {"Endpoint did not pass health verification"}
                    SecureServer(this).save(endpoint.url,endpoint.key,endpoint.proxy)
                    serverResult(JSONObject().put("ok",true).put("health",health))
                }
                "diagnostics"->{
                    val diagnostics=PhoneModal.diagnostics(client.json("/v1/diagnostics"))
                    runOnUiThread {(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Strata runtime diagnostics",diagnostics.toString(2)));Toast.makeText(this,"Setup diagnostics copied. No account keys are included.",Toast.LENGTH_LONG).show()}
                }
                "status"->{}
                else->error("Unknown Modal action")
            }
            val state=PhoneModal.health(this)
            if(state.optBoolean("loginReady") && wantsModalBrowser) {
                val url=PhoneModal.loginUrl(client.json("/v1/auth/browser").getString("url"));wantsModalBrowser=false
                runOnUiThread { if(!isDestroyed) try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch(e:Exception){error(e)} }
            }
            modalStatus(state)
        } catch(e:Exception) { modalResult(JSONObject().put("ok",false).put("message",e.message ?: "Runtime connection failed")) }
        finally {modalRequest=false} }
    }
    private fun notifications() { if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),20) }
    inner class Bridge {
        @JavascriptInterface fun projects():String {
            // A native crash cannot leave a project claiming that training is still running.
            val running=activeTraining()
            if(!running) Store.root(this@MainActivity).listFiles()?.forEach { dir ->
                try { val s=Store.state(dir); if(s.optString("phase") in listOf("starting","training","remote")) Store.atomic(File(dir,"state.json"),s.put("phase","failed").put("message","Processing disconnected. Use Resume/download for server jobs, or retrain from the saved dataset.")) } catch (_:Exception) {}
            }
            return Store.list(this@MainActivity).toString()
        }
        @JavascriptInterface fun device():String {
            val memory=ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            return JSONObject().put("name","${Build.MANUFACTURER} ${Build.MODEL}").put("android",Build.VERSION.RELEASE).put("ramGB",memory.totalMem/(1024.0*1024*1024)).put("vulkan",packageManager.hasSystemFeature("android.hardware.vulkan.level")).put("availableGB",filesDir.usableSpace/(1024.0*1024*1024)).toString()
        }
        @JavascriptInterface fun capture(name:String) { runOnUiThread {
            if(activeTraining()) { error(IllegalStateException("Finish or cancel reconstruction before capturing a new scan.")); return@runOnUiThread }
            startActivity(Intent(this@MainActivity,CaptureActivity::class.java).putExtra("name",name.take(80)))
        } }
        @JavascriptInterface fun train(id:String,preset:String) { runOnUiThread { try {
            check(!activeTraining()) { "A reconstruction is already running" }
            val dir=Store.dir(this@MainActivity,id); Store.validateDataset(File(dir,"dataset.zip"))
            notifications()
            startForegroundService(Intent(this@MainActivity,TrainingService::class.java).putExtra("id",id).putExtra("preset",preset))
            main.postDelayed({refresh()},1000)
        } catch(e:Exception) {error(e)} } }
        @JavascriptInterface fun cancel() { runOnUiThread { for(type in listOf(TrainingService::class.java,RemoteService::class.java)) if(serviceRunning(type)) startService(Intent(this@MainActivity,type).setAction("stop")); main.postDelayed({refresh()},600) } }
        @JavascriptInterface fun view(id:String) { runOnUiThread { try {
            check(!activeTraining()) { "Finish or cancel GPU reconstruction before opening the viewer." }
            val dir=Store.dir(this@MainActivity,id)
            thread { try { Store.validateModel(dir);runOnUiThread { viewing=id;web.loadUrl("https://strata.local/viewer.html?id=$id") } } catch(e:Exception) { error(e);runOnUiThread {refresh()} } }
        } catch(e:Exception) {error(e)} } }
        @JavascriptInterface fun viewMesh(id:String) { runOnUiThread { try { check(!activeTraining());check(Store.mesh(Store.dir(this@MainActivity,id))!=null) { "Generate a mesh on a GPU server first" };viewing=id;web.loadUrl("https://strata.local/viewer.html?id=$id&kind=mesh") } catch(e:Exception){error(e)} } }
        @JavascriptInterface fun serverInfo():String=SecureServer(this@MainActivity).publicInfo().toString()
        @JavascriptInterface fun appUpdateInfo():String=AppUpdates.status(this@MainActivity).toString()
        @JavascriptInterface fun appUpdate(action:String) { runOnUiThread {
            when(action) {
                "releases"->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(GitHubRelease.PAGE)))
                "auto-on","auto-off"->AppUpdates.setAuto(this@MainActivity,action=="auto-on")
                "check"->thread {AppUpdates.check(this@MainActivity);runOnUiThread {if(!isDestroyed)web.evaluateJavascript("window.updateInfo?.()",null)}}
                "download"->AlertDialog.Builder(this@MainActivity).setTitle("Download Strata update?").setMessage("Download the published APK from MetaMysteries8/strata. Android will ask you before installation; the app checks its checksum and signing identity.").setNegativeButton("Back",null).setPositiveButton("Download") {_,_->thread {try {AppUpdates.download(this@MainActivity)}catch(e:Exception){error(e)};runOnUiThread {if(!isDestroyed)web.evaluateJavascript("window.updateInfo?.()",null)}}}.show()
                "install"->{
                    if(serviceRunning(TrainingService::class.java)){error(IllegalStateException("Finish or cancel phone training before updating the app."));return@runOnUiThread}
                    AlertDialog.Builder(this@MainActivity).setTitle("Install Strata update?").setMessage("Android will confirm installation. App monitoring will close during an update; server jobs keep running and can be reconnected with Resume/download.").setNegativeButton("Back",null).setPositiveButton("Continue") {_,_->thread {try {AppUpdates.install(this@MainActivity)}catch(e:Exception){error(e)}}}.show()
                }
            }
        } }
        @JavascriptInterface fun copyRemoteId(id:String) { runOnUiThread {try {
            val ident=JSONObject(File(Store.dir(this@MainActivity,id),"remote.json").readText()).getString("id")
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Strata server job ID",ident))
            Toast.makeText(this@MainActivity,"Job ID copied",Toast.LENGTH_SHORT).show()
        } catch(e:Exception){error(e)} } }
        @JavascriptInterface fun modalInfo():String=JSONObject().put("paired",PhoneModal.store(this@MainActivity).get()!=null).put("setupCommand",PhoneModal.setupCommand).put("restartCommand",PhoneModal.restartCommand).toString()
        @JavascriptInterface fun modalPair(key:String) { thread { try {PhoneModal.store(this@MainActivity).save(PhoneModal.URL,key,"");modalStatus(PhoneModal.health(this@MainActivity))} catch(e:Exception){modalResult(JSONObject().put("ok",false).put("message",e.message))} } }
        @JavascriptInterface fun modalAction(action:String) { runOnUiThread {
            if(action=="deploy"||action=="update") AlertDialog.Builder(this@MainActivity).setTitle(if(action=="update") "Update server from GitHub?" else "Deploy your Modal scan server?").setMessage(if(action=="update") "Download and verify the latest published server bundle from MetaMysteries8/strata, check for active jobs, and redeploy using this phone's existing Modal account and saved deployment identity. Builds and hosting may incur charges. Keep Termux running." else "This creates or updates Strata's protected endpoint, storage, and access keys in your Modal account. Image builds and hosting may incur charges. Scans upload only when you later choose server reconstruction.").setNegativeButton("Back") {_,_->modalResult(JSONObject().put("ok",false).put("message","Deployment not started"))}.setPositiveButton(if(action=="update") "Update server" else "Deploy and connect") {_,_->phoneModal(action)}.show()
            else phoneModal(action)
        } }
        @JavascriptInterface fun modalSetup(action:String) { runOnUiThread { try {when(action) {
            "copy"->{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Strata phone setup",PhoneModal.setupCommand));Toast.makeText(this@MainActivity,"Paste the copied command in Termux.",Toast.LENGTH_LONG).show()}
            "restart-copy"->{(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Restart Strata runtime",PhoneModal.restartCommand));Toast.makeText(this@MainActivity,"Open Termux, paste the restart command, then check connection here.",Toast.LENGTH_LONG).show()}
            "dashboard"->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://modal.com/apps")))
            "termux"->{val launch=packageManager.getLaunchIntentForPackage("com.termux") ?: error("Install Termux using Get Termux first");startActivity(launch)}
            "install"->startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://f-droid.org/packages/com.termux/")))
            "export"->{exportFile=File(cacheDir,"Strata-Modal-Android.zip").also {f->assets.open("modal-runtime.zip").use {i->f.outputStream().use {i.copyTo(it)}}};startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Strata-Modal-Android.zip"),12)}
            else->error("Unknown setup action")
        }} catch(e:Exception){error(e)} } }
        @JavascriptInterface fun saveServer(url:String,key:String,proxy:String) { thread { try { SecureServer(this@MainActivity).save(url,key,proxy);probeServer() } catch(e:Exception) {serverResult(JSONObject().put("ok",false).put("message",e.message))} } }
        @JavascriptInterface fun probeServer() { thread { try { val profile=SecureServer(this@MainActivity).get() ?: error("Save a server address and access key first");val result=ServerHttp(profile).json("/v1/health");require(result.optString("service")=="strata" && result.optInt("protocol")==1) { "This is not a compatible Strata server" };serverResult(JSONObject().put("ok",true).put("health",result).put("url",profile.url)) } catch(e:Exception) {serverResult(JSONObject().put("ok",false).put("message",e.message))} } }
        @JavascriptInterface fun forgetServer() { SecureServer(this@MainActivity).forget();serverResult(JSONObject().put("ok",true).put("forgotten",true)) }
        @JavascriptInterface fun remote(id:String,mode:String,preset:String) { runOnUiThread { try {
            check(!activeTraining()) { "Finish or cancel the current task first" };require(mode in listOf("splat","mesh","both") && preset in listOf("quick","detail","max"))
            val dir=Store.dir(this@MainActivity,id);check(File(dir,"dataset.zip").exists()) { "This scan has no original dataset" }
            val profile=SecureServer(this@MainActivity).get() ?: error("Connect a server in Settings first")
            val message="Send this scan's original photos, camera poses, and seed points to ${profile.url} to reconstruct $mode? Phone capture stays local until you choose Upload. Your hosting provider may charge for GPU time and storage."
            AlertDialog.Builder(this@MainActivity).setTitle("Reconstruct on your server?").setMessage(message).setNegativeButton("Keep local",null).setPositiveButton("Upload and reconstruct") { _,_->notifications();startForegroundService(Intent(this@MainActivity,RemoteService::class.java).putExtra("id",id).putExtra("mode",mode).putExtra("preset",preset));main.postDelayed({refresh()},1000) }.show()
        } catch(e:Exception){error(e)} } }
        @JavascriptInterface fun resumeRemote(id:String) { runOnUiThread { try { check(!activeTraining());Store.dir(this@MainActivity,id);notifications();startForegroundService(Intent(this@MainActivity,RemoteService::class.java).putExtra("id",id).putExtra("resume",true));main.postDelayed({refresh()},1000) } catch(e:Exception){error(e)} } }
        @JavascriptInterface fun back() { runOnUiThread { home() } }
        @JavascriptInterface fun importDataset() { runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),10)
        } }
        @JavascriptInterface fun export(id:String,kind:String) { runOnUiThread { try {
            val dir=Store.dir(this@MainActivity,id)
            check(!activeTraining()) { "Finish or cancel reconstruction before exporting." }
            require(kind in listOf("dataset","model","raw","glb","obj","stl","points"))
            val file=when(kind) { "dataset"->File(dir,"dataset.zip");"glb"->File(dir,"output/mesh.glb");"obj"->File(dir,"output/mesh.obj");"stl"->File(dir,"output/mesh.stl");"points"->File(dir,"output/dense-points.ply");else->Store.model(dir) ?: error("No PLY is available") }
            if(kind=="model") Store.validateModel(dir,file)
            check(file.exists()) { "No dataset is available" }; exportFile=file
            val name=Store.meta(dir).optString("name","scan").replace(Regex("[^a-zA-Z0-9_-]"),"_")
            val extension=when(kind) {"dataset"->"zip";"glb","obj","stl"->kind;else->"ply"}
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(if(kind=="dataset") "application/zip" else "application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"${name}.$extension"),11)
        } catch(e:Exception) {error(e)} } }
        @JavascriptInterface fun delete(id:String) { runOnUiThread { try {
            check(!activeTraining()) { "Finish or cancel reconstruction first." }
            val dir=Store.dir(this@MainActivity,id)
            AlertDialog.Builder(this@MainActivity).setTitle("Delete this scan?").setMessage("This removes the local images, camera poses, and model. Export a copy first if you want to keep it.").setNegativeButton("Keep",null).setPositiveButton("Delete") { _,_ -> thread { dir.deleteRecursively(); runOnUiThread { refresh() } } }.show()
        } catch(e:Exception) {error(e)} } }
    }
    private fun serviceRunning(type:Class<*>)=(getSystemService(ACTIVITY_SERVICE) as ActivityManager).getRunningServices(50).any { it.service.className==type.name }
    private fun activeTraining()=serviceRunning(TrainingService::class.java) || serviceRunning(RemoteService::class.java)
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data?.data==null) return
        val uri=data.data!!
        if(requestCode==11 || requestCode==12) {
            val source=exportFile ?: return
            thread { try { contentResolver.openOutputStream(uri,"w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }; runOnUiThread { Toast.makeText(this,"Export saved",Toast.LENGTH_SHORT).show() } } catch(e:Exception) {error(e)} }
        } else if(requestCode==10) {
            thread {
                var dir:File?=null
                try {
                    check(!activeTraining()) { "Finish or cancel reconstruction before importing." }
                    dir=Store.create(this,"Imported dataset")
                    val zip=File(dir,"dataset.zip")
                    contentResolver.openInputStream(uri)!!.use { input -> zip.outputStream().use { out ->
                        val buffer=ByteArray(65536); var size=0L
                        while(true) { val n=input.read(buffer); if(n<0) break; size+=n; check(size<=Store.MAX_ZIP) { "Dataset exceeds 600 MiB" }; out.write(buffer,0,n) }
                    } }
                    val frames=Store.validateDataset(zip)
                    Store.atomic(File(dir,"meta.json"),Store.meta(dir).put("frames",frames).put("kind","import"))
                    runOnUiThread { refresh() }
                } catch(e:Exception) { dir?.deleteRecursively(); error(e) }
            }
        }
    }
    private fun home() { viewing=null; web.loadUrl("https://strata.local/app.html") }
    override fun onBackPressed() { if(viewing!=null) home() else super.onBackPressed() }
    override fun onResume() { super.onResume(); if(AppUpdates.due(this)) thread {AppUpdates.check(this)}; if(::web.isInitialized) { web.onResume(); refresh();thread { if(!activeTraining()) Store.root(this).listFiles()?.forEach { dir->try { if(Store.model(dir)!=null) Store.validateModel(dir) } catch(e:Exception) { val s=Store.state(dir);if(s.optString("phase")=="complete") Store.atomic(File(dir,"state.json"),s.put("phase","failed").put("message",e.message)) } };runOnUiThread { if(!isDestroyed) refresh() } } } }
    override fun onPause() { if(::web.isInitialized) web.onPause(); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); web.removeJavascriptInterface("Strata"); web.destroy(); super.onDestroy() }
}
