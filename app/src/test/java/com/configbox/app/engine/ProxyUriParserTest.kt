package com.configbox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyUriParserTest {

    @Test
    fun parsesVlessWithRealityAndSpacesInFragment() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@hk.example.com:443" +
            "?security=reality&sni=www.cloudflare.com&fp=chrome&pbk=PUBKEY123&sid=abcd&flow=xtls-rprx-vision&type=tcp#香港 Reality 节点 01"
        val node = ProxyUriParser.parse(uri)
        assertEquals("香港 Reality 节点 01", node.name)
        assertEquals("vless", node.type)
        assertEquals("hk.example.com", node.server)
        assertEquals(443, node.port)

        val map = YamlCodec.parseMap(node.rawYaml)
        assertEquals(true, map["tls"])
        assertEquals("www.cloudflare.com", map["servername"])
        assertEquals("chrome", map["client-fingerprint"])
        @Suppress("UNCHECKED_CAST")
        val reality = map["reality-opts"] as Map<String, Any?>
        assertEquals("PUBKEY123", reality["public-key"])
        assertEquals("abcd", reality["short-id"])
    }

    @Test
    fun parsesVlessWebsocketTransportOptions() {
        val uri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@ws.example.com:443" +
            "?security=tls&type=ws&path=%2Fws%3Fed%3D2048&host=cdn.example.com&alpn=h2,http/1.1#WS Node"
        val node = ProxyUriParser.parse(uri)
        val map = YamlCodec.parseMap(node.rawYaml)
        assertEquals("ws", map["network"])
        @Suppress("UNCHECKED_CAST")
        val wsOpts = map["ws-opts"] as Map<String, Any?>
        assertEquals("/ws?ed=2048", wsOpts["path"])
        @Suppress("UNCHECKED_CAST")
        val headers = wsOpts["headers"] as Map<String, Any?>
        assertEquals("cdn.example.com", headers["Host"])
        assertEquals(listOf("h2", "http/1.1"), map["alpn"])
    }

    @Test
    fun parsesVmessBase64JsonUri() {
        val payload = """
            {"v":"2","ps":"日本 VMess 01","add":"jp.example.com","port":"443","id":"12345678-1234-1234-1234-1234567890ab","aid":"0","scy":"auto","net":"ws","type":"none","host":"cdn.jp.example.com","path":"/vmess","tls":"tls","sni":"sni.jp.example.com"}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val node = ProxyUriParser.parse("vmess://$encoded")
        assertEquals("日本 VMess 01", node.name)
        assertEquals("vmess", node.type)
        assertEquals("jp.example.com", node.server)
        assertEquals(443, node.port)
        val map = YamlCodec.parseMap(node.rawYaml)
        assertEquals(true, map["tls"])
        assertEquals("ws", map["network"])
        assertEquals("sni.jp.example.com", map["servername"])
    }

    @Test
    fun parsesHysteria2WithObfsAndShadowsocksIPv6() {
        val hy2 = ProxyUriParser.parse("hy2://secretPass@hy2.example.com:8443?sni=hy2.example.com&obfs=salamander&obfs-password=myobfs&insecure=1#HY2 节点")
        assertEquals("HY2 节点", hy2.name)
        val hy2Map = YamlCodec.parseMap(hy2.rawYaml)
        assertEquals("salamander", hy2Map["obfs"])
        assertEquals("myobfs", hy2Map["obfs-password"])
        assertEquals(true, hy2Map["skip-cert-verify"])

        val ssCred = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:myPass".toByteArray())
        val ss = ProxyUriParser.parse("ss://$ssCred@[2001:db8::1]:8388#IPv6 SS 节点")
        assertEquals("IPv6 SS 节点", ss.name)
        assertEquals("2001:db8::1", ss.server)
        assertEquals(8388, ss.port)
        assertTrue(ss.rawYaml.contains("aes-256-gcm"))
    }
}
