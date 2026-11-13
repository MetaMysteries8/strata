package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemotePollPolicyTest {
    @Test fun queuedPollingBacksOffAndStageChangesResetIt() {
        val p=RemotePollPolicy()
        assertEquals(5000L,p.observe("queued","queued","Waiting"))
        assertEquals(10000L,p.observe("queued","queued","Waiting"))
        repeat(10) {assertEquals(15000L,p.observe("queued","queued","Waiting"))}
        assertEquals(3000L,p.observe("running","training","Training"))
        assertEquals(6000L,p.observe("running","training","Training"))
        assertEquals(9000L,p.observe("running","training","Training"))
        assertEquals(10000L,p.observe("running","training","Training"))
        assertEquals(3000L,p.observe("running","exporting","Exporting"))
    }

    @Test fun simulatedHourOfUnchangedQueueUsesFewerStatusReads() {
        val policy=RemotePollPolicy()
        var elapsed=0L
        var requests=0
        while(elapsed<=3600000L) {requests++;elapsed+=policy.observe("queued","queued","Waiting")}
        assertEquals(242,requests)
        assertEquals(1201,3600000/3000+1) // Previous fixed interval over the same inclusive window.
    }

    @Test fun readRetriesAreBoundedAndAValidResponseResetsThem() {
        val p=RemotePollPolicy()
        for(delay in listOf(2000L,4000L,8000L,16000L,30000L,30000L)) assertEquals(delay,p.retryDelay(IOException()))
        assertNull(p.retryDelay(IOException()))
        p.observe("running","training","Working")
        assertEquals(2000L,p.retryDelay(ServerError(503,"Temporary")))
    }

    @Test fun authValidationAndMissingJobsAreNeverRetried() {
        for(code in listOf(400,401,403,404,409,413)) assertNull(RemotePollPolicy().retryDelay(ServerError(code,"Permanent")))
        for(code in listOf(408,429,500,502,503,504)) assertEquals(2000L,RemotePollPolicy().retryDelay(ServerError(code,"Temporary")))
        assertNull(RemotePollPolicy().retryDelay(IllegalArgumentException("Invalid response")))
        assertNull(RemotePollPolicy().retryDelay(InterruptedException()))
    }
}
