package com.configbox.app.engine

import com.configbox.app.model.DonorSelection
import com.configbox.app.model.NodeSource
import com.configbox.app.model.NodeSubscription
import com.configbox.app.model.RuleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

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
    fun splitConfigAcceptsBase64UriSubscriptionDirectly() {
        val rawUris = """
            vless://11111111-2222-3333-4444-555555555555@hk.example.com:443?security=tls&sni=hk.example.com#香港 VLESS
            trojan://pass123@jp.example.com:443?sni=jp.example.com#日本 Trojan
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(rawUris.toByteArray(Charsets.UTF_8))

        val split = ConfigEngine.splitConfig(
            rawYaml = encoded,
            profileName = "Base64 订阅",
            subscriptionUrl = "https://sub.example.com/v2ray"
        )
        assertEquals(2, split.proxies.size)
        assertEquals(listOf("香港 VLESS", "日本 Trojan"), split.proxies.map { it.name })
        assertTrue(split.proxyGroups.any { it.name == "🚀 节点选择" && "香港 VLESS" in it.proxies })
        assertTrue(split.rules.any { it.type == "MATCH" })
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
    fun refreshSubscriptionPreservesCustomNodesExtraNodeSubsGroupsRulesOrderAndDialerBindings() {
        val initial = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "订阅测试")
        val customNode = ProxyUriParser.parse("socks5://user:pass@203.0.113.9:1080#我的独享落地")
        val customGroup = ConfigEngine.buildProxyGroup(name = "🏠 独享家宽", type = "select", proxies = listOf("我的独享落地"))
        val customRule = RuleEntry(type = "DOMAIN-SUFFIX", payload = "my-custom.org", target = "🏠 独享家宽")

        // Also attach an extra node subscription
        val extraSub = NodeSubscription(
            id = "sub-extra",
            name = "备用落地",
            url = "https://backup.example.com/sub",
            namePrefix = "[备用] "
        )
        val extraSubPayload = "hy2://pass@tw.example.com:8443?sni=tw.example.com#台湾家宽"
        val (withExtraSub, _) = ConfigEngine.applyNodeSubscription(initial, extraSub, extraSubPayload)

        // User moves custom node to the very top
        val customized = withExtraSub.copy(
            proxies = listOf(customNode) + withExtraSub.proxies,
            proxyGroups = listOf(customGroup) + withExtraSub.proxyGroups,
            rules = listOf(customRule) + withExtraSub.rules
        )

        val refreshed = ConfigEngine.refreshSubscriptionNodes(customized, ConfigEngine.SAMPLE_CLASH_YAML)
        assertEquals("我的独享落地", refreshed.proxies.first().name)
        assertTrue(refreshed.proxies.any { it.name == "我的独享落地" && it.source == NodeSource.CUSTOM })
        assertTrue(refreshed.proxies.any { it.name == "[备用] 台湾家宽" && it.subscriptionId == "sub-extra" })
        assertTrue(refreshed.proxyGroups.any { it.name == "🏠 独享家宽" })
        assertTrue(refreshed.rules.any { it.payload == "my-custom.org" })
    }

    @Test
    fun nodeSubscriptionSupportsAddRefreshDiffDialerPreservationAndDeletion() {
        val initial = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "多订阅测试")
        val sub = NodeSubscription(
            id = "sub-1",
            name = "家宽订阅",
            url = "https://home.example.com/sub",
            namePrefix = "[家宽] ",
            autoAddToGroups = listOf("🚀 节点选择", "🏠 家宽落地")
        )

        // 1. Add via Base64 URI payload (including one unsupported protocol line that should be skipped gracefully)
        val v1Lines = """
            socks5://u1:p1@198.51.100.11:1080#美国西岸 01
            trojan://pass2@us2.example.com:443?sni=us2.example.com#美国东岸 02
            unsupported://foo@bar:123#忽略我
        """.trimIndent()
        val v1Base64 = Base64.getEncoder().encodeToString(v1Lines.toByteArray(Charsets.UTF_8))

        val (afterAdd, addSummary) = ConfigEngine.applyNodeSubscription(initial, sub, v1Base64)
        assertEquals(2, addSummary.totalNodes)
        assertEquals(2, addSummary.addedCount)
        assertEquals(0, addSummary.removedCount)
        assertEquals(1, addSummary.skippedCount)
        assertEquals(1, afterAdd.nodeSubscriptions.size)
        assertEquals(1, afterAdd.meta.subscriptionCount)

        val usWest = afterAdd.proxies.first { it.name == "[家宽] 美国西岸 01" }
        assertEquals("sub-1", usWest.subscriptionId)
        val homeGroupAfterAdd = afterAdd.proxyGroups.first { it.name == "🏠 家宽落地" }
        assertTrue("[家宽] 美国西岸 01" in homeGroupAfterAdd.proxies)
        assertTrue("[家宽] 美国东岸 02" in homeGroupAfterAdd.proxies)

        // Bind dialer-proxy on surviving node `[家宽] 美国西岸 01`
        val boundProfile = afterAdd.copy(
            proxies = ConfigEngine.applyDialerProxy(afterAdd.proxies, setOf(usWest.id), "♻️ 自动测速")
        )

        // 2. Refresh subscription via Clash YAML: keeps 美国西岸 01, removes 美国东岸 02, adds 英国伦敦 03
        val v2Yaml = """
            proxies:
              - name: "美国西岸 01"
                type: socks5
                server: 198.51.100.11
                port: 1080
                username: u1
                password: p1
                udp: true
              - name: "英国伦敦 03"
                type: hysteria2
                server: uk3.example.com
                port: 8443
                password: ukpwd
                sni: uk3.example.com
                udp: true
        """.trimIndent()
        val (afterRefresh, refreshSummary) = ConfigEngine.applyNodeSubscription(boundProfile, sub, v2Yaml)
        assertEquals(2, refreshSummary.totalNodes)
        assertEquals(1, refreshSummary.addedCount)
        assertEquals(1, refreshSummary.removedCount)

        // Surviving node kept its dialer-proxy binding!
        val surviving = afterRefresh.proxies.first { it.name == "[家宽] 美国西岸 01" }
        assertEquals("♻️ 自动测速", surviving.dialerProxy)
        // Removed node pruned from both proxies and proxyGroups; new node auto-added to target groups
        assertFalse(afterRefresh.proxies.any { it.name == "[家宽] 美国东岸 02" })
        assertTrue(afterRefresh.proxies.any { it.name == "[家宽] 英国伦敦 03" })
        val homeGroupAfterRefresh = afterRefresh.proxyGroups.first { it.name == "🏠 家宽落地" }
        assertFalse("[家宽] 美国东岸 02" in homeGroupAfterRefresh.proxies)
        assertTrue("[家宽] 英国伦敦 03" in homeGroupAfterRefresh.proxies)

        // 3a. Delete subscription with removeNodes = false -> keeps nodes as CUSTOM
        val keptNodesProfile = ConfigEngine.removeNodeSubscription(afterRefresh, "sub-1", removeNodes = false)
        assertTrue(keptNodesProfile.nodeSubscriptions.isEmpty())
        val converted = keptNodesProfile.proxies.first { it.name == "[家宽] 英国伦敦 03" }
        assertEquals(NodeSource.CUSTOM, converted.source)
        assertNull(converted.subscriptionId)

        // 3b. Delete subscription with removeNodes = true -> removes nodes & prunes proxyGroups
        val removedNodesProfile = ConfigEngine.removeNodeSubscription(afterRefresh, "sub-1", removeNodes = true)
        assertTrue(removedNodesProfile.nodeSubscriptions.isEmpty())
        assertFalse(removedNodesProfile.proxies.any { it.name == "[家宽] 英国伦敦 03" })
        val homeGroupAfterDelete = removedNodesProfile.proxyGroups.first { it.name == "🏠 家宽落地" }
        assertFalse("[家宽] 英国伦敦 03" in homeGroupAfterDelete.proxies)
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

    @Test
    fun replaceNodeServerWithIpPreservesTlsSniAndWsHost() {
        val vlessWs = ProxyUriParser.parse(
            "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@cdn.example.com:443?security=tls&type=ws&path=%2Fws#VLESS WS"
        )
        val convertedVless = ConfigEngine.replaceNodeServerWithIp(vlessWs, "203.0.113.42")
        assertEquals("203.0.113.42", convertedVless.server)
        val vlessMap = YamlCodec.parseMap(convertedVless.rawYaml)
        assertEquals("203.0.113.42", vlessMap["server"])
        assertEquals("cdn.example.com", vlessMap["servername"])
        @Suppress("UNCHECKED_CAST")
        val wsOpts = vlessMap["ws-opts"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val headers = wsOpts["headers"] as Map<String, Any?>
        assertEquals("cdn.example.com", headers["Host"])

        // Existing explicit SNI must NOT be overwritten
        val trojanWithSni = ProxyUriParser.parse(
            "trojan://secret@edge.example.com:443?sni=real-sni.example.com#Trojan Custom SNI"
        )
        val convertedTrojan = ConfigEngine.replaceNodeServerWithIp(trojanWithSni, "198.51.100.88")
        val trojanMap = YamlCodec.parseMap(convertedTrojan.rawYaml)
        assertEquals("198.51.100.88", trojanMap["server"])
        assertEquals("real-sni.example.com", trojanMap["sni"])
    }

    @Test
    fun copyNodeToProfileDeduplicatesAndAddsToSelectedGroups() {
        val source = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "来源配置")
        val target = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "目标配置")
        val hkNode = source.proxies.first { it.name == "香港 IPLC 01" }

        val (updatedTarget, copied) = ConfigEngine.copyNodeToProfile(
            targetProfile = target,
            node = hkNode,
            targetGroups = listOf("🚀 节点选择")
        )
        assertEquals("香港 IPLC 01 (2)", copied.name)
        assertEquals(NodeSource.CUSTOM, copied.source)
        assertNull(copied.subscriptionId)
        assertEquals(target.proxies.size + 1, updatedTarget.proxies.size)
        val selectGroup = updatedTarget.proxyGroups.first { it.name == "🚀 节点选择" }
        assertTrue("香港 IPLC 01 (2)" in selectGroup.proxies)
    }

    @Test
    fun composeCustomProfileMergesBaseDonorRuleSetProvidersAndPoolNodes() {
        val baseProfile = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "基底配置A")
        val donorYaml = """
            proxies:
              - name: "日本东京 B1"
                type: trojan
                server: jp1.donor.example
                port: 443
                password: jp-secret-password
                udp: true
              - name: "新加坡 B2"
                type: trojan
                server: sg2.donor.example
                port: 443
                password: sg-secret-password
                udp: true
            proxy-groups:
              - name: "🤖 AI 专用"
                type: select
                proxies:
                  - "日本东京 B1"
                  - DIRECT
            rule-providers:
              openai-set:
                type: http
                behavior: domain
                url: https://example.com/openai.yaml
                path: ./ruleset/openai.yaml
                interval: 86400
            rules:
              - RULE-SET,openai-set,🤖 AI 专用
              - DOMAIN-SUFFIX,claude.ai,🤖 AI 专用
              - MATCH,DIRECT
        """.trimIndent()
        val donorProfile = ConfigEngine.splitConfig(donorYaml, "规则配置B")

        val pool = ConfigEngine.buildGlobalNodePool(listOf(baseProfile, donorProfile))
        // 4 from SAMPLE_CLASH_YAML + 2 from donorProfile = 6 unique nodes in global pool
        assertEquals(6, pool.size)

        val pickedPoolNodes = pool.filter { it.node.name in setOf("香港 IPLC 01", "日本东京 B1") }
        val donorAiRules = donorProfile.rules
            .filterNot { it.type.equals("MATCH", ignoreCase = true) }
            .map { it.id }
            .toSet()

        val composed = ConfigEngine.composeCustomProfile(
            profileName = "我的定制主力",
            baseProfile = baseProfile,
            includeBaseGroups = true,
            includeBaseRules = true,
            donors = listOf(
                DonorSelection(
                    donorProfile = donorProfile,
                    selectedGroupNames = setOf("🤖 AI 专用"),
                    selectedRuleIds = donorAiRules
                )
            ),
            selectedPoolNodes = pickedPoolNodes,
            autoPopulateGroups = setOf("🚀 节点选择", "🤖 AI 专用")
        )

        assertTrue(composed.meta.isComposed)
        assertEquals("基底配置A", composed.meta.baseProfileName)
        assertEquals(listOf("香港 IPLC 01", "日本东京 B1"), composed.proxies.map { it.name })
        assertEquals("规则配置B", composed.proxies.first { it.name == "日本东京 B1" }.originProfileName)

        // Merged proxy groups include donor's AI group and auto-populated picked nodes
        val aiGroup = composed.proxyGroups.first { it.name == "🤖 AI 专用" }
        assertTrue("香港 IPLC 01" in aiGroup.proxies)
        assertTrue("日本东京 B1" in aiGroup.proxies)

        // Donor rules inserted strictly BEFORE MATCH and RULE-SET provider auto-merged!
        val matchIdx = composed.rules.indexOfFirst { it.type == "MATCH" }
        val ruleSetIdx = composed.rules.indexOfFirst { it.type == "RULE-SET" && it.payload == "openai-set" }
        val claudeIdx = composed.rules.indexOfFirst { it.payload == "claude.ai" }
        assertTrue(ruleSetIdx in 0 until matchIdx)
        assertTrue(claudeIdx in 0 until matchIdx)

        val compiledYaml = ConfigEngine.compileConfig(composed)
        val compiledMap = YamlCodec.parseMap(compiledYaml)
        @Suppress("UNCHECKED_CAST")
        val compiledProviders = compiledMap["rule-providers"] as Map<String, Any?>
        assertTrue("openai-set" in compiledProviders)

        // Global node pool deduplicates composed copies & preserves source profile ownership
        val poolAfterCompose = ConfigEngine.buildGlobalNodePool(listOf(composed, baseProfile, donorProfile))
        assertEquals(6, poolAfterCompose.size)
        assertEquals("规则配置B", poolAfterCompose.first { it.node.name == "日本东京 B1" }.originProfileName)
    }

    @Test
    fun mixinFromDonorAndSyncNodesFromPoolPreserveDialerProxy() {
        val base = ConfigEngine.splitConfig(ConfigEngine.SAMPLE_CLASH_YAML, "基底配置")
        val donorYaml = """
            proxies:
              - name: "日本东京 B1"
                type: trojan
                server: old-jp.example.com
                port: 443
                password: old-password
                udp: true
            proxy-groups:
              - name: "🎬 流媒体"
                type: select
                proxies:
                  - DIRECT
            rule-providers:
              netflix-set:
                type: http
                behavior: classical
                url: https://example.com/netflix.yaml
                path: ./ruleset/netflix.yaml
                interval: 86400
            rules:
              - RULE-SET,netflix-set,🎬 流媒体
              - MATCH,DIRECT
        """.trimIndent()
        val donor = ConfigEngine.splitConfig(donorYaml, "机场B")

        // 1. Mixin group + RULE-SET rule into base
        val ruleId = donor.rules.first { it.type == "RULE-SET" }.id
        val (mixed, summary) = ConfigEngine.mixinFromDonor(
            target = base,
            donor = donor,
            selectedGroupNames = setOf("🎬 流媒体"),
            selectedRuleIds = setOf(ruleId),
            autoPopulateWithCurrentNodes = true
        )
        assertEquals(1, summary.addedGroups)
        assertEquals(1, summary.addedRules)
        assertEquals(1, summary.addedProviders)
        val mediaGroup = mixed.proxyGroups.first { it.name == "🎬 流媒体" }
        assertTrue("香港 IPLC 01" in mediaGroup.proxies)

        // 2. Compose with donor node, bind dialer-proxy, then update donor node & sync from pool
        val poolV1 = ConfigEngine.buildGlobalNodePool(listOf(base, donor))
        val jpEntry = poolV1.first { it.node.name == "日本东京 B1" }
        val hkEntry = poolV1.first { it.node.name == "香港 IPLC 01" }
        val composed = ConfigEngine.composeCustomProfile(
            profileName = "同步测试",
            baseProfile = base,
            selectedPoolNodes = listOf(hkEntry, jpEntry)
        )
        val jpId = composed.proxies.first { it.name == "日本东京 B1" }.id
        val boundComposed = composed.copy(
            proxies = ConfigEngine.applyDialerProxy(composed.proxies, setOf(jpId), "香港 IPLC 01")
        )

        // Update donor's server & port
        val updatedDonorNode = ConfigEngine.updateNodeBasicFields(
            donor.proxies.first(),
            newName = "日本东京 B1",
            newServer = "new-jp.example.com",
            newPort = 8443,
            newUdp = true,
            newDialerProxy = null
        )
        val updatedDonor = donor.copy(proxies = listOf(updatedDonorNode))
        val poolV2 = ConfigEngine.buildGlobalNodePool(listOf(boundComposed, base, updatedDonor))

        val (synced, changedCount) = ConfigEngine.syncNodesFromPool(boundComposed, poolV2)
        assertEquals(1, changedCount)
        val syncedJp = synced.proxies.first { it.name == "日本东京 B1" }
        assertEquals("new-jp.example.com", syncedJp.server)
        assertEquals(8443, syncedJp.port)
        // Custom dialer-proxy binding must be preserved!
        assertEquals("香港 IPLC 01", syncedJp.dialerProxy)

        // Re-syncing when nothing changed reports 0 changes
        val (_, secondChangedCount) = ConfigEngine.syncNodesFromPool(synced, poolV2)
        assertEquals(0, secondChangedCount)
    }
}
