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
}
