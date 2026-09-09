package gay.zharel.fateweaver

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Regression coverage for the originally reported case: a class with a `Map` field.
 *
 * Logging one used to build a garbage schema from `Map`'s own properties
 * (`{entries, keys, size, values}`) and then fail mid-encode with
 * `IllegalCallableAccessException` on `LinkedHashMap$LinkedEntrySet`.
 */
class MapFieldTest {

    class Holder(val items: Map<String, Any>)

    class TypedHolder(val items: Map<String, Int>)

    @Test
    fun testHeterogeneousMapFieldIsRejectedUpFront() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FateLogWriter(ByteArrayOutputStream()).use {
                it.write("holder", Holder(mapOf("a" to 1, "b" to "two")))
            }
        }

        val message = error.message!!
        assertTrue(message.contains("Holder.items"), "was: $message")
        assertTrue(message.contains("kotlin.Any"), "was: $message")
    }

    @Test
    fun testConcreteMapFieldRoundTrips() {
        val os = ByteArrayOutputStream()
        FateLogWriter(os).use { it.write("holder", TypedHolder(mapOf("a" to 1, "b" to 2))) }

        val entries = FateLogReader(os.toByteArray().inputStream()).use { it.readAll() }
        val messages = entries.filterIsInstance<FateLogEntry.Message<*>>()

        assertEquals(1, messages.size)
        assertEquals(mapOf("items" to mapOf("a" to 1, "b" to 2)), messages[0].data)
    }
}
