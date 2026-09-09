package gay.zharel.fateweaver.schemas

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Optional
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.OptionalLong

/**
 * Null support: the OPTIONAL tag, and the inference that reaches for it.
 */
class OptionalSchemaTest {

    data class Pose(@JvmField val x: Double, @JvmField val y: Double)

    class Target(@JvmField val name: String, @JvmField val pose: Pose?)
    class Readings(@JvmField val values: Map<String, Double?>)
    class Labels(@JvmField val items: List<String?>)
    class Nested(@JvmField val inner: Map<String, List<Int>?>)

    private fun roundTrip(schema: FateSchema<*>, values: List<Any?>): Pair<FateSchema<*>, List<Any?>> {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use { writer ->
            @Suppress("UNCHECKED_CAST")
            val channel = writer.createChannel("channel", schema as FateSchema<Any?>)
            values.forEach { channel.put(it) }
        }

        val entries = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
        return entries.filterIsInstance<FateLogEntry.Schema>().single().schema to
                entries.filterIsInstance<FateLogEntry.Message<*>>().map { it.data }
    }

    @Test
    fun testAbsentValueIsOneByte() {
        val schema = OptionalSchema(DoubleSchema)

        assertEquals(1, schema.objSize(null))
        assertEquals(1 + 8, schema.objSize(1.5))

        val buffer = ByteBuffer.allocate(schema.objSize(null))
        schema.encodeObject(buffer, null)
        buffer.flip()
        assertEquals(0, buffer.get())
    }

    @Test
    fun testPresentValueIsTaggedThenEncodedInline() {
        val schema = OptionalSchema(IntSchema)
        val buffer = ByteBuffer.allocate(schema.objSize(7))
        schema.encodeObject(buffer, 7)
        buffer.flip()

        assertEquals(1, buffer.get())
        assertEquals(7, buffer.int)
    }

    @Test
    fun testSchemaBlobWrapsTheValueSchema() {
        val schema = OptionalSchema(StringSchema)
        val buffer = ByteBuffer.allocate(schema.schemaSize)
        schema.encodeSchema(buffer)
        buffer.flip()

        assertEquals(FateSchema.TypeRegistry.OPTIONAL.value, buffer.int)
        assertEquals(FateSchema.TypeRegistry.STRING.value, buffer.int)
    }

    @Test
    fun testTopLevelOptionalRoundTrips() {
        val (schema, data) = roundTrip(OptionalSchema(StringSchema), listOf("a", null, "b"))

        val optional = assertInstanceOf(OptionalSchema::class.java, schema)
        assertSame(StringSchema, optional.valueSchema)
        assertEquals(listOf("a", null, "b"), data)
    }

    @Test
    fun testNullableFieldRoundTrips() {
        val schema = FateSchema.schemaOfClass(Target::class)
        val (_, data) = roundTrip(schema, listOf(Target("seen", Pose(1.0, 2.0)), Target("lost", null)))

        val seen = data[0] as Map<*, *>
        assertEquals(mapOf("x" to 1.0, "y" to 2.0), seen["pose"])

        val lost = data[1] as Map<*, *>
        assertNull(lost["pose"])
        // The field is still present in the decoded struct, just null. A viewer sees a gap rather
        // than a channel that stops.
        assertTrue(lost.containsKey("pose"))
        assertEquals("lost", lost["name"])
    }

    @Test
    fun testNullableMapValueRoundTrips() {
        val schema = FateSchema.schemaOfClass(Readings::class)
        val (_, data) = roundTrip(schema, listOf(Readings(mapOf("a" to 1.0, "b" to null))))

        val values = (data.single() as Map<*, *>)["values"] as Map<*, *>
        assertEquals(1.0, values["a"])
        assertNull(values["b"])
        assertTrue(values.containsKey("b"))
    }

    @Test
    fun testNullableListElementRoundTrips() {
        val schema = FateSchema.schemaOfClass(Labels::class)
        val (_, data) = roundTrip(schema, listOf(Labels(listOf("x", null))))

        val items = (data.single() as Map<*, *>)["items"] as Array<*>
        assertArrayEquals(arrayOf("x", null), items)
    }

    @Test
    fun testNullableCollectionInsideAMapRoundTrips() {
        val schema = FateSchema.schemaOfClass(Nested::class)
        val (_, data) = roundTrip(schema, listOf(Nested(mapOf("some" to listOf(1, 2), "none" to null))))

        val inner = (data.single() as Map<*, *>)["inner"] as Map<*, *>
        assertArrayEquals(arrayOf<Any>(1, 2), inner["some"] as Array<*>)
        assertNull(inner["none"])
    }

