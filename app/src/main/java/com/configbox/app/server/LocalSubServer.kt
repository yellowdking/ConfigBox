package com.configbox.app.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder

/**
 * Lightweight loopback HTTP server (`127.0.0.1:18080`) that serves compiled Clash YAML
 * directly to local Mihomo / ClashMetaForAndroid clients without any external server.
 */
class LocalSubServer(
    private val port: Int = 18080,
    private val yamlProvider: suspend (profileId: String) -> Pair<String, String>? // (fileName, yamlContent)
) {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    val boundPort: Int
        get() = serverSocket?.localPort ?: port

    fun subscriptionUrl(profileId: String): String = "http://127.0.0.1:$boundPort/sub/$profileId.yaml"

    @Synchronized
    fun start(scope: CoroutineScope): Result<Int> = runCatching {
        if (isRunning) return@runCatching boundPort
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
        }
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { handleClient(client) }
            }
        }
        socket.localPort
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = runCatching { reader.readLine() }.getOrNull() ?: return
            // Drain incoming request headers cleanly to avoid TCP RST on strict HTTP clients.
            var headerLines = 0
            while (headerLines < 64) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                if (line.isEmpty()) break
                headerLines++
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase().orEmpty()
            val path = parts.getOrNull(1).orEmpty().substringBefore("?")
            val out = client.getOutputStream()

            if (method != "GET" && method != "HEAD") {
                val body = "Method Not Allowed\n".toByteArray(Charsets.UTF_8)
                writeResponse(out, "405 Method Not Allowed", "text/plain; charset=utf-8", body, headOnly = false)
                return
            }

            val match = SUB_PATH_REGEX.matchEntire(path)
            if (match == null) {
                val body = "ConfigBox Local Subscription Server. Use /sub/<profileId>.yaml\n".toByteArray(Charsets.UTF_8)
                writeResponse(out, "404 Not Found", "text/plain; charset=utf-8", body, headOnly = method == "HEAD")
                return
            }

            val profileId = match.groupValues[1]
            val result = runCatching { yamlProvider(profileId) }.getOrNull()
            if (result == null) {
                val body = "Profile not found: $profileId\n".toByteArray(Charsets.UTF_8)
                writeResponse(out, "404 Not Found", "text/plain; charset=utf-8", body, headOnly = method == "HEAD")
                return
            }

            val (name, yaml) = result
            val bytes = yaml.toByteArray(Charsets.UTF_8)
            val asciiName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "configbox" }
            val encodedUtf8Name = URLEncoder.encode("$name.yaml", "UTF-8").replace("+", "%20")
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/yaml; charset=utf-8\r\n")
                append("Content-Disposition: attachment; filename=\"$asciiName.yaml\"; filename*=UTF-8''$encodedUtf8Name\r\n")
                append("Profile-Update-Interval: 24\r\n")
                append("Cache-Control: no-store\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.UTF_8))
            if (method != "HEAD") {
                out.write(bytes)
            }
            out.flush()
        }
    }

    private fun writeResponse(
        out: OutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }

    companion object {
        private val SUB_PATH_REGEX = Regex("^/sub/([a-zA-Z0-9_-]+)\\.yaml$")
    }
}
