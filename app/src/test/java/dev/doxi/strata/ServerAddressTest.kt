package dev.doxi.strata
import org.junit.Assert.*
import org.junit.Test

class ServerAddressTest {
    @Test fun allowsPrivateLanAndRemoteHttps(){assertEquals("http://192.168.1.20:8787",ServerAddress.normalize("http://192.168.1.20:8787/"));assertEquals("https://example.modal.run",ServerAddress.normalize("https://example.modal.run/"))}
    @Test fun rejectsPublicCleartextCredentialsAndQuery(){for(url in listOf("http://8.8.8.8:8787","http://10.x.0.0.1:8787","https://user:secret@example.com","https://example.com/?key=secret","file:///tmp/server","http://192.168.999.1","https://example.com/#fragment"))assertThrows(url,IllegalArgumentException::class.java){ServerAddress.normalize(url)}}
}
