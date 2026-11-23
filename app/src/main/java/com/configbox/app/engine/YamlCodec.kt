package com.configbox.app.engine

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

/**
 * Thread-safe helper around SnakeYAML 2.2.
 * Always creates a fresh [Yaml] instance per call because SnakeYAML instances are not thread-safe.
 */
object YamlCodec {

    private fun newYaml(): Yaml {
        val loaderOptions = LoaderOptions().apply {
            maxAliasesForCollections = 200
            codePointLimit = 20 * 1024 * 1024 // 20 MB for large rule-heavy Clash configs
            isAllowDuplicateKeys = true
        }
        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
            isAllowUnicode = true
            width = 1000
            lineBreak = DumperOptions.LineBreak.UNIX
        }
        val representer = Representer(dumperOptions).apply {
            propertyUtils.isSkipMissingProperties = true
        }
        return Yaml(SafeConstructor(loaderOptions), representer, dumperOptions)
    }

    fun parseAny(yamlText: String): Any? {
        if (yamlText.isBlank()) return null
        return newYaml().load<Any?>(yamlText)
    }

    fun parseAll(yamlText: String): List<Any?> {
        if (yamlText.isBlank()) return emptyList()
        return newYaml().loadAll(yamlText).toList()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseMap(yamlText: String): LinkedHashMap<String, Any?> {
        if (yamlText.isBlank()) return linkedMapOf()
        val parsed = newYaml().load<Any?>(yamlText) ?: return linkedMapOf()
        if (parsed !is Map<*, *>) {
            throw IllegalArgumentException("YAML 顶层结构必须是键值对映射 (Map)")
        }
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in parsed) {
            if (k != null) {
                result[k.toString()] = deepCopy(v)
            }
        }
        return result
    }

    fun dump(data: Any?): String {
        if (data == null) return ""
        return newYaml().dump(data).trimEnd() + "\n"
    }

    fun deepCopy(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> {
                val copy = LinkedHashMap<String, Any?>()
                for ((k, v) in value) {
                    if (k != null) copy[k.toString()] = deepCopy(v)
                }
                copy
            }
            is List<*> -> value.map { deepCopy(it) }.toMutableList()
            else -> value
        }
    }
}
