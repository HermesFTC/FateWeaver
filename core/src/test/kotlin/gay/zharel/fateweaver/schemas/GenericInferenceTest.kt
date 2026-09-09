package gay.zharel.fateweaver.schemas

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.log.write
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.reflect.KClass

/**
 * Covers [FateSchema.schemaOfType] — the generic-aware inference used for reflected class fields.
 */
class GenericInferenceTest {

    data class Member(@JvmField val id: Int, @JvmField val name: String)

    class DoubleMapHolder(@JvmField val items: Map<String, Double>)
    class IntMapHolder(@JvmField val items: Map<String, Int>)
    class AnyMapHolder(@JvmField val items: Map<String, Any>)
    class StarMapHolder(@JvmField val items: Map<String, *>)
    class NullableValueHolder(@JvmField val items: Map<String, Double?>)
    class Box<T>(@JvmField val items: List<T>)

    class ListHolder(@JvmField val names: List<String>)
    class OtherListHolder(@JvmField val labels: List<String>)
    class SetHolder(@JvmField val ids: Set<Int>)
    class RosterHolder(@JvmField val members: List<Member>)
    class MapOfListHolder(@JvmField val buckets: Map<String, List<Int>>)

    class NullableElems(@JvmField val xs: Array<String?>)
    class Node(@JvmField val kids: List<Node>)

    /** Decodes every message on the single channel of a freshly written log. */
    private fun roundTrip(channelName: String, cls: Class<*>, vararg values: Any): List<Map<*, *>> {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use { writer ->
            val channel = writer.createChannel(channelName, cls)
            @Suppress("UNCHECKED_CAST")
            values.forEach { (channel as gay.zharel.fateweaver.log.LogChannel<Any>).put(it) }
        }
        return FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
            .filterIsInstance<FateLogEntry.Message<*>>()
            .map { it.data as Map<*, *> }
    }

    @Test
    fun testMapFieldInfersMapSchema() {
        val schema = FateSchema.schemaOfClass(DoubleMapHolder::class) as ReflectedClassSchema<*>
        val items = assertInstanceOf(MapSchema::class.java, schema.fields["items"])

        assertSame(StringSchema, items.keySchema)
        assertSame(DoubleSchema, items.valueSchema)
    }

    @Test
    fun testMapFieldRoundTrips() {
        val decoded = roundTrip(
            "holder", DoubleMapHolder::class.java,
            DoubleMapHolder(linkedMapOf("a" to 1.0, "b" to 2.0)),
        )

        assertEquals(1, decoded.size)
        assertEquals(mapOf("a" to 1.0, "b" to 2.0), decoded[0]["items"])
    }

