package com.configbox.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class NodeSource {
    SUBSCRIPTION,
    CUSTOM
}

/**
 * Metadata persisted in meta.json for each split Clash profile.
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
    val relayGroupCount: Int = 0
)

/**
 * An independent proxy node split out of `proxies:`.
 * [rawYaml] stores the exact single-node YAML block so all protocol-specific
 * fields (reality-opts, ws-opts, grpc-opts, alpn, sni, etc.) are preserved losslessly.
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
    val rawYaml: String
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
 * A single Clash routing rule split out of `rules:`.
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
 * Mihomo `dialer-proxy` chain relay group definition.
 *
 * Traffic flow:
 *   Device -> [entryProxy] -> [middleProxies...] -> each of [exitProxies] -> Target
 *
 * During compilation, original nodes stay untouched; cloned derived nodes with
 * `dialer-proxy` injected are emitted alongside a dedicated proxy-group named [name].
 */
@Serializable
data class RelayGroup(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val groupType: String = "select", // "select" or "url-test"
    val entryProxy: String,           // Node name OR existing proxy-group name
    val middleProxies: List<String> = emptyList(),
    val exitProxies: List<String> = emptyList(),
    val injectToMainGroups: Boolean = true,
    val enabled: Boolean = true,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val interval: Int = 300
)

/**
 * Complete modular representation of a split Clash configuration.
 */
data class SplitProfile(
    val meta: ProfileMeta,
    val baseConfigYaml: String,
    val proxies: List<ProxyNode>,
    val proxyGroups: List<ProxyGroupEntry>,
    val rules: List<RuleEntry>,
    val ruleProvidersYaml: String,
    val relayGroups: List<RelayGroup>
)
