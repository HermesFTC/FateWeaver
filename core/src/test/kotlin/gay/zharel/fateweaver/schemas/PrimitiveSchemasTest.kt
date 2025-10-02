package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class PrimitiveSchemasTest {

    @Test
    fun testIntSchema() {
        val schema = IntSchema
        assertEquals(FateSchema.TypeRegistry.INT.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(4, schema.objSize(42))

        val buffer = ByteBuffer.allocate(8)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, 42)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.INT.value, buffer.int)
        assertEquals(42, buffer.int)
    }

    @Test
    fun testLongSchema() {
        val schema = LongSchema
        assertEquals(FateSchema.TypeRegistry.LONG.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(8, schema.objSize(123456789L))

        val buffer = ByteBuffer.allocate(12)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, 123456789L)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.LONG.value, buffer.int) // tag
        assertEquals(123456789L, buffer.long) // value
    }

    @Test
    fun testDoubleSchema() {
        val schema = DoubleSchema
        assertEquals(FateSchema.TypeRegistry.DOUBLE.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(8, schema.objSize(3.14159))

        val buffer = ByteBuffer.allocate(12)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, 3.14159)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.DOUBLE.value, buffer.int) // tag
        assertEquals(3.14159, buffer.double, 1e-10) // value
    }

    @Test
    fun testStringSchema() {
        val schema = StringSchema
        val testString = "Hello, World!"
        assertEquals(FateSchema.TypeRegistry.STRING.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(4 + testString.toByteArray(Charsets.UTF_8).size, schema.objSize(testString))

        val buffer = ByteBuffer.allocate(4 + 4 + testString.toByteArray(Charsets.UTF_8).size)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testString)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.STRING.value, buffer.int) // tag
        val stringLength = buffer.int
        val stringBytes = ByteArray(stringLength)
        buffer.get(stringBytes)
        assertEquals(testString, String(stringBytes, Charsets.UTF_8))
    }

    @Test
    fun testBooleanSchema() {
        val schema = BooleanSchema
        assertEquals(FateSchema.TypeRegistry.BOOLEAN.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(1, schema.objSize(true))
        assertEquals(1, schema.objSize(false))

        // Test true
        val bufferTrue = ByteBuffer.allocate(5)
        schema.encodeSchema(bufferTrue)
        schema.encodeObject(bufferTrue, true)
        bufferTrue.flip()

        assertEquals(FateSchema.TypeRegistry.BOOLEAN.value, bufferTrue.int) // tag
        assertEquals(1.toByte(), bufferTrue.get()) // true value

        // Test false
        val bufferFalse = ByteBuffer.allocate(5)
        schema.encodeSchema(bufferFalse)
        schema.encodeObject(bufferFalse, false)
        bufferFalse.flip()

        assertEquals(FateSchema.TypeRegistry.BOOLEAN.value, bufferFalse.int) // tag
        assertEquals(0.toByte(), bufferFalse.get()) // false value
    }

    @Test
    fun testUtf8StringEncoding() {
        val schema = StringSchema
        val utf8String = "Hello 🌍 UTF-8!"
        val expectedSize = 4 + utf8String.toByteArray(Charsets.UTF_8).size

        assertEquals(expectedSize, schema.objSize(utf8String))

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(utf8String))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, utf8String)
        buffer.flip()

        assertEquals(4, buffer.int) // tag
        val stringLength = buffer.int
        val stringBytes = ByteArray(stringLength)
        buffer.get(stringBytes)
        assertEquals(utf8String, String(stringBytes, Charsets.UTF_8))
    }
}
