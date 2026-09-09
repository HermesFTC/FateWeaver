package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.ArraySchema
import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.schemas.IntSchema
import gay.zharel.fateweaver.schemas.MapSchema
import gay.zharel.fateweaver.schemas.ReflectedClassSchema
import gay.zharel.fateweaver.schemas.StringSchema
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Covers name-addressed writes of parameterized values — the reified [write] extension and the
 * [FateLogWriter.write] overload behind it.
 *
 * The distinction under test throughout: a value's *runtime class* has no type arguments, so
 * `write("k", mapOf("a" to 1.0))` can only work if the call site's static type reaches the writer.
 */
class TopLevelGenericsTest {

    data class Point(@JvmField val x: Int, @JvmField val y: Int)

    /** Writes to one channel, then reads the channel schema and every message back. */
    private fun roundTrip(write: (FateLogWriter) -> Unit): Pair<FateSchema<*>, List<Any?>> {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use(write)

        val entries = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
        val schema = entries.filterIsInstance<FateLogEntry.Schema>().single().schema
        val data = entries.filterIsInstance<FateLogEntry.Message<*>>().map { it.data }
        return schema to data
    }

    @Test
    fun testTopLevelMapInfersFromCallSite() {
        val (schema, data) = roundTrip { it.write("powers", mapOf("fl" to 0.5, "fr" to -0.5)) }

        val map = assertInstanceOf(MapSchema::class.java, schema)
        assertSame(StringSchema, map.keySchema)
        assertSame(DoubleSchema, map.valueSchema)
        assertEquals(mapOf("fl" to 0.5, "fr" to -0.5), data.single())
    }

    @Test
    fun testTopLevelListUsesArrayWireFormat() {
        val (schema, data) = roundTrip { it.write("names", listOf("a", "b")) }

        assertEquals(FateSchema.TypeRegistry.ARRAY.value, schema.tag)
        assertArrayEquals(arrayOf<Any>("a", "b"), data.single() as Array<*>)
    }

    @Test
    fun testTopLevelListOfStructs() {
        val (schema, data) = roundTrip { it.write("points", listOf(Point(1, 2), Point(3, 4))) }

        val array = assertInstanceOf(ArraySchema::class.java, schema)
        assertInstanceOf(ReflectedClassSchema::class.java, array.elementSchema)

        val decoded = data.single() as Array<*>
        assertEquals(mapOf("x" to 1, "y" to 2), decoded[0])
        assertEquals(mapOf("x" to 3, "y" to 4), decoded[1])
    }

    @Test
    fun testTopLevelNestedGenerics() {
        val (schema, data) = roundTrip { it.write("buckets", mapOf("evens" to listOf(2, 4))) }

        val map = assertInstanceOf(MapSchema::class.java, schema)
        assertSame(StringSchema, map.keySchema)
        assertEquals(FateSchema.TypeRegistry.ARRAY.value, map.valueSchema.tag)

        val buckets = data.single() as Map<*, *>
        assertArrayEquals(arrayOf<Any>(2, 4), buckets["evens"] as Array<*>)
    }

    @Test
    fun testTopLevelMapWithEnumKeys() {
        // Keys go through the same inference as values, so a non-String key type must work.
        val (schema, _) = roundTrip { it.write("byIndex", mapOf(1 to Point(0, 0))) }

        val map = assertInstanceOf(MapSchema::class.java, schema)
        assertSame(IntSchema, map.keySchema)
        assertInstanceOf(ReflectedClassSchema::class.java, map.valueSchema)
    }

    @Test
    fun testReifiedCreateChannel() {
        val (schema, data) = roundTrip { writer ->
            writer.createChannel<Map<String, Double>>("powers").put(mapOf("fl" to 0.5))
        }

        assertInstanceOf(MapSchema::class.java, schema)
        assertEquals(mapOf("fl" to 0.5), data.single())
    }

    @Test
    fun testRepeatedWritesReuseOneChannel() {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use {
            it.write("powers", mapOf("fl" to 0.5))
            it.write("powers", mapOf("fl" to 0.25, "fr" to 0.75))
        }

        val entries = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
        assertEquals(1, entries.filterIsInstance<FateLogEntry.Schema>().size)

        val data = entries.filterIsInstance<FateLogEntry.Message<*>>().map { it.data }
        assertEquals(listOf(mapOf("fl" to 0.5), mapOf("fl" to 0.25, "fr" to 0.75)), data)
    }

    @Test
    fun testConflictingParameterizationIsRejected() {
        FateLogWriter(ByteArrayOutputStream()).use { writer ->
            writer.write("powers", mapOf("fl" to 0.5))

            // A channel's schema is fixed at creation, so the second write would otherwise encode
            // Ints through a DoubleSchema.
            val error = assertThrows(IllegalArgumentException::class.java) {
                writer.write("powers", mapOf("fl" to 1))
            }
            assertTrue(error.message!!.contains("was created for"), error.message)
            assertTrue(error.message!!.contains("kotlin.Int"), error.message)
        }
    }

    @Test
    fun testUnparameterizedStaticTypeStillUsesRuntimeClass() {
        // `Number` carries no more information than the runtime class does, and the runtime class
        // carries more: inference must not regress to a schema for the declared supertype.
        val value: Number = 5
        val (schema, data) = roundTrip { it.write("n", value) }

        assertSame(IntSchema, schema)
        assertEquals(5, data.single())
    }

    @Test
    fun testAnyStaticTypeFallsBackToRuntimeClass() {
        val value: Any = Point(1, 2)
        val (schema, data) = roundTrip { it.write("p", value) }

        assertInstanceOf(ReflectedClassSchema::class.java, schema)
        assertEquals(mapOf("x" to 1, "y" to 2), data.single())
    }

    @Test
    fun testAnyStaticTypeCannotRecoverErasedGenerics() {
        // The honest limit of the feature: once the static type is gone, so is the parameterization,
        // and the raw-class rejection is the correct outcome rather than a guess from the contents.
        val value: Any = mapOf("fl" to 0.5)

        val error = assertThrows(IllegalArgumentException::class.java) {
            FateLogWriter(ByteArrayOutputStream()).use { it.write("powers", value) }
        }
        assertTrue(error.message!!.contains("MapSchema"), error.message)
    }

    @Test
    fun testErasedOverloadIsUnchangedForPlainValues() {
        val (schema, data) = roundTrip { it.writeErased("n", 42) }

        assertSame(IntSchema, schema)
        assertEquals(42, data.single())
    }
}
