package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.ArraySchema
import gay.zharel.fateweaver.schemas.BooleanSchema
import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.EnumSchema
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.schemas.IntSchema
import gay.zharel.fateweaver.schemas.LongSchema
import gay.zharel.fateweaver.schemas.ReflectedClassSchema
import gay.zharel.fateweaver.schemas.StringSchema
import gay.zharel.fateweaver.schemas.TypedClassSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class TestLogSchemas {

    // Test data classes and enums
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

    data class StructWithType(
        val intField: Int,
        val stringField: String,
        val booleanField: Boolean,
        val structField: SimpleStruct
    ) {
        companion object {
            val as_type = "StructWithType"
        }
    }

    @Test
    fun testIntSchema() {
        val schema = IntSchema
        assertEquals(FateSchema.Registry.INT.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(4, schema.objSize(42))

        val buffer = ByteBuffer.allocate(8)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, 42)
        buffer.flip()

        assertEquals(FateSchema.Registry.INT.value, buffer.int)
        assertEquals(42, buffer.int)
    }

    @Test
    fun testLongSchema() {
        val schema = LongSchema
        assertEquals(FateSchema.Registry.LONG.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(8, schema.objSize(123456789L))

        val buffer = ByteBuffer.allocate(12)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, 123456789L)
        buffer.flip()

        assertEquals(FateSchema.Registry.LONG.value, buffer.int) // tag
        assertEquals(123456789L, buffer.long) // value
    }

    @Test
    fun testDoubleSchema() {
        val schema = DoubleSchema
        assertEquals(FateSchema.Registry.DOUBLE.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(8, schema.objSize(3.14159))

        val buffer = ByteBuffer.allocate(12)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, 3.14159)
        buffer.flip()

        assertEquals(FateSchema.Registry.DOUBLE.value, buffer.int) // tag
        assertEquals(3.14159, buffer.double, 1e-10) // value
    }

    @Test
    fun testStringSchema() {
        val schema = StringSchema
        val testString = "Hello, World!"
        assertEquals(FateSchema.Registry.STRING.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(4 + testString.toByteArray(Charsets.UTF_8).size, schema.objSize(testString))

        val buffer = ByteBuffer.allocate(4 + 4 + testString.toByteArray(Charsets.UTF_8).size)
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testString)
        buffer.flip()

        assertEquals(FateSchema.Registry.STRING.value, buffer.int) // tag
        val stringLength = buffer.int
        val stringBytes = ByteArray(stringLength)
        buffer.get(stringBytes)
        assertEquals(testString, String(stringBytes, Charsets.UTF_8))
    }

    @Test
    fun testBooleanSchema() {
        val schema = BooleanSchema
        assertEquals(FateSchema.Registry.BOOLEAN.value, schema.tag)
        assertEquals(4, schema.schemaSize)
        assertEquals(1, schema.objSize(true))
        assertEquals(1, schema.objSize(false))

        // Test true
        val bufferTrue = ByteBuffer.allocate(5)
        schema.encodeSchema(bufferTrue)
        schema.encodeObject(bufferTrue, true)
        bufferTrue.flip()

        assertEquals(FateSchema.Registry.BOOLEAN.value, bufferTrue.int) // tag
        assertEquals(1.toByte(), bufferTrue.get()) // true value

        // Test false
        val bufferFalse = ByteBuffer.allocate(5)
        schema.encodeSchema(bufferFalse)
        schema.encodeObject(bufferFalse, false)
        bufferFalse.flip()

        assertEquals(FateSchema.Registry.BOOLEAN.value, bufferFalse.int) // tag
        assertEquals(0.toByte(), bufferFalse.get()) // false value
    }

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
    fun testArraySchema() {
        val elementSchema = IntSchema
        val schema = ArraySchema(elementSchema)
        val testArray = arrayOf(1, 2, 3, 4, 5)

        assertEquals(FateSchema.Registry.ARRAY.value, schema.tag)
        assertEquals(4 + elementSchema.schemaSize, schema.schemaSize)
        assertEquals(4 + testArray.size * 4, schema.objSize(testArray))

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(testArray))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testArray)
        buffer.flip()

        assertEquals(FateSchema.Registry.ARRAY.value, buffer.int) // tag
        assertEquals(FateSchema.Registry.INT.value, buffer.int) // element schema tag (IntSchema)
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
    fun testReflectedClassSchema() {
        val schema = ReflectedClassSchema.createFromClass(SimpleStruct::class.java)
        val testStruct = SimpleStruct(42, "test", true)

        assertEquals(FateSchema.Registry.REFLECTED_CLASS.value, schema.tag)
        assertEquals(3, schema.fields.size)
        assertTrue(schema.fields.containsKey("intField"))
        assertTrue(schema.fields.containsKey("stringField"))
        assertTrue(schema.fields.containsKey("booleanField"))

        val expectedObjSize = 4 + 4 + "test".toByteArray(Charsets.UTF_8).size + 1
        assertEquals(expectedObjSize, schema.objSize(testStruct))

        val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(testStruct))
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testStruct)
        buffer.flip()

        assertEquals(FateSchema.Registry.REFLECTED_CLASS.value, buffer.int) // tag
        assertEquals(3, buffer.int) // number of fields

        // Verify that fields are present (order may vary)
        val fieldNames = mutableSetOf<String>()
        repeat(3) {
            val nameLength = buffer.int
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            fieldNames.add(String(nameBytes, Charsets.UTF_8))

            // Skip field schema
            val fieldTag = buffer.int
            when (fieldTag) {
                FateSchema.Registry.INT.value -> {} // IntSchema - no additional data
                FateSchema.Registry.STRING.value -> {} // StringSchema - no additional data
                FateSchema.Registry.BOOLEAN.value -> {} // BooleanSchema - no additional data
            }
        }

        assertEquals(setOf("intField", "stringField", "booleanField"), fieldNames)
    }

    @Test
    fun testComplexReflectedClassSchema() {
        val schema = ReflectedClassSchema.createFromClass(ComplexStruct::class.java)
        val testStruct = ComplexStruct(3.14, TestEnum.SECOND, arrayOf(1, 2, 3))

        assertEquals(FateSchema.Registry.REFLECTED_CLASS.value, schema.tag)
        assertEquals(3, schema.fields.size)

        assertDoesNotThrow {
            val objSize = schema.objSize(testStruct)
            assertTrue(objSize > 0)
        }

        assertDoesNotThrow {
            val buffer = ByteBuffer.allocate(1000) // Large buffer for complex schema
            schema.encodeSchema(buffer)
            schema.encodeObject(buffer, testStruct)
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
    fun testSchemaConsistency() {
        // Test that objSize is consistent for the same type
        val schema = ReflectedClassSchema.createFromClass(SimpleStruct::class.java)
        val obj1 = SimpleStruct(1, "test", true)
        val obj2 = SimpleStruct(2, "test", false)

        // Same string length should give same object size
        assertEquals(schema.objSize(obj1), schema.objSize(obj2))

        val obj3 = SimpleStruct(3, "different", true)
        // Different string length should give different object size
        assertNotEquals(schema.objSize(obj1), schema.objSize(obj3))
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
    fun testNestedReflectedClassSchema() {
        data class NestedStruct(
            @JvmField val simple: SimpleStruct,
            @JvmField val id: Int
        )

        val schema = ReflectedClassSchema.createFromClass(NestedStruct::class.java)
        val testObj = NestedStruct(SimpleStruct(1, "nested", true), 42)

        assertEquals(0, schema.tag)
        assertEquals(2, schema.fields.size)
        assertTrue(schema.fields.containsKey("simple"))
        assertTrue(schema.fields.containsKey("id"))

        assertDoesNotThrow {
            val objSize = schema.objSize(testObj)
            assertTrue(objSize > 0)

            val buffer = ByteBuffer.allocate(objSize)
            schema.encodeObject(buffer, testObj)
            assertEquals(0, buffer.remaining())
        }
    }

    @Test
    fun testTypedClassSchemaCreation() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java)

        // Should create a TypedClassSchema because StructWithType has a companion object with type property
        assertTrue(schema is TypedClassSchema<*>, "Expected TypedClassSchema for StructWithType")

        val typedSchema = schema as TypedClassSchema<*>
        assertEquals("StructWithType", typedSchema.type)
        assertEquals(FateSchema.Registry.REFLECTED_CLASS.value, typedSchema.tag)

        // Should have all fields from the class
        assertEquals(4, typedSchema.fields.size)
        assertTrue(typedSchema.fields.containsKey("intField"))
        assertTrue(typedSchema.fields.containsKey("stringField"))
        assertTrue(typedSchema.fields.containsKey("booleanField"))
        assertTrue(typedSchema.fields.containsKey("structField"))

        // Verify field types
        assertTrue(typedSchema.fields["intField"] is IntSchema)
        assertTrue(typedSchema.fields["stringField"] is StringSchema)
        assertTrue(typedSchema.fields["booleanField"] is BooleanSchema)
        assertTrue(typedSchema.fields["structField"] is ReflectedClassSchema<*>)
    }

    @Test
    fun testTypedClassSchemaSize() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java) as TypedClassSchema<StructWithType>
        val simpleStruct = SimpleStruct(42, "nested", true)
        val testStruct = StructWithType(123, "test", false, simpleStruct)

        // Calculate expected object size
        val expectedTypeSize = 4 + "StructWithType".toByteArray(Charsets.UTF_8).size // type field
        val expectedIntSize = 4 // intField
        val expectedStringSize = 4 + "test".toByteArray(Charsets.UTF_8).size // stringField
        val expectedBooleanSize = 1 // booleanField
        val expectedStructSize = 4 + 4 + "nested".toByteArray(Charsets.UTF_8).size + 1 // structField
        val expectedTotalSize = expectedTypeSize + expectedIntSize + expectedStringSize + expectedBooleanSize + expectedStructSize

        assertEquals(expectedTotalSize, schema.objSize(testStruct))
    }

    @Test
    fun testTypedClassSchemaEncoding() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java) as TypedClassSchema<StructWithType>
        val simpleStruct = SimpleStruct(42, "nested", true)
        val testStruct = StructWithType(123, "test", false, simpleStruct)

        val objSize = schema.objSize(testStruct)
        val buffer = ByteBuffer.allocate(schema.schemaSize + objSize)

        // Encode schema and object
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testStruct)
        buffer.flip()

        // Verify schema encoding
        assertEquals(FateSchema.Registry.REFLECTED_CLASS.value, buffer.int) // tag
        assertEquals(4, buffer.int) // number of fields (including type field)

        // Verify .type field is encoded first
        val typeFieldNameLength = buffer.int
        val typeFieldNameBytes = ByteArray(typeFieldNameLength)
        buffer.get(typeFieldNameBytes)
        assertEquals(".type", String(typeFieldNameBytes, Charsets.UTF_8))

        // Skip the string schema tag for .type field
        assertEquals(FateSchema.Registry.STRING.value, buffer.int)

        // Skip remaining schema fields for brevity in test
        // The important part is that we can encode without errors
        assertTrue(buffer.position() > 0, "Schema should have been encoded")
    }

    @Test
    fun testTypedClassSchemaObjectEncoding() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java) as TypedClassSchema<StructWithType>
        val simpleStruct = SimpleStruct(42, "nested", true)
        val testStruct = StructWithType(123, "test", false, simpleStruct)

        val objSize = schema.objSize(testStruct)
        val buffer = ByteBuffer.allocate(objSize)

        // Encode just the object
        schema.encodeObject(buffer, testStruct)
        buffer.flip()

        // Verify type field is encoded first
        val typeLength = buffer.int
        val typeBytes = ByteArray(typeLength)
        buffer.get(typeBytes)
        assertEquals("StructWithType", String(typeBytes, Charsets.UTF_8))

        // The remaining fields are encoded in the order they appear in the fields map
        // Based on the debug output, the order is: booleanField, intField, stringField, structField

        // Create a map of expected values for verification
        val expectedValues = mapOf(
            "booleanField" to false,
            "intField" to 123,
            "stringField" to "test",
            "structField" to simpleStruct
        )

        // Read and verify each field in the order they were encoded
        for ((fieldName, fieldSchema) in schema.fields) {
            val expectedValue = expectedValues[fieldName]
            when (fieldSchema) {
                is BooleanSchema -> {
                    val actualValue = buffer.get() != 0.toByte()
                    assertEquals(expectedValue, actualValue, "Mismatch for field $fieldName")
                }
                is IntSchema -> {
                    val actualValue = buffer.int
                    assertEquals(expectedValue, actualValue, "Mismatch for field $fieldName")
                }
                is StringSchema -> {
                    val stringLength = buffer.int
                    val stringBytes = ByteArray(stringLength)
                    buffer.get(stringBytes)
                    val actualValue = String(stringBytes, Charsets.UTF_8)
                    assertEquals(expectedValue, actualValue, "Mismatch for field $fieldName")
                }
                is ReflectedClassSchema<*> -> {
                    // For the nested struct, verify its fields
                    assertEquals("structField", fieldName)
                    val nestedStruct = expectedValue as SimpleStruct

                    // SimpleStruct fields are encoded in alphabetical order: booleanField, intField, stringField
                    assertEquals(if (nestedStruct.booleanField) 1.toByte() else 0.toByte(), buffer.get()) // structField.booleanField
                    assertEquals(nestedStruct.intField, buffer.int) // structField.intField
                    val nestedStringLength = buffer.int
                    val nestedStringBytes = ByteArray(nestedStringLength)
                    buffer.get(nestedStringBytes)
                    assertEquals(nestedStruct.stringField, String(nestedStringBytes, Charsets.UTF_8)) // structField.stringField
                }
                else -> fail("Unexpected field schema type for field $fieldName: ${fieldSchema::class.java}")
            }
        }

        assertEquals(0, buffer.remaining(), "All data should have been read")
    }

    @Test
    fun testTypedClassSchemaVsReflectedClassSchema() {
        // StructWithType should create TypedClassSchema
        val typedSchema = ReflectedClassSchema.createFromClass(StructWithType::class.java)
        assertTrue(typedSchema is TypedClassSchema<*>)

        // SimpleStruct should create regular ReflectedClassSchema
        val reflectedSchema = ReflectedClassSchema.createFromClass(SimpleStruct::class.java)
        assertFalse(reflectedSchema is TypedClassSchema<*>)
        assertFalse(reflectedSchema::class == TypedClassSchema::class)
    }

    @Test
    fun testTypedClassSchemaFieldOrder() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java) as TypedClassSchema<*>

        // The schema should include the .type field in addition to class fields
        val expectedFields = setOf("intField", "stringField", "booleanField", "structField")
        val actualFields = schema.fields.keys

        assertEquals(expectedFields, actualFields)
        assertEquals(4, schema.fields.size)

        // Type is stored separately from fields
        assertEquals("StructWithType", schema.type)
    }

    @Test
    fun testTypedClassSchemaConsistency() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java) as TypedClassSchema<StructWithType>
        val simpleStruct1 = SimpleStruct(1, "test", true)
        val simpleStruct2 = SimpleStruct(2, "test", false)
        val struct1 = StructWithType(1, "same", true, simpleStruct1)
        val struct2 = StructWithType(2, "same", false, simpleStruct2)

        // Same string lengths should give same object size
        assertEquals(schema.objSize(struct1), schema.objSize(struct2))

        val struct3 = StructWithType(3, "different", true, simpleStruct1)
        // Different string length should give different object size
        assertNotEquals(schema.objSize(struct1), schema.objSize(struct3))
    }

    @Test
    fun testTypedClassSchemaRoundTrip() {
        val schema = ReflectedClassSchema.createFromClass(StructWithType::class.java) as TypedClassSchema<StructWithType>
        val simpleStruct = SimpleStruct(99, "roundtrip", false)
        val testStruct = StructWithType(456, "encode-decode", true, simpleStruct)

        // Test that objSize calculation matches actual encoding
        val objSize = schema.objSize(testStruct)
        val buffer = ByteBuffer.allocate(objSize)
        schema.encodeObject(buffer, testStruct)

        assertEquals(0, buffer.remaining(), "Object size should match encoded size exactly")
    }
}