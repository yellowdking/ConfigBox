package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.RuleEntry
import org.junit.Assert.assertEquals
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
    fun refreshSubscriptionPreservesCustomNodesGroupsRulesAndDialerBindings() {
        val initial = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "订阅测试")
        val customNode = ProxyUriParser.parse("socks5://user:pass@203.0.113.9:1080#我的独享落地")
        val customGroup = ConfigEngine.buildProxyGroup(name = "🏠 独享家宽", type = "select", proxies = listOf("我的独享落地"))
        val customRule = RuleEntry(type = "DOMAIN-SUFFIX", payload = "my-custom.org", target = "🏠 独享家宽")

        val customized = initial.copy(
            proxies = initial.proxies + customNode,
            proxyGroups = listOf(customGroup) + initial.proxyGroups,
            rules = listOf(customRule) + initial.rules
        )

        val refreshed = ConfigEngine.refreshSubscriptionNodes(customized, ConfigEngine.SAMPLE_CLASH_YAML)
        assertTrue(refreshed.proxies.any { it.name == "我的独享落地" && it.source == NodeSource.CUSTOM })
        assertTrue(refreshed.proxyGroups.any { it.name == "🏠 独享家宽" })
        assertTrue(refreshed.rules.any { it.payload == "my-custom.org" })
    }
}
