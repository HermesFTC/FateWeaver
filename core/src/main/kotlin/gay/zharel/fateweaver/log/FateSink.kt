package gay.zharel.fateweaver.log

import java.io.OutputStream

/**
 * Receives the bytes a [FateLogWriter] produces.
 *
 * The three entry kinds are separate methods because a sink that serves late-joining readers must
 * replay the header and every schema entry before it sends a message entry. Channel indices are
 * positional, so a reader that missed a schema entry can't decode messages for that channel. A sink
 * that doesn't care, like [StreamSink], treats all three the same.
 *
 * Each array passed in is one complete entry, exactly sized, ready to send as is.
 *
 * Implementations must tolerate calls from any thread.
 */
interface FateSink : AutoCloseable {
    /**
     * The 4-byte magic and version that open a log.
     *
     * A second call means a new log has started on the same sink. That happens when a new OpMode
     * starts while a live reader is still attached.
     */
    fun header(bytes: ByteArray)

    /** A complete schema entry (kind 0). It declares the channel at the next index. */
    fun schemaEntry(bytes: ByteArray)

    /** A complete message entry (kind 1). It carries one value for an already declared channel. */
    fun messageEntry(bytes: ByteArray)

    /** Pushes anything buffered onward. */
    fun flush()
}

/**
 * Writes every entry to [stream] as is, whatever its kind.
 *
 * [FateLogWriter]'s [OutputStream] constructor wraps a stream in one of these.
 */
class StreamSink(val stream: OutputStream) : FateSink {
    override fun header(bytes: ByteArray) = stream.write(bytes)

    override fun schemaEntry(bytes: ByteArray) = stream.write(bytes)

    override fun messageEntry(bytes: ByteArray) = stream.write(bytes)

    override fun flush() = stream.flush()

    override fun close() {
        stream.flush()
        stream.close()
    }

    override fun toString() = "StreamSink($stream)"
}

/**
 * Sends every entry to each of [sinks], in order.
 *
 * Use it to record to a file and broadcast to live readers from one [FateLogWriter].
 */
class MultiSink(private val sinks: List<FateSink>) : FateSink {
    constructor(vararg sinks: FateSink) : this(sinks.toList())

    override fun header(bytes: ByteArray) = sinks.forEach { it.header(bytes) }

    override fun schemaEntry(bytes: ByteArray) = sinks.forEach { it.schemaEntry(bytes) }

    override fun messageEntry(bytes: ByteArray) = sinks.forEach { it.messageEntry(bytes) }

    override fun flush() = sinks.forEach { it.flush() }

    override fun close() = sinks.forEach { it.close() }

    override fun toString() = "MultiSink($sinks)"
}
