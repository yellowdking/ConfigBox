package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyGroupEntry
import com.configbox.app.model.ProxyNode
import com.configbox.app.model.RelayGroup
import com.configbox.app.model.RuleEntry
import com.configbox.app.model.SplitProfile

/**
 * Core engine responsible for:
 * 1. Splitting an imported Clash/Mihomo YAML into independent modules (base, proxies, groups, rules, rule-providers).
 * 2. Refreshing subscription nodes without clobbering custom nodes, rule edits, or dialer-proxy relay groups.
 * 3. Compiling all modules + Mihomo `dialer-proxy` relay groups back into a valid Clash/Mihomo YAML.
 */
object ConfigEngine {

    private val SPLIT_KEYS = setOf("proxies", "proxy-groups", "rules", "rule-providers")

    /**
     * Automatically splits a monolithic Clash YAML file into independent editable modules.
     */
    @Suppress("UNCHECKED_CAST")
    fun splitConfig(
        rawYaml: String,
        profileName: String,
        subscriptionUrl: String = ""
    ): SplitProfile {
        val root = YamlCodec.parseMap(rawYaml)

        // 1. Base global config (port, dns, tun, sniffer, mode, etc.)
        val baseMap = LinkedHashMap<String, Any?>()
        for ((key, value) in root) {
            if (key !in SPLIT_KEYS) {
                baseMap[key] = value
            }
        }
        val baseYaml = if (baseMap.isEmpty()) defaultBaseYaml() else YamlCodec.dump(baseMap)

        // 2. Proxies
        val rawProxies = (root["proxies"] as? List<*>) ?: emptyList<Any?>()
        val proxies = rawProxies.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            mapToProxyNode(map, NodeSource.SUBSCRIPTION)
        }

