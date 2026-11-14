package dev.doxi.strata

import org.json.JSONObject

object RemoteJobStatus {
    fun milliseconds(seconds:Double):Double=if(seconds.isFinite()&&seconds>0&&seconds<1e12) seconds*1000 else 0.0
    fun completion(record:JSONObject):JSONObject {
        val outcomes=record.optJSONObject("outcomes") ?: JSONObject()
        val summary=listOf("mesh" to "Mesh","splat" to "Splats").mapNotNull { (key,label)->outcomes.optJSONObject(key)?.let { "$label: ${it.optString("status","unknown")}" } }.joinToString("; ")
        return JSONObject().put("partial",record.optBoolean("partial"))
            .put("outcomes",record.optJSONObject("outcomes") ?: JSONObject()).put("recovery",record.optJSONObject("recovery") ?: JSONObject())
            .put("message",if(record.optBoolean("partial")) "Downloaded available results. $summary. " + record.optString("message","Some outputs failed").take(2400) else "Server reconstruction downloaded and validated")
    }
    fun details(record:JSONObject):JSONObject {
        val result=JSONObject().put("stage",record.optString("stage")).put("serverPhase",record.optString("phase")).put("outcomes",record.optJSONObject("outcomes") ?: JSONObject())
        for((source,target) in listOf("created" to "serverCreated","stage_started" to "stageStarted","worker_heartbeat" to "workerHeartbeat","worker_deadline" to "workerDeadline"))
            result.put(target,milliseconds(record.optDouble(source,0.0)))
        result.put("checkpointStep",record.optLong("checkpoint_step",-1))
        result.put("workerLimit",record.optLong("worker_limit",0))
        result.put("engine",record.optString("engine").take(80))
        val elapsed=record.optDouble("engine_elapsed",0.0)
        result.put("engineElapsed",if(elapsed.isFinite()&&elapsed>=0&&elapsed<1e9) elapsed else 0.0)
        return result
    }
}
