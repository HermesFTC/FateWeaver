package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

/**
 * A schema for maps with independently typed keys and values.
 *
 * Keys are not restricted to strings: any schema-able type works, so `Map<Int, Pose>` and
 * `Map<MyEnum, Double>` are both encodable.
 *
 * ## Binary Format
 *
 * **Schema Encoding**:
 * ```
 * [4 bytes: tag] [key schema blob] [value schema blob]
 * ```
 *
 * **Object Encoding**:
 * ```
 * [4 bytes: entry count N] [key1][value1] [key2][value2] ... [keyN][valueN]
 * ```
 *
 * Entries are written in the map's own iteration order. That order is *not* part of the format
 * contract — every key is written explicitly, so a [HashMap] and a [LinkedHashMap] with the same
 * contents both encode validly, just to different bytes.
 *
 * ## Mutation
 *
 * [objSize] and [encodeObject] are two separate calls made by
 * [gay.zharel.fateweaver.log.FateLogWriter.write]. The map must not be mutated between them; doing
 * so fails loudly — either a [java.nio.BufferOverflowException] or the writer's "encoded object does
 * not match reported size" check — rather than writing corrupt bytes. Pass a defensive copy if the
 * map may be mutated by another thread.
 *
 * ## Nulls
 *
 * The FateWeaver format has no encoding for null, so [K] and [V] are bounded to [Any] and null keys
 * and values are rejected at compile time.
 *
 * ## Size Limits
 *
 * [objSize] returns an [Int], so a map whose encoding would exceed 2 GiB overflows and the writer's
 * buffer allocation then throws. Loud, but not diagnosable — keep logged maps to a sane size.
 *
 * ## Usage Example
 *
 * ```kotlin
 * val powers = writer.createChannel("Motors/Powers", MapSchema(StringSchema, DoubleSchema))
 * powers.put(mapOf("fl" to 0.5, "fr" to -0.5))
 * ```
 *
 * @param K the key type
 * @param V the value type
 * @param keySchema schema used to encode each key
 * @param valueSchema schema used to encode each value
 */
class MapSchema<K : Any, V : Any>(
    val keySchema: FateSchema<K>,
    val valueSchema: FateSchema<V>,
) : FateSchema<Map<K, V>> {
    override val tag: Int = FateSchema.TypeRegistry.MAP.value

    override val schemaSize: Int = Int.SIZE_BYTES + keySchema.schemaSize + valueSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(keySchema.schema)
        buffer.put(valueSchema.schema)
        buffer.array()
    }

    override fun objSize(obj: Map<K, V>): Int {
        var size = Int.SIZE_BYTES
        for ((key, value) in obj) {
            size += keySchema.objSize(key) + valueSchema.objSize(value)
        }
        return size
    }

    override fun encodeObject(buffer: ByteBuffer, obj: Map<K, V>) {
        // Snapshot the entries so the count prefix can never disagree with the body that follows.
        val entries = obj.entries.toList()
        buffer.putInt(entries.size)
        for (entry in entries) {
            keySchema.encodeObject(buffer, entry.key)
            valueSchema.encodeObject(buffer, entry.value)
        }
    }
}
