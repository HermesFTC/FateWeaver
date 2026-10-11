package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

/**
 * A schema for maps with independently typed keys and values.
 *
 * Keys can be any type that has a schema, so `Map<Int, Pose>` and `Map<MyEnum, Double>` both work.
 *
 * ## Binary format
 *
 * The schema:
 * ```
 * [4 bytes: tag] [key schema blob] [value schema blob]
 * ```
 *
 * An encoded map:
 * ```
 * [4 bytes: entry count N] [key1][value1] [key2][value2] ... [keyN][valueN]
 * ```
 *
 * Entries are written in the map's own iteration order, and the format does not depend on it. Every
 * key is written, so a [HashMap] and a [LinkedHashMap] with the same entries both decode correctly,
 * but their bytes differ.
 *
 * ## Mutation
 *
 * [gay.zharel.fateweaver.log.FateLogWriter.write] calls [objSize] and [encodeObject] separately, so
 * don't mutate the map in between. If you do, the write fails with a
 * [java.nio.BufferOverflowException] or the writer's "encoded object does not match reported size"
 * check. It does not write corrupt bytes. Pass a copy if another thread might touch the map.
 *
 * ## Nulls
 *
 * A null key or value needs an [OptionalSchema] on that side. `Map<String, Double?>` infers as
 * `MapSchema(StringSchema, OptionalSchema(DoubleSchema))`, and each absent value costs one byte.
 * A null passed to a schema that is not optional throws.
 *
 * ## Size limits
 *
 * [objSize] returns an [Int]. A map that encodes to more than 2 GiB overflows it, and the writer's
 * buffer allocation then throws without saying why.
 *
 * ## Example
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
class MapSchema<K, V>(
    val keySchema: FateSchema<K>,
    val valueSchema: FateSchema<V>,
) : FateSchema<@JvmSuppressWildcards Map<K, V>> {
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
        // Copy the entries so the count prefix always matches the entries written after it.
        val entries = obj.entries.toList()
        buffer.putInt(entries.size)
        for (entry in entries) {
            keySchema.encodeObject(buffer, entry.key)
            valueSchema.encodeObject(buffer, entry.value)
        }
    }
}
