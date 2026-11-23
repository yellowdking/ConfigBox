package com.configbox.app.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LocalSubServerTest {

    @Test
    fun servesGetHeadAnd404Cleanly() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = LocalSubServer(port = 0) { profileId ->
            if (profileId == "demo1") {
                "香港配置" to "mixed-port: 7890\n"
            } else {
                null
            }
        }
        try {
            val port = server.start(scope).getOrThrow()
            assertTrue(port > 0)

            // GET valid profile
            val getConn = (URL("http://127.0.0.1:$port/sub/demo1.yaml").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            assertEquals(200, getConn.responseCode)
            val body = getConn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals("mixed-port: 7890\n", body)
            val cd = getConn.getHeaderField("Content-Disposition")
            assertTrue(cd.contains("filename*=UTF-8''"))
            getConn.disconnect()

            // HEAD valid profile
            val headConn = (URL("http://127.0.0.1:$port/sub/demo1.yaml").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 3000
                readTimeout = 3000
            }
            assertEquals(200, headConn.responseCode)
            assertEquals("mixed-port: 7890\n".toByteArray(Charsets.UTF_8).size, headConn.contentLength)
            headConn.disconnect()

            // GET unknown profile -> 404
            val notFoundConn = (URL("http://127.0.0.1:$port/sub/missing.yaml").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            assertEquals(404, notFoundConn.responseCode)
            notFoundConn.disconnect()
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
