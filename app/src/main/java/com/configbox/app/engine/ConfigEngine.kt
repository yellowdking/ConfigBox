package com.configbox.app.engine

import com.configbox.app.model.NodeSource
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyGroupEntry
import com.configbox.app.model.ProxyNode
import com.configbox.app.model.RuleEntry
import com.configbox.app.model.SplitProfile
import java.util.UUID

/**
 * Core engine responsible for:
 * 1. Splitting an imported Clash/Mihomo YAML into independent modules (base, proxies, proxy-groups, rules, rule-providers).
 * 2. Refreshing subscription nodes without clobbering custom nodes, custom proxy groups, rule edits, or node `dialer-proxy` bindings.
 * 3. Compiling all modules cleanly back into a valid Mihomo/Clash YAML without injecting hidden duplicate nodes or groups.
 */
object ConfigEngine {

    private val SPLIT_KEYS = setOf("proxies", "proxy-groups", "rules", "rule-providers")

    fun splitConfig(
        rawYaml: String,
        profileName: String,
        subscriptionUrl: String = ""
    ): SplitProfile {
        val root = YamlCodec.parseMap(rawYaml)

        val baseMap = LinkedHashMap<String, Any?>()
        for ((key, value) in root) {
            if (key !in SPLIT_KEYS) {
                baseMap[key] = value
            }
        }
        val baseYaml = if (baseMap.isEmpty()) defaultBaseYaml() else YamlCodec.dump(baseMap)

        val rawProxies = (root["proxies"] as? List<*>) ?: emptyList<Any?>()
        val proxies = rawProxies.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            mapToProxyNode(map, NodeSource.SUBSCRIPTION)
        }

