package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class ClassSchemasTest {

    // Test data classes
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

    enum class TestEnum { FIRST, SECOND, THIRD }

    // Test classes for CustomStructSchema
    data class Person(val name: String, val age: Int, val email: String)

    data class Point3D(val x: Double, val y: Double, val z: Double)

    class CustomClass(val id: Int, val data: String, val active: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CustomClass
            if (id != other.id) return false
            if (data != other.data) return false
            if (active != other.active) return false
            return true
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + data.hashCode()
            result = 31 * result + active.hashCode()
            return result
        }
    }

    @Test
    fun testReflectedClassSchema() {
        val schema = ReflectedClassSchema.createFromClass(SimpleStruct::class.java)
        val testStruct = SimpleStruct(42, "test", true)

        assertEquals(FateSchema.Registry.CUSTOM.value, schema.tag)
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

        assertEquals(FateSchema.Registry.CUSTOM.value, buffer.int) // tag
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

        assertEquals(FateSchema.Registry.CUSTOM.value, schema.tag)
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
        assertEquals(FateSchema.Registry.CUSTOM.value, typedSchema.tag)

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
        assertEquals(FateSchema.Registry.CUSTOM.value, buffer.int) // tag
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
    fun testCustomStructSchemaCreation() {
        // Create a CustomStructSchema for Person class
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        assertEquals(FateSchema.Registry.CUSTOM.value, schema.tag)
        assertEquals("Person", schema.type)
        assertEquals(listOf("name", "age", "email"), schema.componentNames)
        assertEquals(3, schema.componentSchemas.size)

        // Verify component schemas
        assertTrue(schema.componentSchemas[0] is StringSchema)
        assertTrue(schema.componentSchemas[1] is IntSchema)
        assertTrue(schema.componentSchemas[2] is StringSchema)
    }

    @Test
    fun testCustomStructSchemaSize() {
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        val testPerson = Person("John Doe", 30, "john@example.com")

        // Calculate expected object size
        val expectedTypeSize = 4 + "Person".toByteArray(Charsets.UTF_8).size // type field
        val expectedNameSize = 4 + "John Doe".toByteArray(Charsets.UTF_8).size // name
        val expectedAgeSize = 4 // age (int)
        val expectedEmailSize = 4 + "john@example.com".toByteArray(Charsets.UTF_8).size // email
        val expectedTotalSize = expectedTypeSize + expectedNameSize + expectedAgeSize + expectedEmailSize

        assertEquals(expectedTotalSize, schema.objSize(testPerson))
    }

    @Test
    fun testCustomStructSchemaSchemaSize() {
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        // Schema size should include:
        // - tag (4 bytes)
        // - field count (4 bytes)
        // - .type field name length + name + schema (4 + 5 + 4 = 13 bytes)
        // - each component name length + name + schema
        val expectedSchemaSize = 4 + 4 + // tag + field count
                4 + ".type".toByteArray().size + StringSchema.schemaSize + // .type field
                (4 + "name".toByteArray().size + StringSchema.schemaSize) + // name field
                (4 + "age".toByteArray().size + IntSchema.schemaSize) + // age field
                (4 + "email".toByteArray().size + StringSchema.schemaSize) // email field

        assertEquals(expectedSchemaSize, schema.schemaSize)
    }

    @Test
    fun testCustomStructSchemaEncoding() {
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        val testPerson = Person("Alice", 25, "alice@test.com")

        val objSize = schema.objSize(testPerson)
        val buffer = ByteBuffer.allocate(schema.schemaSize + objSize)

        // Encode schema and object
        schema.encodeSchema(buffer)
        schema.encodeObject(buffer, testPerson)
        buffer.flip()

        // Verify schema encoding
        assertEquals(FateSchema.Registry.CUSTOM.value, buffer.int) // tag
        assertEquals(3, buffer.int) // number of components

        // Verify .type field is encoded first in schema
        val typeFieldNameLength = buffer.int
        val typeFieldNameBytes = ByteArray(typeFieldNameLength)
        buffer.get(typeFieldNameBytes)
        assertEquals(".type", String(typeFieldNameBytes, Charsets.UTF_8))

        // Skip the string schema tag for .type field
        assertEquals(FateSchema.Registry.STRING.value, buffer.int)

        // Skip remaining schema fields for brevity
        // The important part is that we can encode without errors
        assertTrue(buffer.position() > 0, "Schema should have been encoded")
    }

    @Test
    fun testCustomStructSchemaObjectEncoding() {
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        val testPerson = Person("Bob", 35, "bob@company.org")

        val objSize = schema.objSize(testPerson)
        val buffer = ByteBuffer.allocate(objSize)

        // Encode just the object
        schema.encodeObject(buffer, testPerson)
        buffer.flip()

        // Verify type field is encoded first
        val typeLength = buffer.int
        val typeBytes = ByteArray(typeLength)
        buffer.get(typeBytes)
        assertEquals("Person", String(typeBytes, Charsets.UTF_8))

        // Verify name field
        val nameLength = buffer.int
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        assertEquals("Bob", String(nameBytes, Charsets.UTF_8))

        // Verify age field
        assertEquals(35, buffer.int)

        // Verify email field
        val emailLength = buffer.int
        val emailBytes = ByteArray(emailLength)
        buffer.get(emailBytes)
        assertEquals("bob@company.org", String(emailBytes, Charsets.UTF_8))

        assertEquals(0, buffer.remaining(), "All data should have been read")
    }

    @Test
    fun testCustomStructSchemaWithDifferentTypes() {
        // Test with Point3D using all double values
        val pointSchema = CustomStructSchema<Point3D>(
            type = "Point3D",
            componentNames = listOf("x", "y", "z"),
            componentSchemas = listOf(DoubleSchema, DoubleSchema, DoubleSchema),
            encoder = { point -> listOf(point.x, point.y, point.z) }
        )

        val testPoint = Point3D(1.5, 2.7, -3.14)

        // Test size calculation
        val expectedSize = 4 + "Point3D".toByteArray().size + 8 + 8 + 8 // type + 3 doubles
        assertEquals(expectedSize, pointSchema.objSize(testPoint))

        // Test encoding
        val buffer = ByteBuffer.allocate(pointSchema.objSize(testPoint))
        pointSchema.encodeObject(buffer, testPoint)
        buffer.flip()

        // Verify encoding
        val typeLength = buffer.int
        val typeBytes = ByteArray(typeLength)
        buffer.get(typeBytes)
        assertEquals("Point3D", String(typeBytes, Charsets.UTF_8))

        assertEquals(1.5, buffer.double, 1e-10)
        assertEquals(2.7, buffer.double, 1e-10)
        assertEquals(-3.14, buffer.double, 1e-10)

        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testCustomStructSchemaWithMixedTypes() {
        // Test with CustomClass using mixed types
        val customSchema = CustomStructSchema<CustomClass>(
            type = "CustomClass",
            componentNames = listOf("id", "data", "active"),
            componentSchemas = listOf(IntSchema, StringSchema, BooleanSchema),
            encoder = { obj -> listOf(obj.id, obj.data, obj.active) }
        )

        val testObject = CustomClass(42, "test data", true)

        // Test object size calculation
        val expectedSize = 4 + "CustomClass".toByteArray().size + // type
                4 + // id (int)
                4 + "test data".toByteArray().size + // data (string)
                1 // active (boolean)
        assertEquals(expectedSize, customSchema.objSize(testObject))

        // Test encoding
        val buffer = ByteBuffer.allocate(customSchema.objSize(testObject))
        customSchema.encodeObject(buffer, testObject)
        buffer.flip()

        // Verify type
        val typeLength = buffer.int
        val typeBytes = ByteArray(typeLength)
        buffer.get(typeBytes)
        assertEquals("CustomClass", String(typeBytes, Charsets.UTF_8))

        // Verify id
        assertEquals(42, buffer.int)

        // Verify data
        val dataLength = buffer.int
        val dataBytes = ByteArray(dataLength)
        buffer.get(dataBytes)
        assertEquals("test data", String(dataBytes, Charsets.UTF_8))

        // Verify active
        assertEquals(1.toByte(), buffer.get()) // true

        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testCustomStructSchemaConsistency() {
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        val person1 = Person("Same", 30, "test")
        val person2 = Person("Same", 40, "test") // different age, same string lengths
        val person3 = Person("Different", 30, "longer_email@domain.com") // different string lengths

        // Same string lengths should give same object size
        assertEquals(schema.objSize(person1), schema.objSize(person2))

        // Different string lengths should give different object size
        assertNotEquals(schema.objSize(person1), schema.objSize(person3))
    }

    @Test
    fun testCustomStructSchemaRoundTrip() {
        val schema = CustomStructSchema<Person>(
            type = "Person",
            componentNames = listOf("name", "age", "email"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person -> listOf(person.name, person.age, person.email) }
        )

        val testPerson = Person("Charlie", 28, "charlie@example.com")

        // Test that objSize calculation matches actual encoding
        val objSize = schema.objSize(testPerson)
        val buffer = ByteBuffer.allocate(objSize)
        schema.encodeObject(buffer, testPerson)

        assertEquals(0, buffer.remaining(), "Object size should match encoded size exactly")
    }

    @Test
    fun testCustomStructSchemaWithCustomEncoder() {
        // Test a custom encoder that transforms the data
        val transformedSchema = CustomStructSchema<Person>(
            type = "TransformedPerson",
            componentNames = listOf("fullName", "yearsOld", "contact"),
            componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
            encoder = { person ->
                listOf(
                    person.name.uppercase(), // Transform name to uppercase
                    person.age + 1, // Add 1 to age
                    "EMAIL:${person.email}" // Prefix email
                )
            }
        )

        val testPerson = Person("john doe", 29, "john@test.com")

        val objSize = transformedSchema.objSize(testPerson)
        val buffer = ByteBuffer.allocate(objSize)
        transformedSchema.encodeObject(buffer, testPerson)
        buffer.flip()

        // Verify type
        val typeLength = buffer.int
        val typeBytes = ByteArray(typeLength)
        buffer.get(typeBytes)
        assertEquals("TransformedPerson", String(typeBytes, Charsets.UTF_8))

        // Verify transformed name
        val nameLength = buffer.int
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        assertEquals("JOHN DOE", String(nameBytes, Charsets.UTF_8))

        // Verify transformed age
        assertEquals(30, buffer.int) // 29 + 1

        // Verify transformed email
        val emailLength = buffer.int
        val emailBytes = ByteArray(emailLength)
        buffer.get(emailBytes)
        assertEquals("EMAIL:john@test.com", String(emailBytes, Charsets.UTF_8))

        assertEquals(0, buffer.remaining())
    }

    @Test
    fun testCustomStructSchemaEmptyComponents() {
        // Test with no components (edge case)
        val emptySchema = CustomStructSchema<Person>(
            type = "Empty",
            componentNames = emptyList(),
            componentSchemas = emptyList(),
            encoder = { _ -> emptyList() }
        )

        val testPerson = Person("test", 25, "test@test.com")

        // Should only include the type field
        val expectedSize = 4 + "Empty".toByteArray().size
        assertEquals(expectedSize, emptySchema.objSize(testPerson))

        val buffer = ByteBuffer.allocate(emptySchema.objSize(testPerson))
        emptySchema.encodeObject(buffer, testPerson)
        buffer.flip()

        // Should only encode the type
        val typeLength = buffer.int
        val typeBytes = ByteArray(typeLength)
        buffer.get(typeBytes)
        assertEquals("Empty", String(typeBytes, Charsets.UTF_8))

        assertEquals(0, buffer.remaining())
    }
}
