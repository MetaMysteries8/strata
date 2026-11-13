package dev.doxi.strata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseTest {
    private val hash="a".repeat(64)
    private fun manifest()=JSONObject().put("schema",1).put("version","0.3.0").put("versionCode",10).put("signerSHA256",hash).put("apk",JSONObject().put("name","Strata-Preview-0.3.0.apk").put("size",100).put("sha256",hash))
    @Test fun releaseManifestPinsPackageAssetAndRepository() {
        val r=GitHubRelease.parse(manifest(),"v0.3.0")
        assertEquals("https://github.com/MetaMysteries8/strata/releases/download/v0.3.0/Strata-Preview-0.3.0.apk",r.url)
        assertEquals(r,GitHubRelease.saved(r.json()))
        for(tag in listOf("../main","v0.3.0/evil","v1.0"))assertThrows(IllegalArgumentException::class.java){GitHubRelease.parse(manifest(),tag)}
    }
    @Test fun invalidSizeHashAndAssetNameAreRejected() {
        for(key in listOf("name","sha256","size")) {
            val m=manifest();m.getJSONObject("apk").put(key,when(key){"name"->"../evil.apk";"size"->160L*1024*1024;else->"invalid"})
            assertThrows(IllegalArgumentException::class.java){GitHubRelease.parse(m,"v0.3.0")}
        }
    }
    @Test fun packageVersionAndInstalledSignerMustAllMatch() {
        val r=GitHubRelease.parse(manifest(),"v0.3.0")
        GitHubRelease.verifyIdentity(r,"dev.doxi.strata.preview",10,setOf(hash),setOf(hash))
        assertThrows(IllegalArgumentException::class.java){GitHubRelease.verifyIdentity(r,"other.package",10,setOf(hash),setOf(hash))}
        assertThrows(IllegalArgumentException::class.java){GitHubRelease.verifyIdentity(r,"dev.doxi.strata.preview",9,setOf(hash),setOf(hash))}
        assertThrows(IllegalArgumentException::class.java){GitHubRelease.verifyIdentity(r,"dev.doxi.strata.preview",10,setOf("b".repeat(64)),setOf(hash))}
    }
    @Test fun redirectPolicyRejectsCredentialsAndUntrustedHosts() {
        assertTrue(GitHubRelease.allowed("https://release-assets.githubusercontent.com/file?sig=test"))
        for(url in listOf("http://github.com/a","https://github.com.evil.test/a","https://user@github.com/a","https://github.com:123/a","file:///tmp/a"))assertFalse(GitHubRelease.allowed(url))
    }
}
