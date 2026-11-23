package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Parses common single-node proxy URIs (`socks5://`, `http://`, `ss://`, `trojan://`,
 * `vless://`, `vmess://`, `hysteria2://` / `hy2://`) into a [ProxyNode] for quick node addition.
 */
object ProxyUriParser {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawUri: String): ProxyNode {
        val trimmed = rawUri.trim()
        if (!trimmed.contains("://")) {
            throw IllegalArgumentException("URI 格式错误，缺少协议头（如 vless://、trojan://、ss://、vmess://）")
        }
        val scheme = trimmed.substringBefore("://").lowercase()
        return when (scheme) {
            "socks5", "socks" -> parseSocksOrHttp(trimmed, "socks5")
            "http", "https" -> parseSocksOrHttp(trimmed, "http")
            "trojan" -> parseTrojan(trimmed)
            "vless" -> parseVless(trimmed)
            "vmess" -> parseVmess(trimmed)
            "hysteria2", "hy2" -> parseHysteria2(trimmed)
            "ss" -> parseShadowsocks(trimmed)
            else -> throw IllegalArgumentException("暂不支持该协议 URI：$scheme，请改用 YAML 手动导入")
        }
    }

    /**
     * Parses multiple newline-separated proxy URIs, skipping blank lines.
     */
    fun parseBatch(rawText: String): List<ProxyNode> {
        return rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { parse(it) }
            .toList()
    }

    private fun parseSocksOrHttp(raw: String, type: String): ProxyNode {
        val (mainPart, fragment) = splitFragment(raw)
        val uri = URI(mainPart)
        val server = stripIPv6Brackets(uri.host) ?: throw IllegalArgumentException("缺少服务器地址")
        val port = if (uri.port > 0) uri.port else if (type == "socks5") 1080 else 8080
        val name = fragment ?: "$type-$server:$port"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to type,
            "server" to server,
            "port" to port,
            "udp" to true
        )
        val userInfo = uri.rawUserInfo
        if (!userInfo.isNullOrBlank()) {
            val parts = userInfo.split(":", limit = 2)
            map["username"] = urlDecode(parts[0])
            if (parts.size > 1) map["password"] = urlDecode(parts[1])
        }
        if (raw.startsWith("https://", ignoreCase = true)) {
            map["tls"] = true
        }
        return ProxyNode(
            name = name,
            type = type,
            server = server,
            port = port,
            udp = true,
            source = NodeSource.CUSTOM,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun parseTrojan(raw: String): ProxyNode {
        val (mainPart, fragment) = splitFragment(raw)
        val uri = URI(mainPart)
        val server = stripIPv6Brackets(uri.host) ?: throw IllegalArgumentException("Trojan URI 缺少服务器地址")
        val port = if (uri.port > 0) uri.port else 443
        val password = urlDecode(uri.rawUserInfo ?: "")
        val query = parseQuery(uri.rawQuery)
        val name = fragment ?: "trojan-$server"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "trojan",
            "server" to server,
            "port" to port,
            "password" to password,
            "udp" to true
        )
        query["sni"]?.takeIf { it.isNotBlank() }?.let { map["sni"] = it }
        query["fp"]?.takeIf { it.isNotBlank() }?.let { map["client-fingerprint"] = it }
        parseAlpn(query["alpn"])?.let { map["alpn"] = it }
        if (query["allowInsecure"] == "1" || query["insecure"] == "1") {
            map["skip-cert-verify"] = true
        }
        applyTransportOptions(map, query)
        return ProxyNode(
            name = name,
            type = "trojan",
            server = server,
            port = port,
            udp = true,
            source = NodeSource.CUSTOM,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun parseVless(raw: String): ProxyNode {
        val (mainPart, fragment) = splitFragment(raw)
        val uri = URI(mainPart)
        val server = stripIPv6Brackets(uri.host) ?: throw IllegalArgumentException("VLESS URI 缺少服务器地址")
        val port = if (uri.port > 0) uri.port else 443
        val uuid = urlDecode(uri.rawUserInfo ?: "")
        val query = parseQuery(uri.rawQuery)
        val name = fragment ?: "vless-$server"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vless",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "udp" to true
        )
        val security = query["security"].orEmpty().lowercase()
        if (security == "tls" || security == "reality") {
            map["tls"] = true
        }
        query["sni"]?.takeIf { it.isNotBlank() }?.let { map["servername"] = it }
        query["flow"]?.takeIf { it.isNotBlank() }?.let { map["flow"] = it }
        query["fp"]?.takeIf { it.isNotBlank() }?.let { map["client-fingerprint"] = it }
        parseAlpn(query["alpn"])?.let { map["alpn"] = it }
        if (query["allowInsecure"] == "1" || query["insecure"] == "1") {
            map["skip-cert-verify"] = true
        }
        if (security == "reality") {
            val reality = linkedMapOf<String, Any?>()
            query["pbk"]?.takeIf { it.isNotBlank() }?.let { reality["public-key"] = it }
            query["sid"]?.let { reality["short-id"] = it }
            if (reality.isNotEmpty()) map["reality-opts"] = reality
        }
        applyTransportOptions(map, query)
        return ProxyNode(
            name = name,
            type = "vless",
            server = server,
            port = port,
            udp = true,
            source = NodeSource.CUSTOM,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun parseVmess(raw: String): ProxyNode {
        val encoded = raw.substringAfter("vmess://").substringBefore("#").trim()
        val jsonText = decodeBase64(encoded)
        val obj = lenientJson.parseToJsonElement(jsonText) as? JsonObject
            ?: throw IllegalArgumentException("VMess Base64 JSON 格式无效")

        fun str(key: String): String =
            (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        val server = stripIPv6Brackets(str("add"))
            ?: throw IllegalArgumentException("VMess 缺少服务器地址 (add)")
        val port = (obj["port"] as? JsonPrimitive)?.intOrNull
            ?: str("port").toIntOrNull()
            ?: 443
        val uuid = str("id")
        if (uuid.isBlank()) throw IllegalArgumentException("VMess 缺少 UUID (id)")
        val aid = (obj["aid"] as? JsonPrimitive)?.intOrNull ?: str("aid").toIntOrNull() ?: 0
        val cipher = str("scy").ifBlank { "auto" }
        val name = str("ps").ifBlank { "vmess-$server" }
        val tls = str("tls").equals("tls", ignoreCase = true)
        val sni = str("sni").ifBlank { str("host") }
        val net = str("net").lowercase()
        val path = str("path")
        val host = str("host")
        val fp = str("fp")

        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vmess",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "alterId" to aid,
            "cipher" to cipher,
            "udp" to true
        )
        if (tls) {
            map["tls"] = true
            if (sni.isNotBlank()) map["servername"] = sni
        }
        if (fp.isNotBlank()) map["client-fingerprint"] = fp
        if (str("skip-cert-verify").equals("true", ignoreCase = true)) {
            map["skip-cert-verify"] = true
        }
        val syntheticQuery = buildMap {
            if (net.isNotBlank()) put("type", net)
            if (path.isNotBlank()) put("path", path)
            if (host.isNotBlank()) put("host", host)
            if (net == "grpc" && path.isNotBlank()) put("serviceName", path)
        }
        applyTransportOptions(map, syntheticQuery)

        return ProxyNode(
            name = name,
            type = "vmess",
            server = server,
            port = port,
            udp = true,
            source = NodeSource.CUSTOM,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun parseHysteria2(raw: String): ProxyNode {
        val normalized = if (raw.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + raw.substring(6)
        } else {
            raw
        }
        val (mainPart, fragment) = splitFragment(normalized)
        val uri = URI(mainPart)
        val server = stripIPv6Brackets(uri.host) ?: throw IllegalArgumentException("Hysteria2 URI 缺少服务器地址")
        val port = if (uri.port > 0) uri.port else 443
        val password = urlDecode(uri.rawUserInfo ?: "")
        val query = parseQuery(uri.rawQuery)
        val name = fragment ?: "hy2-$server"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "hysteria2",
            "server" to server,
            "port" to port,
            "password" to password,
            "udp" to true
        )
        query["sni"]?.takeIf { it.isNotBlank() }?.let { map["sni"] = it }
        parseAlpn(query["alpn"])?.let { map["alpn"] = it }
        query["obfs"]?.takeIf { it.isNotBlank() && it != "none" }?.let { obfs ->
            map["obfs"] = obfs
            query["obfs-password"]?.takeIf { it.isNotBlank() }?.let { map["obfs-password"] = it }
        }
        if (query["insecure"] == "1" || query["allowInsecure"] == "1") {
            map["skip-cert-verify"] = true
        }
        return ProxyNode(
            name = name,
            type = "hysteria2",
            server = server,
            port = port,
            udp = true,
            source = NodeSource.CUSTOM,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun parseShadowsocks(raw: String): ProxyNode {
        val (mainWithScheme, fragment) = splitFragment(raw)
        val mainPart = mainWithScheme.substringAfter("ss://")
        val name = fragment ?: "ss-node"

        val (method, password, server, port) = if ("@" in mainPart) {
            val credPart = mainPart.substringBefore("@")
            val hostPort = mainPart.substringAfter("@").substringBefore("?").substringBefore("/")
            val decodedCred = if (":" in credPart) urlDecode(credPart) else decodeBase64(credPart)
            val m = decodedCred.substringBefore(":")
            val p = decodedCred.substringAfter(":", "")
            val (s, pt) = splitHostPort(hostPort, 8388)
            Quad(m, p, s, pt)
        } else {
            val decoded = decodeBase64(mainPart)
            val cred = decoded.substringBefore("@")
            val hostPort = decoded.substringAfter("@")
            val (s, pt) = splitHostPort(hostPort, 8388)
            Quad(cred.substringBefore(":"), cred.substringAfter(":", ""), s, pt)
        }

        if (server.isBlank()) throw IllegalArgumentException("Shadowsocks URI 缺少服务器地址")

        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "ss",
            "server" to server,
            "port" to port,
            "cipher" to method,
            "password" to password,
            "udp" to true
        )
        return ProxyNode(
            name = name,
            type = "ss",
            server = server,
            port = port,
            udp = true,
            source = NodeSource.CUSTOM,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun applyTransportOptions(map: LinkedHashMap<String, Any?>, query: Map<String, String>) {
        val network = query["type"]?.lowercase()?.takeIf { it.isNotBlank() && it != "tcp" } ?: return
        map["network"] = network
        when (network) {
            "ws" -> {
                val wsOpts = linkedMapOf<String, Any?>()
                query["path"]?.takeIf { it.isNotBlank() }?.let { wsOpts["path"] = it }
                query["host"]?.takeIf { it.isNotBlank() }?.let { host ->
                    wsOpts["headers"] = linkedMapOf("Host" to host)
                }
                if (wsOpts.isNotEmpty()) map["ws-opts"] = wsOpts
            }
            "grpc" -> {
                val svcName = query["serviceName"] ?: query["path"]
                if (!svcName.isNullOrBlank()) {
                    map["grpc-opts"] = linkedMapOf("grpc-service-name" to svcName)
                }
            }
            "h2" -> {
                val h2Opts = linkedMapOf<String, Any?>()
                query["path"]?.takeIf { it.isNotBlank() }?.let { h2Opts["path"] = it }
                query["host"]?.takeIf { it.isNotBlank() }?.let { h2Opts["host"] = listOf(it) }
                if (h2Opts.isNotEmpty()) map["h2-opts"] = h2Opts
            }
        }
    }

    private fun parseAlpn(raw: String?): List<String>? {
        val list = raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        return list?.takeIf { it.isNotEmpty() }
    }

    private fun splitFragment(raw: String): Pair<String, String?> {
        val hashIdx = raw.indexOf('#')
        if (hashIdx < 0) return raw to null
        val main = raw.substring(0, hashIdx)
        val frag = urlDecode(raw.substring(hashIdx + 1)).trim().ifBlank { null }
        return main to frag
    }

    private fun stripIPv6Brackets(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return host.removePrefix("[").removeSuffix("]")
    }

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val clean = hostPort.trim()
        if (clean.startsWith("[")) {
            val endBracket = clean.indexOf(']')
            if (endBracket > 0) {
                val host = clean.substring(1, endBracket)
                val portPart = clean.substring(endBracket + 1).removePrefix(":")
                return host to (portPart.toIntOrNull() ?: defaultPort)
            }
        }
        val lastColon = clean.lastIndexOf(':')
        if (lastColon <= 0) return clean to defaultPort
        val host = clean.substring(0, lastColon)
        val port = clean.substring(lastColon + 1).toIntOrNull() ?: defaultPort
        return host to port
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: Int)

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(input: String): String {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = clean.padEnd(((clean.length + 3) / 4) * 4, '=')
        return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }
}
