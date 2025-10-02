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

    @Test
    fun testArraySchemaCast() {
        val schema = ArraySchema(IntSchema)

        // Test various array types
        val intArray = intArrayOf(1, 2, 3)
        val longArray = longArrayOf(1L, 2L, 3L)
        val doubleArray = doubleArrayOf(1.0, 2.0, 3.0)
        val booleanArray = booleanArrayOf(true, false, true)
        val objectArray = arrayOf(1, 2, 3)

        assertDoesNotThrow { schema.cast(intArray) }
        assertDoesNotThrow { schema.cast(longArray) }
        assertDoesNotThrow { schema.cast(doubleArray) }
        assertDoesNotThrow { schema.cast(booleanArray) }
        assertDoesNotThrow { schema.cast(objectArray) }

        assertThrows(IllegalArgumentException::class.java) {
            schema.cast("not an array")
        }
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
