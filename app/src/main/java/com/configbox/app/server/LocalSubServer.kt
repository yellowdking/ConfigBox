package com.configbox.app.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight loopback HTTP server (`127.0.0.1:18080`) that serves compiled Clash YAML
 * directly to local Mihomo / ClashMetaForAndroid clients without any external server.
 */
class LocalSubServer(
    private val port: Int = 18080,
    private val yamlProvider: suspend (profileId: String) -> Pair<String, String>? // (fileName, yamlContent)
) {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun subscriptionUrl(profileId: String): String = "http://127.0.0.1:$port/sub/$profileId.yaml"

    fun start(scope: CoroutineScope): Result<Int> = runCatching {
        if (isRunning) return@runCatching port
        val socket = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch(Dispatchers.IO) { handleClient(client) }
            }
        }
        port
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1).orEmpty().substringBefore("?")

            val match = Regex("^/sub/([a-zA-Z0-9_-]+)\\.yaml$").matchEntire(path)
            val out = client.getOutputStream()

            if (match == null) {
                val body = "ConfigBox Local Subscription Server. Use /sub/<profileId>.yaml\n".toByteArray()
                writeResponse(out, "404 Not Found", "text/plain; charset=utf-8", body)
                return
            }

            val profileId = match.groupValues[1]
            val result = runCatching { yamlProvider(profileId) }.getOrNull()
            if (result == null) {
                val body = "Profile not found: $profileId\n".toByteArray()
                writeResponse(out, "404 Not Found", "text/plain; charset=utf-8", body)
                return
            }

            val (name, yaml) = result
            val bytes = yaml.toByteArray(Charsets.UTF_8)
            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/yaml; charset=utf-8\r\n")
                append("Content-Disposition: attachment; filename=\"$safeName.yaml\"\r\n")
                append("Profile-Update-Interval: 24\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun writeResponse(
        out: java.io.OutputStream,
        status: String,
        contentType: String,
        body: ByteArray
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }
}
