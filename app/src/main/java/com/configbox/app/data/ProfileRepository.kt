package com.configbox.app.data

import android.content.Context
import com.configbox.app.engine.ConfigEngine
import com.configbox.app.engine.YamlCodec
import com.configbox.app.model.NodeSource
import com.configbox.app.model.NodeSubscription
import com.configbox.app.model.ProfileMeta
import com.configbox.app.model.ProxyGroupEntry
import com.configbox.app.model.ProxyNode
import com.configbox.app.model.RuleEntry
import com.configbox.app.model.SplitProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class NodeOriginRef(
    val profileId: String,
    val profileName: String
)

/**
 * Persists each split profile into its own modular directory:
 *
 *   filesDir/profiles/<profileId>/
 *     ├── meta.json
 *     ├── base.yaml
 *     ├── proxies.yaml            (subscription nodes)
 *     ├── custom_proxies.yaml     (user-added / custom nodes)
 *     ├── node_order.json         (user-defined global node order)
 *     ├── node_subscriptions.json (extra node subscription sources)
 *     ├── node_sub_map.json       (nodeName -> subscriptionId mapping)
 *     ├── groups.yaml             (editable proxy-groups)
 *     ├── rules.json              (editable rules)
 *     └── rule_providers.yaml
 */
class ProfileRepository(context: Context) {

    private val profilesRoot = File(context.filesDir, "profiles").apply { mkdirs() }
    private val ioMutex = Mutex()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun resolveProfileDir(profileId: String): File {
        require(SAFE_ID_REGEX.matches(profileId)) { "非法的配置 ID：$profileId" }
        return File(profilesRoot, profileId)
    }