        val rawGroups = (root["proxy-groups"] as? List<*>) ?: emptyList<Any?>()
        val groups = rawGroups.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            mapToProxyGroup(map)
        }

        val rawRules = (root["rules"] as? List<*>) ?: emptyList<Any?>()
        val rules = rawRules.mapNotNull { item ->
            val line = item?.toString()?.trim()
            if (line.isNullOrBlank()) null else parseRuleLine(line)
        }

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
            relayBoundCount = proxies.count { !it.dialerProxy.isNullOrBlank() }
        )

        return SplitProfile(
            meta = meta,
            baseConfigYaml = baseYaml,
            proxies = proxies,
            proxyGroups = groups,
            rules = rules,
            ruleProvidersYaml = ruleProvidersYaml
        )
    }

    /**
     * Refreshes ONLY subscription-sourced nodes while preserving:
     * - all user-added CUSTOM nodes
     * - all existing `dialer-proxy` bindings by node name
     * - all user-edited proxy-groups & rules
     */
    fun refreshSubscriptionNodes(
        existing: SplitProfile,
        freshRawYaml: String
    ): SplitProfile {
        val fresh = splitConfig(freshRawYaml, existing.meta.name, existing.meta.subscriptionUrl)
        val savedDialerByName = existing.proxies
            .filter { !it.dialerProxy.isNullOrBlank() }
            .associate { it.name to it.dialerProxy }

        val refreshedSubNodes = fresh.proxies.map { node ->
            val savedDialer = savedDialerByName[node.name]
            if (!savedDialer.isNullOrBlank()) {
                updateNodeBasicFields(node, node.name, node.server, node.port, node.udp, savedDialer)
            } else {
                node
            }
        }

        val customNodes = existing.proxies.filter { it.source == NodeSource.CUSTOM }
        val freshSubNames = refreshedSubNodes.map { it.name }.toSet()
        val mergedNodes = refreshedSubNodes + customNodes.filter { it.name !in freshSubNames }

        return existing.copy(
            proxies = mergedNodes,
            meta = existing.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = mergedNodes.size,
                groupCount = existing.proxyGroups.size,
                relayBoundCount = mergedNodes.count { !it.dialerProxy.isNullOrBlank() }
            )
        )
    }

    /**
     * Compiles all split modules cleanly into a ready-to-use Mihomo/Clash YAML.
     * `dialer-proxy` is emitted directly on each bound exit node; no extra hidden groups are added.
     */
    fun compileConfig(profile: SplitProfile): String {
        val output = LinkedHashMap<String, Any?>()

        val baseMap = YamlCodec.parseMap(profile.baseConfigYaml)
        output.putAll(baseMap)

        val compiledProxies = mutableListOf<LinkedHashMap<String, Any?>>()
        val validNodeNames = mutableSetOf("DIRECT", "REJECT")

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
            validNodeNames.add(node.name)
        }
        output["proxies"] = compiledProxies

        val allGroupNames = profile.proxyGroups.map { it.name }.toSet()
        val compiledGroups = mutableListOf<LinkedHashMap<String, Any?>>()
        for (group in profile.proxyGroups) {
            val map = YamlCodec.parseMap(group.rawYaml)
            map["name"] = group.name
            map["type"] = group.type
            val filteredMembers = group.proxies
                .filter { it in validNodeNames || (it in allGroupNames && it != group.name) }
                .ifEmpty { listOf("DIRECT") }
            map["proxies"] = filteredMembers
            if (group.type == "url-test" || group.type == "fallback" || group.type == "load-balance") {
                map["url"] = group.url?.takeIf { it.isNotBlank() } ?: "https://www.gstatic.com/generate_204"
                map["interval"] = (group.interval ?: 300).coerceAtLeast(60)
            } else {
                map.remove("url")
                map.remove("interval")
            }
            compiledGroups.add(map)
        }
        output["proxy-groups"] = compiledGroups

        if (profile.ruleProvidersYaml.isNotBlank()) {
            val providersMap = YamlCodec.parseMap(profile.ruleProvidersYaml)
            if (providersMap.isNotEmpty()) {
                output["rule-providers"] = providersMap
            }
        }

        output["rules"] = profile.rules
            .filter { it.enabled }
            .map { it.toClashRuleString() }

        return YamlCodec.dump(output)
    }

    /**
     * Builds or updates a [ProxyGroupEntry] with clean serialized YAML.
     */
    fun buildProxyGroup(
        id: String = UUID.randomUUID().toString().take(8),
        name: String,
        type: String,
        proxies: List<String>,
        url: String? = null,
        interval: Int? = null
    ): ProxyGroupEntry {
        val cleanName = name.trim()
        val cleanType = type.trim().ifBlank { "select" }
        val cleanProxies = proxies.distinct().ifEmpty { listOf("DIRECT") }
        val map = linkedMapOf<String, Any?>(
            "name" to cleanName,
            "type" to cleanType,
            "proxies" to cleanProxies
        )
        val effectiveUrl = if (cleanType in setOf("url-test", "fallback", "load-balance")) {
            url?.trim()?.ifBlank { "https://www.gstatic.com/generate_204" }
                ?: "https://www.gstatic.com/generate_204"
        } else {
            null
        }
        val effectiveInterval = if (cleanType in setOf("url-test", "fallback", "load-balance")) {
            (interval ?: 300).coerceAtLeast(60)
        } else {
            null
        }
        if (effectiveUrl != null) map["url"] = effectiveUrl
        if (effectiveInterval != null) map["interval"] = effectiveInterval

        return ProxyGroupEntry(
            id = id,
            name = cleanName,
            type = cleanType,
            proxies = cleanProxies,
            url = effectiveUrl,
            interval = effectiveInterval,
            rawYaml = YamlCodec.dump(map)
        )
    }

    /**
     * Directly binds (or clears) `dialer-proxy` on the specified exit node IDs.
     */
    fun applyDialerProxy(
        nodes: List<ProxyNode>,
        exitNodeIds: Set<String>,
        entryProxy: String?
    ): List<ProxyNode> {
        val cleanEntry = entryProxy?.trim()?.ifBlank { null }
        return nodes.map { node ->
            if (node.id in exitNodeIds) {
                updateNodeBasicFields(node, node.name, node.server, node.port, node.udp, cleanEntry)
            } else {
                node
            }
        }
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
            dialer-proxy: "♻️ 自动测速"

        proxy-groups:
          - name: "🚀 节点选择"
            type: select
            proxies:
              - "♻️ 自动测速"
              - "🏠 家宽落地"
              - "香港 IPLC 01"
              - "新加坡高速 02"
              - "日本东京 03"
              - DIRECT
          - name: "♻️ 自动测速"
            type: url-test
            url: https://www.gstatic.com/generate_204
            interval: 300
            proxies:
              - "香港 IPLC 01"
              - "新加坡高速 02"
              - "日本东京 03"
          - name: "🏠 家宽落地"
            type: select
            proxies:
              - "美国住宅家宽落地"

        rules:
          - DOMAIN-SUFFIX,openai.com,🏠 家宽落地
          - DOMAIN-SUFFIX,claude.ai,🏠 家宽落地
          - DOMAIN-SUFFIX,github.com,🚀 节点选择
          - DOMAIN-SUFFIX,google.com,🚀 节点选择
          - GEOIP,LAN,DIRECT,no-resolve
          - GEOIP,CN,DIRECT
          - MATCH,🚀 节点选择
    """.trimIndent()
}
