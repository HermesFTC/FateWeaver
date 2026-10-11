package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import java.util.Optional
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.OptionalLong

/**
 * A schema for values that may be absent, wrapping the schema for the value itself.
 *
 * You rarely build one yourself. A nullable property gets one automatically, so
 * `class Target(val pose: Pose?)` encodes `pose` as an optional POSE. Nullable type arguments work
 * too, as in `Map<String, Double?>` and `List<String?>`.
 *
 * ## Binary format
 *
 * The schema:
 * ```
 * [4 bytes: tag] [inner schema blob]
 * ```
 *
 * An encoded value:
 * ```
 * [1 byte: 0x00 absent]
 * [1 byte: 0x01 present] [value encoded by the inner schema]
 * ```
 *
 * An absent value costs one byte. A present value costs one byte more than it would unwrapped.
 *
 * ## Decoder compatibility
 *
 * This schema uses tag 9, added after MAP. Log entries have no length prefix, so a reader that
 * doesn't know the tag cannot skip the value. It stops at the first channel that uses one. Non-null
 * fields encode as before.
 *
 * @param T the value type when present
 * @param valueSchema schema used to encode the value when it is present
 */
class OptionalSchema<T : Any>(val valueSchema: FateSchema<T>) : FateSchema<T?> {
    override val tag: Int = FateSchema.TypeRegistry.OPTIONAL.value

    override val schemaSize: Int = Int.SIZE_BYTES + valueSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(valueSchema.schema)
        buffer.array()
    }

    override fun objSize(obj: T?): Int =
        Byte.SIZE_BYTES + if (obj == null) 0 else valueSchema.objSize(obj)

    override fun encodeObject(buffer: ByteBuffer, obj: T?) {
        if (obj == null) {
            buffer.put(0)
        } else {
            buffer.put(1)
            valueSchema.encodeObject(buffer, obj)
        }
    }
}

/**
 * A schema for [java.util.Optional], encoded exactly like [OptionalSchema].
 *
 * `Optional<T>` means the same as Kotlin's `T?`, so it has the same OPTIONAL tag and the same
 * bytes. A reader cannot tell the two apart.
 *
 * `Optional<Double>` infers from a declared property. The raw class has no type argument, so
 * `schemaOfClass(Optional::class.java)` is rejected, the same as a raw `Map`.
 *
 * A null `Optional` reference is rejected, not recorded as absent. `Optional` is never supposed to
 * be null, so a null there is a bug.
 *
 * @param T the value type when present
 * @param valueSchema schema used to encode the value when it is present
 */
class JavaOptionalSchema<T : Any>(val valueSchema: FateSchema<T>) : FateSchema<Optional<T>> {
    override val tag: Int = FateSchema.TypeRegistry.OPTIONAL.value

    override val schemaSize: Int = Int.SIZE_BYTES + valueSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(valueSchema.schema)
        buffer.array()
    }

    override fun objSize(obj: Optional<T>): Int =
        Byte.SIZE_BYTES + if (obj.isPresent) valueSchema.objSize(obj.get()) else 0

    override fun encodeObject(buffer: ByteBuffer, obj: Optional<T>) {
        if (obj.isPresent) {
            buffer.put(1)
            valueSchema.encodeObject(buffer, obj.get())
        } else {
            buffer.put(0)
        }
    }
}

/**
 * Base for the schemas of `OptionalInt`, `OptionalLong` and `OptionalDouble`. These classes are not
 * generic, so inference needs no type argument.
 */
sealed class PrimitiveOptionalSchema<A : Any>(val valueSchema: FateSchema<*>) : FateSchema<A> {
    override val tag: Int = FateSchema.TypeRegistry.OPTIONAL.value

    override val schemaSize: Int = Int.SIZE_BYTES + valueSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(valueSchema.schema)
        buffer.array()
    }
}

/** Encodes an [OptionalInt] like an optional INT. */
object OptionalIntSchema : PrimitiveOptionalSchema<OptionalInt>(IntSchema) {
    override fun objSize(obj: OptionalInt): Int =
        Byte.SIZE_BYTES + if (obj.isPresent) Int.SIZE_BYTES else 0

    override fun encodeObject(buffer: ByteBuffer, obj: OptionalInt) {
        if (obj.isPresent) {
            buffer.put(1)
            buffer.putInt(obj.asInt)
        } else {
            buffer.put(0)
        }
    }
}

/** Encodes an [OptionalLong] like an optional LONG. */
object OptionalLongSchema : PrimitiveOptionalSchema<OptionalLong>(LongSchema) {
    override fun objSize(obj: OptionalLong): Int =
        Byte.SIZE_BYTES + if (obj.isPresent) Long.SIZE_BYTES else 0

    override fun encodeObject(buffer: ByteBuffer, obj: OptionalLong) {
        if (obj.isPresent) {
            buffer.put(1)
            buffer.putLong(obj.asLong)
        } else {
            buffer.put(0)
        }
    }
}

/** Encodes an [OptionalDouble] like an optional DOUBLE. */
object OptionalDoubleSchema : PrimitiveOptionalSchema<OptionalDouble>(DoubleSchema) {
    override fun objSize(obj: OptionalDouble): Int =
        Byte.SIZE_BYTES + if (obj.isPresent) Double.SIZE_BYTES else 0

    override fun encodeObject(buffer: ByteBuffer, obj: OptionalDouble) {
        if (obj.isPresent) {
            buffer.put(1)
            buffer.putDouble(obj.asDouble)
        } else {
            buffer.put(0)
        }
    }
}
