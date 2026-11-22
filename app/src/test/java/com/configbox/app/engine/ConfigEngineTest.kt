package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.RelayGroup
import com.configbox.app.model.RuleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals(2, split.proxyGroups.size)
        assertEquals(7, split.rules.size)
        assertTrue(split.baseConfigYaml.contains("mixed-port: 7890"))
        assertTrue(split.baseConfigYaml.contains("fake-ip"))

        // Verify protocol-specific fields (e.g. VLESS flow & client-fingerprint) remain intact
        val hkNode = split.proxies.first { it.name == "香港 IPLC 01" }
        assertTrue(hkNode.rawYaml.contains("xtls-rprx-vision"))
        assertTrue(hkNode.rawYaml.contains("client-fingerprint: chrome"))
    }

    @Test
    fun compileConfigGeneratesDialerProxyRelayGroupWithoutMutatingOriginalNodes() {
        val split = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "链式测试")
        val relay = RelayGroup(
            name = "美国家宽中转组",
            groupType = "select",
            entryProxy = "♻️ 自动测速",
            middleProxies = listOf("日本东京 03"),
            exitProxies = listOf("美国住宅家宽落地"),
            injectToMainGroups = true,
            enabled = true
        )

        val compiledYaml = ConfigEngine.compileConfig(split.copy(relayGroups = listOf(relay)))
        val root = YamlCodec.parseMap(compiledYaml)

        @Suppress("UNCHECKED_CAST")
        val proxies = root["proxies"] as List<Map<String, Any?>>
        // Original 4 + 1 middle hop + 1 exit hop = 6
        assertEquals(6, proxies.size)

        // Original exit node has NO dialer-proxy
        val originalExit = proxies.first { it["name"] == "美国住宅家宽落地" }
        assertEquals(null, originalExit["dialer-proxy"])

        // Middle hop chains onto entry group
        val midHop = proxies.first { it["name"] == "[美国家宽中转组#1] 日本东京 03" }
        assertEquals("♻️ 自动测速", midHop["dialer-proxy"])

        // Exit hop chains onto middle hop
        val exitHop = proxies.first { it["name"] == "[美国家宽中转组] 美国住宅家宽落地" }
        assertEquals("[美国家宽中转组#1] 日本东京 03", exitHop["dialer-proxy"])
        assertEquals("homeuser", exitHop["username"])

        @Suppress("UNCHECKED_CAST")
        val groups = root["proxy-groups"] as List<Map<String, Any?>>
        val relayGroup = groups.firstOrNull { it["name"] == "美国家宽中转组" }
        assertNotNull(relayGroup)
        assertEquals(listOf("[美国家宽中转组] 美国住宅家宽落地"), relayGroup!!["proxies"])

        // Auto-injected into main select group
        val mainGroup = groups.first { it["name"] == "🚀 节点选择" }
        @Suppress("UNCHECKED_CAST")
        val mainProxies = mainGroup["proxies"] as List<String>
        assertTrue(mainProxies.contains("美国家宽中转组"))
    }

    @Test
    fun refreshSubscriptionPreservesCustomNodesRulesAndRelays() {
        val initial = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "订阅测试")
        val customNode = ProxyUriParser.parse("socks5://user:pass@203.0.113.9:1080#我的独享落地")
        val customRule = RuleEntry(type = "DOMAIN-SUFFIX", payload = "my-custom.org", target = "美国家宽中转组")
        val relay = RelayGroup(
            name = "美国家宽中转组",
            entryProxy = "香港 IPLC 01",
            exitProxies = listOf("我的独享落地")
        )

        val customized = initial.copy(
            proxies = initial.proxies + customNode,
            rules = listOf(customRule) + initial.rules,
            relayGroups = listOf(relay)
        )

        val refreshed = ConfigEngine.refreshSubscriptionNodes(customized, ConfigEngine.SAMPLE_CLASH_YAML)
        assertTrue(refreshed.proxies.any { it.name == "我的独享落地" && it.source == NodeSource.CUSTOM })
        assertTrue(refreshed.rules.any { it.payload == "my-custom.org" })
        assertEquals(1, refreshed.relayGroups.size)
    }
}
