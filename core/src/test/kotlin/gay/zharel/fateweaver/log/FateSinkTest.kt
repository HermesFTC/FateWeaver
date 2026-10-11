package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.LongSchema
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sink that keeps every entry it is handed, tagged by kind.
 *
 * This is the shape [gay.zharel.fateweaver.flight.FlightProjector] relies on: it has to know which
 * entries are schema entries so it can replay them to a reader that connected late.
 */
private class RecordingSink : FateSink {
    val headers = mutableListOf<ByteArray>()
    val schemas = mutableListOf<ByteArray>()
    val messages = mutableListOf<ByteArray>()
    val order = mutableListOf<ByteArray>()
    var flushed = 0
    var closed = 0

    override fun header(bytes: ByteArray) { headers += bytes; order += bytes }
    override fun schemaEntry(bytes: ByteArray) { schemas += bytes; order += bytes }
    override fun messageEntry(bytes: ByteArray) { messages += bytes; order += bytes }
    override fun flush() { flushed++ }
    override fun close() { closed++ }

    /** Everything this sink saw, concatenated: what a file would have contained. */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        order.forEach { out.write(it) }
        return out.toByteArray()
    }
}

/**
 * Writes two channels and three messages, the fixture every test here shares.
 *
 * The message timestamps are fixed rather than read from the clock, so two writers fed this
 * fixture produce identical bytes.
 */
private fun writeSample(writer: FateLogWriter) {
    val timestamps = writer.createChannel("TIMESTAMP", LongSchema)
    val voltage = writer.createChannel("Battery/Voltage", DoubleSchema)

    writer.write(timestamps, 1_000L, 10L)
    writer.write(voltage, 12.4, 20L)
    writer.write(timestamps, 2_000L, 30L)
}

class FateSinkTest {
    @Test
    fun `writer reports each entry kind separately`() {
        val sink = RecordingSink()
        FateLogWriter(sink).use { writeSample(it) }

        assertEquals(1, sink.headers.size, "one header")
        assertEquals(2, sink.schemas.size, "one schema entry per channel")
        assertEquals(3, sink.messages.size, "one message entry per put")

        assertContentEquals(byteArrayOf(0x52, 0x52, 0x00, 0x03), sink.headers.single())

        // The header must come first, and a channel's schema entry must precede any message
        // referencing it, or a reader could not resolve the channel index.
        assertEquals(sink.headers.single(), sink.order.first())
        assertTrue(sink.order.indexOf(sink.schemas[0]) < sink.order.indexOf(sink.messages[0]))
    }

    @Test
    fun `sink split does not change the bytes on the wire`() {
        val sink = RecordingSink()
        FateLogWriter(sink).use { writeSample(it) }

        val stream = ByteArrayOutputStream()
        FateLogWriter(stream).use { writeSample(it) }

        assertContentEquals(stream.toByteArray(), sink.bytes())
    }

    @Test
    fun `entries recorded by a sink still parse as a log`() {
        val sink = RecordingSink()
        FateLogWriter(sink).use { writeSample(it) }

        val entries = FateLogReader(ByteArrayInputStream(sink.bytes())).use { it.readAll() }
        val schemas = entries.filterIsInstance<FateLogEntry.Schema>()
        val messages = entries.filterIsInstance<FateLogEntry.Message<*>>()

        assertEquals(listOf("TIMESTAMP", "Battery/Voltage"), schemas.map { it.channelName })
        assertEquals(listOf(1_000L, 12.4, 2_000L), messages.map { it.data })
        assertEquals(listOf(10L, 20L, 30L), messages.map { it.timestamp })
    }

    @Test
    fun `close flushes and closes the sink`() {
        val sink = RecordingSink()
        FateLogWriter(sink).use { writeSample(it) }

        assertEquals(1, sink.flushed)
        assertEquals(1, sink.closed)
    }

    @Test
    fun `MultiSink gives every sink the same entries`() {
        val a = RecordingSink()
        val b = RecordingSink()
        FateLogWriter(MultiSink(a, b)).use { writeSample(it) }

        assertContentEquals(a.bytes(), b.bytes())
        assertEquals(1, b.closed)
    }

    @Test
    fun `MultiSink writes a file and a live sink at once`() {
        val file = ByteArrayOutputStream()
        val live = RecordingSink()
        FateLogWriter(MultiSink(StreamSink(file), live)).use { writeSample(it) }

        assertContentEquals(file.toByteArray(), live.bytes())
    }
}
