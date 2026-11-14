package dev.doxi.strata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteJobStatusTest {
    @Test fun separatesWorkerHeartbeatFromApiContactAndMapsActualEngineRuntime() {
        val status=RemoteJobStatus.details(JSONObject().put("worker_heartbeat",123.0).put("engine","ns-train").put("engine_elapsed",42.0).put("created",100.0))
        assertEquals(123000.0,status.getDouble("workerHeartbeat"),0.0)
        assertEquals("ns-train",status.getString("engine"))
        assertEquals(42.0,status.getDouble("engineElapsed"),0.0)
        assertFalse(status.has("lastContact"))
    }
    @Test fun absentOrInvalidTelemetryDoesNotBreakMonitoring() {
        val status=RemoteJobStatus.details(JSONObject().put("created",-5).put("engine_elapsed",-1))
        assertEquals(0.0,status.getDouble("workerHeartbeat"),0.0)
        assertEquals(0.0,status.getDouble("serverCreated"),0.0)
        assertEquals(0.0,status.getDouble("engineElapsed"),0.0)
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,1e13))assertEquals(0.0,RemoteJobStatus.milliseconds(value),0.0)
    }
    @Test fun partialResultsRetainFailuresAfterDownloadingAvailableArtifacts() {
        val outcomes=JSONObject().put("mesh",JSONObject().put("status","failed").put("stage","meshing")).put("splat",JSONObject().put("status","complete"))
        val result=RemoteJobStatus.completion(JSONObject().put("partial",true).put("outcomes",outcomes).put("message","Mesh had no triangles"))
        assertTrue(result.getBoolean("partial"))
        assertTrue(result.getString("message").contains("Mesh: failed"))
        assertTrue(result.getString("message").contains("Splats: complete"))
        assertEquals("failed",result.getJSONObject("outcomes").getJSONObject("mesh").getString("status"))
        assertFalse(RemoteJobStatus.completion(JSONObject()).getBoolean("partial"))
    }
    @Test fun workerDeadlineAndCheckpointArePreservedWithoutAnInventedCompletionPercentage() {
        val record=JSONObject().put("worker_deadline",200.0).put("worker_limit",7200).put("checkpoint_step",9999).put("recovery",JSONObject().put("resume_ready",true))
        val status=RemoteJobStatus.details(record)
        assertEquals(200000.0,status.getDouble("workerDeadline"),0.0)
        assertEquals(7200,status.getLong("workerLimit"))
        assertEquals(9999,status.getLong("checkpointStep"))
        assertFalse(status.has("percent"))
        assertTrue(RemoteJobStatus.completion(record).getJSONObject("recovery").getBoolean("resume_ready"))
    }
}
