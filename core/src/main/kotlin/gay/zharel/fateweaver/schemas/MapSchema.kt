package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

/**
 * A schema for maps with independently typed keys and values.
 *
 * Keys are not restricted to strings: any schema-able type works, so `Map<Int, Pose>` and
 * `Map<MyEnum, Double>` are both encodable.
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
 * Entries go out in the map's own iteration order, which is *not* part of the format contract.
 * Every key is written explicitly, so a [HashMap] and a [LinkedHashMap] holding the same entries
 * both encode validly, just to different bytes.
 *
 * ## Mutation
 *
 * [gay.zharel.fateweaver.log.FateLogWriter.write] calls [objSize] and [encodeObject] separately, so
 * don't mutate the map in between. If you do, you get either a [java.nio.BufferOverflowException] or
 * the writer's "encoded object does not match reported size" check, not corrupt bytes. Pass a copy
 * if another thread might touch the map.
 *
 * ## Nulls
 *
 * A null key or value needs an [OptionalSchema] around the relevant side. `Map<String, Double?>`
 * infers as `MapSchema(StringSchema, OptionalSchema(DoubleSchema))`, and each absent value costs
 * one byte. Passing a null to a schema that isn't optional throws rather than writing bad bytes.
 *
 * ## Size limits
 *
 * [objSize] returns an [Int], so a map that would encode to more than 2 GiB overflows it and the
 * writer's buffer allocation throws. You'll notice, but the error won't tell you why. Keep logged
 * maps to a sane size.
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
        // Snapshot the entries so the count prefix can never disagree with the body that follows.
        val entries = obj.entries.toList()
        buffer.putInt(entries.size)
        for (entry in entries) {
            keySchema.encodeObject(buffer, entry.key)
            valueSchema.encodeObject(buffer, entry.value)
        }
    }
}
