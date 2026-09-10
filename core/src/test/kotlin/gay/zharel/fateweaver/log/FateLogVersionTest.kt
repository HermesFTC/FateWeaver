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
    fun writerEmitsVersion2() {
        val bytes = ByteArrayOutputStream()
        FateLogWriter(bytes).close()
        assertEquals(2.toShort(), VERSION)
        assertEquals(headerOf(2).toList(), bytes.toByteArray().take(4))
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
            FateLogReader(ByteArrayInputStream(headerOf(3)))
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
