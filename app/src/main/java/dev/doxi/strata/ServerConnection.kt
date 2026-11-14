package dev.doxi.strata

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ServerAddress {
    fun normalize(text:String):String {
        val uri=URI(text.trim())
        require(uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null) { "Enter an http:// LAN address or an https:// server URL, without credentials or query parameters" }
        require(uri.port in -1..65535 && uri.port!=0 && !text.contains('\n') && !text.contains('\r')) { "Invalid server address" }
        val host=uri.host.lowercase().removePrefix("[").removeSuffix("]")
        if(uri.scheme=="http") {
            val parts=host.split('.')
            val numbers=if(parts.size==4 && parts.all {it.matches(Regex("[0-9]{1,3}"))}) parts.map {it.toInt()} else emptyList()
            val privateIp=numbers.size==4 && numbers.all { it in 0..255 } && (numbers[0]==10 || numbers[0]==127 || numbers[0]==192 && numbers[1]==168 || numbers[0]==172 && numbers[1] in 16..31)
            require(host=="localhost" || privateIp || (host.startsWith("fd") || host.startsWith("fc")) && host.contains(':') || host.startsWith("fe80:") || host=="::1") { "Plain HTTP is restricted to private LAN addresses. Use HTTPS for remote servers." }
        }
        require(uri.host!="strata.local") { "Choose the GPU server's address" }
        return uri.normalize().toString().trimEnd('/')
    }
}

data class ServerProfile(val url:String,val key:String,val proxy:String="") {
    fun json()=JSONObject().put("url",url).put("key",key).put("proxy",proxy)
}

