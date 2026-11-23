package com.configbox.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Result of a TCP reachability / latency probe against a proxy node endpoint.
 */
data class LatencyResult(
    val latencyMs: Long? = null,
    val resolvedIp: String? = null,
    val error: String? = null,
    val testing: Boolean = false
)

/**
 * Network helpers for proxy nodes:
 * 1. Detecting IPv4/IPv6 literals vs domain names.
 * 2. Resolving domain names to IPv4/IPv6 addresses (preferring IPv4 first).
 * 3. Measuring TCP handshake RTT latency to a node's `server:port`.
 */
object NodeNetworkTools {

    private val IPV4_REGEX = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )
    private val IPV6_CHARS_REGEX = Regex("^[0-9a-fA-F:.]+$")

    /**
     * Returns true if [host] is already a numeric IPv4 or IPv6 address literal.
     */
    fun isIpAddress(host: String): Boolean {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isEmpty()) return false
        if (IPV4_REGEX.matches(clean)) return true
        if (":" in clean && IPV6_CHARS_REGEX.matches(clean)) {
            val colons = clean.count { it == ':' }
            if (colons < 2 || colons > 7) return false
            if (clean.contains(":::")) return false
            return runCatching { InetAddress.getByName(clean) }.isSuccess
        }
        return false
    }

    /**
     * Resolves a domain name to deduplicated IP addresses (IPv4 sorted before IPv6).
     * If [host] is already an IP literal, returns it directly without DNS lookup.
     */
    suspend fun resolveDomainIps(host: String): List<String> = withContext(Dispatchers.IO) {
        val clean = host.trim().removePrefix("[").removeSuffix("]")
        if (clean.isEmpty()) {
            throw IllegalArgumentException("服务器地址为空")
        }
        if (isIpAddress(clean)) {
            return@withContext listOf(clean)
        }
        val addresses = try {
            InetAddress.getAllByName(clean)
        } catch (_: UnknownHostException) {
            throw IllegalStateException("无法解析域名：$clean")
        }
        val sorted = addresses
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress }
            .distinct()
        if (sorted.isEmpty()) {
            throw IllegalStateException("未解析到有效 IP：$clean")
        }
        sorted
    }

    /**
     * Measures TCP connect RTT to [server]:[port].
     * DNS resolution happens before timing starts so reported ms reflects connection RTT.
     */
    suspend fun testTcpLatency(
        server: String,
        port: Int,
        timeoutMs: Int = 3000
    ): LatencyResult = withContext(Dispatchers.IO) {
        val clean = server.trim().removePrefix("[").removeSuffix("]")
        if (clean.isEmpty() || port !in 1..65535) {
            return@withContext LatencyResult(error = "地址无效")
        }
        val targetAddr = try {
            val all = InetAddress.getAllByName(clean)
            all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
                ?: return@withContext LatencyResult(error = "解析失败")
        } catch (_: UnknownHostException) {
            return@withContext LatencyResult(error = "解析失败")
        } catch (_: Exception) {
            return@withContext LatencyResult(error = "解析失败")
        }

        val resolvedIp = targetAddr.hostAddress
        val startNs = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(targetAddr, port), timeoutMs)
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            LatencyResult(latencyMs = elapsedMs, resolvedIp = resolvedIp)
        } catch (_: SocketTimeoutException) {
            LatencyResult(resolvedIp = resolvedIp, error = "超时")
        } catch (_: IOException) {
            LatencyResult(resolvedIp = resolvedIp, error = "不可达")
        } catch (_: Exception) {
            LatencyResult(resolvedIp = resolvedIp, error = "失败")
        }
    }
}
