package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.FateSchema
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.reflect.KClass

/** Magic bytes that identify a FateWeaver log file. */
const val MAGIC = "RR"

/** Version number of the FateWeaver log file format. */
const val VERSION: Short = 1

/**
 * A log channel represents a named stream of typed data within a FateWeaver log.
 *
 * Each channel has a unique name and is associated with a specific schema that defines
 * how objects of type [T] are serialized. Channels provide a way to organize and
 * separate different types of log data within the same log file.
 *
 * @param T The type of objects that can be written to this channel
 */
interface LogChannel<T> {
    /** The unique name of this channel within the log. */
    val name: String

    /** The schema used to serialize objects of type [T] for this channel. */
    val schema: FateSchema<T>

    /**
     * Writes an object to this channel.
     *
     * @param obj The object to write to the channel
     */
    fun put(obj: T)
}

/**
 * A writer for FateWeaver log files that supports multiple typed channels.
 *
 * FateLogWriter provides a high-performance binary logging format with the following features:
 * - **Type Safety**: Each channel is strongly typed with compile-time guarantees
 * - **Schema Evolution**: Schemas are embedded in the log file for compatibility
 * - **Multiple Channels**: Different data types can be logged to separate named channels
 * - **Efficient Encoding**: Binary format optimized for size and speed
 * - **Auto-Schema Detection**: Can automatically infer schemas from class types
 *
 * ## File Format
 *
 * Each log file begins with a header containing magic bytes and version information,
 * followed by a series of entries. There are two types of entries:
 *
 * 1. **Schema Entries** (type 0): Define the structure of a channel
 * 2. **Message Entries** (type 1): Contain the actual logged data
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Create a log writer
 * FateLogWriter.create("mylog.fate").use { writer ->
 *     // Create typed channels
 *     val userChannel = writer.createChannel("users", User::class)
 *     val eventChannel = writer.createChannel("events", Event::class)
 *
 *     // Write data
 *     userChannel.put(User("alice", 25))
 *     eventChannel.put(Event("login", System.currentTimeMillis()))
 * }
 * ```
 *
 * @param stream The output stream to write the log data to
 * @constructor Creates a new FateLogWriter that writes to the specified stream
 */
class FateLogWriter(val stream: OutputStream) : AutoCloseable {
    init {
        val headerBuffer = ByteBuffer.allocate(4)
            .put(MAGIC.toByteArray(Charsets.UTF_8))
            .putShort(VERSION)
        headerBuffer.flip()
        stream.write(headerBuffer)
    }

    private val channels = mutableListOf<WriterChannel<*>>()

    /**
     * Adds a channel to the log and immediately writes its schema definition.
     *
     * This method registers the channel with the log writer and writes a schema entry
     * to the log file. The schema entry contains the channel name and the complete
     * schema definition for the channel's data type.
     *
     * @param T The type of objects that will be written to this channel
     * @param channel The channel to add to this log writer
     * @return The same channel that was passed in, for method chaining
     * @throws IllegalArgumentException if a channel with the same name already exists
     * @throws IllegalArgumentException if the channel belongs to a different log writer
     */
    fun <T> addChannel(channel: WriterChannel<T>): WriterChannel<T> {
        // Check for duplicate names
        require(channels.none { it.name == channel.name }) {
            "Channel with name '${channel.name}' already exists"
        }

        // this uses referential equality (as equals isn't overridden) because we want to make sure
        //that we are only adding channels that belong to this log writer
        require(channel.writer == this) {
            "Channel belongs to a different log writer"
        }

        channels.add(channel)
        // Write schema entry immediately

        val chBytes = channel.name.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + chBytes.size + channel.schema.schemaSize)
        buffer.putInt(0) // schema entry
        buffer.putInt(chBytes.size)
        buffer.put(chBytes)
        channel.schema.encodeSchema(buffer)
        require(!buffer.hasRemaining()) {
            "encoded schema does not match reported size: ${buffer.remaining()} bytes remaining"
        }
        buffer.flip()
        this.stream.write(buffer)

