package dev.doxi.strata

/** Read-only status requests back off; uploads and job creation are never retried here. */
class RemotePollPolicy {
    private var previous:Triple<String,String,String>?=null
    private var interval=0L
    private var failures=0

    fun observe(phase:String,stage:String,message:String):Long {
        val current=Triple(phase,stage,message)
        val queued=phase=="queued" || phase=="uploading"
        val initial=if(queued) 5000L else 3000L
        val maximum=if(queued) 15000L else 10000L
        interval=if(current==previous) (interval+initial).coerceAtMost(maximum) else initial
        previous=current
        failures=0
        return interval
    }

    fun retryDelay(error:Exception):Long? {
        if(error is ServerError && error.status !in listOf(408,429,500,502,503,504)) return null
        if(error !is java.io.IOException) return null
        failures++
        if(failures>6) return null
        return (2000L shl (failures-1)).coerceAtMost(30000L)
    }
}
