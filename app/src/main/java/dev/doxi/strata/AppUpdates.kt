package dev.doxi.strata

import android.app.*
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object AppUpdates {
    @Volatile var checking=false
    private fun prefs(c:Context)=c.getSharedPreferences("release-updates",Context.MODE_PRIVATE)
    fun auto(c:Context)=prefs(c).getBoolean("auto",true)
    fun setAuto(c:Context,value:Boolean){prefs(c).edit().putBoolean("auto",value).apply()}
    fun due(c:Context)=auto(c)&&System.currentTimeMillis()-prefs(c).getLong("checked",0)>12*60*60*1000L
    fun installed(c:Context)=c.packageManager.getPackageInfo(c.packageName,0).longVersionCode
    private fun release(c:Context)=prefs(c).getString("release",null)?.let {GitHubRelease.saved(JSONObject(it))} ?: error("Check for an update first")
    fun check(c:Context) {
        synchronized(this){if(checking)return;checking=true}
        try {
            val found=GitHubRelease.latest()
            prefs(c).edit().putLong("checked",System.currentTimeMillis()).putString("release",found.json().toString()).remove("error").apply()
        }catch(e:Exception){prefs(c).edit().putLong("checked",System.currentTimeMillis()).putString("error",e.message ?: "Release check failed").apply()}
        finally {checking=false}
    }
    private fun destination(c:Context)=File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Download storage unavailable"),"strata-update.apk")
    fun status(c:Context):JSONObject {
        val p=prefs(c);val value=JSONObject().put("auto",auto(c)).put("checking",checking).put("repo",GitHubRelease.PAGE).put("installedCode",installed(c))
        val found=try{release(c)}catch(_:Exception){null}
        if(found!=null)value.put("version",found.version).put("available",found.code>installed(c)).put("size",found.apk.size)
        value.put("message",p.getString("error",if(found==null) "Check GitHub for a published update" else if(found.code>installed(c)) "Update available" else "You have the latest published Preview"))
        val id=p.getLong("download",-1)
        val pending=try{GitHubRelease.saved(JSONObject(p.getString("downloadRelease","") ?: ""))}catch(_:Exception){null}
        if(id>=0&&pending!=null&&pending.code>installed(c)){
            val dm=c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use {cursor->if(cursor.moveToFirst()){
                val state=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                value.put("downloadState",state).put("downloaded",state==DownloadManager.STATUS_SUCCESSFUL).put("downloading",state in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED))
                value.put("bytes",cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))).put("total",cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                if(state==DownloadManager.STATUS_FAILED)value.put("message","Download failed. Tap Download update to retry.")
            }}
        }
        return value
    }
    fun download(c:Context) {
        val found=release(c);require(found.code>installed(c)){"This release is already installed"}
        require(!status(c).optBoolean("downloading")){"An update is already downloading"}
        val dm=c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val previous=prefs(c).getLong("download",-1);if(previous>=0)dm.remove(previous)
        destination(c).delete()
        val request=DownloadManager.Request(Uri.parse(found.url)).setTitle("Strata Preview ${found.version}").setDescription("Download from MetaMysteries8/strata").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE).setDestinationInExternalFilesDir(c,Environment.DIRECTORY_DOWNLOADS,"strata-update.apk")
        val id=dm.enqueue(request)
        prefs(c).edit().putLong("download",id).putString("downloadRelease",found.json().toString()).remove("error").apply()
    }
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun install(activity:Activity) {
        require(status(activity).optBoolean("downloaded")){"Wait for the update download to finish"}
        if(!activity.packageManager.canRequestPackageInstalls()) {
            activity.runOnUiThread {
                Toast.makeText(activity,"Allow Strata to install updates, then return and tap Install update.",Toast.LENGTH_LONG).show()
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.packageName)))
            };return
        }
        val expected=GitHubRelease.saved(JSONObject(prefs(activity).getString("downloadRelease",null) ?: error("Missing download metadata")))
        val file=destination(activity);require(file.length()==expected.apk.size){"Downloaded APK size does not match release"}
        val sha=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;sha.update(buf,0,n)}}
        require(sha.digest().joinToString(""){"%02x".format(it)}==expected.apk.sha256){"Downloaded APK checksum does not match release"}
        val pm=activity.packageManager
        val archive=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Downloaded file is not an Android APK")
        val current=pm.getPackageInfo(activity.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val incoming=archive.signingInfo?.apkContentsSigners?.map {digest(it.toByteArray())}?.toSet() ?: emptySet()
        val existing=current.signingInfo?.apkContentsSigners?.map {digest(it.toByteArray())}?.toSet() ?: emptySet()
        require(expected.code>current.longVersionCode){"This APK is already installed"}
        GitHubRelease.verifyIdentity(expected,archive.packageName,archive.longVersionCode,incoming,existing)
        val installer=pm.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {setAppPackageName(activity.packageName);setSize(file.length());setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)}
        val id=installer.createSession(params)
        try {
            installer.openSession(id).use {session->
                val copied=MessageDigest.getInstance("SHA-256")
                session.openWrite("update.apk",0,file.length()).use {out->file.inputStream().use {input->val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0)break;copied.update(buf,0,n);out.write(buf,0,n)}};session.fsync(out)}
                require(copied.digest().joinToString(""){"%02x".format(it)}==expected.apk.sha256){"APK changed during installation preparation"}
                val callback=PendingIntent.getBroadcast(activity,id,Intent(activity,UpdateInstallReceiver::class.java).setAction(activity.packageName+".INSTALL_RESULT"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(callback.intentSender)
            }
        }catch(e:Exception){installer.abandonSession(id);throw e}
    }
}

class UpdateInstallReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        when(intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION->{
                @Suppress("DEPRECATION") val action=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if(action!=null)context.startActivity(action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS->Toast.makeText(context,"Strata update installed",Toast.LENGTH_LONG).show()
            else->Toast.makeText(context,"Update was not installed. You can retry from Settings.",Toast.LENGTH_LONG).show()
        }
    }
}
