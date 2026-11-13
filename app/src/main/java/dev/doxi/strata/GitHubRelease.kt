package dev.doxi.strata

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class ReleaseAsset(val name:String,val size:Long,val sha256:String)
data class AppRelease(val version:String,val code:Long,val signer:String,val apk:ReleaseAsset) {
    val url get()="https://github.com/MetaMysteries8/strata/releases/download/v$version/${apk.name}"
    fun json()=JSONObject().put("version",version).put("versionCode",code).put("signerSHA256",signer).put("name",apk.name).put("size",apk.size).put("sha256",apk.sha256)
}

object GitHubRelease {
    const val REPO="MetaMysteries8/strata"
    const val PAGE="https://github.com/MetaMysteries8/strata/releases"
    private val hosts=setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")
    fun allowed(value:String):Boolean=try {val u=URI(value);u.scheme=="https"&&u.host in hosts&&u.userInfo==null&&u.port in listOf(-1,443)}catch(_:Exception){false}
    fun bytes(address:String,limit:Int):ByteArray {
        var current=address
        repeat(6) {
            require(allowed(current)) {"Unexpected release download host"}
            val c=URL(current).openConnection() as HttpsURLConnection
            c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=30000
            c.setRequestProperty("User-Agent","Strata-Preview");c.setRequestProperty("Accept","application/vnd.github+json")
            try {
                val code=c.responseCode
                if(code in 300..399){current=URI(current).resolve(c.getHeaderField("Location") ?: error("Missing release redirect")).toString();return@repeat}
                require(code==200) {if(code==404) "No published GitHub release is available yet" else "GitHub returned HTTP $code. Try again later."}
                val out=ByteArrayOutputStream()
                c.inputStream.use {input->val buf=ByteArray(16384);while(true){val n=input.read(buf);if(n<0)break;require(out.size()+n<=limit){"Release response exceeds size limit"};out.write(buf,0,n)}}
                return out.toByteArray()
            } finally {c.disconnect()}
        }
        error("Too many release redirects")
    }
    fun parse(value:JSONObject,tag:String):AppRelease {
        require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))&&value.optInt("schema")==1&&"v"+value.getString("version")==tag){"Unsupported release manifest"}
        val version=value.getString("version");val code=value.getLong("versionCode");val signer=value.getString("signerSHA256")
        val a=value.getJSONObject("apk");val asset=ReleaseAsset(a.getString("name"),a.getLong("size"),a.getString("sha256"))
        require(code>0&&signer.matches(Regex("[a-f0-9]{64}"))&&asset.name=="Strata-Preview-$version.apk"&&asset.size in 1..150L*1024*1024&&asset.sha256.matches(Regex("[a-f0-9]{64}"))){"Invalid APK release metadata"}
        return AppRelease(version,code,signer,asset)
    }
    fun latest():AppRelease {
        val r=JSONObject(String(bytes("https://api.github.com/repos/$REPO/releases/latest",2*1024*1024),Charsets.UTF_8))
        require(!r.optBoolean("draft")&&!r.optBoolean("prerelease")){"A stable published release is required"}
        val tag=r.getString("tag_name");require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))){"Unexpected release tag"}
        return parse(JSONObject(String(bytes("https://github.com/$REPO/releases/download/$tag/release-manifest.json",65536),Charsets.UTF_8)),tag)
    }
    fun saved(value:JSONObject):AppRelease {
        val version=value.getString("version")
        return parse(JSONObject().put("schema",1).put("version",version).put("versionCode",value.getLong("versionCode")).put("signerSHA256",value.getString("signerSHA256")).put("apk",JSONObject().put("name",value.getString("name")).put("size",value.getLong("size")).put("sha256",value.getString("sha256"))),"v$version")
    }
    fun verifyIdentity(expected:AppRelease,packageName:String,versionCode:Long,archiveSigners:Set<String>,installedSigners:Set<String>) {
        require(packageName=="dev.doxi.strata.preview"&&versionCode==expected.code&&archiveSigners==installedSigners&&archiveSigners==setOf(expected.signer)){"APK package, version, or signing identity does not match this Preview"}
    }
}
