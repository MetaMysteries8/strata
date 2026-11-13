package dev.doxi.strata

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServerHttpTest {
    @Test fun sendsAuthenticatedStreamingUploadAndDownloadsExactBytes() {
        val socket=ServerSocket(0);val pool=Executors.newSingleThreadExecutor()
        val archive=File.createTempFile("strata-upload-test",".zip");val destination=File.createTempFile("strata-download-test",".glb")
        val payload=ByteArray(130000) { (it%251).toByte() };archive.writeBytes(payload)
        val observed=pool.submit<List<Pair<List<String>,ByteArray>>> {
            val requests=ArrayList<Pair<List<String>,ByteArray>>()
            repeat(3) { index->socket.accept().use { client->
                client.soTimeout=10000
                val input=client.getInputStream().buffered()
                fun line():String {val bytes=ArrayList<Byte>();while(true){val n=input.read();check(n>=0);if(n==10)break;if(n!=13)bytes.add(n.toByte())};return bytes.toByteArray().toString(Charsets.US_ASCII)}
                val headers=ArrayList<String>();while(true){val value=line();if(value.isEmpty())break;headers.add(value)}
                val length=headers.firstOrNull {it.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt() ?: 0
                val body=ByteArray(length);java.io.DataInputStream(input).readFully(body);requests.add(headers to body)
                val response=when(index){0->"{\"service\":\"strata\"}".toByteArray();1->"{\"phase\":\"queued\"}".toByteArray();else->payload}
                client.getOutputStream().use { out->out.write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(response) }
            } };requests
        }
        try {
            val key="test-key-at-least-24-characters";val http=ServerHttp(ServerProfile("http://127.0.0.1:${socket.localPort}",key,"test.proxy"))
            assertEquals("strata",http.json("/v1/health").getString("service"))
            assertEquals("queued",http.upload(archive,"test-request","splat","quick"){_,_->}.getString("phase"))
            http.download("/v1/jobs/test/artifacts/glb",destination){_,_->};assertArrayEquals(payload,destination.readBytes())
            val requests=observed.get(15,TimeUnit.SECONDS)
            assertTrue(requests.all { (headers,_)->headers.any {it.equals("X-Strata-Key: $key",true)} && headers.any {it.equals("Authorization: Bearer test.proxy",true)} })
            assertTrue(requests[1].first.any {it.equals("X-Request-ID: test-request",true)})
            assertArrayEquals(payload,requests[1].second)
            http.stop();assertThrows(IllegalStateException::class.java){http.json("/v1/health")}
        } finally {socket.close();pool.shutdownNow();archive.delete();destination.delete()}
    }
    @Test fun densePointCloudDoesNotReplaceGaussianModel() {
        val dir=java.nio.file.Files.createTempDirectory("strata-artifact-test").toFile()
        try {val output=File(dir,"output").apply {mkdirs()};val scene=File(output,"scene_1.ply").apply {writeBytes(ByteArray(120))};File(output,"dense-points.ply").apply {writeBytes(ByteArray(500));setLastModified(scene.lastModified()+10000)};assertEquals(scene,Store.model(dir))} finally {dir.deleteRecursively()}
    }
}