class SecureServer(private val context:Context,private val scope:String="server") {
    private val prefs=context.getSharedPreferences(scope,Context.MODE_PRIVATE)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("strata-$scope",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder("strata-$scope",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    fun snapshot()=prefs.getString("profile",null) ?: error("Connect a GPU server first")
    fun decode(value:String):ServerProfile {
        val bytes=Base64.decode(value,Base64.NO_WRAP);val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val obj=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        return ServerProfile(ServerAddress.normalize(obj.getString("url")),obj.getString("key"),obj.optString("proxy"))
    }
    fun get():ServerProfile?=try { decode(snapshot()) } catch(_:Exception) { null }
    fun save(url:String,token:String,proxy:String) {
        val normalized=ServerAddress.normalize(url);val old=get()?.takeIf { it.url==normalized }
        val secret=token.trim().ifEmpty { old?.key ?: "" }
        val proxySecret=if(proxy=="-") "" else proxy.trim().ifEmpty { old?.proxy ?: "" }
        require(secret.length in 24..512 && secret.none { it=='\n' || it=='\r' }) { "Enter the server access key (at least 24 characters)" }
        require(proxySecret.isEmpty() || proxySecret.matches(Regex("[A-Za-z0-9_.-]{10,1024}"))) { "Invalid Modal proxy token" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val encoded=Base64.encodeToString(cipher.iv+cipher.doFinal(ServerProfile(normalized,secret,proxySecret).json().toString().toByteArray()),Base64.NO_WRAP)
        prefs.edit().putString("profile",encoded).apply()
    }
    fun forget() { prefs.edit().clear().apply() }
    fun publicInfo()=get()?.let { JSONObject().put("configured",true).put("url",it.url).put("hasProxy",it.proxy.isNotEmpty()) } ?: JSONObject().put("configured",false)
}

class ServerError(val status:Int,message:String):java.io.IOException(message)
class ServerHttp(private val profile:ServerProfile,private val connectionFactory:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}) {
    @Volatile private var active:HttpURLConnection?=null
    @Volatile var stopped=false
    fun stop() { stopped=true;active?.disconnect() }
    private fun open(address:String,method:String):HttpURLConnection {
        check(!stopped) { "Transfer cancelled" }
        return connectionFactory(URL(address)).apply {
            instanceFollowRedirects=false;requestMethod=method;connectTimeout=20000;readTimeout=180000
            setRequestProperty("X-Strata-Key",profile.key)
            if(profile.proxy.isNotEmpty()) setRequestProperty("Authorization","Bearer ${profile.proxy}")
            active=this
        }
    }
    private fun close(c:HttpURLConnection) { c.disconnect();if(active===c)active=null }
    private fun request(path:String,method:String,readTimeoutMs:Int=180000,body:((HttpURLConnection)->Unit)?=null):HttpURLConnection {
        var address=profile.url+path;var verb=method;var redirects=0;var replays=0
        while(true) {
            val c=open(address,verb);c.readTimeout=readTimeoutMs
            try {
                if(verb=="POST") {
                    if(body!=null) body(c) else {c.doOutput=true;c.setFixedLengthStreamingMode(0);c.outputStream.close()}
                }
                val status=c.responseCode
                if(status in ServerRedirect.statuses) {
                    if(redirects++>=ServerRedirect.LIMIT)throw ServerError(status,"Server redirect limit reached. The job may still be running; resume monitoring later.")
                    val next=try {ServerRedirect.next(address,status,c.getHeaderField("Location"),verb)}catch(e:Exception){throw ServerError(status,e.message ?: "Unsafe server redirect")}
                    if(next.method=="POST"&&++replays>3)throw ServerError(status,"POST redirect limit reached; the request was not sent again.")
                    close(c);address=next.url;verb=next.method;continue
                }
                check(c)
                return c
            } catch(e:Exception) {close(c);throw e}
        }
    }
    private fun check(connection:HttpURLConnection) {
        val code=connection.responseCode
        if(code !in 200..299) {
            val detail=connection.errorStream?.bufferedReader()?.use { reader->val chars=CharArray(1800);val n=reader.read(chars);if(n>0)String(chars,0,n) else "HTTP $code" } ?: "HTTP $code"
            throw ServerError(code,if(code in 300..399) "Unsupported server response (HTTP $code). The saved endpoint was kept." else "Server returned $code: $detail")
        }
    }
    fun json(path:String,method:String="GET",readTimeoutMs:Int=180000):JSONObject {
        val c=request(path,method,readTimeoutMs)
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText().also { text->require(text.length<1024*1024) { "Server response is too large" } } }) }
        finally { close(c) }
    }
    fun upload(file:File,id:String,mode:String,preset:String,onBytes:(Long,Long)->Unit):JSONObject {
        require(mode in listOf("splat","mesh","both") && preset in listOf("quick","detail","max"))
        val c=request("/v1/jobs?mode=$mode&preset=$preset","POST") { connection->
            connection.doOutput=true;connection.setFixedLengthStreamingMode(file.length());connection.setRequestProperty("Content-Type","application/zip");connection.setRequestProperty("X-Request-ID",id)
            connection.outputStream.buffered(65536).use { out->file.inputStream().use { input->val buffer=ByteArray(65536);var sent=0L;while(true) { check(!stopped) { "Transfer cancelled" };val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n);sent+=n;onBytes(sent,file.length()) } } }
        }
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) }
        finally { close(c) }
    }
    fun download(path:String,destination:File,onBytes:(Long,Long)->Unit) {
        val c=request(path,"GET");val temp=File(destination.parentFile,destination.name+".part")
        try {
            val total=c.contentLengthLong;require(total<=1024L*1024*1024) { "Artifact exceeds the 1 GiB download limit" }
            c.inputStream.use { input->temp.outputStream().buffered().use { out->val buffer=ByteArray(65536);var received=0L;while(true) { check(!stopped) { "Transfer cancelled" };val n=input.read(buffer);if(n<0) break;received+=n;require(received<=1024L*1024*1024);out.write(buffer,0,n);onBytes(received,total) };require(total<0 || received==total) { "Download was truncated" } } }
            check(temp.renameTo(destination)) { "Could not save downloaded artifact" }
        } finally { temp.delete();close(c) }
    }
}
