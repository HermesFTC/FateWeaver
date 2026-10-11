package gay.zharel.fateweaver.log

import java.io.OutputStream

/**
 * A destination for the bytes a [FateLogWriter] produces.
 *
 * The three entry kinds are separate methods rather than one `write(ByteArray)` because a sink that
 * serves late-joining readers has to replay the header and every schema entry before it can send a
 * message entry. Channel indices are positional, so a reader that missed a schema entry cannot
 * decode anything that references it. A sink that doesn't care, like [StreamSink], simply treats
 * all three the same.
 *
 * Every array handed to these methods is one complete entry, exactly sized, ready to go on the
 * wire as-is.
 *
 * Implementations must tolerate calls from arbitrary threads.
 */
interface FateSink : AutoCloseable {
    /**
     * The 4-byte magic and version that open a log.
     *
     * A second call means a new log has started on the same sink, which is what happens when a
     * fresh OpMode begins while a live reader is still attached.
     */
    fun header(bytes: ByteArray)

    /** A complete schema entry (kind 0), declaring the channel at the next index. */
    fun schemaEntry(bytes: ByteArray)

    /** A complete message entry (kind 1), carrying one value for an already declared channel. */
    fun messageEntry(bytes: ByteArray)

    /** Pushes anything buffered onward. */
    fun flush()
}

/**
 * Writes every entry straight through to [stream], making no distinction between the entry kinds.
 *
 * This is the sink behind ordinary file logging, and the one [FateLogWriter]'s [OutputStream]
 * constructor wraps.
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
 * Fans every entry out to [sinks], in order.
 *
 * This is what lets one [FateLogWriter] record to a file and broadcast to live readers at the same
 * time without either knowing about the other.
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
