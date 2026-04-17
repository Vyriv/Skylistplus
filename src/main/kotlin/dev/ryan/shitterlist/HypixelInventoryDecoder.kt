package dev.ryan.throwerlist

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream

object HypixelInventoryDecoder {
    fun decodeItems(encodedData: String?): List<InventoryItem> {
        val encoded = encodedData?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { return emptyList() }
        val root = runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                DataInputStream(gzip).use(::readRootCompound)
            }
        }.getOrElse { return emptyList() }

        val items = root["i"] as? List<*> ?: return emptyList()
        return items.mapNotNull(::toInventoryItem)
    }

    data class InventoryItem(
        val itemId: String?,
        val displayName: String?,
        val lore: List<String>,
        val enchantments: Map<String, Int>,
        val rawData: Map<String, Any?>,
    ) {
        fun containsString(text: String): Boolean {
            val needle = text.lowercase()
            return searchableStrings().any { it.contains(needle) }
        }

        private fun searchableStrings(): List<String> =
            buildList {
                itemId?.let { add(it.lowercase()) }
                displayName?.let { add(it.lowercase()) }
                lore.forEach { add(it.lowercase()) }
                collectStrings(rawData, this)
            }
    }

    private fun toInventoryItem(rawItem: Any?): InventoryItem? {
        val item = rawItem as? Map<*, *> ?: return null
        if (item.isEmpty()) {
            return null
        }

        val itemMap = item.entries.associate { (key, value) -> key.toString() to value }
        val tag = itemMap["tag"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val display = tag["display"] as? Map<*, *>
        val extraAttributes = tag["ExtraAttributes"] as? Map<*, *>
        val enchantments = extraAttributes?.get("enchantments") as? Map<*, *>

        return InventoryItem(
            itemId = extraAttributes?.get("id") as? String,
            displayName = display?.get("Name") as? String,
            lore = (display?.get("Lore") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            enchantments = enchantments
                ?.mapNotNull { (key, value) ->
                    val name = key as? String ?: return@mapNotNull null
                    val level = (value as? Number)?.toInt() ?: return@mapNotNull null
                    name.lowercase() to level
                }
                ?.toMap()
                .orEmpty(),
            rawData = itemMap,
        )
    }

    private fun collectStrings(value: Any?, output: MutableList<String>) {
        when (value) {
            is String -> output += value.lowercase()
            is Map<*, *> -> value.values.forEach { collectStrings(it, output) }
            is List<*> -> value.forEach { collectStrings(it, output) }
        }
    }

    private fun readRootCompound(input: DataInputStream): Map<String, Any?> {
        val rootType = input.readUnsignedByte()
        if (rootType != TAG_COMPOUND) {
            throw IllegalArgumentException("Expected compound root tag but got $rootType")
        }
        readString(input)
        return readCompoundPayload(input)
    }

    private fun readPayload(input: DataInputStream, type: Int): Any? =
        when (type) {
            TAG_END -> null
            TAG_BYTE -> input.readByte()
            TAG_SHORT -> input.readShort()
            TAG_INT -> input.readInt()
            TAG_LONG -> input.readLong()
            TAG_FLOAT -> input.readFloat()
            TAG_DOUBLE -> input.readDouble()
            TAG_BYTE_ARRAY -> ByteArray(input.readInt()).also(input::readFully)
            TAG_STRING -> readString(input)
            TAG_LIST -> {
                val elementType = input.readUnsignedByte()
                List(input.readInt()) { readPayload(input, elementType) }
            }
            TAG_COMPOUND -> readCompoundPayload(input)
            TAG_INT_ARRAY -> IntArray(input.readInt()) { input.readInt() }.toList()
            TAG_LONG_ARRAY -> LongArray(input.readInt()) { input.readLong() }.toList()
            else -> throw IllegalArgumentException("Unsupported NBT tag type $type")
        }

    private fun readCompoundPayload(input: DataInputStream): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        while (true) {
            val type = input.readUnsignedByte()
            if (type == TAG_END) {
                return values
            }
            val name = readString(input)
            values[name] = readPayload(input, type)
        }
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort()
        if (length == 0) {
            return ""
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12
}
