package com.madtreasures.faceclaw.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/** Persists the settings document (a flat JSON object). */
interface SettingsStorage {
    fun load(): String?
    fun save(json: String)
}

class FileSettingsStorage(private val file: File) : SettingsStorage {
    override fun load(): String? = if (file.exists()) file.readText() else null
    override fun save(json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }
}

class MemorySettingsStorage(private var content: String? = null) : SettingsStorage {
    override fun load(): String? = content
    override fun save(json: String) {
        content = json
    }
}

/** A typed setting with a key, a default and a JSON codec. */
sealed class Setting<T>(val key: String, val default: T) {
    abstract fun decode(e: JsonElement): T?
    abstract fun encode(v: T): JsonElement

    class Bool(key: String, default: Boolean) : Setting<Boolean>(key, default) {
        override fun decode(e: JsonElement) = (e as? JsonPrimitive)?.booleanOrNull
        override fun encode(v: Boolean): JsonElement = JsonPrimitive(v)
    }

    class Int(key: String, default: kotlin.Int, val range: IntRange? = null) : Setting<kotlin.Int>(key, default) {
        override fun decode(e: JsonElement) = (e as? JsonPrimitive)?.intOrNull?.let { v -> range?.let { v.coerceIn(it) } ?: v }
        override fun encode(v: kotlin.Int): JsonElement = JsonPrimitive(v)
    }

    class Long(key: String, default: kotlin.Long) : Setting<kotlin.Long>(key, default) {
        override fun decode(e: JsonElement) = (e as? JsonPrimitive)?.longOrNull
        override fun encode(v: kotlin.Long): JsonElement = JsonPrimitive(v)
    }

    class Double(key: String, default: kotlin.Double) : Setting<kotlin.Double>(key, default) {
        override fun decode(e: JsonElement) = (e as? JsonPrimitive)?.doubleOrNull
        override fun encode(v: kotlin.Double): JsonElement = JsonPrimitive(v)
    }

    class Str(key: String, default: String) : Setting<String>(key, default) {
        override fun decode(e: JsonElement) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
        override fun encode(v: String): JsonElement = JsonPrimitive(v)
    }

    class Choice<E : Enum<E>>(key: String, default: E, private val values: Array<E>) : Setting<E>(key, default) {
        override fun decode(e: JsonElement): E? {
            val name = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return values.firstOrNull { it.name == name }
        }
        override fun encode(v: E): JsonElement = JsonPrimitive(v.name)
    }
}

/**
 * The settings document. Reads are cheap (in-memory map); writes persist immediately.
 * Each setting also exposes a [StateFlow] so UI on the phone and glasses stays in sync.
 */
class Settings(private val storage: SettingsStorage) {
    private val values = HashMap<String, JsonElement>()
    private val flows = HashMap<String, MutableStateFlow<Any?>>()
    private val flowSettings = HashMap<String, Setting<*>>()
    private val json = Json { prettyPrint = true }

    init {
        storage.load()?.let { text ->
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { values.putAll(it) }
        }
    }

    @Synchronized
    operator fun <T> get(s: Setting<T>): T = values[s.key]?.let { s.decode(it) } ?: s.default

    @Synchronized
    operator fun <T> set(s: Setting<T>, value: T) {
        values[s.key] = s.encode(value)
        storage.save(json.encodeToString(JsonObject.serializer(), JsonObject(values.toMap())))
        @Suppress("UNCHECKED_CAST")
        (flows[s.key] as MutableStateFlow<T>?)?.value = value
    }

    @Synchronized
    fun <T> flow(s: Setting<T>): StateFlow<T> {
        @Suppress("UNCHECKED_CAST")
        val f = flows.getOrPut(s.key) {
            flowSettings[s.key] = s
            MutableStateFlow(get(s))
        } as MutableStateFlow<T>
        return f.asStateFlow()
    }

    /** Raw export of the whole document (for backup/transfer). */
    @Synchronized
    fun exportJson(): String = json.encodeToString(JsonObject.serializer(), JsonObject(values.toMap()))

    @Synchronized
    fun importJson(text: String) {
        val obj = json.parseToJsonElement(text) as? JsonObject ?: return
        values.clear()
        values.putAll(obj)
        storage.save(exportJson())
        for ((key, flow) in flows) flow.value = flowSettings[key]?.let { get(it) }
    }
}
