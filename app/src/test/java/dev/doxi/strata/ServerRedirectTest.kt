package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServerRedirectTest {
    private data class Response(val code:Int,val location:String?=null,val bytes:ByteArray="{\"phase\":\"running\"}".toByteArray())
    private data class Seen(val headers:List<String>,val body:ByteArray)
    private fun serve(responses:List<Response>,action:(ServerHttp)->Unit):List<Seen> {
        val socket=ServerSocket(0);socket.soTimeout=10000
        val pool=Executors.newSingleThreadExecutor()
        val observed=pool.submit<List<Seen>> {
            responses.map { response->socket.accept().use { client->
                client.soTimeout=10000
                val input=client.getInputStream().buffered()
                fun line():String {val bytes=ByteArrayOutputStream();while(true){val n=input.read();check(n>=0);if(n==10)break;if(n!=13)bytes.write(n)};return bytes.toString("US-ASCII")}
                val headers=ArrayList<String>();while(true){val line=line();if(line.isEmpty())break;headers.add(line)}
                val count=headers.firstOrNull {it.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt() ?: 0
                val body=ByteArray(count);java.io.DataInputStream(input).readFully(body)
                client.getOutputStream().use { out->
                    val location=response.location?.let {"Location: $it\r\n"} ?: ""
                    out.write("HTTP/1.1 ${response.code} Result\r\n${location}Content-Length: ${response.bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(response.bytes)
                };Seen(headers,body)
            }}
        }
        try {
            action(ServerHttp(ServerProfile("http://127.0.0.1:${socket.localPort}","synthetic-key-at-least-24-characters","test.proxy")))
            return observed.get(10,TimeUnit.SECONDS)
        }finally {socket.close();pool.shutdownNow()}
    }
    @Test fun modalStyleQueryRedirectKeepsTheJobPathAndBothCredentials() {
        val seen=serve(listOf(Response(303,"?result=test-only"),Response(200))) {http->
            assertEquals("running",http.json("/v1/jobs/job-1").getString("phase"))
        }
        assertEquals("GET /v1/jobs/job-1?result=test-only HTTP/1.1",seen[1].headers.first())
        for(request in seen) {
            assertTrue(request.headers.any {it=="X-Strata-Key: synthetic-key-at-least-24-characters"})
            assertTrue(request.headers.any {it=="Authorization: Bearer test.proxy"})
            assertTrue(request.body.isEmpty())
        }
    }
    @Test fun upload303RetrievesTheResultWithoutSendingTheArchiveAgain() {
        val archive=File.createTempFile("strata-redirect",".zip").apply {writeBytes(ByteArray(130000){(it%251).toByte()})}
        try {
            val seen=serve(listOf(Response(303,"/v1/jobs/result?poll=test-only"),Response(200))) {http->
                assertEquals("running",http.upload(archive,"stable-upload-id","both","detail"){_,_->}.getString("phase"))
            }
            assertArrayEquals(archive.readBytes(),seen[0].body)
            assertEquals("GET /v1/jobs/result?poll=test-only HTTP/1.1",seen[1].headers.first())
            assertTrue(seen[1].body.isEmpty())
            assertFalse(seen[1].headers.any {it.startsWith("X-Request-ID:",true)||it.startsWith("Content-Type:",true)})
        } finally {archive.delete()}
    }
    @Test fun upload307And308ReplayExactBytesAndKeepTheIdempotencyKey() {
        val archive=File.createTempFile("strata-redirect",".zip").apply {writeBytes(ByteArray(130000){(it%251).toByte()})}
        try {
            val seen=serve(listOf(Response(307,"/v1/jobs/"),Response(308,"/v1/jobs/final"),Response(200))) {http->
                http.upload(archive,"stable-upload-id","splat","quick"){_,_->}
            }
            for(request in seen) {
                assertTrue(request.headers.first().startsWith("POST "))
                assertTrue(request.headers.any {it=="X-Request-ID: stable-upload-id"})
                assertArrayEquals(archive.readBytes(),request.body)
            }
        } finally {archive.delete()}
    }
    @Test fun redirectedDownloadPublishesExactBytes() {
        val payload=ByteArray(65537){(it%251).toByte()};val destination=File.createTempFile("strata-redirect",".ply")
        try {
            serve(listOf(Response(302,"/download/final"),Response(200,bytes=payload))) {it.download("/v1/jobs/1/artifacts/splat",destination){_,_->}}
            assertArrayEquals(payload,destination.readBytes())
        }finally {destination.delete()}
    }
    @Test fun policyRejectsOtherOriginsUnsafeDestinationsAndAmbiguousPostRedirects() {
        val base="https://owned.modal.run/v1/jobs/1"
        for(location in listOf("https://other.modal.run/result","http://owned.modal.run/result","https://owned.modal.run:444/result","https://user:secret@owned.modal.run/result","https://owned.modal.run.evil.test/result","//other.modal.run/result","/result#fragment","/bad%secret","/path\r\nInjected: value")) {
            val error=assertThrows(IllegalArgumentException::class.java){ServerRedirect.next(base,303,location,"GET")}
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        for(code in listOf(301,302))assertThrows(IllegalArgumentException::class.java){ServerRedirect.next(base,code,"/result","POST")}
        assertThrows(IllegalArgumentException::class.java){ServerRedirect.next(base,303,null,"GET")}
        assertEquals("https://owned.modal.run:443/result",ServerRedirect.next(base,303,"https://owned.modal.run:443/result","GET").url)
        assertEquals("GET",ServerRedirect.next(base,303,"/result","POST").method)
    }
    private class RedirectConnection(url:URL,val target:String,val status:Int=303):HttpURLConnection(url) {
        var closed=false
        val sent=ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {closed=true}
        override fun usingProxy()=false
        override fun getResponseCode()=status
        override fun getHeaderField(name:String?)=if(name=="Location")target else null
        override fun getOutputStream()=sent
        override fun getInputStream()=ByteArrayInputStream(ByteArray(0))
    }
    @Test fun blockedRedirectNeverOpensAnotherHostAndDoesNotExposeItsQuery() {
        var opens=0;lateinit var connection:RedirectConnection
        val http=ServerHttp(ServerProfile("https://owned.modal.run","synthetic-key-at-least-24-characters","test.proxy")) {url->
            opens++;RedirectConnection(url,"https://other.modal.run/result?token=synthetic-secret").also {connection=it}
        }
        val error=assertThrows(ServerError::class.java){http.json("/v1/health")}
        assertEquals(303,error.status);assertEquals(1,opens);assertTrue(connection.closed)
        assertFalse(error.message.orEmpty().contains("synthetic-secret"))
    }
    @Test fun repeatedResultRedirectsAreBoundedAndCancellationStopsFollowing() {
        var count=0
        val connections=ArrayList<RedirectConnection>()
        val http=ServerHttp(ServerProfile("https://owned.modal.run","synthetic-key-at-least-24-characters")) {url->
            count++;RedirectConnection(url,"?result=synthetic").also {connections.add(it)}
        }
        assertThrows(ServerError::class.java){http.json("/v1/jobs/1")}
        assertEquals(ServerRedirect.LIMIT+1,count);assertTrue(connections.all {it.closed})
        count=0
        val cancelled=ServerHttp(ServerProfile("https://owned.modal.run","synthetic-key-at-least-24-characters")) {count++;RedirectConnection(it,"/result")}
        cancelled.stop();assertThrows(IllegalStateException::class.java){cancelled.json("/v1/health")};assertEquals(0,count)
    }
    @Test fun repeatedUploadRedirectsAreBounded() {
        var count=0
        val archive=File.createTempFile("strata-redirect",".zip").apply {writeBytes(byteArrayOf(1,2,3))}
        val http=ServerHttp(ServerProfile("https://owned.modal.run","synthetic-key-at-least-24-characters")) {url->count++;RedirectConnection(url,"/v1/jobs/",307)}
        try {assertThrows(ServerError::class.java){http.upload(archive,"stable-upload-id","both","detail"){_,_->}};assertEquals(4,count)} finally {archive.delete()}
    }
}
