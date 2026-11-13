package dev.doxi.strata
import org.junit.Assert.*
import org.junit.Test
class PhoneModalTest {
 @Test fun copiedSetupCommandHasCompleteBashSyntax(){
  org.junit.Assume.assumeTrue(java.io.File("/bin/bash").canExecute())
  for(command in listOf(PhoneModal.setupCommand,PhoneModal.restartCommand)) {
  val process=ProcessBuilder("/bin/bash","-n").redirectErrorStream(true).start()
  try {
   process.outputStream.bufferedWriter().use {it.write(command);it.newLine()}
   assertTrue("Shell syntax check timed out",process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))
   assertEquals(process.inputStream.bufferedReader().readText(),0,process.exitValue())
  } finally {process.destroyForcibly()}
  }
 }
 @Test fun loginOnlyOpensModalsOwnHttpsSite(){assertEquals("https://modal.com/token-flow/test?code=abc",PhoneModal.loginUrl("https://modal.com/token-flow/test?code=abc"));for(u in listOf("http://modal.com/a","https://modal.com.evil.test","https://user@modal.com/a","https://modal.com:123/a","javascript:alert(1)"))assertThrows(IllegalArgumentException::class.java){PhoneModal.loginUrl(u)}}
 @Test fun endpointUsesModalHttps(){assertEquals("https://test.modal.run",PhoneModal.endpoint("https://test.modal.run/"));for(u in listOf("https://modal.run.evil.test","http://192.168.1.10","https://evil.test"))assertThrows(IllegalArgumentException::class.java){PhoneModal.endpoint(u)};assertEquals("http://127.0.0.1:8788",PhoneModal.URL)}
 @Test fun rejectsInvalidEndpointCredentialsBeforeConnection(){val good=org.json.JSONObject().put("url","https://test.modal.run").put("key","test-key-at-least-24-characters").put("proxy","wk-test.ws-test");assertEquals("https://test.modal.run",PhoneModal.connection(good).url);good.put("key","bad\nheader");assertThrows(IllegalArgumentException::class.java){PhoneModal.connection(good)}}
 @Test fun diagnosticCopyDropsUnexpectedCredentialFields(){val raw=org.json.JSONObject().put("version","0.2.2").put("key","sensitive").put("proxy","sensitive").put("token_secret","sensitive").put("hasEndpoint",true);val safe=PhoneModal.diagnostics(raw);assertEquals("0.2.2",safe.getString("version"));assertFalse(safe.toString().contains("sensitive"));assertTrue(safe.getBoolean("hasEndpoint"))}
}
