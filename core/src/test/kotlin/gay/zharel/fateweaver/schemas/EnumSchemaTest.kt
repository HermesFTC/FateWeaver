package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class EnumSchemaTest {

    // Test enum for testing
    enum class TestEnum { FIRST, SECOND, THIRD }

    @Test
    fun testEnumSchema() {
        val schema = EnumSchema(TestEnum::class.java)
        assertEquals(FateSchema.Registry.ENUM.value, schema.tag)
        assertEquals(4, schema.objSize(TestEnum.FIRST))
        assertEquals(4, schema.objSize(TestEnum.SECOND))

        val expectedSchemaSize = 4 + 4 + TestEnum.entries.sumOf {
            4 + it.name.toByteArray(Charsets.UTF_8).size
        }
        assertEquals(expectedSchemaSize, schema.schemaSize)

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(TestEnum.SECOND))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, TestEnum.SECOND)
        buffer.flip()

        assertEquals(FateSchema.Registry.ENUM.value, buffer.int) // tag
        assertEquals(3, buffer.int) // number of enum constants

        // Skip reading the enum constant names for brevity
        for (enumValue in TestEnum.entries) {
            val nameLength = buffer.int
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            assertEquals(enumValue.name, String(nameBytes, Charsets.UTF_8))
        }

        assertEquals(TestEnum.SECOND.ordinal, buffer.int) // encoded enum value
    }

    @Test
    fun testEnumSchemaTypeCast() {
        // Test that enum schema creation works correctly with type casting
        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(TestEnum::class.java)
            assertTrue(schema is EnumSchema<*>)
            // Verify the enum schema was created correctly
            assertEquals(6, schema.tag)
        }
    }

    enum class SingleValueEnum { ONLY }
    enum class LargeEnum { A, B, C, D, E, F, G, H, I, J }

    @Test
    fun testEnumSchemaWithDifferentEnums() {

        // Test single value enum
        val singleSchema = EnumSchema(SingleValueEnum::class.java)
        assertEquals(4, singleSchema.objSize(SingleValueEnum.ONLY))

        // Test larger enum
        val largeSchema = EnumSchema(LargeEnum::class.java)
        assertEquals(4, largeSchema.objSize(LargeEnum.A))
        assertEquals(4, largeSchema.objSize(LargeEnum.J))

        // Schema sizes should be different due to different number of constants
        assertNotEquals(singleSchema.schemaSize, largeSchema.schemaSize)
    }

    @Test
    fun testEnumSchemaRoundTrip() {
        val schema = EnumSchema(TestEnum::class.java)
        val enumObj = TestEnum.SECOND
        val enumObjSize = schema.objSize(enumObj)
        val enumBuffer = ByteBuffer.allocate(enumObjSize)
        schema.encodeObject(enumBuffer, enumObj)
        assertEquals(0, enumBuffer.remaining(), "Object size should match encoded size exactly")
    }
}
