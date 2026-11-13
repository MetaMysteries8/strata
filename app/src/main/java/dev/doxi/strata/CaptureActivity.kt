package dev.doxi.strata

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.graphics.*
import android.opengl.*
import android.os.*
import android.view.*
import android.widget.*
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

@Suppress("DEPRECATION")
class CaptureActivity: Activity(), GLSurfaceView.Renderer {
    private lateinit var surface:GLSurfaceView
    private lateinit var status:TextView
    private lateinit var counter:TextView
    private lateinit var toggle:Button
    private lateinit var done:Button
    @Volatile private var session:Session?=null
    @Volatile private var recording=false
    @Volatile private var saving=false
    @Volatile private var frameCount=0
    @Volatile private var pointCount=0
    @Volatile private var captureError:String?=null
    private var installRequested=false
    private var texture=0
    private var shader=0
    private var pointShader=0
    private var depthEnabled=false
    private var depthFrames=0
    private var lastDepthTimestamp=0L
    @Volatile private var mappedPreview=FloatArray(0)
    private var displayedPreview:FloatArray?=null
    private var pointBuffer:FloatBuffer?=null
    private var previousPosition:FloatArray?=null
    private var previousRotation:FloatArray?=null
    private var lastCandidate=0L
    private var lastUi=0L
    private var pathMeters=0f
    private var previousPathPosition:FloatArray?=null
    private var directory:File?=null
    private var storage:FrameStorage?=null
    private val points=LinkedHashMap<String,Seed>()
    private val busy=AtomicBoolean(false)
    private val io=Executors.newSingleThreadExecutor()
    private val positions=buffer(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
    private val uv=buffer(FloatArray(8))
    private data class Seed(val x:Float,val y:Float,val z:Float,val color:IntArray)
    private fun dp(x:Int)=(resources.displayMetrics.density*x).roundToInt()
    private fun label(size:Float)=TextView(this).apply { textSize=size; setTextColor(Color.WHITE); setPadding(dp(20),dp(12),dp(20),dp(12)) }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=android.widget.FrameLayout(this)
        root.setOnApplyWindowInsetsListener { view,insets -> val bars=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets }
        surface=GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true; setRenderer(this@CaptureActivity) }
        root.addView(surface)
        val top=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(0xb2111613.toInt()); setPadding(0,dp(20),0,dp(4)) }
        top.addView(label(13f).apply { text="STRATA  /  CAPTURE"; setTextColor(0xffffffff.toInt()); letterSpacing=.18f })
        counter=label(25f).apply { text="Find your subject" }; top.addView(counter)
        status=label(14f).apply { text="Move slowly to establish camera tracking." }; top.addView(status)
        root.addView(top,android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        // Reticle is a capture aid, not an invented coverage score.
        root.addView(TextView(this).apply { text="+"; textSize=40f; gravity=Gravity.CENTER; setTextColor(0xb3ffffff.toInt()) },android.widget.FrameLayout.LayoutParams(dp(60),dp(60),Gravity.CENTER))
        val bottom=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(26)); setBackgroundColor(0xd9111613.toInt()) }
        bottom.addView(label(13f).apply { text="Walk around the subject, then repeat higher or lower. Keep the subject still and fill the frame."; setPadding(0,dp(8),0,dp(16)) })
        val row=LinearLayout(this)
        toggle=Button(this).apply { text="Start capture"; isAllCaps=false; setTextColor(0xffffffff.toInt()); backgroundTintList=android.content.res.ColorStateList.valueOf(0xffeb5757.toInt()); setOnClickListener { if(!saving) { captureError=null;previousPosition=null;previousRotation=null;recording=!recording; text=if(recording) "Pause" else "Resume" } } }
        done=Button(this).apply { text="Finish"; isAllCaps=false; setOnClickListener { finishCapture() } }
        row.addView(toggle,LinearLayout.LayoutParams(0,dp(58),1f)); row.addView(done,LinearLayout.LayoutParams(dp(112),dp(58)))
        bottom.addView(row); root.addView(bottom,android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA),1)
    }
    private fun resumeCamera() {
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) return
        try {
            if(session==null) {
                when(ArCoreApk.getInstance().requestInstall(this,!installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested=true; return }
                    else -> {}
                }
                val created=Session(this)
                val configs=created.getSupportedCameraConfigs(CameraConfigFilter(created).setTargetFps(java.util.EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30)))
                configs.filter { it.imageSize.width<=1920 && it.imageSize.height<=1920 }.maxByOrNull { it.imageSize.width*it.imageSize.height }?.let { created.cameraConfig=it }
                depthEnabled=created.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
                created.configure(Config(created).apply { focusMode=Config.FocusMode.AUTO; updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE; lightEstimationMode=Config.LightEstimationMode.DISABLED;if(depthEnabled) depthMode=Config.DepthMode.RAW_DEPTH_ONLY })
                session=created
            }
            session!!.resume(); surface.onResume()
        } catch(e:Exception) {
            recording=false
            status.text="Capture unavailable: ${e.message ?: e.javaClass.simpleName}. Install/update Google Play Services for AR; imported posed datasets still work."
            toggle.isEnabled=false
        }
    }
    override fun onResume() { super.onResume(); resumeCamera() }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) { super.onRequestPermissionsResult(code,permissions,results); if(code==1 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED) resumeCamera() else status.text="Camera permission is needed to capture a scan." }
    override fun onPause() { recording=false; if(::toggle.isInitialized) toggle.text="Resume"; surface.onPause(); session?.pause(); super.onPause() }
    override fun onDestroy() { io.shutdown(); session?.close(); session=null; super.onDestroy() }
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        val tex=IntArray(1); GLES20.glGenTextures(1,tex,0); texture=tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        fun compile(type:Int,source:String):Int=GLES20.glCreateShader(type).also { GLES20.glShaderSource(it,source); GLES20.glCompileShader(it); val ok=IntArray(1); GLES20.glGetShaderiv(it,GLES20.GL_COMPILE_STATUS,ok,0); check(ok[0]!=0) { GLES20.glGetShaderInfoLog(it) } }
        shader=GLES20.glCreateProgram()
        GLES20.glAttachShader(shader,compile(GLES20.GL_VERTEX_SHADER,"attribute vec2 a; attribute vec2 t; varying vec2 v; void main(){gl_Position=vec4(a,0.,1.);v=t;}"))
        GLES20.glAttachShader(shader,compile(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES camera; varying vec2 v; void main(){gl_FragColor=texture2D(camera,v);}"))
        GLES20.glLinkProgram(shader)
        pointShader=GLES20.glCreateProgram()
        GLES20.glAttachShader(pointShader,compile(GLES20.GL_VERTEX_SHADER,"attribute vec4 p;uniform mat4 m;varying float c;void main(){gl_Position=m*vec4(p.xyz,1.);gl_PointSize=5.;c=p.w;}"))
        GLES20.glAttachShader(pointShader,compile(GLES20.GL_FRAGMENT_SHADER,"precision mediump float;varying float c;void main(){vec2 d=gl_PointCoord-vec2(.5);if(dot(d,d)>.25||c<.5)discard;gl_FragColor=vec4(.35,.95,.9,.8);}"))
        GLES20.glLinkProgram(pointShader)
    }
    override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int) { GLES20.glViewport(0,0,width,height) }
    override fun onDrawFrame(gl:GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val active=session ?: return
        try {
            active.setDisplayGeometry(windowManager.defaultDisplay.rotation,surface.width,surface.height)
            active.setCameraTextureName(texture)
            val frame=active.update(); val camera=frame.camera
            positions.position(0); uv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,positions,Coordinates2d.TEXTURE_NORMALIZED,uv)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glUseProgram(shader)
            val a=GLES20.glGetAttribLocation(shader,"a"); val t=GLES20.glGetAttribLocation(shader,"t")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(shader,"camera"),0)
            positions.position(0); uv.position(0)
            GLES20.glEnableVertexAttribArray(a); GLES20.glEnableVertexAttribArray(t)
            GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,positions); GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,0,uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GLES20.glDisableVertexAttribArray(a); GLES20.glDisableVertexAttribArray(t)
            if(camera.trackingState!=TrackingState.TRACKING) { ui("Tracking paused · ${camera.trackingFailureReason.name.lowercase().replace('_',' ')}"); return }
            drawMappedPoints(camera)
            val pose=camera.pose
            val position=pose.translation; val rotation=pose.rotationQuaternion
            previousPathPosition?.let { val d=Geometry.distance(position,it); if(d<.5f) pathMeters+=d }
            previousPathPosition=position
            if(!recording) { ui(if(frameCount==0) "Tracking ready. Tap Start and move around your subject." else "Paused · tap Resume or Finish"); return }
            if(frameCount>=240) { recording=false; ui("240 keyframes captured. Tap Finish."); return }
            ui("Keep moving slowly · ${"%.1f".format(pathMeters)} m camera path")
            val now=SystemClock.elapsedRealtime()
            if(now-lastCandidate<300 || busy.get() || !Geometry.shouldCapture(position,rotation,previousPosition,previousRotation)) return
            lastCandidate=now
            val image=try { frame.acquireCameraImage() } catch(_:NotYetAvailableException) { return }
            val w=image.width; val h=image.height
            val nv=try {
                Geometry.toNv21(w,h,image.planes.map { p->ByteArray(p.buffer.remaining()).also { p.buffer.duplicate().get(it) } },image.planes.map { it.rowStride }.toIntArray(),image.planes.map { it.pixelStride }.toIntArray())
            } finally { image.close() }
            val (sharpness,light)=Geometry.imageQuality(nv,w,h)
            if(light<30 || light>235) { ui("${if(light<30) "Too dark" else "Too bright"} · improve lighting before moving on",true); return }
            if(sharpness<22) { ui("Frame is soft · hold steadier or move toward textured detail",true); return }
            val intrinsics=camera.imageIntrinsics
            val focal=intrinsics.focalLength; val principal=intrinsics.principalPoint
            val matrix=FloatArray(16); pose.toMatrix(matrix,0)
            val view=FloatArray(16); camera.getViewMatrix(view,0)
            // Sparse points are optional. A temporarily unavailable cloud must not discard an RGB keyframe.
            val cloudPoints=try { val cloud=frame.acquirePointCloud();try { FloatArray(cloud.points.remaining()).also { cloud.points.duplicate().get(it) } } finally { cloud.release() } } catch(_:Exception) { FloatArray(0) }
            val depth=if(depthEnabled) try { DepthCapture.acquire(frame)?.takeIf { it.timestamp!=lastDepthTimestamp }?.also { lastDepthTimestamp=it.timestamp } } catch(_:Exception) {null} else null
            busy.set(true); previousPosition=position; previousRotation=rotation
            io.execute {
                var stage="Creating capture directory"
                try {
                    if(directory==null) directory=Store.create(this,intent.getStringExtra("name") ?: "New scan")
                    val dir=directory!!
                    if(storage==null) storage=FrameStorage(File(dir,"capture"))
                    stage="Encoding JPEG ($w × $h)"
                    val jpeg=JpegEncoder.encode(nv,w,h)
                    stage="Saving image and camera pose"
                    frameCount=storage!!.save(jpeg,w,h,focal,principal,matrix,sharpness)
                    if(depth!=null) try {storage!!.addDepth(depth);depthFrames++} catch(e:Exception) {android.util.Log.w("StrataCapture","RGB frame saved; optional depth could not be saved",e)}
                    // Point-cloud updates are independent of the committed photo and pose.
                    try { val sources=listOf(cloudPoints,depth?.points ?: FloatArray(0));for(source in sources) for(i in 0 until source.size-3 step 4) {
                        if(points.size>=120000) break
                        val x=source[i];val y=source[i+1];val z=source[i+2]
                        val pixel=Geometry.projectSeed(view,floatArrayOf(x,y,z,source[i+3]),focal,principal,w,h) ?: continue
                        val key="${floor(x/.004f).toInt()},${floor(y/.004f).toInt()},${floor(z/.004f).toInt()}"
                        points[key]=Seed(x,y,z,Geometry.rgb(nv,w,h,pixel[0],pixel[1]))
                    } } catch(e:Exception) { android.util.Log.w("StrataCapture","Optional seed update failed; image is saved",e) }
                    pointCount=points.size
                    val preview=points.values.filterIndexed {index,_->index%max(1,points.size/8000)==0}.take(8000)
                    mappedPreview=FloatArray(preview.size*4).also { values->for((i,p) in preview.withIndex()){values[i*4]=p.x;values[i*4+1]=p.y;values[i*4+2]=p.z;values[i*4+3]=1f} }
                    try { Store.atomic(File(dir,"meta.json"),Store.meta(dir).put("frames",frameCount).put("points",pointCount).put("depthFrames",depthFrames).put("depthAvailable",depthEnabled).put("width",w).put("height",h)) }
                    catch(e:Exception) { android.util.Log.w("StrataCapture","Image saved; project metadata refresh failed",e) }
                } catch(e:Exception) { reportFailure(stage,e) }
                finally { busy.set(false) }
            }
        } catch(_:CameraNotAvailableException) { ui("Camera unavailable. Close other camera apps and reopen capture.",true) }
        catch(_:SessionPausedException) { /* Activity is pausing. */ }
        catch(e:Exception) { reportFailure("Reading camera frame",e) }
    }
    private fun ui(message:String,force:Boolean=false) {
        if(saving) return
        val now=SystemClock.elapsedRealtime(); if(!force && now-lastUi<250) return; lastUi=now
        runOnUiThread {
            counter.text="$frameCount views  ·  $pointCount mapped points"
            status.text=captureError ?: message
            if(!recording && !saving) toggle.text=if(frameCount==0) "Start capture" else "Resume"
        }
    }
    private fun drawMappedPoints(camera:com.google.ar.core.Camera) {
        val data=mappedPreview;if(data.isEmpty()) return
        if(data!==displayedPreview) {pointBuffer=buffer(data);displayedPreview=data}
        val projection=FloatArray(16);val view=FloatArray(16);val mvp=FloatArray(16)
        camera.getProjectionMatrix(projection,0,.05f,30f);camera.getViewMatrix(view,0);android.opengl.Matrix.multiplyMM(mvp,0,projection,0,view,0)
        GLES20.glUseProgram(pointShader);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(pointShader,"m"),1,false,mvp,0)
        val attribute=GLES20.glGetAttribLocation(pointShader,"p");pointBuffer!!.position(0);GLES20.glEnableVertexAttribArray(attribute);GLES20.glVertexAttribPointer(attribute,4,GLES20.GL_FLOAT,false,16,pointBuffer)
        GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);GLES20.glDrawArrays(GLES20.GL_POINTS,0,data.size/4);GLES20.glDisableVertexAttribArray(attribute);GLES20.glDisable(GLES20.GL_BLEND)
    }
    private fun reportFailure(stage:String,error:Exception) {
        if(captureError!=null) return
        recording=false
        val message="$stage: ${error.javaClass.simpleName}: ${error.message ?: "No error details"}"
        captureError=message
        android.util.Log.e("StrataCapture",message,error)
        try { File(filesDir,"capture-error.txt").writeText(message+"\n"+error.stackTraceToString()) } catch(_:Exception) {}
        ui(message,true)
        runOnUiThread { if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("Could not save frame").setMessage(message).setNegativeButton("Close",null).setPositiveButton("Copy error") { _,_->
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Strata capture error",message))
        }.show() }
    }
    private fun finishCapture() {
        if(saving) return
        recording=false
        if(frameCount<12) { AlertDialog.Builder(this).setTitle("Capture a little more").setMessage("At least 12 sharp views are needed. Walk around the subject; 60–180 views usually give the trainer more to work with.").setPositiveButton("Continue",null).show(); return }
        saving=true; toggle.isEnabled=false; done.isEnabled=false; status.text="Saving your images and camera poses…"
        io.execute {
            try {
                val dir=directory ?: error("No frames saved"); val capture=File(dir,"capture")
                val manifest=JSONObject(File(capture,"transforms.json").readText())
                if(points.size>=32) {
                    File(capture,"points.ply").bufferedWriter().use { out ->
                        out.write("ply\nformat ascii 1.0\nelement vertex ${points.size}\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n")
                        for(p in points.values) out.write("${p.x} ${p.y} ${p.z} ${p.color[0]} ${p.color[1]} ${p.color[2]}\n")
                    }
                    manifest.put("ply_file_path","points.ply")
                    Store.atomic(File(capture,"transforms.json"),manifest)
                }
                Store.pack(dir); Store.validateDataset(File(dir,"dataset.zip"))
                Store.atomic(File(dir,"meta.json"),Store.meta(dir).put("frames",frameCount).put("points",pointCount))
                Store.atomic(File(dir,"state.json"),JSONObject().put("phase","ready").put("message","$frameCount views saved. Choose a preset to reconstruct."))
                runOnUiThread { finish() }
            } catch(e:Exception) { saving=false;reportFailure("Packaging capture",e);runOnUiThread { toggle.isEnabled=true;done.isEnabled=true } }
        }
    }
    override fun onBackPressed() {
        if(saving) return
        if(frameCount==0) { super.onBackPressed(); return }
        recording=false
        AlertDialog.Builder(this).setTitle("Finish this capture?").setMessage("${frameCount} views have been saved. Finish to package this capture, or discard it.").setNeutralButton("Keep capturing",null).setPositiveButton("Finish") { _,_->finishCapture() }.setNegativeButton("Discard") { _,_->
            saving=true; io.execute { directory?.deleteRecursively(); runOnUiThread { finish() } }
        }.show()
    }
    companion object { private fun buffer(values:FloatArray)=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) } }
}