    suspend fun listProfiles(): List<ProfileMeta> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            profilesRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    val metaFile = File(dir, "meta.json")
                    if (!metaFile.exists()) return@mapNotNull null
                    runCatching { json.decodeFromString<ProfileMeta>(metaFile.readText()) }.getOrNull()
                }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
        }
    }

    suspend fun loadAllProfiles(): List<SplitProfile> = withContext(Dispatchers.IO) {
        val metas = listProfiles()
        metas.mapNotNull { loadProfile(it.id) }
    }

    suspend fun loadProfile(profileId: String): SplitProfile? = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (!SAFE_ID_REGEX.matches(profileId)) return@withLock null
            val dir = File(profilesRoot, profileId)
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@withLock null

            val meta = runCatching {
                json.decodeFromString<ProfileMeta>(metaFile.readText())
            }.getOrNull() ?: return@withLock null
            val baseYaml = File(dir, "base.yaml").takeIf { it.exists() }?.readText() ?: ""
            val nodeSubscriptions = File(dir, "node_subscriptions.json").takeIf { it.exists() }?.let {
                runCatching { json.decodeFromString<List<NodeSubscription>>(it.readText()) }.getOrDefault(emptyList())
            } ?: emptyList()
            val validSubIds = nodeSubscriptions.map { it.id }.toSet()
            val subMap = File(dir, "node_sub_map.json").takeIf { it.exists() }?.let {
                runCatching { json.decodeFromString<Map<String, String>>(it.readText()) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val originMap = File(dir, "node_origins.json").takeIf { it.exists() }?.let {
                runCatching { json.decodeFromString<Map<String, NodeOriginRef>>(it.readText()) }.getOrDefault(emptyMap())
            } ?: emptyMap()

            val subProxies = readProxyListFile(File(dir, "proxies.yaml"), NodeSource.SUBSCRIPTION)
                .map { node ->
                    val subId = subMap[node.name]?.takeIf { it in validSubIds }
                    val origin = originMap[node.name]
                    node.copy(
                        subscriptionId = subId,
                        originProfileId = origin?.profileId,
                        originProfileName = origin?.profileName
                    )
                }
            val customProxies = readProxyListFile(File(dir, "custom_proxies.yaml"), NodeSource.CUSTOM)
                .map { node ->
                    val origin = originMap[node.name]
                    if (origin != null) {
                        node.copy(originProfileId = origin.profileId, originProfileName = origin.profileName)
                    } else {
                        node
                    }
                }
            val combined = subProxies + customProxies

            // Restore exact user-defined node order if persisted
            val savedOrder = File(dir, "node_order.json").takeIf { it.exists() }?.let {
                runCatching { json.decodeFromString<List<String>>(it.readText()) }.getOrNull()
            }
            val allProxies = if (!savedOrder.isNullOrEmpty()) {
                val orderIndex = savedOrder.withIndex().associate { it.value to it.index }
                combined.sortedBy { orderIndex[it.name] ?: Int.MAX_VALUE }
            } else {
                combined
            }

            val disabledGroups = File(dir, "disabled_groups.json").takeIf { it.exists() }?.let {
                runCatching { json.decodeFromString<List<String>>(it.readText()) }.getOrDefault(emptyList())
            }?.toSet() ?: emptySet()
            val groups = readGroupListFile(File(dir, "groups.yaml"))
                .map { group -> group.copy(enabled = group.name !in disabledGroups) }
            val rules = File(dir, "rules.json").takeIf { it.exists() }?.let {
                runCatching { json.decodeFromString<List<RuleEntry>>(it.readText()) }.getOrDefault(emptyList())
            } ?: emptyList()
            val ruleProvidersYaml = File(dir, "rule_providers.yaml").takeIf { it.exists() }?.readText() ?: ""

            SplitProfile(
                meta = meta.copy(
                    nodeCount = allProxies.size,
                    groupCount = groups.size,
                    ruleCount = rules.size,
                    relayBoundCount = allProxies.count { !it.dialerProxy.isNullOrBlank() },
                    subscriptionCount = nodeSubscriptions.size
                ),
                baseConfigYaml = baseYaml,
                proxies = allProxies,
                proxyGroups = groups,
                rules = rules,
                ruleProvidersYaml = ruleProvidersYaml,
                nodeSubscriptions = nodeSubscriptions
            )
        }
    }

    suspend fun saveProfile(profile: SplitProfile): SplitProfile = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val updatedMeta = profile.meta.copy(
                updatedAt = System.currentTimeMillis(),
                nodeCount = profile.proxies.size,
                groupCount = profile.proxyGroups.size,
                ruleCount = profile.rules.size,
                relayBoundCount = profile.proxies.count { !it.dialerProxy.isNullOrBlank() },
                subscriptionCount = profile.nodeSubscriptions.size
            )
            val updated = profile.copy(meta = updatedMeta)
            val dir = resolveProfileDir(updatedMeta.id).apply { mkdirs() }

            writeTextAtomic(File(dir, "meta.json"), json.encodeToString(updatedMeta))
            writeTextAtomic(File(dir, "base.yaml"), updated.baseConfigYaml)

            val subNodes = updated.proxies.filter { it.source == NodeSource.SUBSCRIPTION }
            val customNodes = updated.proxies.filter { it.source == NodeSource.CUSTOM }
            writeProxyListFile(File(dir, "proxies.yaml"), subNodes)
            writeProxyListFile(File(dir, "custom_proxies.yaml"), customNodes)
            writeTextAtomic(File(dir, "node_order.json"), json.encodeToString(updated.proxies.map { it.name }))

            writeTextAtomic(File(dir, "node_subscriptions.json"), json.encodeToString(updated.nodeSubscriptions))
            val subMap = updated.proxies
                .mapNotNull { node -> node.subscriptionId?.let { node.name to it } }
                .toMap()
            writeTextAtomic(File(dir, "node_sub_map.json"), json.encodeToString(subMap))

            val originMap = updated.proxies
                .mapNotNull { node ->
                    val pid = node.originProfileId
                    val pname = node.originProfileName
                    if (!pid.isNullOrBlank() && !pname.isNullOrBlank()) {
                        node.name to NodeOriginRef(pid, pname)
                    } else {
                        null
                    }
                }
                .toMap()
            writeTextAtomic(File(dir, "node_origins.json"), json.encodeToString(originMap))

            writeGroupListFile(File(dir, "groups.yaml"), updated.proxyGroups)
            val disabledGroupNames = updated.proxyGroups.filterNot { it.enabled }.map { it.name }
            writeTextAtomic(File(dir, "disabled_groups.json"), json.encodeToString(disabledGroupNames))
            writeTextAtomic(File(dir, "rules.json"), json.encodeToString(updated.rules))
            writeTextAtomic(File(dir, "rule_providers.yaml"), updated.ruleProvidersYaml)

            updated
        }
    }

    suspend fun deleteProfile(profileId: String) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (SAFE_ID_REGEX.matches(profileId)) {
                File(profilesRoot, profileId).deleteRecursively()
            }
        }
    }

    suspend fun fetchSubscriptionYaml(url: String): String = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        require(cleanUrl.startsWith("http://", ignoreCase = true) || cleanUrl.startsWith("https://", ignoreCase = true)) {
            "订阅链接必须以 http:// 或 https:// 开头"
        }
        val request = Request.Builder()
            .url(cleanUrl)
            .header("User-Agent", "clash-verge/v2.0.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("订阅拉取失败：HTTP ${response.code}")
            }
            val body = response.body?.string()?.removePrefix("\uFEFF")
            if (body.isNullOrBlank()) {
                throw IllegalStateException("订阅返回内容为空")
            }
            body
        }
    }

    private fun writeTextAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Fallback for filesystems where renameTo does not replace existing targets
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun writeProxyListFile(file: File, nodes: List<ProxyNode>) {
        val maps = nodes.map { YamlCodec.parseMap(it.rawYaml) }
        writeTextAtomic(file, YamlCodec.dump(linkedMapOf("proxies" to maps)))
    }

    private fun readProxyListFile(file: File, source: NodeSource): List<ProxyNode> {
        if (!file.exists()) return emptyList()
        val root = runCatching { YamlCodec.parseMap(file.readText()) }.getOrNull() ?: return emptyList()
        val list = root["proxies"] as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val yamlStr = YamlCodec.dump(map)
            runCatching { ConfigEngine.parseNodeFromYaml(yamlStr, source) }.getOrNull()
        }
    }

    private fun writeGroupListFile(file: File, groups: List<ProxyGroupEntry>) {
        val maps = groups.map { YamlCodec.parseMap(it.rawYaml) }
        writeTextAtomic(file, YamlCodec.dump(linkedMapOf("proxy-groups" to maps)))
    }

    private fun readGroupListFile(file: File): List<ProxyGroupEntry> {
        if (!file.exists()) return emptyList()
        val root = runCatching { YamlCodec.parseMap(file.readText()) }.getOrNull() ?: return emptyList()
        val list = root["proxy-groups"] as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val type = map["type"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "select"
            val proxies = (map["proxies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            ProxyGroupEntry(
                name = name,
                type = type,
                proxies = proxies,
                url = map["url"]?.toString(),
                interval = map["interval"]?.toString()?.toIntOrNull(),
                rawYaml = YamlCodec.dump(map)
            )
        }
    }

    companion object {
        private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    }
}
