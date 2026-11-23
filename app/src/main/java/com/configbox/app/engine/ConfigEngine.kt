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
    private val BUILTIN_POLICIES = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")
    private val PROBE_GROUP_TYPES = setOf("url-test", "fallback", "load-balance")

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
        val parsedProxies = rawProxies.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            mapToProxyNode(map, NodeSource.SUBSCRIPTION)
        }
        val proxies = deduplicateNodes(parsedProxies)

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
     * - user-defined relative node order for surviving nodes
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
            if (!savedDialer.isNullOrBlank() && savedDialer != node.name) {
                updateNodeBasicFields(node, node.name, node.server, node.port, node.udp, savedDialer)
            } else {
                node
            }
        }

        val customNodes = existing.proxies.filter { it.source == NodeSource.CUSTOM }
        val freshSubNames = refreshedSubNodes.map { it.name }.toSet()
        val combined = refreshedSubNodes + customNodes.filter { it.name !in freshSubNames }

        val existingOrder = existing.proxies.withIndex().associate { it.value.name to it.index }
        val mergedNodes = combined.sortedBy { existingOrder[it.name] ?: Int.MAX_VALUE }

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
        val validNodeNames = BUILTIN_POLICIES.toMutableSet()

        val uniqueNodes = deduplicateNodes(profile.proxies)
        for (node in uniqueNodes) {
            val map = YamlCodec.parseMap(node.rawYaml)
            map["name"] = node.name
            map["type"] = node.type
            map["server"] = node.server
            map["port"] = node.port
            map["udp"] = node.udp
            val dialer = node.dialerProxy?.trim()?.takeIf { it.isNotBlank() && it != node.name }
            if (dialer != null) {
                map["dialer-proxy"] = dialer
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

            val hasDynamicProviders = hasDynamicGroupMembers(map)
            val filteredMembers = group.proxies
                .filter { it in validNodeNames || (it in allGroupNames && it != group.name) }
                .distinct()

            if (filteredMembers.isNotEmpty()) {
                map["proxies"] = filteredMembers
            } else if (!hasDynamicProviders) {
                map["proxies"] = listOf("DIRECT")
            } else {
                map.remove("proxies")
            }

            if (group.type in PROBE_GROUP_TYPES) {
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
     * Builds or updates a [ProxyGroupEntry] with clean serialized YAML while losslessly
     * preserving extra Mihomo group fields (e.g. `icon`, `tolerance`, `filter`, `use`, `lazy`)
     * when [existingRawYaml] is provided.
     */
    fun buildProxyGroup(
        id: String = UUID.randomUUID().toString().take(8),
        name: String,
        type: String,
        proxies: List<String>,
        url: String? = null,
        interval: Int? = null,
        existingRawYaml: String? = null
    ): ProxyGroupEntry {
        val cleanName = name.trim()
        val cleanType = type.trim().ifBlank { "select" }
        val map = if (!existingRawYaml.isNullOrBlank()) {
            runCatching { YamlCodec.parseMap(existingRawYaml) }.getOrDefault(linkedMapOf())
        } else {
            linkedMapOf()
        }

        val hasDynamicProviders = hasDynamicGroupMembers(map)
        val distinctProxies = proxies.distinct()
        val cleanProxies = if (distinctProxies.isEmpty() && !hasDynamicProviders) {
            listOf("DIRECT")
        } else {
            distinctProxies
        }

        map["name"] = cleanName
        map["type"] = cleanType
        if (cleanProxies.isNotEmpty()) {
            map["proxies"] = cleanProxies
        } else {
            map.remove("proxies")
        }

        val effectiveUrl = if (cleanType in PROBE_GROUP_TYPES) {
            url?.trim()?.ifBlank { "https://www.gstatic.com/generate_204" }
                ?: "https://www.gstatic.com/generate_204"
        } else {
            null
        }
        val effectiveInterval = if (cleanType in PROBE_GROUP_TYPES) {
            (interval ?: 300).coerceAtLeast(60)
        } else {
            null
        }
        if (effectiveUrl != null) {
            map["url"] = effectiveUrl
        } else {
            map.remove("url")
        }
        if (effectiveInterval != null) {
            map["interval"] = effectiveInterval
        } else {
            map.remove("interval")
        }

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
     * Prevents self-referencing `dialer-proxy` loops where a node points to itself.
     */
    fun applyDialerProxy(
        nodes: List<ProxyNode>,
        exitNodeIds: Set<String>,
        entryProxy: String?
    ): List<ProxyNode> {
        val cleanEntry = entryProxy?.trim()?.ifBlank { null }
        return nodes.map { node ->
            if (node.id in exitNodeIds) {
                val safeEntry = cleanEntry?.takeIf { it != node.name }
                updateNodeBasicFields(node, node.name, node.server, node.port, node.udp, safeEntry)
            } else {
                node
            }
        }
    }

    /**
     * Ensures [candidate] has a name that does not collide with [existingNames].
     */
    fun ensureUniqueNodeName(candidate: ProxyNode, existingNames: Set<String>): ProxyNode {
        if (candidate.name !in existingNames) return candidate
        var idx = 2
        var unique = "${candidate.name} ($idx)"
        while (unique in existingNames) {
            idx++
            unique = "${candidate.name} ($idx)"
        }
        return updateNodeBasicFields(
            candidate,
            newName = unique,
            newServer = candidate.server,
            newPort = candidate.port,
            newUdp = candidate.udp,
            newDialerProxy = candidate.dialerProxy
        )
    }

    private fun deduplicateNodes(nodes: List<ProxyNode>): List<ProxyNode> {
        val seen = mutableSetOf<String>()
        return nodes.map { node ->
            val unique = ensureUniqueNodeName(node, seen)
            seen.add(unique.name)
            unique
        }
    }

    private fun hasDynamicGroupMembers(map: Map<String, Any?>): Boolean {
        val useList = map["use"] as? List<*>
        val includeAll = map["include-all"]?.toString()?.toBooleanStrictOrNull() == true
        val includeAllProxies = map["include-all-proxies"]?.toString()?.toBooleanStrictOrNull() == true
        return !useList.isNullOrEmpty() || includeAll || includeAllProxies
    }

    fun parseRuleLine(line: String): RuleEntry {
        val parts = line.split(",").map { it.trim() }.filterIndexed { idx, s -> idx < 3 || s.isNotEmpty() }
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
        val cleanName = newName.trim()
        val cleanServer = newServer.trim()
        val safeDialer = newDialerProxy?.trim()?.ifBlank { null }?.takeIf { it != cleanName }
        val map = YamlCodec.parseMap(node.rawYaml)
        map["name"] = cleanName
        map["server"] = cleanServer
        map["port"] = newPort
        map["udp"] = newUdp
        if (safeDialer != null) {
            map["dialer-proxy"] = safeDialer
        } else {
            map.remove("dialer-proxy")
        }
        return node.copy(
            name = cleanName,
            server = cleanServer,
            port = newPort,
            udp = newUdp,
            dialerProxy = safeDialer,
            rawYaml = YamlCodec.dump(map)
        )
    }

    private fun mapToProxyNode(map: Map<*, *>, source: NodeSource): ProxyNode? {
        val normalized = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            if (k != null) normalized[k.toString()] = YamlCodec.deepCopy(v)
        }
        val name = normalized["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = normalized["type"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        val server = normalized["server"]?.toString()?.trim() ?: ""
        val port = normalized["port"]?.toString()?.toIntOrNull() ?: 443
        val udp = normalized["udp"]?.toString()?.toBooleanStrictOrNull() ?: true
        val dialerProxy = normalized["dialer-proxy"]?.toString()?.trim()?.ifBlank { null }?.takeIf { it != name }

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
        val name = normalized["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = normalized["type"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "select"
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
