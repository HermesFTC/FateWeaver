package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.IntSchema
import gay.zharel.fateweaver.schemas.MapSchema
import gay.zharel.fateweaver.schemas.OptionalSchema
import gay.zharel.fateweaver.schemas.StringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test

/**
 * The header version is part of the wire contract with third-party decoders, so it is pinned here
 * rather than left to whatever the writer happens to emit.
 */
class FateLogVersionTest {

    private fun headerOf(version: Short): ByteArray =
        ByteBuffer.allocate(4).put(MAGIC.toByteArray(Charsets.UTF_8)).putShort(version).array()

    @Test
    fun writerEmitsVersion3() {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).close()
        assertEquals(3.toShort(), VERSION)
        assertEquals(headerOf(3).toList(), bytes.toByteArray().take(4))
    }

    @Test
    fun readerAcceptsEverySupportedVersion() {
        for (version in SUPPORTED_VERSIONS) {
            FateLogReader(ByteArrayInputStream(headerOf(version))).use { assertEquals(false, it.hasNext()) }
        }
    }

    @Test
    fun readerRejectsUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            FateLogReader(ByteArrayInputStream(headerOf(4)))
        }
    }

    @Test
    fun version3MessagesCarryATimestamp() {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).use { writer ->
            writer.write(writer.createChannel("voltage", DoubleSchema), 12.4, 1_234_567_890L)
        }

        val message = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use {
            it.readAll().filterIsInstance<FateLogEntry.Message<*>>().single()
        }
        assertEquals(1_234_567_890L, message.timestamp)
        assertEquals(12.4, message.data)
    }

    @Test
    fun olderLogsStillReadWithoutATimestamp() {
        // Entries are version-independent except for the message prefix, so a version 2 log is a
        // version 3 schema entry with a message that has no timestamp slot.
        val schemaEntry = ByteArrayOutputStream().let { out ->
            FateLogWriter(out).use { it.createChannel("voltage", DoubleSchema) }
            out.toByteArray().drop(4).toByteArray()
        }
        val message = ByteBuffer.allocate(16).putInt(1).putInt(0).putDouble(12.4).array()

        for (version in listOf<Short>(1, 2)) {
            val log = headerOf(version) + schemaEntry + message
            val entry = FateLogReader(ByteArrayInputStream(log)).use {
                it.readAll().filterIsInstance<FateLogEntry.Message<*>>().single()
            }
            assertEquals(12.4, entry.data)
            assertEquals(null, entry.timestamp)
        }
    }

    @Test
    fun readerRejectsVersion2TagsUnderAVersion1Header() {
        // Tags 8 and 9 desynchronize a decoder that predates them, so a version 1 header promising
        // only tags 0-7 must not contain one.
        for (schema in listOf(MapSchema(StringSchema, DoubleSchema), OptionalSchema(IntSchema))) {
            val body = ByteArrayOutputStream()
            FateLogWriter(body).use { it.createChannel("channel", schema) }
            val downgraded = headerOf(1) + body.toByteArray().drop(4)

            val failure = assertThrows(IllegalArgumentException::class.java) {
                FateLogReader(ByteArrayInputStream(downgraded)).use { it.readAll() }
            }
            assertEquals(true, failure.message!!.contains("requires log version 2"))
        }
    }
}
