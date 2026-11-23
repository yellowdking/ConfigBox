package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.RuleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigEngineTest {

    @Test
    fun splitConfigSeparatesAllModulesLosslessly() {
        val split = ConfigEngine.splitConfig(
            rawYaml = ConfigEngine.SAMPLE_CLASH_YAML,
            profileName = "测试配置"
        )

        assertEquals(4, split.proxies.size)
        assertEquals(3, split.proxyGroups.size)
        assertEquals(7, split.rules.size)
        assertEquals(1, split.meta.relayBoundCount)
        assertTrue(split.baseConfigYaml.contains("mixed-port: 7890"))

        val hkNode = split.proxies.first { it.name == "香港 IPLC 01" }
        assertTrue(hkNode.rawYaml.contains("xtls-rprx-vision"))
    }

    @Test
    fun directDialerProxyBindingAndManualProxyGroupCompileCleanly() {
        val split = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "直绑测试")
        val exitNode = split.proxies.first { it.name == "美国住宅家宽落地" }

        // Rebind dialer-proxy directly onto exit node (no cloned nodes, no auto-created groups)
        val updatedProxies = ConfigEngine.applyDialerProxy(
            nodes = split.proxies,
            exitNodeIds = setOf(exitNode.id),
            entryProxy = "香港 IPLC 01"
        )

        // Self-reference guard: binding a node to itself must be ignored (cleared)
        val selfBound = ConfigEngine.applyDialerProxy(
            nodes = updatedProxies,
            exitNodeIds = setOf(exitNode.id),
            entryProxy = exitNode.name
        )
        assertNull(selfBound.first { it.id == exitNode.id }.dialerProxy)

        // Manually create a custom proxy group with Emoji
        val manualGroup = ConfigEngine.buildProxyGroup(
            name = "🤖 AI 专用",
            type = "select",
            proxies = listOf("美国住宅家宽落地")
        )

        val compiledYaml = ConfigEngine.compileConfig(
            split.copy(
                proxies = updatedProxies,
                proxyGroups = listOf(manualGroup) + split.proxyGroups
            )
        )
        val root = YamlCodec.parseMap(compiledYaml)

        @Suppress("UNCHECKED_CAST")
        val proxies = root["proxies"] as List<Map<String, Any?>>
        // Still exactly 4 nodes — zero hidden clones
        assertEquals(4, proxies.size)
        val compiledExit = proxies.first { it["name"] == "美国住宅家宽落地" }
        assertEquals("香港 IPLC 01", compiledExit["dialer-proxy"])

        // Clear dialer-proxy
        val clearedProxies = ConfigEngine.applyDialerProxy(updatedProxies, setOf(exitNode.id), null)
        val compiledCleared = YamlCodec.parseMap(ConfigEngine.compileConfig(split.copy(proxies = clearedProxies)))
        @Suppress("UNCHECKED_CAST")
        val clearedExit = (compiledCleared["proxies"] as List<Map<String, Any?>>).first { it["name"] == "美国住宅家宽落地" }
        assertNull(clearedExit["dialer-proxy"])
    }

    @Test
    fun refreshSubscriptionPreservesCustomNodesGroupsRulesOrderAndDialerBindings() {
        val initial = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "订阅测试")
        val customNode = ProxyUriParser.parse("socks5://user:pass@203.0.113.9:1080#我的独享落地")
        val customGroup = ConfigEngine.buildProxyGroup(name = "🏠 独享家宽", type = "select", proxies = listOf("我的独享落地"))
        val customRule = RuleEntry(type = "DOMAIN-SUFFIX", payload = "my-custom.org", target = "🏠 独享家宽")

        // User moves custom node to the very top
        val customized = initial.copy(
            proxies = listOf(customNode) + initial.proxies,
            proxyGroups = listOf(customGroup) + initial.proxyGroups,
            rules = listOf(customRule) + initial.rules
        )

        val refreshed = ConfigEngine.refreshSubscriptionNodes(customized, ConfigEngine.SAMPLE_CLASH_YAML)
        assertEquals("我的独享落地", refreshed.proxies.first().name)
        assertTrue(refreshed.proxies.any { it.name == "我的独享落地" && it.source == NodeSource.CUSTOM })
        assertTrue(refreshed.proxyGroups.any { it.name == "🏠 独享家宽" })
        assertTrue(refreshed.rules.any { it.payload == "my-custom.org" })
    }

    @Test
    fun buildProxyGroupPreservesExtraMihomoFieldsAndBuiltinPolicies() {
        val richGroupYaml = """
            name: "⚡ 自动测速"
            type: url-test
            url: https://www.gstatic.com/generate_204
            interval: 300
            tolerance: 50
            lazy: true
            icon: https://example.com/icon.png
            use:
              - airport-provider
            proxies:
              - REJECT-DROP
              - PASS
        """.trimIndent()

        val updated = ConfigEngine.buildProxyGroup(
            name = "⚡ 自动测速",
            type = "url-test",
            proxies = listOf("REJECT-DROP", "PASS"),
            url = "https://www.gstatic.com/generate_204",
            interval = 300,
            existingRawYaml = richGroupYaml
        )
        val parsed = YamlCodec.parseMap(updated.rawYaml)
        assertEquals(50, parsed["tolerance"])
        assertEquals(true, parsed["lazy"])
        assertEquals("https://example.com/icon.png", parsed["icon"])
        assertEquals(listOf("airport-provider"), parsed["use"])

        val split = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "内置策略测试")
        val compiled = ConfigEngine.compileConfig(split.copy(proxyGroups = listOf(updated)))
        val root = YamlCodec.parseMap(compiled)
        @Suppress("UNCHECKED_CAST")
        val groupOut = (root["proxy-groups"] as List<Map<String, Any?>>).first()
        @Suppress("UNCHECKED_CAST")
        val members = groupOut["proxies"] as List<String>
        assertTrue("REJECT-DROP" in members)
        assertTrue("PASS" in members)
        assertFalse("DIRECT" in members)
    }

    @Test
    fun duplicateProxyNamesAreAutomaticallyDeduplicated() {
        val yaml = """
            proxies:
              - name: "HK 01"
                type: trojan
                server: a.example.com
                port: 443
                password: p1
              - name: "HK 01"
                type: trojan
                server: b.example.com
                port: 443
                password: p2
        """.trimIndent()
        val split = ConfigEngine.splitConfig(yaml, "去重测试")
        assertEquals(listOf("HK 01", "HK 01 (2)"), split.proxies.map { it.name })
    }
}
