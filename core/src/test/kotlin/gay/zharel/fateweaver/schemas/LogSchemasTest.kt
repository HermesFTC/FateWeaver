package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertContains

class LogSchemasTest {

    // Test data classes and enums for schema factory testing
    enum class TestEnum { FIRST, SECOND, THIRD }

    data class SimpleStruct(
        @JvmField val intField: Int,
        @JvmField val stringField: String,
        @JvmField val booleanField: Boolean
    )

    data class ComplexStruct(
        @JvmField val doubleField: Double,
        @JvmField val enumField: TestEnum,
        @JvmField val arrayField: Array<Int>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ComplexStruct
            if (doubleField != other.doubleField) return false
            if (enumField != other.enumField) return false
            if (!arrayField.contentEquals(other.arrayField)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = doubleField.hashCode()
            result = 31 * result + enumField.hashCode()
            result = 31 * result + arrayField.contentHashCode()
            return result
        }
    }

    @Test
    fun testSchemaOfClass() {
        // Test primitive types
        assertEquals(IntSchema, FateSchema.schemaOfClass(Int::class.java))
        assertEquals(LongSchema, FateSchema.schemaOfClass(Long::class.java))
        assertEquals(DoubleSchema, FateSchema.schemaOfClass(Double::class.java))
        assertEquals(StringSchema, FateSchema.schemaOfClass(String::class.java))
        assertEquals(BooleanSchema, FateSchema.schemaOfClass(Boolean::class.java))

        // Test enum
        assertTrue(FateSchema.schemaOfClass(TestEnum::class.java) is EnumSchema<*>)

        // Test array
        assertTrue(FateSchema.schemaOfClass(Array<Int>::class.java) is ArraySchema<*>)

        // Test struct
        assertTrue(FateSchema.schemaOfClass(SimpleStruct::class.java) is ReflectedClassSchema<*>)
    }

    @Test
    fun testSchemaOfClassTypeCastSafety() {
        // Test that type casts in schemaOfClass don't fail with ClassCastException

        // Test primitive types and their boxed versions
        assertDoesNotThrow { FateSchema.schemaOfClass(Int::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(Integer::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(Long::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(java.lang.Long::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(Double::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(java.lang.Double::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(Boolean::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(java.lang.Boolean::class.java) }
        assertDoesNotThrow { FateSchema.schemaOfClass(String::class.java) }

        // Test enum type cast - this tests the @Suppress("UNCHECKED_CAST") cast
        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(TestEnum::class.java)
            assertTrue(schema is EnumSchema<*>)
            // Verify the enum schema was created correctly
            assertEquals(6, schema.tag)
        }

        // Test array type casts
        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(Array<Int>::class.java)
            assertTrue(schema is ArraySchema<*>)
            assertEquals(7, schema.tag)
        }

        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(Array<String>::class.java)
            assertTrue(schema is ArraySchema<*>)
        }

        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(Array<TestEnum>::class.java)
            assertTrue(schema is ArraySchema<*>)
        }

        // Test nested array type casts
        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(Array<Array<Int>>::class.java)
            assertTrue(schema is ArraySchema<*>)
        }

        // Test struct type cast - this tests the final `as EntrySchema<T>` cast
        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(SimpleStruct::class.java)
            assertTrue(schema is ReflectedClassSchema<*>)
            assertEquals(0, schema.tag)
        }

        assertDoesNotThrow {
            val schema = FateSchema.schemaOfClass(ComplexStruct::class.java)
            assertTrue(schema is ReflectedClassSchema<*>)
        }

        // Test that the returned schemas can actually be used without casting issues
        val intSchema = FateSchema.schemaOfClass(Int::class.java)
        assertDoesNotThrow { intSchema.objSize(42) }
        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(4)
            intSchema.encodeObject(buffer, 42)
        }

        val enumSchema = FateSchema.schemaOfClass(TestEnum::class.java)
        assertDoesNotThrow { enumSchema.objSize(TestEnum.FIRST) }
        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(4)
            enumSchema.encodeObject(buffer, TestEnum.FIRST)
        }

