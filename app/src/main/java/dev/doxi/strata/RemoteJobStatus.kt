package dev.doxi.strata

import org.json.JSONObject

object RemoteJobStatus {
    fun milliseconds(seconds:Double):Double=if(seconds.isFinite()&&seconds>0&&seconds<1e12) seconds*1000 else 0.0
    fun details(record:JSONObject):JSONObject {
        val result=JSONObject().put("stage",record.optString("stage")).put("serverPhase",record.optString("phase"))
        for((source,target) in listOf("created" to "serverCreated","stage_started" to "stageStarted","worker_heartbeat" to "workerHeartbeat"))
            result.put(target,milliseconds(record.optDouble(source,0.0)))
        result.put("engine",record.optString("engine").take(80))
        val elapsed=record.optDouble("engine_elapsed",0.0)
        result.put("engineElapsed",if(elapsed.isFinite()&&elapsed>=0&&elapsed<1e9) elapsed else 0.0)
        return result
    }
}
