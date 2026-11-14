package dev.doxi.strata

import java.net.URI

object ServerRedirect {
    const val LIMIT=20
    val statuses=setOf(301,302,303,307,308)
    data class Target(val url:String,val method:String)
    fun next(current:String,status:Int,location:String?,method:String):Target {
        require(status in statuses) { "Unsupported server redirect (HTTP $status)" }
        require(!location.isNullOrBlank()&&location.length<=8192&&location.none {it=='\r'||it=='\n'}) { "Server redirect (HTTP $status) has no valid destination" }
        val value=location.trim()
        val from:URI
        val to:URI
        try {
            from=URI(current)
            // URI.resolve drops the last path component for a query-only reference.
            to=if(value.startsWith("?")) URI(current.substringBefore('?')+value) else from.resolve(value)
        } catch(_:Exception) {throw IllegalArgumentException("Server redirect (HTTP $status) has an invalid destination")}
        fun port(uri:URI)=if(uri.port>=0)uri.port else if(uri.scheme=="https")443 else 80
        require(to.scheme==from.scheme&&to.host!=null&&to.host.equals(from.host,true)&&port(to)==port(from)&&to.userInfo==null&&to.rawFragment==null) {
            "Server redirect (HTTP $status) changed host, protocol, or port, or supplied an unsafe URL. Credentials were not forwarded."
        }
        require(method in setOf("GET","HEAD")||status in setOf(303,307,308)) {
            "Server redirect (HTTP $status) cannot safely replay this request. Check the server's route configuration."
        }
        return Target(to.toString(),if(status==303&&method!="HEAD")"GET" else method)
    }
}