        val arraySchema = FateSchema.schemaOfClass(Array<String>::class.java)
        val testArray = arrayOf("test1", "test2")
        assertDoesNotThrow { arraySchema.objSize(testArray) }
        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(100)
            arraySchema.encodeObject(buffer, testArray)
        }

        val structSchema = FateSchema.schemaOfClass(SimpleStruct::class.java)
        val testStruct = SimpleStruct(1, "test", true)
        assertDoesNotThrow { structSchema.objSize(testStruct) }
        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(100)
            structSchema.encodeObject(buffer, testStruct)
        }
    }

    @Test
    fun testEncodingRoundTrip() {
        // Test that we can encode and measure sizes correctly
        val testCases = listOf(
            IntSchema to 42,
            LongSchema to 123456789L,
            DoubleSchema to 3.14159,
            StringSchema to "Hello, World!",
            BooleanSchema to true
        )

        for ((schema, obj) in testCases) {
            when (schema) {
                is IntSchema -> {
                    val objSize = schema.objSize(obj as Int)
                    val buffer = ByteBuffer.allocate(objSize)
                    schema.encodeObject(buffer, obj)
                    assertEquals(0, buffer.remaining(), "Object size mismatch for IntSchema")
                }
                is LongSchema -> {
                    val objSize = schema.objSize(obj as Long)
                    val buffer = ByteBuffer.allocate(objSize)
                    schema.encodeObject(buffer, obj)
                    assertEquals(0, buffer.remaining(), "Object size mismatch for LongSchema")
                }
                is DoubleSchema -> {
                    val objSize = schema.objSize(obj as Double)
                    val buffer = ByteBuffer.allocate(objSize)
                    schema.encodeObject(buffer, obj)
                    assertEquals(0, buffer.remaining(), "Object size mismatch for DoubleSchema")
                }
                is StringSchema -> {
                    val objSize = schema.objSize(obj as String)
                    val buffer = ByteBuffer.allocate(objSize)
                    schema.encodeObject(buffer, obj)
                    assertEquals(0, buffer.remaining(), "Object size mismatch for StringSchema")
                }
                is BooleanSchema -> {
                    val objSize = schema.objSize(obj as Boolean)
                    val buffer = ByteBuffer.allocate(objSize)
                    schema.encodeObject(buffer, obj)
                    assertEquals(0, buffer.remaining(), "Object size mismatch for BooleanSchema")
                }
                else -> {
                    fail("Unexpected schema type: ${schema::class.java.simpleName}")
                }
            }
        }

        // Test enum schema separately
        val enumSchema = EnumSchema(TestEnum::class.java)
        val enumObj = TestEnum.SECOND
        val enumObjSize = enumSchema.objSize(enumObj)
        val enumBuffer = ByteBuffer.allocate(enumObjSize)
        enumSchema.encodeObject(enumBuffer, enumObj)
        assertEquals(0, enumBuffer.remaining(), "Object size mismatch for EnumSchema")
    }

    @Test
    fun testSchemaRegistry() {
        val structSchema1 = FateSchema.schemaOfClass(SimpleStruct::class.java)
        val structSchema2 = FateSchema.schemaOfClass<SimpleStruct>()

        assertSame(structSchema1, structSchema2)

        val customSchema1 = CustomStructSchema<ComplexStruct>(
            "CustomStruct",
            listOf("double", "array"),
            listOf(DoubleSchema, ArraySchema(IntSchema))
        ) { s -> listOf(s.doubleField, s.arrayField) }

        FateSchema.registerSchema(customSchema1)
        assertContains(FateSchema.SCHEMA_REGISTRY.keys, ComplexStruct::class)

        val customSchema2 = FateSchema.schemaOfClass<ComplexStruct>()

        assertSame(customSchema1, customSchema2)
    }

    @Test
    fun testSchemaEncodeSchemaMethod() {
        // Test that the default encodeSchema method works correctly
        val testSchemas = listOf(
            IntSchema,
            StringSchema,
            BooleanSchema,
            ArraySchema(IntSchema),
            EnumSchema(TestEnum::class.java)
        )

        for (schema in testSchemas) {
            val buffer = ByteBuffer.allocate(schema.schemaSize)
            schema.encodeSchema(buffer)
            buffer.flip()

            // First int should be the schema tag
            assertEquals(schema.tag, buffer.int)
            // Remaining bytes should match schema content
            assertTrue(buffer.hasRemaining() || schema.schemaSize == 4,
                "Schema should have content or be minimal (tag only)")
        }
    }
}
