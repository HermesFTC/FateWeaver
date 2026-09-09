package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

/**
 * A schema for arrays of [T], written as a 4-byte element count followed by each element.
 *
 * `List`, `Set` and `Collection` fields use this same wire format, so a `List<Double>` field is
 * byte-identical to a `Double[]` field. Kotlin's primitive arrays have their own schemas
 * ([DoubleArraySchema] and friends), which produce the same bytes without boxing.
 *
 * ## Binary format
 *
 * The schema:
 * ```
 * [4 bytes: tag] [element schema blob]
 * ```
 *
 * An encoded array:
 * ```
 * [4 bytes: element count N] [element1] [element2] ... [elementN]
 * ```
 */
class ArraySchema<T>(val elementSchema: FateSchema<T>) : FateSchema<Array<T>> {
    override val tag: Int = FateSchema.TypeRegistry.ARRAY.value

    override val schemaSize: Int = Int.SIZE_BYTES + elementSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(elementSchema.schema)
        buffer.array()
    }

    override fun objSize(obj: Array<T>): Int = Int.SIZE_BYTES + obj.sumOf {
        elementSchema.objSize(it)
    }

    override fun encodeObject(buffer: ByteBuffer, obj: Array<T>) {
        buffer.putInt(obj.size)
        for (element in obj) {
            elementSchema.encodeObject(buffer, element)
        }
    }
}

/**
 * Base for the primitive-array schemas.
 *
 * [ArraySchema] cannot encode a `double[]`: it is a `FateSchema<Array<T>>`, so its methods erase to
 * taking `Object[]`, and a primitive array is not one. Boxing through `toTypedArray()` would work
 * but allocates a whole array on every [objSize] and every [encodeObject], which is twice per
 * logged value on a channel written each loop.
 *
 * These schemas write the ARRAY wire format directly instead, so the bytes are identical to the
 * boxed equivalent and readers need no changes. [elementSchema] describes an element for the schema
 * blob and for decoding; on the write side elements are encoded inline, without going through it.
 */
sealed class PrimitiveArraySchema<A : Any>(
    val elementSchema: FateSchema<*>,
    private val elementBytes: Int,
) : FateSchema<A> {
    override val tag: Int = FateSchema.TypeRegistry.ARRAY.value

    override val schemaSize: Int = Int.SIZE_BYTES + elementSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(elementSchema.schema)
        buffer.array()
    }

    /** Number of elements in [obj]. */
    protected abstract fun sizeOf(obj: A): Int

    final override fun objSize(obj: A): Int = Int.SIZE_BYTES + sizeOf(obj) * elementBytes
}

/** Encodes a [DoubleArray] as an array of DOUBLE. */
object DoubleArraySchema : PrimitiveArraySchema<DoubleArray>(DoubleSchema, Double.SIZE_BYTES) {
    override fun sizeOf(obj: DoubleArray): Int = obj.size

    override fun encodeObject(buffer: ByteBuffer, obj: DoubleArray) {
        buffer.putInt(obj.size)
        for (element in obj) buffer.putDouble(element)
    }
}

/** Encodes an [IntArray] as an array of INT. */
object IntArraySchema : PrimitiveArraySchema<IntArray>(IntSchema, Int.SIZE_BYTES) {
    override fun sizeOf(obj: IntArray): Int = obj.size

    override fun encodeObject(buffer: ByteBuffer, obj: IntArray) {
        buffer.putInt(obj.size)
        for (element in obj) buffer.putInt(element)
    }
}

/** Encodes a [LongArray] as an array of LONG. */
object LongArraySchema : PrimitiveArraySchema<LongArray>(LongSchema, Long.SIZE_BYTES) {
    override fun sizeOf(obj: LongArray): Int = obj.size

    override fun encodeObject(buffer: ByteBuffer, obj: LongArray) {
        buffer.putInt(obj.size)
        for (element in obj) buffer.putLong(element)
    }
}

/**
 * Encodes a [FloatArray] as an array of DOUBLE, widening each element.
 *
 * The format has no float type. Widening is exact, so nothing is lost, but the encoded array is
 * twice the size of the input.
 */
object FloatArraySchema : PrimitiveArraySchema<FloatArray>(DoubleSchema, Double.SIZE_BYTES) {
    override fun sizeOf(obj: FloatArray): Int = obj.size

    override fun encodeObject(buffer: ByteBuffer, obj: FloatArray) {
        buffer.putInt(obj.size)
        for (element in obj) buffer.putDouble(element.toDouble())
    }
}

/** Encodes a [BooleanArray] as an array of BOOLEAN, one byte per element. */
object BooleanArraySchema : PrimitiveArraySchema<BooleanArray>(BooleanSchema, Byte.SIZE_BYTES) {
    override fun sizeOf(obj: BooleanArray): Int = obj.size

    override fun encodeObject(buffer: ByteBuffer, obj: BooleanArray) {
        buffer.putInt(obj.size)
        for (element in obj) buffer.put(if (element) 1.toByte() else 0.toByte())
    }
}