        // 3. Proxy Groups
        val rawGroups = (root["proxy-groups"] as? List<*>) ?: emptyList<Any?>()
        val groups = rawGroups.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            mapToProxyGroup(map)
        }

        // 4. Rules
        val rawRules = (root["rules"] as? List<*>) ?: emptyList<Any?>()
        val rules = rawRules.mapNotNull { item ->
            val line = item?.toString()?.trim()
            if (line.isNullOrBlank()) null else parseRuleLine(line)
        }

        // 5. Rule Providers
        val ruleProvidersMap = root["rule-providers"] as? Map<*, *>
        val ruleProvidersYaml = if (ruleProvidersMap != null && ruleProvidersMap.isNotEmpty()) {
            YamlCodec.dump(ruleProvidersMap)
        } else {
            ""
        }

        val now = System.currentTimeMillis()
        val meta = ProfileMeta(
            name = profileName.ifBlank { "未命名配置" },
            subscriptionUrl = subscriptionUrl.trim(),
            createdAt = now,
            updatedAt = now,
            nodeCount = proxies.size,
            groupCount = groups.size,
            ruleCount = rules.size,
            relayGroupCount = 0
        )

        return SplitProfile(
            meta = meta,
            baseConfigYaml = baseYaml,
            proxies = proxies,
            proxyGroups = groups,
            rules = rules,
            ruleProvidersYaml = ruleProvidersYaml,
            relayGroups = emptyList()
        )
    }

    /**
     * Refreshes ONLY subscription-sourced nodes from a newly fetched YAML while preserving:
     * - all user-added CUSTOM nodes
     * - all custom rule edits
     * - all `dialer-proxy` relay groups
     * - base config settings
     */
    fun refreshSubscriptionNodes(
        existing: SplitProfile,
        freshRawYaml: String
    ): SplitProfile {
        val fresh = splitConfig(freshRawYaml, existing.meta.name, existing.meta.subscriptionUrl)
        val customNodes = existing.proxies.filter { it.source == NodeSource.CUSTOM }
        val freshSubNames = fresh.proxies.map { it.name }.toSet()
        // Keep custom nodes (avoid exact name collision by keeping custom nodes distinct)
        val mergedNodes = fresh.proxies + customNodes.filter { it.name !in freshSubNames }

        // Refresh proxy-groups proxy references if a group existed in fresh subscription,
        // otherwise keep existing groups.
        val updatedGroups = if (fresh.proxyGroups.isNotEmpty()) {
            fresh.proxyGroups
        } else {
            existing.proxyGroups
        }

        return existing.copy(
            proxies = mergedNodes,
            proxyGroups = updatedGroups,
            meta = existing.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = mergedNodes.size,
                groupCount = updatedGroups.size
            )
        )
    }

    /**
     * Compiles all split modules + `dialer-proxy` relay groups into a ready-to-use Mihomo/Clash YAML.
     */
    fun compileConfig(profile: SplitProfile): String {
        val output = LinkedHashMap<String, Any?>()

        // 1. Base settings
        val baseMap = YamlCodec.parseMap(profile.baseConfigYaml)
        output.putAll(baseMap)

        // 2. Original proxy nodes
        val compiledProxies = mutableListOf<LinkedHashMap<String, Any?>>()
        val nodeMapByName = LinkedHashMap<String, LinkedHashMap<String, Any?>>()

        for (node in profile.proxies) {
            val map = YamlCodec.parseMap(node.rawYaml)
            map["name"] = node.name
            map["type"] = node.type
            map["server"] = node.server
            map["port"] = node.port
            map["udp"] = node.udp
            if (!node.dialerProxy.isNullOrBlank()) {
                map["dialer-proxy"] = node.dialerProxy
            } else {
                map.remove("dialer-proxy")
            }
            compiledProxies.add(map)
            nodeMapByName[node.name] = map
        }

        // 3. Generate Mihomo `dialer-proxy` chained nodes & relay proxy-groups
        val generatedRelayGroups = mutableListOf<LinkedHashMap<String, Any?>>()
        val relayGroupsToInject = mutableListOf<String>()
        val existingNames = compiledProxies.mapNotNull { it["name"]?.toString() }.toMutableSet()

        for (relay in profile.relayGroups.filter { it.enabled }) {
            if (relay.entryProxy.isBlank() || relay.exitProxies.isEmpty()) continue

            // Build optional intermediate hop chain: entry -> mid1 -> mid2 -> ...
            var currentDialer = relay.entryProxy
            relay.middleProxies.forEachIndexed { idx, midName ->
                val midSource = nodeMapByName[midName] ?: return@forEachIndexed
                val clonedMid = copyNodeMap(midSource)
                val derivedMidName = uniqueName("[${relay.name}#${idx + 1}] $midName", existingNames)
                clonedMid["name"] = derivedMidName
                clonedMid["dialer-proxy"] = currentDialer
                compiledProxies.add(clonedMid)
                existingNames.add(derivedMidName)
                currentDialer = derivedMidName
            }

            // Build exit nodes chained onto currentDialer
            val chainedExitNames = mutableListOf<String>()
            for (exitName in relay.exitProxies) {
                val exitSource = nodeMapByName[exitName] ?: continue
                val clonedExit = copyNodeMap(exitSource)
                val derivedExitName = uniqueName("[${relay.name}] $exitName", existingNames)
                clonedExit["name"] = derivedExitName
                clonedExit["dialer-proxy"] = currentDialer
                compiledProxies.add(clonedExit)
                existingNames.add(derivedExitName)
                chainedExitNames.add(derivedExitName)
            }

            if (chainedExitNames.isNotEmpty()) {
                val groupMap = LinkedHashMap<String, Any?>()
                groupMap["name"] = relay.name
                groupMap["type"] = relay.groupType.ifBlank { "select" }
                groupMap["proxies"] = chainedExitNames
                if (relay.groupType == "url-test" || relay.groupType == "fallback") {
                    groupMap["url"] = relay.testUrl.ifBlank { "https://www.gstatic.com/generate_204" }
                    groupMap["interval"] = relay.interval.coerceAtLeast(60)
                }
                generatedRelayGroups.add(groupMap)
                if (relay.injectToMainGroups) {
                    relayGroupsToInject.add(relay.name)
                }
            }
        }

        output["proxies"] = compiledProxies

        // 4. Proxy groups (preserving original groups + injecting relay groups if requested)
        val compiledGroups = mutableListOf<LinkedHashMap<String, Any?>>()
        val validNodeNames = existingNames + setOf("DIRECT", "REJECT")
        val allGroupNames = profile.proxyGroups.map { it.name }.toSet() +
            generatedRelayGroups.mapNotNull { it["name"]?.toString() }.toSet()

        for (group in profile.proxyGroups) {
            val map = YamlCodec.parseMap(group.rawYaml)
            map["name"] = group.name
            map["type"] = group.type

            val currentList = ((map["proxies"] as? List<*>)?.map { it.toString() } ?: group.proxies)
                .filter { it in validNodeNames || it in allGroupNames }
                .toMutableList()

            // Inject enabled relay groups at the top of select groups
            if (group.type == "select" && relayGroupsToInject.isNotEmpty()) {
                for (relayName in relayGroupsToInject.reversed()) {
                    if (relayName !in currentList && relayName != group.name) {
                        currentList.add(0, relayName)
                    }
                }
            }
            if (currentList.isEmpty()) {
                currentList.add("DIRECT")
            }
            map["proxies"] = currentList
            compiledGroups.add(map)
        }

        // Append generated relay groups
        compiledGroups.addAll(generatedRelayGroups)
        output["proxy-groups"] = compiledGroups

        // 5. Rule providers (if any)
        if (profile.ruleProvidersYaml.isNotBlank()) {
            val providersMap = YamlCodec.parseMap(profile.ruleProvidersYaml)
            if (providersMap.isNotEmpty()) {
                output["rule-providers"] = providersMap
            }
        }

        // 6. Enabled rules
        val compiledRules = profile.rules
            .filter { it.enabled }
            .map { it.toClashRuleString() }
        output["rules"] = compiledRules

        return YamlCodec.dump(output)
    }

    /**
     * Generates a focused YAML preview snippet for a single [RelayGroup] so the user can
     * inspect the exact `dialer-proxy` nodes and group that will be emitted.
     */
    fun previewRelayGroupYaml(profile: SplitProfile, relay: RelayGroup): String {
        val tempProfile = profile.copy(
            proxyGroups = emptyList(),
            rules = emptyList(),
            ruleProvidersYaml = "",
            relayGroups = listOf(relay.copy(enabled = true, injectToMainGroups = false))
        )
        val fullMap = YamlCodec.parseMap(compileConfig(tempProfile))
        val originalNames = profile.proxies.map { it.name }.toSet()
        val allProxies = (fullMap["proxies"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val derivedOnly = allProxies.filter { it["name"]?.toString() !in originalNames }
        val groupsOnly = fullMap["proxy-groups"] ?: emptyList<Any>()
        return YamlCodec.dump(
            linkedMapOf(
                "proxies" to derivedOnly,
                "proxy-groups" to groupsOnly
            )
        )
    }

    fun parseRuleLine(line: String): RuleEntry {
        val parts = line.split(",").map { it.trim() }
        return when {
            parts.size >= 4 -> RuleEntry(
                type = parts[0],
                payload = parts[1],
                target = parts[2],
                noResolve = parts.drop(3).any { it.equals("no-resolve", ignoreCase = true) }
            )
            parts.size == 3 -> RuleEntry(
                type = parts[0],
                payload = parts[1],
                target = parts[2],
                noResolve = false
            )
            parts.size == 2 -> RuleEntry(
                type = parts[0],
                payload = "",
                target = parts[1],
                noResolve = false
            )
            else -> RuleEntry(
                type = "MATCH",
                payload = "",
                target = parts.firstOrNull().orEmpty().ifBlank { "DIRECT" },
                noResolve = false
            )
        }
    }

    fun parseNodeFromYaml(yamlText: String, source: NodeSource = NodeSource.CUSTOM): ProxyNode {
        val map = YamlCodec.parseMap(yamlText)
        return mapToProxyNode(map, source)
            ?: throw IllegalArgumentException("节点 YAML 缺少必要字段（name / type / server / port）")
    }

    fun updateNodeBasicFields(
        node: ProxyNode,
        newName: String,
        newServer: String,
        newPort: Int,
        newUdp: Boolean,
        newDialerProxy: String?
    ): ProxyNode {
        val map = YamlCodec.parseMap(node.rawYaml)
        map["name"] = newName.trim()
        map["server"] = newServer.trim()
        map["port"] = newPort
        map["udp"] = newUdp
        if (!newDialerProxy.isNullOrBlank()) {
            map["dialer-proxy"] = newDialerProxy.trim()
        } else {
            map.remove("dialer-proxy")
        }
        return node.copy(
            name = newName.trim(),
            server = newServer.trim(),
            port = newPort,
            udp = newUdp,
            dialerProxy = newDialerProxy?.trim()?.ifBlank { null },
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun mapToProxyNode(map: Map<*, *>, source: NodeSource): ProxyNode? {
        val normalized = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            if (k != null) normalized[k.toString()] = YamlCodec.deepCopy(v)
        }
        val name = normalized["name"]?.toString()?.trim() ?: return null
        val type = normalized["type"]?.toString()?.trim() ?: "unknown"
        val server = normalized["server"]?.toString()?.trim() ?: ""
        val port = normalized["port"]?.toString()?.toIntOrNull() ?: 443
        val udp = normalized["udp"]?.toString()?.toBooleanStrictOrNull() ?: true
        val dialerProxy = normalized["dialer-proxy"]?.toString()?.trim()?.ifBlank { null }

        return ProxyNode(
            name = name,
            type = type,
            server = server,
            port = port,
            udp = udp,
            dialerProxy = dialerProxy,
            source = source,
            rawYaml = YamlCodec.dump(normalized)
        )
    }

    private fun mapToProxyGroup(map: Map<*, *>): ProxyGroupEntry? {
        val normalized = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            if (k != null) normalized[k.toString()] = YamlCodec.deepCopy(v)
        }
        val name = normalized["name"]?.toString()?.trim() ?: return null
        val type = normalized["type"]?.toString()?.trim() ?: "select"
        val proxies = (normalized["proxies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val url = normalized["url"]?.toString()
        val interval = normalized["interval"]?.toString()?.toIntOrNull()
        return ProxyGroupEntry(
            name = name,
            type = type,
            proxies = proxies,
            url = url,
            interval = interval,
            rawYaml = YamlCodec.dump(normalized)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyNodeMap(source: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
        return YamlCodec.deepCopy(source) as LinkedHashMap<String, Any?>
    }

    private fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var idx = 2
        while ("$base #$idx" in existing) idx++
        return "$base #$idx"
    }

    private fun defaultBaseYaml(): String = """
        mixed-port: 7890
        allow-lan: false
        mode: rule
        log-level: info
        ipv6: true
        dns:
          enable: true
          enhanced-mode: fake-ip
          nameserver:
            - 223.5.5.5
            - 119.29.29.29
    """.trimIndent() + "\n"

    val SAMPLE_CLASH_YAML: String = """
        mixed-port: 7890
        allow-lan: false
        mode: rule
        log-level: info
        ipv6: true
        dns:
          enable: true
          enhanced-mode: fake-ip
          fake-ip-range: 198.18.0.1/16
          nameserver:
            - https://doh.pub/dns-query
            - https://dns.alidns.com/dns-query

        proxies:
          - name: "香港 IPLC 01"
            type: vless
            server: hk01.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            tls: true
            udp: true
            flow: xtls-rprx-vision
            servername: www.cloudflare.com
            client-fingerprint: chrome
          - name: "新加坡高速 02"
            type: trojan
            server: sg02.example.com
            port: 443
            password: demo-trojan-password
            sni: sg02.example.com
            udp: true
          - name: "日本东京 03"
            type: hysteria2
            server: jp03.example.com
            port: 8443
            password: demo-hy2-password
            sni: jp03.example.com
            udp: true
          - name: "美国住宅家宽落地"
            type: socks5
            server: 198.51.100.25
            port: 10808
            username: homeuser
            password: homepassword
            udp: true

        proxy-groups:
          - name: "🚀 节点选择"
            type: select
            proxies:
              - "♻️ 自动测速"
              - "香港 IPLC 01"
              - "新加坡高速 02"
              - "日本东京 03"
              - "美国住宅家宽落地"
              - DIRECT
          - name: "♻️ 自动测速"
            type: url-test
            url: https://www.gstatic.com/generate_204
            interval: 300
            proxies:
              - "香港 IPLC 01"
              - "新加坡高速 02"
              - "日本东京 03"

        rules:
          - DOMAIN-SUFFIX,openai.com,🚀 节点选择
          - DOMAIN-SUFFIX,claude.ai,🚀 节点选择
          - DOMAIN-SUFFIX,github.com,🚀 节点选择
          - DOMAIN-SUFFIX,google.com,🚀 节点选择
          - GEOIP,LAN,DIRECT,no-resolve
          - GEOIP,CN,DIRECT
          - MATCH,🚀 节点选择
    """.trimIndent()
}