        return channel
    }

    /**
     * Adds a channel to the log, creating a [WriterChannel] from the given [LogChannel] if necessary.
     *
     * If the provided channel is already a [WriterChannel] belonging to this writer, it is added directly.
     * Otherwise, a new [WriterChannel] is created with the same name and schema.
     *
     * @param T The type of objects that will be written to this channel
     * @param channel The channel to add
     * @return A [WriterChannel] bound to this writer
     */
    fun <T> addChannel(channel: LogChannel<T>) = boundChannel(channel)

    /**
     * Creates a new log channel with the specified name and schema, and adds it to the log.
     *
     * @param T The type of objects that will be written to this channel
     * @param name The unique name for the channel
     * @param schema The schema defining how objects of type [T] will be serialized
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists
     */
    fun <T> createChannel(name: String, schema: FateSchema<T>) =
        addChannel(WriterChannel(name, schema))

    /**
     * Creates a new log channel with the specified name and automatically inferred schema.
     *
     * The schema is automatically derived from the provided Java class using reflection.
     * This is convenient for simple data classes but may not work for complex types.
     *
     * @param T The type of objects that will be written to this channel
     * @param name The unique name for the channel
     * @param cls The Java class to derive the schema from
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists
     */
    fun <T : Any> createChannel(name: String, cls: Class<T>) =
        createChannel(name, FateSchema.schemaOfClass(cls))

    /**
     * Creates a new log channel with the specified name and automatically inferred schema.
     *
     * The schema is automatically derived from the provided Kotlin class using reflection.
     * This is convenient for simple data classes but may not work for complex types.
     *
     * @param T The type of objects that will be written to this channel
     * @param name The unique name for the channel
     * @param cls The Kotlin class to derive the schema from
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists
     */
    fun <T : Any> createChannel(name: String, cls: KClass<T>) =
        createChannel(name, FateSchema.schemaOfClass(cls.java))

    /**
     * Writes an object to the specified channel.
     *
     * If the channel is not yet registered with this writer, it will be automatically
     * added before writing the object. The object is serialized using the channel's
     * schema and written as a message entry to the log file.
     *
     * @param T The type of the object being written
     * @param channel The channel to write to
     * @param obj The object to write
     */
    fun <T> write(channel: LogChannel<T>, obj: T) {
        var index = channels.indexOf(boundChannel(channel))

        if (index < 0) {
            addChannel(channel)
            index = channels.lastIndex
        }

        val objSize = channel.schema.objSize(obj)
        val buffer = ByteBuffer.allocate(8 + objSize)
        buffer.putInt(1) // message entry
        buffer.putInt(index) // channel index
        channel.schema.encodeObject(buffer, obj)
        require(!buffer.hasRemaining()) {
            "encoded object does not match reported size: ${buffer.remaining()} bytes remaining"
        }
        buffer.flip()
        this.stream.write(buffer)
    }

    /**
     * Writes an object to a channel identified by name.
     *
     * This method provides a dynamic way to write objects when the channel type is not
     * known at compile time. If a channel with the specified name exists, the object
     * is written to that channel. If no such channel exists, a new channel is created
     * automatically using schema inference from the object's class.
     *
     * **Warning**: This method uses type erasure and runtime casting, so type safety
     * is not guaranteed. Use the typed [write] method when possible.
     *
     * @param channelName The name of the channel to write to
     * @param obj The object to write
     */
    fun write(channelName: String, obj: Any) {
        // Find existing channel by name
        val existingChannel = channels.find { it.name == channelName }

        if (existingChannel != null) {
            @Suppress("UNCHECKED_CAST")
            write(existingChannel as WriterChannel<Any>, obj)
        } else {
            // Create new channel on-demand
            val schema = FateSchema.schemaOfClass(obj.javaClass)

            @Suppress("UNCHECKED_CAST")
            val newChannel = WriterChannel(channelName, schema)
            addChannel(newChannel)
            write(newChannel, obj)
        }
    }

    /**
     * Closes the log writer and flushes any remaining data to the underlying stream.
     *
     * After calling this method, no further write operations should be performed.
     * The underlying stream is also closed.
     */
    override fun close() {
        stream.flush()
        stream.close()
    }

    /**
     * A log channel implementation that is bound to a specific [FateLogWriter].
     *
     * WriterChannels are created by [FateLogWriter] and provide a convenient way to write
     * typed objects to the log. Each channel maintains a reference to its parent writer
     * and delegates write operations to it.
     *
     * @param T The type of objects that can be written to this channel
     * @param name The unique name of this channel
     * @param schema The schema used to serialize objects for this channel
     */
    inner class WriterChannel<T> internal constructor(
        override val name: String,
        override val schema: FateSchema<T>
    ) : LogChannel<T> {
        /** Reference to the [FateLogWriter] that owns this channel. */
        val writer: FateLogWriter get() = this@FateLogWriter

        /**
         * Writes an object to this channel.
         *
         * This is equivalent to calling [FateLogWriter.write] with this channel.
         *
         * @param obj The object to write to the channel
         */
        override fun put(obj: T) = write(this, obj)

        /**
         * Writes an object to this channel.
         *
         * This is an alias for [put] that may be more intuitive in some contexts.
         *
         * @param obj The object to write to the channel
         */
        fun write(obj: T) = put(obj)

        override fun toString() = "Channel($name, $schema, $writer)"
    }

    /**
     * Returns the [WriterChannel] associated with [channel] or creates a new one if it doesn't exist.
     *
     * This method ensures that external [LogChannel] instances are properly bound to this writer.
     * If a channel with the same name already exists, it is returned. Otherwise, a new
     * [WriterChannel] is created and added to this writer.
     *
     * @param T The type of the channel
     * @param channel The channel to bind
     * @return A [WriterChannel] bound to this writer
     */
    private fun <T> boundChannel(channel: LogChannel<T>): WriterChannel<T> {
        val found = channels.find { it.name == channel.name }

        if (found != null) {
            @Suppress("UNCHECKED_CAST")
            return found as WriterChannel<T>
        }

        return addChannel(WriterChannel(channel.name, channel.schema))
    }

    override fun toString() = "LogWriter($stream)"

    /**
     * Checks if a channel with the same name as the provided channel exists in this writer.
     *
     * @param channel The channel to check for
     * @return `true` if a channel with the same name exists, `false` otherwise
     */
    operator fun contains(channel: LogChannel<*>) = channels.any { it.name == channel.name }

    companion object Companion {
        /**
         * Creates a FateLogWriter for the given file.
         *
         * This is a convenience factory method that creates a [FileOutputStream] for the
         * specified file and wraps it in a [FateLogWriter]. The file will be created if
         * it doesn't exist, or overwritten if it does.
         *
         * @param file The file to write the log to
         * @return A new [FateLogWriter] instance
         * @throws java.io.IOException if the file cannot be created or opened for writing
         */
        fun create(file: File): FateLogWriter {
            return FateLogWriter(FileOutputStream(file))
        }

        /**
         * Creates a FateLogWriter for the given file path.
         *
         * This is a convenience factory method that creates a [File] from the path string
         * and then calls [create(File)].
         *
         * @param filePath The path to the file to write the log to
         * @return A new [FateLogWriter] instance
         * @throws java.io.IOException if the file cannot be created or opened for writing
         */
        fun create(filePath: String): FateLogWriter {
            return create(File(filePath))
        }
    }
}

/**
 * Extension function to write a [ByteBuffer] to an [OutputStream].
 *
 * This is an internal utility function used by [FateLogWriter] to write binary data
 * efficiently.
 * It requires that the buffer has a backing array for direct access.
 *
 * @receiver The output stream to write to
 * @param buffer The byte buffer to write
 * @throws IllegalStateException if the buffer doesn't have a backing array
 */
internal fun OutputStream.write(buffer: ByteBuffer) {
    if (!buffer.hasArray()) {
        error { "LogWriter only supports direct byte buffer access" }
    }

    write(buffer.array())
}