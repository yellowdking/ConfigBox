package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.ProxyNode
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Parses common single-node proxy URIs (`socks5://`, `http://`, `ss://`, `trojan://`,
 * `vless://`, `hysteria2://` / `hy2://`) into a [ProxyNode] for quick node addition.
 */
object ProxyUriParser {

    fun parse(rawUri: String): ProxyNode {
        val trimmed = rawUri.trim()
        val scheme = trimmed.substringBefore("://").lowercase()
        return when (scheme) {
            "socks5", "socks" -> parseSocksOrHttp(trimmed, "socks5")
            "http", "https" -> parseSocksOrHttp(trimmed, "http")
            "trojan" -> parseTrojan(trimmed)
            "vless" -> parseVless(trimmed)
            "hysteria2", "hy2" -> parseHysteria2(trimmed)
            "ss" -> parseShadowsocks(trimmed)
            else -> throw IllegalArgumentException("暂不支持该协议 URI：$scheme，请改用 YAML 手动导入")
        }
    }

    private fun parseSocksOrHttp(raw: String, type: String): ProxyNode {
        val uri = URI(raw)
        val server = uri.host ?: throw IllegalArgumentException("缺少服务器地址")
        val port = if (uri.port > 0) uri.port else if (type == "socks5") 1080 else 8080
        val name = decodeFragment(uri.rawFragment) ?: "$type-$server:$port"
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
        val uri = URI(raw)
        val server = uri.host ?: throw IllegalArgumentException("Trojan URI 缺少服务器地址")
        val port = if (uri.port > 0) uri.port else 443
        val password = urlDecode(uri.rawUserInfo ?: "")
        val query = parseQuery(uri.rawQuery)
        val name = decodeFragment(uri.rawFragment) ?: "trojan-$server"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "trojan",
            "server" to server,
            "port" to port,
            "password" to password,
            "udp" to true
        )
        query["sni"]?.takeIf { it.isNotBlank() }?.let { map["sni"] = it }
        if (query["allowInsecure"] == "1" || query["insecure"] == "1") {
            map["skip-cert-verify"] = true
        }
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
        val uri = URI(raw)
        val server = uri.host ?: throw IllegalArgumentException("VLESS URI 缺少服务器地址")
        val port = if (uri.port > 0) uri.port else 443
        val uuid = urlDecode(uri.rawUserInfo ?: "")
        val query = parseQuery(uri.rawQuery)
        val name = decodeFragment(uri.rawFragment) ?: "vless-$server"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "vless",
            "server" to server,
            "port" to port,
            "uuid" to uuid,
            "udp" to true
        )
        val security = query["security"].orEmpty()
        if (security == "tls" || security == "reality") {
            map["tls"] = true
        }
        query["sni"]?.takeIf { it.isNotBlank() }?.let { map["servername"] = it }
        query["flow"]?.takeIf { it.isNotBlank() }?.let { map["flow"] = it }
        query["fp"]?.takeIf { it.isNotBlank() }?.let { map["client-fingerprint"] = it }
        if (security == "reality") {
            val reality = linkedMapOf<String, Any?>()
            query["pbk"]?.let { reality["public-key"] = it }
            query["sid"]?.let { reality["short-id"] = it }
            if (reality.isNotEmpty()) map["reality-opts"] = reality
        }
        query["type"]?.takeIf { it.isNotBlank() && it != "tcp" }?.let { map["network"] = it }
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

    private fun parseHysteria2(raw: String): ProxyNode {
        val normalized = if (raw.startsWith("hy2://", ignoreCase = true)) {
            "hysteria2://" + raw.substring(6)
        } else {
            raw
        }
        val uri = URI(normalized)
        val server = uri.host ?: throw IllegalArgumentException("Hysteria2 URI 缺少服务器地址")
        val port = if (uri.port > 0) uri.port else 443
        val password = urlDecode(uri.rawUserInfo ?: "")
        val query = parseQuery(uri.rawQuery)
        val name = decodeFragment(uri.rawFragment) ?: "hy2-$server"
        val map = linkedMapOf<String, Any?>(
            "name" to name,
            "type" to "hysteria2",
            "server" to server,
            "port" to port,
            "password" to password,
            "udp" to true
        )
        query["sni"]?.takeIf { it.isNotBlank() }?.let { map["sni"] = it }
        if (query["insecure"] == "1") map["skip-cert-verify"] = true
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
        val withoutScheme = raw.substringAfter("ss://")
        val fragmentPart = withoutScheme.substringAfter("#", "")
        val mainPart = withoutScheme.substringBefore("#")
        val name = if (fragmentPart.isNotBlank()) urlDecode(fragmentPart) else "ss-node"

        val (method, password, server, port) = if ("@" in mainPart) {
            val credPart = mainPart.substringBefore("@")
            val hostPort = mainPart.substringAfter("@").substringBefore("?").substringBefore("/")
            val decodedCred = if (":" in credPart) credPart else decodeBase64(credPart)
            val m = decodedCred.substringBefore(":")
            val p = decodedCred.substringAfter(":", "")
            val s = hostPort.substringBeforeLast(":")
            val pt = hostPort.substringAfterLast(":").toIntOrNull() ?: 8388
            Quad(m, p, s, pt)
        } else {
            val decoded = decodeBase64(mainPart)
            val cred = decoded.substringBefore("@")
            val hostPort = decoded.substringAfter("@")
            Quad(
                cred.substringBefore(":"),
                cred.substringAfter(":", ""),
                hostPort.substringBeforeLast(":"),
                hostPort.substringAfterLast(":").toIntOrNull() ?: 8388
            )
        }

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

    private data class Quad(val a: String, val b: String, val c: String, val d: Int)

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else urlDecode(pair.substring(0, idx)) to urlDecode(pair.substring(idx + 1))
        }.toMap()
    }

    private fun decodeFragment(rawFragment: String?): String? {
        if (rawFragment.isNullOrBlank()) return null
        return urlDecode(rawFragment).trim().ifBlank { null }
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64(input: String): String {
        val clean = input.trim().replace('-', '+').replace('_', '/')
        val padded = clean.padEnd(((clean.length + 3) / 4) * 4, '=')
        return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }
}
