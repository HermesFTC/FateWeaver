package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.ArraySchema
import gay.zharel.fateweaver.schemas.CustomStructSchema
import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.EnumSchema
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.schemas.IntSchema
import gay.zharel.fateweaver.schemas.MapSchema
import gay.zharel.fateweaver.schemas.StringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Writer to reader round trips for MAP channels.
 */
class TestMapLogRoundTrip {

    enum class Status { IDLE, RUNNING }

    data class Point(@JvmField val x: Int, @JvmField val y: Int)

    private fun <T> roundTrip(schema: FateSchema<T>, values: List<T>): Pair<FateSchema<*>, List<Any?>> {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use { writer ->
            val channel = writer.createChannel("channel", schema)
            values.forEach { channel.put(it) }
        }

        val entries = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
        val decodedSchema = entries.filterIsInstance<FateLogEntry.Schema>().single().schema
        val data = entries.filterIsInstance<FateLogEntry.Message<*>>().map { it.data }
        return decodedSchema to data
    }

    @Test
    fun testMapChannelRoundTrip() {
        val (schema, data) = roundTrip(
            MapSchema(StringSchema, DoubleSchema),
            listOf(
                mapOf("fl" to 0.5, "fr" to -0.5),
                mapOf("fl" to 1.0, "fr" to 1.0),
                emptyMap(),
            ),
        )

        val decoded = assertInstanceOf(MapSchema::class.java, schema)
        assertSame(StringSchema, decoded.keySchema)
        assertSame(DoubleSchema, decoded.valueSchema)

        assertEquals(3, data.size)
        assertEquals(mapOf("fl" to 0.5, "fr" to -0.5), data[0])
        assertEquals(mapOf("fl" to 1.0, "fr" to 1.0), data[1])
        assertEquals(emptyMap<Any, Any>(), data[2])
    }

    @Test
    fun testNestedMapRoundTrip() {
        val (_, data) = roundTrip(
            MapSchema(StringSchema, MapSchema(StringSchema, IntSchema)),
            listOf(mapOf("outer" to mapOf("inner" to 7))),
        )

        val outer = data[0] as Map<*, *>
        assertEquals(mapOf("inner" to 7), outer["outer"])
    }

    @Test
    fun testMapOfStructsRoundTrip() {
        val (_, data) = roundTrip(
            MapSchema(StringSchema, FateSchema.schemaOfClass(Point::class)),
            listOf(mapOf("origin" to Point(0, 0), "unit" to Point(1, 2))),
        )

        val decoded = data[0] as Map<*, *>
        // Struct values decode to a field map, as they do on any other channel.
        assertEquals(mapOf("x" to 0, "y" to 0), decoded["origin"])
        assertEquals(mapOf("x" to 1, "y" to 2), decoded["unit"])
    }

    @Test
    fun testEnumKeyedMapRoundTrip() {
        val (_, data) = roundTrip(
            MapSchema(EnumSchema<Status>(Status::class.java), DoubleSchema),
            listOf(mapOf<Enum<Status>, Double>(Status.IDLE to 0.0, Status.RUNNING to 1.0)),
        )

        // Enum keys come back as constant names, matching how enum values decode elsewhere.
        val decoded = data[0] as Map<*, *>
        assertEquals(setOf("IDLE", "RUNNING"), decoded.keys)
        assertEquals(1.0, decoded["RUNNING"])
    }

    @Test
    fun testStructContainingMapFieldRoundTrip() {
        val schema = CustomStructSchema<Pair<String, Map<String, Int>>>(
            type = "Tagged",
            componentNames = listOf("tag", "counts"),
            componentSchemas = listOf(StringSchema, MapSchema(StringSchema, IntSchema)),
            encoder = { listOf(it.first, it.second) },
        )

        val (_, data) = roundTrip(schema, listOf("a" to mapOf("hits" to 3)))

        val decoded = data[0] as Map<*, *>
        assertEquals("a", decoded["tag"])
        assertEquals(mapOf("hits" to 3), decoded["counts"])
    }

    @Test
    fun testMapInsideArrayRoundTrip() {
        val (_, data) = roundTrip(
            ArraySchema(MapSchema(StringSchema, IntSchema)),
            listOf(arrayOf(mapOf("a" to 1), mapOf("b" to 2))),
        )

        val decoded = data[0] as Array<*>
        assertEquals(2, decoded.size)
        assertEquals(mapOf("a" to 1), decoded[0])
        assertEquals(mapOf("b" to 2), decoded[1])
    }

    @Test
    fun testDecodedMapPreservesOnWireOrder() {
        val (_, data) = roundTrip(
            MapSchema(StringSchema, IntSchema),
            listOf(linkedMapOf("z" to 1, "a" to 2, "m" to 3)),
        )

        val decoded = data[0] as Map<*, *>
        assertTrue(decoded is LinkedHashMap<*, *>)
        assertEquals(listOf("z", "a", "m"), decoded.keys.toList())
    }
}