    @Test
    fun testMapOfAnyFailsFastWithClearMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateSchema.schemaOfClass(AnyMapHolder::class)
        }

        val message = error.message!!
        assertTrue(message.contains("AnyMapHolder.items"), "was: $message")
        assertTrue(message.contains("kotlin.Any"), "was: $message")
        assertTrue(message.contains("type argument #2"), "was: $message")
    }

    @Test
    fun testMapOfAnyFailsOnTheOriginalWritePath() {
        // Previously this crashed deep in encoding with IllegalCallableAccessException.
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateLogWriter(ByteArrayOutputStream()).use {
                it.write("holder", AnyMapHolder(mapOf("a" to 1)))
            }
        }

        assertTrue(error.message!!.contains("kotlin.Any"), "was: ${error.message}")
    }

    @Test
    fun testStarProjectionRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateSchema.schemaOfClass(StarMapHolder::class)
        }
        assertTrue(error.message!!.contains("star projection"), "was: ${error.message}")
    }

    @Test
    fun testNullableTypeArgumentBecomesOptional() {
        val schema = FateSchema.schemaOfClass(NullableValueHolder::class) as ReflectedClassSchema<*>
        val items = assertInstanceOf(MapSchema::class.java, schema.fields["items"])

        assertSame(StringSchema, items.keySchema)
        val value = assertInstanceOf(OptionalSchema::class.java, items.valueSchema)
        assertSame(DoubleSchema, value.valueSchema)
    }

    @Test
    fun testTypeVariableArgumentRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateSchema.schemaOfClass(Box::class)
        }
        assertTrue(error.message!!.contains("type variable"), "was: ${error.message}")
    }

    @Test
    fun testListFieldUsesArrayWireFormat() {
        val schema = FateSchema.schemaOfClass(ListHolder::class) as ReflectedClassSchema<*>

        // Wire-identical to String[]: the translated schema reports the ARRAY tag.
        assertEquals(FateSchema.TypeRegistry.ARRAY.value, schema.fields["names"]!!.tag)

        val decoded = roundTrip("team", ListHolder::class.java, ListHolder(listOf("a", "b")))
        assertArrayEquals(arrayOf<Any>("a", "b"), decoded[0]["names"] as Array<*>)
    }

    @Test
    fun testSetFieldRoundTrips() {
        val decoded = roundTrip("ids", SetHolder::class.java, SetHolder(linkedSetOf(3, 1, 2)))
        assertArrayEquals(arrayOf<Any>(3, 1, 2), decoded[0]["ids"] as Array<*>)
    }

    @Test
    fun testListOfStructsRoundTrips() {
        val decoded = roundTrip(
            "roster", RosterHolder::class.java,
            RosterHolder(listOf(Member(1, "ada"), Member(2, "grace"))),
        )

        val members = decoded[0]["members"] as Array<*>
        assertEquals(2, members.size)
        assertEquals(mapOf("id" to 1, "name" to "ada"), members[0])
        assertEquals(mapOf("id" to 2, "name" to "grace"), members[1])
    }

    @Test
    fun testMapOfListRoundTrips() {
        val decoded = roundTrip(
            "buckets", MapOfListHolder::class.java,
            MapOfListHolder(linkedMapOf("evens" to listOf(2, 4))),
        )

        val buckets = decoded[0]["buckets"] as Map<*, *>
        assertArrayEquals(arrayOf<Any>(2, 4), buckets["evens"] as Array<*>)
    }

    @Test
    fun testRawMapAndCollectionClassesRejected() {
        for (cls in listOf(HashMap::class.java, ArrayList::class.java)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                FateSchema.schemaOfClass(cls)
            }
            assertTrue(error.message!!.contains("raw class"), "was: ${error.message}")
            assertTrue(error.message!!.contains("MapSchema"), "was: ${error.message}")
        }
    }

    @Test
    fun testParameterizedTypesDoNotShareOrPolluteTheClassRegistry() {
        val doubles = (FateSchema.schemaOfClass(DoubleMapHolder::class) as ReflectedClassSchema<*>)
            .fields["items"]!!
        val ints = (FateSchema.schemaOfClass(IntMapHolder::class) as ReflectedClassSchema<*>)
            .fields["items"]!!

        assertNotSame(doubles, ints)
        assertSame(DoubleSchema, (doubles as MapSchema<*, *>).valueSchema)
        assertSame(IntSchema, (ints as MapSchema<*, *>).valueSchema)

        // A map field must never register a schema against Map::class itself.
        assertFalse(FateSchema.SCHEMA_REGISTRY.keys.contains(Map::class))
        assertFalse(FateSchema.SCHEMA_REGISTRY.keys.contains(List::class))
    }

    @Test
    fun testIdenticalParameterizedTypesShareOneSchema() {
        val first = (FateSchema.schemaOfClass(ListHolder::class) as ReflectedClassSchema<*>)
            .fields["names"]!!
        val second = (FateSchema.schemaOfClass(OtherListHolder::class) as ReflectedClassSchema<*>)
            .fields["labels"]!!

        // Both are List<String>, and KType equality is structural, so the cache returns one schema.
        assertSame(first, second)
    }

    @Test
    fun testExplicitRegistrationWinsOverInference() {
        val custom = CustomStructSchema<Map<String, Any>>(
            type = "OpaqueMap",
            componentNames = listOf("size"),
            componentSchemas = listOf(IntSchema),
            encoder = { listOf(it.size) },
        )

        try {
            @Suppress("UNCHECKED_CAST")
            FateSchema.registerSchema(Map::class as KClass<Map<String, Any>>, custom)

            val schema = FateSchema.schemaOfClass(AnyMapHolder::class) as ReflectedClassSchema<*>
            // Registration is the escape hatch: even Map<String, Any> resolves now.
            assertSame(custom, schema.fields["items"])
        } finally {
            FateSchema.clearInferenceCaches()
        }
    }

    @Test
    fun testCycleThroughListStillDetected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateSchema.schemaOfClass(Node::class)
        }
        assertTrue(error.message!!.contains("Cyclic reference detected"), "was: ${error.message}")
    }

    @Test
    fun testJavaDeclaredGenericFieldsInfer() {
        val schema = FateSchema.schemaOfClass(JavaGenericsTestClass::class) as ReflectedClassSchema<*>

        // Java generics arrive as platform types (isMarkedNullable == false), so they must infer
        // rather than trip the nullable-type-argument rejection.
        val map = assertInstanceOf(MapSchema::class.java, schema.fields["javaMap"])
        assertSame(StringSchema, map.keySchema)
        assertSame(IntSchema, map.valueSchema)

        assertEquals(FateSchema.TypeRegistry.ARRAY.value, schema.fields["javaList"]!!.tag)
    }

    @Test
    fun testArrayFieldsAreUnaffected() {
        val schema = FateSchema.schemaOfClass(NullableElems::class) as ReflectedClassSchema<*>

        // Arrays keep their pre-existing path: the component type survives on the Class, so a
        // nullable element type must not trip the type-argument rejection.
        val xs = assertInstanceOf(ArraySchema::class.java, schema.fields["xs"])
        assertSame(StringSchema, xs.elementSchema)
    }
}
