package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ArraySchemaTest {

    @Test
    fun testArraySchema() {
        val elementSchema = IntSchema
        val schema = ArraySchema(elementSchema)
        val testArray = arrayOf(1, 2, 3, 4, 5)

        assertEquals(FateSchema.TypeRegistry.ARRAY.value, schema.tag)
        assertEquals(4 + elementSchema.schemaSize, schema.schemaSize)
        assertEquals(4 + testArray.size * 4, schema.objSize(testArray))

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(testArray))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testArray)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.ARRAY.value, buffer.int) // tag
        assertEquals(FateSchema.TypeRegistry.INT.value, buffer.int) // element schema tag (IntSchema)
        assertEquals(5, buffer.int) // array length
        for (i in testArray.indices) {
            assertEquals(testArray[i], buffer.int)
        }
    }

    /**
     * A primitive array and its boxed equivalent must encode to the same bytes, since the reader
     * decodes both through the same ARRAY tag.
     */
    @Test
    fun testPrimitiveArraysMatchBoxedEncoding() {
        fun bytes(schema: FateSchema<Any>, value: Any): ByteArray {
            val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(value))
            schema.encodeSchema(buffer)
            schema.encodeObject(buffer, value)
            return buffer.array()
        }

        @Suppress("UNCHECKED_CAST")
        fun <P : Any, B> check(primitive: FateSchema<P>, value: P, boxed: ArraySchema<B>, box: Array<B>) {
            assertArrayEquals(
                bytes(boxed as FateSchema<Any>, box),
                bytes(primitive as FateSchema<Any>, value),
                "${value::class.simpleName} must encode like its boxed form",
            )
        }

        check(DoubleArraySchema, doubleArrayOf(1.0, -2.5), ArraySchema(DoubleSchema), arrayOf(1.0, -2.5))
        check(IntArraySchema, intArrayOf(1, -2, 3), ArraySchema(IntSchema), arrayOf(1, -2, 3))
        check(LongArraySchema, longArrayOf(1L, Long.MIN_VALUE), ArraySchema(LongSchema), arrayOf(1L, Long.MIN_VALUE))
        check(BooleanArraySchema, booleanArrayOf(true, false), ArraySchema(BooleanSchema), arrayOf(true, false))
    }

    @Test
    fun testPrimitiveArraySizesAreExact() {
        // The writer requires objSize to match encodeObject exactly, so an off-by-one here would
        // surface as a buffer over/underflow rather than bad data.
        assertEquals(4 + 3 * 8, DoubleArraySchema.objSize(doubleArrayOf(1.0, 2.0, 3.0)))
        assertEquals(4 + 3 * 4, IntArraySchema.objSize(intArrayOf(1, 2, 3)))
        assertEquals(4 + 3 * 8, LongArraySchema.objSize(longArrayOf(1, 2, 3)))
        assertEquals(4 + 3 * 1, BooleanArraySchema.objSize(booleanArrayOf(true, false, true)))

        assertEquals(4, DoubleArraySchema.objSize(doubleArrayOf()))
    }

    @Test
    fun testPrimitiveArraysAreInferred() {
        assertSame(DoubleArraySchema, FateSchema.schemaOfClass(DoubleArray::class))
        assertSame(IntArraySchema, FateSchema.schemaOfClass(IntArray::class))
        assertSame(LongArraySchema, FateSchema.schemaOfClass(LongArray::class))
        assertSame(BooleanArraySchema, FateSchema.schemaOfClass(BooleanArray::class))
        assertSame(FloatArraySchema, FateSchema.schemaOfClass(FloatArray::class))
    }

    @Test
    fun testFloatArrayWidensToDouble() {
        // No FLOAT tag exists, so a FloatArray encodes exactly like the DoubleArray it widens to.
        val floats = floatArrayOf(1.5f, -2.25f)
        val doubles = doubleArrayOf(1.5, -2.25)

        assertEquals(DoubleArraySchema.objSize(doubles), FloatArraySchema.objSize(floats))

        fun bytes(schema: FateSchema<Any>, value: Any): ByteArray {
            val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(value))
            schema.encodeSchema(buffer)
            schema.encodeObject(buffer, value)
            return buffer.array()
        }

        @Suppress("UNCHECKED_CAST")
        assertArrayEquals(
            bytes(DoubleArraySchema as FateSchema<Any>, doubles),
            bytes(FloatArraySchema as FateSchema<Any>, floats),
        )
    }

    @Test
    fun testEmptyArraySchema() {
        val schema = ArraySchema(StringSchema)
        val emptyArray = arrayOf<String>()

        assertEquals(4, schema.objSize(emptyArray)) // Just the size field

        val buffer = ByteBuffer.allocate(schema.objSize(emptyArray))
        schema.encodeObject(buffer, emptyArray)
        buffer.flip()

        assertEquals(0, buffer.int) // array size
    }

    @Test
    fun testArraySchemaWithDifferentElementTypes() {
        // Test with String elements
        val stringArraySchema = ArraySchema(StringSchema)
        val testArray = arrayOf("test1", "test2")

        assertDoesNotThrow { stringArraySchema.objSize(testArray) }
        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(100)
            stringArraySchema.encodeObject(buffer, testArray)
        }

        // Test with nested arrays
        val nestedArraySchema = ArraySchema(ArraySchema(IntSchema))
        val nestedTestArray = arrayOf(arrayOf(1, 2), arrayOf(3, 4))

        assertDoesNotThrow { nestedArraySchema.objSize(nestedTestArray) }
        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(100)
            nestedArraySchema.encodeObject(buffer, nestedTestArray)
        }
    }
}