    @Test
    fun testNonNullFieldsAreByteIdenticalToBefore() {
        // Null support must not change the encoding of anything that isn't nullable, or every
        // existing log and decoder is invalidated.
        val optional = FateSchema.schemaOfClass(Target::class)
        val plain = ReflectedClassSchema<Target>(mapOf("name" to StringSchema))

        @Suppress("UNCHECKED_CAST")
        fun bytes(schema: FateSchema<*>, value: Target): ByteArray {
            schema as FateSchema<Any>
            val buffer = ByteBuffer.allocate(schema.objSize(value))
            schema.encodeObject(buffer, value)
            return buffer.array()
        }

        // The optional field adds exactly its presence byte and nothing else.
        val target = Target("a", null)
        assertEquals(bytes(plain, target).size + 1, bytes(optional, target).size)
    }

    @Test
    fun testNullThroughANonOptionalSchemaThrows() {
        val schema = MapSchema(StringSchema, DoubleSchema)

        @Suppress("UNCHECKED_CAST")
        val erased = schema as FateSchema<Map<String, Double?>>

        assertThrows(NullPointerException::class.java) {
            erased.objSize(mapOf("a" to null))
        }
    }
}

/**
 * `java.util.Optional` and friends, which share the OPTIONAL tag with Kotlin's `T?`.
 */
class JavaOptionalSchemaTest {

    class Tracking(@JvmField val target: Optional<String>)
    class Counters(
        @JvmField val hits: OptionalInt,
        @JvmField val elapsed: OptionalLong,
        @JvmField val voltage: OptionalDouble,
    )

    private fun decode(schema: FateSchema<*>, value: Any): Any? {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use { writer ->
            @Suppress("UNCHECKED_CAST")
            writer.createChannel("c", schema as FateSchema<Any>).put(value)
        }
        return FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
            .filterIsInstance<FateLogEntry.Message<*>>().single().data
    }

    @Test
    fun testOptionalFieldInfers() {
        val schema = FateSchema.schemaOfClass(Tracking::class) as ReflectedClassSchema<*>
        val target = assertInstanceOf(JavaOptionalSchema::class.java, schema.fields["target"])
        assertSame(StringSchema, target.valueSchema)
    }

    @Test
    fun testOptionalEncodesLikeANullableKotlinField() {
        // The whole point of sharing the tag: a reader cannot tell the two apart.
        val javaForm = JavaOptionalSchema(StringSchema)
        val kotlinForm = OptionalSchema(StringSchema)

        fun bytes(schema: FateSchema<*>, value: Any?): ByteArray {
            @Suppress("UNCHECKED_CAST")
            schema as FateSchema<Any?>
            val buffer = ByteBuffer.allocate(schema.schemaSize + schema.objSize(value))
            schema.encodeSchema(buffer)
            schema.encodeObject(buffer, value)
            return buffer.array()
        }

        assertArrayEquals(bytes(kotlinForm, "hi"), bytes(javaForm, Optional.of("hi")))
        assertArrayEquals(bytes(kotlinForm, null), bytes(javaForm, Optional.empty<String>()))
    }

    @Test
    fun testOptionalRoundTripsToValueOrNull() {
        val schema = FateSchema.schemaOfClass(Tracking::class)

        assertEquals("found", (decode(schema, Tracking(Optional.of("found"))) as Map<*, *>)["target"])
        assertNull((decode(schema, Tracking(Optional.empty())) as Map<*, *>)["target"])
    }

    @Test
    fun testPrimitiveOptionalsInfer() {
        val schema = FateSchema.schemaOfClass(Counters::class) as ReflectedClassSchema<*>

        assertSame(OptionalIntSchema, schema.fields["hits"])
        assertSame(OptionalLongSchema, schema.fields["elapsed"])
        assertSame(OptionalDoubleSchema, schema.fields["voltage"])
    }

    @Test
    fun testPrimitiveOptionalsRoundTrip() {
        val schema = FateSchema.schemaOfClass(Counters::class)

        val present = decode(schema, Counters(
            OptionalInt.of(3), OptionalLong.of(9L), OptionalDouble.of(12.6),
        )) as Map<*, *>
        assertEquals(3, present["hits"])
        assertEquals(9L, present["elapsed"])
        assertEquals(12.6, present["voltage"])

        val absent = decode(schema, Counters(
            OptionalInt.empty(), OptionalLong.empty(), OptionalDouble.empty(),
        )) as Map<*, *>
        assertNull(absent["hits"])
        assertNull(absent["elapsed"])
        assertNull(absent["voltage"])
    }

    @Test
    fun testRawOptionalIsRejected() {
        // Erased just like a raw Map. Without this it would reflect to a struct with no fields
        // and silently encode nothing.
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateSchema.schemaOfClass(Optional::class)
        }
        assertTrue(error.message!!.contains("java.util.Optional"), error.message)
        assertTrue(error.message!!.contains("erased"), error.message)
    }
}
