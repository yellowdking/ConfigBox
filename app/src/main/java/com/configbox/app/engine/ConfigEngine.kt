package com.configbox.app.engine

import com.configbox.app.model.DonorSelection
import com.configbox.app.model.GlobalPoolNode
import com.configbox.app.model.NodeSource
import com.configbox.app.model.NodeSubscription
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyGroupEntry
import com.configbox.app.model.ProxyNode
import com.configbox.app.model.RuleEntry
import com.configbox.app.model.SplitProfile
import java.util.UUID

data class SubscriptionParseResult(
    val nodes: List<ProxyNode>,
    val skippedCount: Int = 0
)

data class SubscriptionSyncSummary(
    val totalNodes: Int,
    val addedCount: Int,
    val removedCount: Int,
    val skippedCount: Int
)

data class MixinSummary(
    val addedGroups: Int,
    val addedRules: Int,
    val addedProviders: Int
)

/**
 * Core engine responsible for:
 * 1. Splitting an imported Clash/Mihomo YAML (or Base64/URI subscription) into independent modules.
 * 2. Managing primary and extra [NodeSubscription] sources without clobbering custom nodes,
 *    other subscriptions, custom proxy groups, rule edits, or node `dialer-proxy` bindings.
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
        val cleaned = rawYaml.removePrefix("\uFEFF").trim()
        val parsedMap = runCatching { YamlCodec.parseMap(cleaned) }.getOrNull()

        // If the imported text is a Clash/Mihomo YAML map, split it losslessly.
        if (parsedMap != null && (parsedMap.containsKey("proxies") || parsedMap.containsKey("proxy-groups") || parsedMap.containsKey("rules") || !ProxyUriParser.looksLikeUriList(cleaned))) {
            return splitClashMap(parsedMap, profileName, subscriptionUrl)
        }

        // Fallback: allow importing a Base64 or plaintext proxy-URI subscription directly as a profile.
        val parsed = parseSubscriptionNodes(cleaned, subscriptionId = null, namePrefix = "")
        val proxies = deduplicateNodes(parsed.nodes)
        val nodeNames = proxies.map { it.name }
        val autoGroup = buildProxyGroup(
            name = "♻️ 自动测速",
            type = "url-test",
            proxies = nodeNames
        )
        val selectGroup = buildProxyGroup(
            name = "🚀 节点选择",
            type = "select",
            proxies = listOf("♻️ 自动测速") + nodeNames + listOf("DIRECT")
        )
        val groups = listOf(selectGroup, autoGroup)
        val rules = listOf(
            RuleEntry(type = "GEOIP", payload = "LAN", target = "DIRECT", noResolve = true),
            RuleEntry(type = "GEOIP", payload = "CN", target = "DIRECT"),
            RuleEntry(type = "MATCH", payload = "", target = "🚀 节点选择")
        )
        val now = System.currentTimeMillis()
        val meta = ProfileMeta(
            name = profileName.ifBlank { "订阅配置" },
            subscriptionUrl = subscriptionUrl.trim(),
            createdAt = now,
            updatedAt = now,
            nodeCount = proxies.size,
            groupCount = groups.size,
            ruleCount = rules.size,
            relayBoundCount = 0,
            subscriptionCount = 0
        )
        return SplitProfile(
            meta = meta,
            baseConfigYaml = defaultBaseYaml(),
            proxies = proxies,
            proxyGroups = groups,
            rules = rules,
            ruleProvidersYaml = "",
            nodeSubscriptions = emptyList()
        )
    }

    private fun splitClashMap(
        root: LinkedHashMap<String, Any?>,
        profileName: String,
        subscriptionUrl: String
    ): SplitProfile {
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
            mapToProxyNode(map, NodeSource.SUBSCRIPTION, subscriptionId = null)
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
            relayBoundCount = proxies.count { !it.dialerProxy.isNullOrBlank() },
            subscriptionCount = 0
        )

        return SplitProfile(
            meta = meta,
            baseConfigYaml = baseYaml,
            proxies = proxies,
            proxyGroups = groups,
            rules = rules,
            ruleProvidersYaml = ruleProvidersYaml,
            nodeSubscriptions = emptyList()
        )
    }

    /**
     * Parses nodes from a remote or pasted subscription payload.
     * Supports:
     * 1. Clash / Mihomo YAML (`proxies: [...]`)
     * 2. Plaintext newline-separated proxy URIs (`vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://`, `socks5://`, `http://`)
     * 3. Base64-encoded proxy URI lists or Base64-encoded Clash YAML
     */
    fun parseSubscriptionNodes(
        rawContent: String,
        subscriptionId: String? = null,
        namePrefix: String = ""
    ): SubscriptionParseResult {
        val cleaned = rawContent.removePrefix("\uFEFF").trim()
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("订阅内容为空")
        }

        // 1. Try Clash/Mihomo YAML with `proxies:`
        val yamlNodes = extractNodesFromClashYaml(cleaned, subscriptionId)
        if (yamlNodes != null) {
            if (yamlNodes.isEmpty()) {
                throw IllegalArgumentException("订阅 YAML 中未找到任何有效节点")
            }
            val prefixed = applyPrefixAndSubTag(yamlNodes, subscriptionId, namePrefix)
            return SubscriptionParseResult(nodes = deduplicateNodes(prefixed), skippedCount = 0)
        }

        // 2. Try plaintext URI lines
        if (ProxyUriParser.looksLikeUriList(cleaned)) {
            val (parsed, skipped) = ProxyUriParser.parseBatchLenient(cleaned)
            if (parsed.isNotEmpty()) {
                val tagged = applyPrefixAndSubTag(parsed, subscriptionId, namePrefix)
                return SubscriptionParseResult(nodes = deduplicateNodes(tagged), skippedCount = skipped)
            }
        }

        // 3. Try Base64-decoded payload (either URI list or Clash YAML)
        val decoded = ProxyUriParser.tryDecodeBase64Subscription(cleaned)
        if (!decoded.isNullOrBlank()) {
            val decodedYamlNodes = extractNodesFromClashYaml(decoded, subscriptionId)
            if (decodedYamlNodes != null && decodedYamlNodes.isNotEmpty()) {
                val prefixed = applyPrefixAndSubTag(decodedYamlNodes, subscriptionId, namePrefix)
                return SubscriptionParseResult(nodes = deduplicateNodes(prefixed), skippedCount = 0)
            }
            if (ProxyUriParser.looksLikeUriList(decoded)) {
                val (parsed, skipped) = ProxyUriParser.parseBatchLenient(decoded)
                if (parsed.isNotEmpty()) {
                    val tagged = applyPrefixAndSubTag(parsed, subscriptionId, namePrefix)
                    return SubscriptionParseResult(nodes = deduplicateNodes(tagged), skippedCount = skipped)
                }
            }
        }

        throw IllegalArgumentException("无法识别订阅格式（支持 Clash YAML、Base64 节点订阅及纯文本 URI 列表）")
    }

    private fun extractNodesFromClashYaml(text: String, subscriptionId: String?): List<ProxyNode>? {
        val map = runCatching { YamlCodec.parseMap(text) }.getOrNull() ?: return null
        val rawProxies = map["proxies"] as? List<*> ?: return null
        return rawProxies.mapNotNull { item ->
            val proxyMap = item as? Map<*, *> ?: return@mapNotNull null
            mapToProxyNode(proxyMap, NodeSource.SUBSCRIPTION, subscriptionId = subscriptionId)
        }
    }

    private fun applyPrefixAndSubTag(
        nodes: List<ProxyNode>,
        subscriptionId: String?,
        namePrefix: String
    ): List<ProxyNode> {
        val cleanPrefix = if (namePrefix.isBlank()) "" else namePrefix.trimStart()
        return nodes.map { node ->
            val targetName = if (cleanPrefix.isNotEmpty() && !node.name.startsWith(cleanPrefix)) {
                "$cleanPrefix${node.name}"
            } else {
                node.name
            }
            val tagged = node.copy(
                source = NodeSource.SUBSCRIPTION,
                subscriptionId = subscriptionId
            )
            if (targetName != tagged.name) {
                updateNodeBasicFields(tagged, targetName, tagged.server, tagged.port, tagged.udp, tagged.dialerProxy)
            } else {
                tagged
            }
        }
    }

    /**
     * Refreshes ONLY primary-profile subscription nodes (`source == SUBSCRIPTION && subscriptionId == null`) while preserving:
     * - all user-added CUSTOM nodes
     * - all extra [NodeSubscription] nodes (`subscriptionId != null`)
     * - all existing `dialer-proxy` bindings by node name
     * - user-defined relative node order for surviving nodes
     * - all user-edited proxy-groups & rules
     */
    fun refreshSubscriptionNodes(
        existing: SplitProfile,
        freshRawYaml: String
    ): SplitProfile {
        val parsed = parseSubscriptionNodes(freshRawYaml, subscriptionId = null, namePrefix = "")
        val savedDialerByName = existing.proxies
            .filter { !it.dialerProxy.isNullOrBlank() }
            .associate { it.name to it.dialerProxy }

        val preservedOtherNodes = existing.proxies.filter {
            it.source == NodeSource.CUSTOM || it.subscriptionId != null
        }
        val reservedNames = preservedOtherNodes.map { it.name }.toMutableSet()

        val refreshedPrimaryNodes = parsed.nodes.map { candidate ->
            val unique = ensureUniqueNodeName(candidate, reservedNames)
            reservedNames.add(unique.name)
            val savedDialer = savedDialerByName[unique.name]
            if (!savedDialer.isNullOrBlank() && savedDialer != unique.name) {
                updateNodeBasicFields(unique, unique.name, unique.server, unique.port, unique.udp, savedDialer)
            } else {
                unique
            }
        }

        val combined = refreshedPrimaryNodes + preservedOtherNodes
        val existingOrder = existing.proxies.withIndex().associate { it.value.name to it.index }
        val mergedNodes = combined.sortedBy { existingOrder[it.name] ?: Int.MAX_VALUE }

        return existing.copy(
            proxies = mergedNodes,
            meta = existing.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = mergedNodes.size,
                groupCount = existing.proxyGroups.size,
                relayBoundCount = mergedNodes.count { !it.dialerProxy.isNullOrBlank() },
                subscriptionCount = existing.nodeSubscriptions.size
            )
        )
    }

    /**
     * Adds or refreshes an extra [NodeSubscription] on [existing]:
     * - Replaces ONLY nodes belonging to `subscription.id`
     * - Preserves primary subscription nodes, other node subscriptions, custom nodes, rules, and `dialer-proxy` bindings
     * - Automatically prunes removed nodes of this subscription from proxy groups
     * - Automatically appends fetched nodes to any proxy group listed in `subscription.autoAddToGroups`
     */
    fun applyNodeSubscription(
        existing: SplitProfile,
        subscription: NodeSubscription,
        rawContent: String
    ): Pair<SplitProfile, SubscriptionSyncSummary> {
        val parseResult = parseSubscriptionNodes(
            rawContent = rawContent,
            subscriptionId = subscription.id,
            namePrefix = subscription.namePrefix
        )

        val oldSubNodes = existing.proxies.filter { it.subscriptionId == subscription.id }
        val oldSubNames = oldSubNodes.map { it.name }.toSet()
        val savedDialerByName = oldSubNodes
            .filter { !it.dialerProxy.isNullOrBlank() }
            .associate { it.name to it.dialerProxy }

        val otherNodes = existing.proxies.filter { it.subscriptionId != subscription.id }
        val reservedNames = otherNodes.map { it.name }.toMutableSet()

        val syncedSubNodes = parseResult.nodes.map { candidate ->
            val unique = ensureUniqueNodeName(candidate, reservedNames)
            reservedNames.add(unique.name)
            val savedDialer = savedDialerByName[unique.name]
            if (!savedDialer.isNullOrBlank() && savedDialer != unique.name) {
                updateNodeBasicFields(unique, unique.name, unique.server, unique.port, unique.udp, savedDialer)
            } else {
                unique
            }
        }

        val newSubNames = syncedSubNodes.map { it.name }.toSet()
        val removedNames = oldSubNames - newSubNames
        val addedCount = (newSubNames - oldSubNames).size

        // Preserve relative order of surviving nodes; append newly discovered nodes cleanly
        val existingOrder = existing.proxies.withIndex().associate { it.value.name to it.index }
        val cleanedOtherNodes = if (removedNames.isEmpty()) {
            otherNodes
        } else {
            otherNodes.map { n ->
                if (n.dialerProxy in removedNames) {
                    updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, null)
                } else {
                    n
                }
            }
        }
        val mergedNodes = (cleanedOtherNodes + syncedSubNodes)
            .sortedBy { existingOrder[it.name] ?: Int.MAX_VALUE }

        val targetGroups = subscription.autoAddToGroups.toSet()
        val syncedOrderedNames = syncedSubNodes.map { it.name }
        val updatedGroups = existing.proxyGroups.map { group ->
            var members = group.proxies
            var changed = false
            if (removedNames.isNotEmpty() && members.any { it in removedNames }) {
                members = members.filterNot { it in removedNames }
                changed = true
            }
            if (group.name in targetGroups) {
                val existingMemberSet = members.toSet()
                val toAppend = syncedOrderedNames.filter { it !in existingMemberSet }
                if (toAppend.isNotEmpty()) {
                    members = members + toAppend
                    changed = true
                }
            }
            if (changed) {
                buildProxyGroup(
                    id = group.id,
                    name = group.name,
                    type = group.type,
                    proxies = members,
                    url = group.url,
                    interval = group.interval,
                    existingRawYaml = group.rawYaml
                )
            } else {
                group
            }
        }

        val now = System.currentTimeMillis()
        val updatedSub = subscription.copy(
            name = subscription.name.trim().ifBlank { "节点订阅" },
            url = subscription.url.trim(),
            namePrefix = if (subscription.namePrefix.isBlank()) "" else subscription.namePrefix.trimStart(),
            lastUpdatedAt = now,
            nodeCount = syncedSubNodes.size
        )
        val subExists = existing.nodeSubscriptions.any { it.id == updatedSub.id }
        val updatedSubs = if (subExists) {
            existing.nodeSubscriptions.map { if (it.id == updatedSub.id) updatedSub else it }
        } else {
            listOf(updatedSub) + existing.nodeSubscriptions
        }

        val updatedProfile = existing.copy(
            proxies = mergedNodes,
            proxyGroups = updatedGroups,
            nodeSubscriptions = updatedSubs,
            meta = existing.meta.copy(
                updatedAt = now,
                nodeCount = mergedNodes.size,
                groupCount = updatedGroups.size,
                relayBoundCount = mergedNodes.count { !it.dialerProxy.isNullOrBlank() },
                subscriptionCount = updatedSubs.size
            )
        )
        val summary = SubscriptionSyncSummary(
            totalNodes = syncedSubNodes.size,
            addedCount = addedCount,
            removedCount = removedNames.size,
            skippedCount = parseResult.skippedCount
        )
        return updatedProfile to summary
    }

    /**
     * Removes a [NodeSubscription] from [existing].
     * When [removeNodes] is true, its nodes are deleted and pruned from all proxy groups & dialer-proxy links.
     * When [removeNodes] is false, its nodes are kept and converted to [NodeSource.CUSTOM].
     */
    fun removeNodeSubscription(
        existing: SplitProfile,
        subscriptionId: String,
        removeNodes: Boolean
    ): SplitProfile {
        val remainingSubs = existing.nodeSubscriptions.filterNot { it.id == subscriptionId }
        val removedNames = if (removeNodes) {
            existing.proxies.filter { it.subscriptionId == subscriptionId }.map { it.name }.toSet()
        } else {
            emptySet()
        }

        val updatedNodes = if (removeNodes) {
            existing.proxies
                .filterNot { it.subscriptionId == subscriptionId }
                .map { n ->
                    if (n.dialerProxy in removedNames) {
                        updateNodeBasicFields(n, n.name, n.server, n.port, n.udp, null)
                    } else {
                        n
                    }
                }
        } else {
            existing.proxies.map { n ->
                if (n.subscriptionId == subscriptionId) {
                    n.copy(source = NodeSource.CUSTOM, subscriptionId = null)
                } else {
                    n
                }
            }
        }

        val updatedGroups = if (removedNames.isEmpty()) {
            existing.proxyGroups
        } else {
            existing.proxyGroups.map { group ->
                if (group.proxies.any { it in removedNames }) {
                    buildProxyGroup(
                        id = group.id,
                        name = group.name,
                        type = group.type,
                        proxies = group.proxies.filterNot { it in removedNames },
                        url = group.url,
                        interval = group.interval,
                        existingRawYaml = group.rawYaml
                    )
                } else {
                    group
                }
            }
        }

        return existing.copy(
            proxies = updatedNodes,
            proxyGroups = updatedGroups,
            nodeSubscriptions = remainingSubs,
            meta = existing.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = updatedNodes.size,
                groupCount = updatedGroups.size,
                relayBoundCount = updatedNodes.count { !it.dialerProxy.isNullOrBlank() },
                subscriptionCount = remainingSubs.size
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

    fun parseNodeFromYaml(
        yamlText: String,
        source: NodeSource = NodeSource.CUSTOM,
        subscriptionId: String? = null
    ): ProxyNode {
        val map = YamlCodec.parseMap(yamlText)
        return mapToProxyNode(map, source, subscriptionId)
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

    /**
     * Replaces a node's domain [ProxyNode.server] with [resolvedIp] while preserving the
     * original domain in TLS SNI (`sni` / `servername`) and WebSocket `Host` headers if unset,
     * so TLS/CDN handshakes continue working seamlessly after domain-to-IP conversion.
     */
    fun replaceNodeServerWithIp(node: ProxyNode, resolvedIp: String): ProxyNode {
        val cleanIp = resolvedIp.trim().removePrefix("[").removeSuffix("]")
        require(cleanIp.isNotEmpty()) { "目标 IP 不能为空" }
        val oldServer = node.server.trim()
        val wasDomain = oldServer.isNotEmpty() && !NodeNetworkTools.isIpAddress(oldServer)

        val map = YamlCodec.parseMap(node.rawYaml)
        map["server"] = cleanIp

        if (wasDomain) {
            val typeLower = node.type.lowercase()
            val tlsEnabled = map["tls"]?.toString()?.toBooleanStrictOrNull() == true || map["reality-opts"] != null
            when (typeLower) {
                "trojan", "hysteria2", "hysteria", "tuic", "anytls" -> {
                    val existingSni = map["sni"]?.toString()?.trim()
                    if (existingSni.isNullOrEmpty()) {
                        map["sni"] = oldServer
                    }
                }
                "vless", "vmess" -> {
                    if (tlsEnabled) {
                        val existingServerName = map["servername"]?.toString()?.trim()
                        val existingSni = map["sni"]?.toString()?.trim()
                        if (existingServerName.isNullOrEmpty() && existingSni.isNullOrEmpty()) {
                            map["servername"] = oldServer
                        }
                    }
                }
            }
            if (map["network"]?.toString()?.equals("ws", ignoreCase = true) == true) {
                @Suppress("UNCHECKED_CAST")
                val wsOpts = (map["ws-opts"] as? MutableMap<String, Any?>)
                    ?: linkedMapOf<String, Any?>().also { map["ws-opts"] = it }
                @Suppress("UNCHECKED_CAST")
                val headers = (wsOpts["headers"] as? MutableMap<String, Any?>)
                    ?: linkedMapOf<String, Any?>().also { wsOpts["headers"] = it }
                val hasHost = headers.keys.any { it.equals("Host", ignoreCase = true) }
                if (!hasHost) {
                    headers["Host"] = oldServer
                }
            }
        }

        return node.copy(
            server = cleanIp,
            rawYaml = YamlCodec.dump(map)
        )
    }

    /**
     * Copies [node] into [targetProfile] as a standalone [NodeSource.CUSTOM] node with
     * automatic name deduplication and optional insertion into [targetGroups].
     */
    fun copyNodeToProfile(
        targetProfile: SplitProfile,
        node: ProxyNode,
        targetGroups: List<String> = emptyList()
    ): Pair<SplitProfile, ProxyNode> {
        val validTargetEntries = targetProfile.proxyGroups.map { it.name }.toSet() +
            targetProfile.proxies.map { it.name }.toSet()
        val preservedDialer = node.dialerProxy?.takeIf { it in validTargetEntries && it != node.name }
        val baseCopy = updateNodeBasicFields(
            node = node.copy(
                id = UUID.randomUUID().toString().take(8),
                source = NodeSource.CUSTOM,
                subscriptionId = null
            ),
            newName = node.name,
            newServer = node.server,
            newPort = node.port,
            newUdp = node.udp,
            newDialerProxy = preservedDialer
        )
        val existingNames = targetProfile.proxies.map { it.name }.toSet()
        val uniqueNode = ensureUniqueNodeName(baseCopy, existingNames)
        val newProxies = listOf(uniqueNode) + targetProfile.proxies

        val groupSet = targetGroups.toSet()
        val updatedGroups = if (groupSet.isEmpty()) {
            targetProfile.proxyGroups
        } else {
            targetProfile.proxyGroups.map { group ->
                if (group.name in groupSet && uniqueNode.name !in group.proxies) {
                    buildProxyGroup(
                        id = group.id,
                        name = group.name,
                        type = group.type,
                        proxies = group.proxies + uniqueNode.name,
                        url = group.url,
                        interval = group.interval,
                        existingRawYaml = group.rawYaml
                    )
                } else {
                    group
                }
            }
        }

        val updatedTarget = targetProfile.copy(
            proxies = newProxies,
            proxyGroups = updatedGroups,
            meta = targetProfile.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = newProxies.size,
                groupCount = updatedGroups.size,
                relayBoundCount = newProxies.count { !it.dialerProxy.isNullOrBlank() },
                subscriptionCount = targetProfile.nodeSubscriptions.size
            )
        )
        return updatedTarget to uniqueNode
    }

    /**
     * Aggregates all nodes across every imported profile into a unified Global Node Pool.
     * Source (non-composed) profiles take canonical ownership first; duplicate copies already
     * pulled into composed profiles are deduplicated by (name, type, server, port).
     */
    fun buildGlobalNodePool(allProfiles: List<SplitProfile>): List<GlobalPoolNode> {
        val ordered = allProfiles.sortedBy { if (it.meta.isComposed) 1 else 0 }
        val seenFingerprints = mutableSetOf<String>()
        val seenOriginAndName = mutableSetOf<String>()
        val result = mutableListOf<GlobalPoolNode>()
        for (profile in ordered) {
            for (node in profile.proxies) {
                val originId = node.originProfileId?.takeIf { it.isNotBlank() } ?: profile.meta.id
                val originName = node.originProfileName?.takeIf { it.isNotBlank() } ?: profile.meta.name
                val originKey = "$originId::${node.name.trim()}"
                // If a source (non-composed) profile already provides this origin node,
                // skip stale copies carried inside composed profiles even after server/port updates.
                if (profile.meta.isComposed && originKey in seenOriginAndName) continue
                val fp = "${node.name.trim()}|${node.type.lowercase()}|${node.server.lowercase()}|${node.port}"
                if (!seenFingerprints.add(fp)) continue
                seenOriginAndName.add(originKey)
                val key = "${profile.meta.id}::${node.id}"
                result.add(
                    GlobalPoolNode(
                        key = key,
                        node = node.copy(originProfileId = originId, originProfileName = originName),
                        originProfileId = originId,
                        originProfileName = originName
                    )
                )
            }
        }
        return result
    }

    /**
     * Composes a brand-new custom [SplitProfile] from:
     * 1. A chosen [baseProfile] in the Config Pool (providing `baseConfigYaml`, `ruleProvidersYaml`, and optional base groups/rules)
     * 2. Cherry-picked proxy groups & rules from any number of [donors] in the Config Pool (automatically merging referenced `RULE-SET` providers & inserting rules before `MATCH`)
     * 3. Chosen nodes from the Global Node Pool ([selectedPoolNodes]), optionally auto-populating [autoPopulateGroups].
     */
    fun composeCustomProfile(
        profileName: String,
        baseProfile: SplitProfile,
        includeBaseGroups: Boolean = true,
        includeBaseRules: Boolean = true,
        donors: List<DonorSelection> = emptyList(),
        selectedPoolNodes: List<GlobalPoolNode>,
        autoPopulateGroups: Set<String> = emptySet()
    ): SplitProfile {
        val seenNames = mutableSetOf<String>()
        val pickedNodes = selectedPoolNodes.map { poolNode ->
            val rawCopy = poolNode.node.copy(
                id = UUID.randomUUID().toString().take(8),
                source = NodeSource.CUSTOM,
                subscriptionId = null,
                originProfileId = poolNode.originProfileId,
                originProfileName = poolNode.originProfileName
            )
            val unique = ensureUniqueNodeName(rawCopy, seenNames)
            seenNames.add(unique.name)
            unique
        }
        val pickedNodeNames = pickedNodes.map { it.name }

        // Build merged proxy groups (losslessly preserving Mihomo extra YAML fields)
        val mergedGroups = mutableListOf<ProxyGroupEntry>()
        val existingGroupNames = mutableSetOf<String>()

        if (includeBaseGroups) {
            for (g in baseProfile.proxyGroups) {
                if (existingGroupNames.add(g.name)) {
                    mergedGroups.add(g.copy(id = UUID.randomUUID().toString().take(8)))
                }
            }
        }
        for (donor in donors) {
            for (g in donor.donorProfile.proxyGroups) {
                if (g.name in donor.selectedGroupNames && existingGroupNames.add(g.name)) {
                    mergedGroups.add(g.copy(id = UUID.randomUUID().toString().take(8)))
                }
            }
        }

        // If user opted not to include any groups at all, create a sensible default select group
        if (mergedGroups.isEmpty() && pickedNodeNames.isNotEmpty()) {
            val defaultGroup = buildProxyGroup(
                name = "🚀 节点选择",
                type = "select",
                proxies = pickedNodeNames + listOf("DIRECT")
            )
            mergedGroups.add(defaultGroup)
            existingGroupNames.add(defaultGroup.name)
        }

        // Auto-populate selected groups with the picked nodes (and clean stale node names from base/donor)
        val validNamesForGroups = BUILTIN_POLICIES + existingGroupNames + pickedNodeNames.toSet()
        val finalGroups = mergedGroups.map { group ->
            val surviving = group.proxies.filter { it in validNamesForGroups }
            val members = if (group.name in autoPopulateGroups) {
                (surviving + pickedNodeNames).distinct()
            } else {
                surviving.distinct()
            }
            buildProxyGroup(
                id = group.id,
                name = group.name,
                type = group.type,
                proxies = members,
                url = group.url,
                interval = group.interval,
                existingRawYaml = group.rawYaml
            )
        }

        // Sanitize dialer-proxy on picked nodes so only valid target names in the new profile are kept
        val validDialerTargets = existingGroupNames + pickedNodeNames.toSet()
        val finalNodes = pickedNodes.map { node ->
            if (!node.dialerProxy.isNullOrBlank() && node.dialerProxy !in validDialerTargets) {
                updateNodeBasicFields(node, node.name, node.server, node.port, node.udp, null)
            } else {
                node
            }
        }

        // Build merged rules (cherry-picked donor rules inserted strictly BEFORE MATCH)
        var finalRules = if (includeBaseRules) {
            baseProfile.rules.map { it.copy(id = UUID.randomUUID().toString().take(8)) }
        } else {
            emptyList()
        }

        val providersMap = if (baseProfile.ruleProvidersYaml.isNotBlank()) {
            runCatching { YamlCodec.parseMap(baseProfile.ruleProvidersYaml) }.getOrDefault(linkedMapOf())
        } else {
            linkedMapOf()
        }

        for (donor in donors) {
            val chosenRules = donor.donorProfile.rules
                .filter { it.id in donor.selectedRuleIds }
                .map { it.copy(id = UUID.randomUUID().toString().take(8)) }
            if (chosenRules.isNotEmpty()) {
                finalRules = insertRulesBeforeMatch(finalRules, chosenRules)
                mergeReferencedRuleProviders(providersMap, chosenRules, donor.donorProfile.ruleProvidersYaml)
            }
        }

        if (finalRules.isEmpty() && finalGroups.isNotEmpty()) {
            finalRules = listOf(
                RuleEntry(type = "GEOIP", payload = "LAN", target = "DIRECT", noResolve = true),
                RuleEntry(type = "GEOIP", payload = "CN", target = "DIRECT"),
                RuleEntry(type = "MATCH", payload = "", target = finalGroups.first().name)
            )
        }

        val mergedProvidersYaml = if (providersMap.isNotEmpty()) YamlCodec.dump(providersMap) else ""
        val now = System.currentTimeMillis()
        val meta = ProfileMeta(
            name = profileName.trim().ifBlank { "${baseProfile.meta.name} · 定制" },
            subscriptionUrl = "",
            createdAt = now,
            updatedAt = now,
            nodeCount = finalNodes.size,
            groupCount = finalGroups.size,
            ruleCount = finalRules.size,
            relayBoundCount = finalNodes.count { !it.dialerProxy.isNullOrBlank() },
            subscriptionCount = 0,
            isComposed = true,
            baseProfileName = baseProfile.meta.name
        )

        return SplitProfile(
            meta = meta,
            baseConfigYaml = baseProfile.baseConfigYaml,
            proxies = finalNodes,
            proxyGroups = finalGroups,
            rules = finalRules,
            ruleProvidersYaml = mergedProvidersYaml,
            nodeSubscriptions = emptyList()
        )
    }

    /**
     * Mixes cherry-picked proxy groups and rules (plus referenced `RULE-SET` providers)
     * from [donor] into an existing [target] profile without clobbering existing edits.
     */
    fun mixinFromDonor(
        target: SplitProfile,
        donor: SplitProfile,
        selectedGroupNames: Set<String>,
        selectedRuleIds: Set<String>,
        autoPopulateWithCurrentNodes: Boolean = false
    ): Pair<SplitProfile, MixinSummary> {
        val currentNodeNames = target.proxies.map { it.name }
        val existingGroupNames = target.proxyGroups.map { it.name }.toMutableSet()
        val addedGroups = mutableListOf<ProxyGroupEntry>()

        for (g in donor.proxyGroups) {
            if (g.name in selectedGroupNames && existingGroupNames.add(g.name)) {
                val members = if (autoPopulateWithCurrentNodes && currentNodeNames.isNotEmpty()) {
                    (g.proxies.filter { it in BUILTIN_POLICIES || it in existingGroupNames } + currentNodeNames).distinct()
                } else {
                    g.proxies
                }
                addedGroups.add(
                    buildProxyGroup(
                        name = g.name,
                        type = g.type,
                        proxies = members,
                        url = g.url,
                        interval = g.interval,
                        existingRawYaml = g.rawYaml
                    )
                )
            }
        }
        val mergedGroups = target.proxyGroups + addedGroups

        val chosenRules = donor.rules
            .filter { it.id in selectedRuleIds }
            .map { it.copy(id = UUID.randomUUID().toString().take(8)) }
        val beforeRuleCount = target.rules.size
        val mergedRules = if (chosenRules.isNotEmpty()) {
            insertRulesBeforeMatch(target.rules, chosenRules)
        } else {
            target.rules
        }
        val addedRuleCount = mergedRules.size - beforeRuleCount

        val providersMap = if (target.ruleProvidersYaml.isNotBlank()) {
            runCatching { YamlCodec.parseMap(target.ruleProvidersYaml) }.getOrDefault(linkedMapOf())
        } else {
            linkedMapOf()
        }
        val beforeProviderCount = providersMap.size
        if (chosenRules.isNotEmpty()) {
            mergeReferencedRuleProviders(providersMap, chosenRules, donor.ruleProvidersYaml)
        }
        val addedProviders = providersMap.size - beforeProviderCount
        val mergedProvidersYaml = if (providersMap.isNotEmpty()) YamlCodec.dump(providersMap) else ""

        val updated = target.copy(
            proxyGroups = mergedGroups,
            rules = mergedRules,
            ruleProvidersYaml = mergedProvidersYaml,
            meta = target.meta.copy(
                updatedAt = System.currentTimeMillis(),
                groupCount = mergedGroups.size,
                ruleCount = mergedRules.size
            )
        )
        return updated to MixinSummary(
            addedGroups = addedGroups.size,
            addedRules = addedRuleCount,
            addedProviders = addedProviders
        )
    }

    /**
     * Refreshes matching nodes in [target] from the latest definitions in [globalPool]
     * while preserving each node's custom `dialer-proxy` binding, order, groups, and rules.
     */
    fun syncNodesFromPool(
        target: SplitProfile,
        globalPool: List<GlobalPoolNode>
    ): Pair<SplitProfile, Int> {
        val externalPool = globalPool.filterNot { it.key.startsWith("${target.meta.id}::") }
        val byOriginAndName = LinkedHashMap<String, GlobalPoolNode>()
        val byName = LinkedHashMap<String, GlobalPoolNode>()
        for (entry in externalPool) {
            byOriginAndName.putIfAbsent("${entry.originProfileId}::${entry.node.name}", entry)
            byName.putIfAbsent(entry.node.name, entry)
        }
        var syncedCount = 0

        val refreshedNodes = target.proxies.map { node ->
            val match = node.originProfileId?.let { byOriginAndName["$it::${node.name}"] }
                ?: byName[node.name]
            if (match != null && match.originProfileId != target.meta.id) {
                val fresh = match.node
                val merged = updateNodeBasicFields(
                    node = fresh.copy(
                        id = node.id,
                        source = node.source,
                        subscriptionId = node.subscriptionId,
                        originProfileId = match.originProfileId,
                        originProfileName = match.originProfileName
                    ),
                    newName = node.name,
                    newServer = fresh.server,
                    newPort = fresh.port,
                    newUdp = fresh.udp,
                    newDialerProxy = node.dialerProxy
                )
                if (merged.rawYaml != node.rawYaml || merged.server != node.server || merged.port != node.port) {
                    syncedCount++
                }
                merged
            } else {
                node
            }
        }

        val updated = target.copy(
            proxies = refreshedNodes,
            meta = target.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = refreshedNodes.size,
                relayBoundCount = refreshedNodes.count { !it.dialerProxy.isNullOrBlank() }
            )
        )
        return updated to syncedCount
    }

    private fun insertRulesBeforeMatch(existing: List<RuleEntry>, incoming: List<RuleEntry>): List<RuleEntry> {
        val seenKeys = existing.map { it.toClashRuleString().lowercase() }.toMutableSet()
        val uniqueIncoming = incoming.filter { rule ->
            seenKeys.add(rule.toClashRuleString().lowercase())
        }
        if (uniqueIncoming.isEmpty()) return existing
        val matchIdx = existing.indexOfFirst { it.type.equals("MATCH", ignoreCase = true) }
        return if (matchIdx >= 0) {
            existing.subList(0, matchIdx) + uniqueIncoming + existing.subList(matchIdx, existing.size)
        } else {
            existing + uniqueIncoming
        }
    }

    private fun mergeReferencedRuleProviders(
        targetProviders: LinkedHashMap<String, Any?>,
        rules: List<RuleEntry>,
        donorProvidersYaml: String
    ) {
        if (donorProvidersYaml.isBlank()) return
        val neededProviders = rules
            .filter { it.type.equals("RULE-SET", ignoreCase = true) && it.payload.isNotBlank() }
            .map { it.payload.trim() }
            .toSet()
        if (neededProviders.isEmpty()) return
        val donorMap = runCatching { YamlCodec.parseMap(donorProvidersYaml) }.getOrNull() ?: return
        for (providerName in neededProviders) {
            if (providerName !in targetProviders && providerName in donorMap) {
                targetProviders[providerName] = YamlCodec.deepCopy(donorMap[providerName])
            }
        }
    }

    private fun mapToProxyNode(
        map: Map<*, *>,
        source: NodeSource,
        subscriptionId: String? = null
    ): ProxyNode? {
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
            subscriptionId = subscriptionId,
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
