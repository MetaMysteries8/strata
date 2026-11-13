package dev.doxi.strata
import android.content.Context
import org.json.JSONObject
import java.net.URI

object PhoneModal {
    const val URL="http://127.0.0.1:8788"
    val setupCommand = listOf(
        "termux-setup-storage",
        "pkg install -y unzip",
        "mkdir -p \"\$HOME/strata-phone\"",
        "unzip -o \"\$HOME/storage/downloads/Strata-Modal-Android.zip\" -d \"\$HOME/strata-phone\"",
        "bash \"\$HOME/strata-phone/strata-modal/android_runtime/setup.sh\""
    ).joinToString(" && ")
    // Old installs have setup.sh but no start.sh. Their installer reuses Ubuntu and credentials.
    val restartCommand = "if [ -f \"\$HOME/strata-phone/strata-modal/android_runtime/start.sh\" ]; then bash \"\$HOME/strata-phone/strata-modal/android_runtime/start.sh\"; else bash \"\$HOME/strata-phone/strata-modal/android_runtime/setup.sh\"; fi"
    fun store(context:Context)=SecureServer(context,"modal-runtime")
    fun client(context:Context):ServerHttp {
        val profile=store(context).get() ?: error("Run phone setup once, then paste its runtime pairing key")
        require(profile.url==URL) {"Phone runtime must use loopback"};return ServerHttp(profile)
    }
    fun health(context:Context):JSONObject {
        val state=client(context).json("/v1/health")
        require(state.optString("service")=="strata-modal-runtime" && state.optInt("protocol")==1) {"Incompatible phone runtime"};return state
    }
    fun loginUrl(value:String):String {
        val u=URI(value);require(u.scheme=="https" && u.host in listOf("modal.com","www.modal.com") && u.userInfo==null && u.port in listOf(-1,443)) {"Unexpected login address"};return u.toString()
    }
    fun endpoint(value:String):String {
        val u=URI(ServerAddress.normalize(value));require(u.scheme=="https" && u.host.endsWith(".modal.run")) {"Expected your Modal HTTPS endpoint"};return u.toString()
    }
    fun connection(value:JSONObject):ServerProfile {
        val key=value.getString("key");val proxy=value.getString("proxy")
        require(key.length in 24..512 && key.none {it=='\r'||it=='\n'}) {"Invalid endpoint access key"}
        require(proxy.matches(Regex("[A-Za-z0-9_.-]{10,1024}"))) {"Invalid Modal endpoint credential"}
        return ServerProfile(endpoint(value.getString("url")),key,proxy)
    }
    fun diagnostics(value:JSONObject):JSONObject {
        val safe=JSONObject()
        for(key in listOf("service","version","python","authPhase","deploymentPhase","stage"))
            if(value.has(key)) safe.put(key,value.optString(key).take(160))
        safe.put("hasEndpoint",value.optBoolean("hasEndpoint"))
        return safe
    }
}
