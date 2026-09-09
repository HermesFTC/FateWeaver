package gay.zharel.fateweaver.schemas

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The JVM primitives narrower than the format's own types.
 *
 * `Float` used to reach [ReflectedClassSchema], which found no public properties on it and encoded
 * zero bytes. The write succeeded and the value simply vanished from the log. Floats now widen to
 * double; the rest are rejected when the schema is built.
 */
class NarrowPrimitiveTest {

    class FloatHolder(@JvmField val value: Float, @JvmField val samples: FloatArray)

    class ShortHolder(@JvmField val value: Short)
    class ByteHolder(@JvmField val value: Byte)
    class CharHolder(@JvmField val value: Char)
    class ShortArrayHolder(@JvmField val values: ShortArray)

    private fun roundTrip(channel: String, value: Any): Any? {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use { it.writeErased(channel, value) }
        return FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
            .filterIsInstance<FateLogEntry.Message<*>>()
            .single().data
    }

    @Test
    fun testFloatFieldWidensToDoubleInsteadOfVanishing() {
        val schema = FateSchema.schemaOfClass(FloatHolder::class) as ReflectedClassSchema<*>

        // The bug was a zero-byte encoding, so the size is the assertion that matters:
        // 8 bytes for the widened double, plus 4 + 2 * 8 for the widened array.
        val holder = FloatHolder(1.5f, floatArrayOf(0.25f, -0.5f))

        @Suppress("UNCHECKED_CAST")
        assertEquals(8 + 4 + 16, (schema as FateSchema<Any>).objSize(holder))

        val decoded = roundTrip("holder", holder) as Map<*, *>
        assertEquals(1.5, decoded["value"])
        assertArrayEquals(arrayOf<Any>(0.25, -0.5), decoded["samples"] as Array<*>)
    }

    @Test
    fun testFloatChannelRoundTrips() {
        assertEquals(2.5, roundTrip("f", 2.5f))
    }

    @Test
    fun testNarrowerPrimitivesAreRejectedAtSchemaBuild() {
        for (cls in listOf(ShortHolder::class, ByteHolder::class, CharHolder::class)) {
            val error = assertThrows(IllegalArgumentException::class.java, { FateSchema.schemaOfClass(cls) }, cls.simpleName)
            assertTrue(error.message!!.contains("no encoding for it"), error.message)
            assertTrue(error.message!!.contains("Widen the field to"), error.message)
        }
    }

    @Test
    fun testNarrowPrimitiveArraysAreRejectedThroughTheirComponentType() {
        // ShortArray has no branch of its own; it resolves `short` through the same guard.
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateSchema.schemaOfClass(ShortArrayHolder::class)
        }
        assertTrue(error.message!!.contains("no encoding for it"), error.message)
    }
}
