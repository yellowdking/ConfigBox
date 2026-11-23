package com.configbox.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class NodeSource {
    SUBSCRIPTION,
    CUSTOM
}

/**
 * Metadata persisted in meta.json for each split YAML profile.
 */
@Serializable
data class ProfileMeta(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val subscriptionUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val nodeCount: Int = 0,
    val groupCount: Int = 0,
    val ruleCount: Int = 0,
    val relayBoundCount: Int = 0,
    val subscriptionCount: Int = 0,
    val isComposed: Boolean = false,
    val baseProfileName: String = ""
)

/**
 * An external node subscription source attached to a profile.
 * Supports Clash YAML, Base64 URI lists, and plaintext URI lists.
 */
@Serializable
data class NodeSubscription(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val url: String,
    val namePrefix: String = "",
    val autoAddToGroups: List<String> = emptyList(),
    val lastUpdatedAt: Long = 0L,
    val nodeCount: Int = 0
)

/**
 * An independent proxy node split out of `proxies:`.
 * When relay is configured, [dialerProxy] is written directly onto this node (`dialer-proxy`).
 */
data class ProxyNode(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val udp: Boolean = true,
    val dialerProxy: String? = null,
    val source: NodeSource = NodeSource.SUBSCRIPTION,
    val subscriptionId: String? = null,
    val originProfileId: String? = null,
    val originProfileName: String? = null,
    val rawYaml: String
)

/**
 * A node entry exposed in the unified Global Node Pool aggregated across all imported profiles.
 */
data class GlobalPoolNode(
    val key: String,
    val node: ProxyNode,
    val originProfileId: String,
    val originProfileName: String
)

/**
 * Cherry-picked groups & rules from a donor profile in the Config Pool.
 */
data class DonorSelection(
    val donorProfile: SplitProfile,
    val selectedGroupNames: Set<String> = emptySet(),
    val selectedRuleIds: Set<String> = emptySet()
)

/**
 * A proxy group split out of `proxy-groups:`.
 */
data class ProxyGroupEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val type: String,
    val proxies: List<String>,
    val url: String? = null,
    val interval: Int? = null,
    val rawYaml: String
)

/**
 * A single routing rule split out of `rules:`.
 */
@Serializable
data class RuleEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val type: String,
    val payload: String,
    val target: String,
    val noResolve: Boolean = false,
    val enabled: Boolean = true
) {
    fun toClashRuleString(): String {
        return buildString {
            append(type.trim())
            if (payload.isNotBlank()) {
                append(",")
                append(payload.trim())
            }
            if (target.isNotBlank()) {
                append(",")
                append(target.trim())
            }
            if (noResolve) {
                append(",no-resolve")
            }
        }
    }
}

/**
 * Complete modular representation of a split configuration.
 */
data class SplitProfile(
    val meta: ProfileMeta,
    val baseConfigYaml: String,
    val proxies: List<ProxyNode>,
    val proxyGroups: List<ProxyGroupEntry>,
    val rules: List<RuleEntry>,
    val ruleProvidersYaml: String,
    val nodeSubscriptions: List<NodeSubscription> = emptyList()
)
