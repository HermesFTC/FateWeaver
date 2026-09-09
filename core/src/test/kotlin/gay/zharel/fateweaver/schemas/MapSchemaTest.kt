package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.BufferOverflowException
import java.nio.ByteBuffer

class MapSchemaTest {

    enum class Status { IDLE, RUNNING }

    data class Point(@JvmField val x: Int, @JvmField val y: Int)

    @Test
    fun testStringToIntMapSchema() {
        val schema = MapSchema(StringSchema, IntSchema)
        val map = linkedMapOf("a" to 1, "b" to 2)

        assertEquals(FateSchema.TypeRegistry.MAP.value, schema.tag)
        assertEquals(4 + StringSchema.schemaSize + IntSchema.schemaSize, schema.schemaSize)
        // count + per entry: (key length + 1 byte key) + 4 byte value
        assertEquals(4 + 2 * (4 + 1 + 4), schema.objSize(map))

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(map))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, map)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.MAP.value, buffer.int) // tag
        assertEquals(FateSchema.TypeRegistry.STRING.value, buffer.int) // key schema
        assertEquals(FateSchema.TypeRegistry.INT.value, buffer.int) // value schema
        assertEquals(2, buffer.int) // entry count

        for ((key, value) in map) {
            val keyBytes = ByteArray(buffer.int)
            buffer.get(keyBytes)
            assertEquals(key, String(keyBytes, Charsets.UTF_8))
            assertEquals(value, buffer.int)
        }
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testEmptyMap() {
        val schema = MapSchema(StringSchema, DoubleSchema)

        assertEquals(4, schema.objSize(emptyMap()))

        val buffer = ByteBuffer.allocate(schema.objSize(emptyMap()))
        schema.encodeObject(buffer, emptyMap())
        buffer.flip()

        assertEquals(0, buffer.int)
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testNestedMapSchema() {
        val schema = MapSchema(StringSchema, MapSchema(StringSchema, IntSchema))
        val map = mapOf("outer" to mapOf("inner" to 7))

        assertEquals(4 + 4 + (4 + 4 + 4), schema.schemaSize)

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(map))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, map)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.MAP.value, buffer.int)
        assertEquals(FateSchema.TypeRegistry.STRING.value, buffer.int)
        assertEquals(FateSchema.TypeRegistry.MAP.value, buffer.int)
        assertEquals(FateSchema.TypeRegistry.STRING.value, buffer.int)
        assertEquals(FateSchema.TypeRegistry.INT.value, buffer.int)
    }

    @Test
    fun testMapOfStructs() {
        val valueSchema = FateSchema.schemaOfClass(Point::class)
        val schema = MapSchema(StringSchema, valueSchema)
        val map = mapOf("origin" to Point(0, 0), "unit" to Point(1, 1))

        val buffer = ByteBuffer.allocate(schema.objSize(map))
        schema.encodeObject(buffer, map)

        // objSize and encodeObject must agree exactly.
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testEnumKeyedMap() {
        val keySchema = EnumSchema<Status>(Status::class.java)
        val schema = MapSchema(keySchema, DoubleSchema)
        // EnumSchema<T> is a FateSchema<Enum<T>>, and Map's key parameter is invariant.
        val map = mapOf<Enum<Status>, Double>(Status.IDLE to 0.0, Status.RUNNING to 1.0)

        assertEquals(4 + keySchema.schemaSize + DoubleSchema.schemaSize, schema.schemaSize)

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(map))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, map)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.MAP.value, buffer.int)
        // The full ENUM blob is inlined as the key schema: tag, constant count, then each name.
        assertEquals(FateSchema.TypeRegistry.ENUM.value, buffer.int)
        assertEquals(2, buffer.int)
        for (constant in Status.entries) {
            val nameBytes = ByteArray(buffer.int)
            buffer.get(nameBytes)
            assertEquals(constant.name, String(nameBytes, Charsets.UTF_8))
        }
        assertEquals(FateSchema.TypeRegistry.DOUBLE.value, buffer.int)
    }

    @Test
    fun testStructKeyedMap() {
        // Keys are genuinely arbitrary, not string-only.
        val schema = MapSchema(FateSchema.schemaOfClass(Point::class), IntSchema)
        val map = mapOf(Point(1, 2) to 3)

        val buffer = ByteBuffer.allocate(schema.objSize(map))
        schema.encodeObject(buffer, map)

        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testMultiByteUtf8Keys() {
        val schema = MapSchema(StringSchema, IntSchema)
        val map = linkedMapOf("日本" to 1, "🤖" to 2)

        val objSize = schema.objSize(map)
        val buffer = ByteBuffer.allocate(objSize)
        schema.encodeObject(buffer, map)

        assertEquals(0, buffer.remaining())
        // 4 count + per key (4 length + utf8 bytes) + 4 value
        assertEquals(4 + (4 + 6 + 4) + (4 + 4 + 4), objSize)
    }

    @Test
    fun testMutationBetweenSizeAndEncodeFailsLoudly() {
        val schema = MapSchema(StringSchema, IntSchema)
        val map = linkedMapOf("a" to 1)

        val staleSize = schema.objSize(map)
        map["b"] = 2

        val buffer = ByteBuffer.allocate(staleSize)
        assertThrows(BufferOverflowException::class.java) {
            schema.encodeObject(buffer, map)
        }
    }
}
