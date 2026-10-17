package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.FateSchema
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.TimeSource

/** Magic bytes that identify a FateWeaver log file. */
const val MAGIC = "RR"

/**
 * Version number written into the header of new FateWeaver logs.
 */
const val VERSION: Short = 3

/** Header versions this library can read. */
val SUPPORTED_VERSIONS: Set<Short> = setOf(1, 2, 3)

/** First version whose message entries carry a timestamp. */
internal const val TIMESTAMPED_VERSION: Short = 3

/** Entry kind (4), channel index (4) and timestamp (8) ahead of every message payload. */
private const val MESSAGE_PREFIX_SIZE = 16

/**
 * A named stream of typed data within a FateWeaver log.
 *
 * A channel's name is unique within its log, and its [schema] fixes how values of type [T] are
 * encoded. Use one channel per thing you want to plot.
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
 * Writes a FateWeaver log to a [FateSink]. A log holds any number of typed channels.
 *
 * Each channel has a type, so the compiler rejects a value that doesn't match its schema. Schemas
 * are written into the log, so a reader can decode the file without the classes that produced it.
 * Most types need no hand-written schema, because [createChannel] infers one from a class.
 *
 * ## File format
 *
 * The file starts with a 4-byte header of magic bytes and a version. After that comes a run of
 * entries. A schema entry (kind 0) defines a channel. A message entry (kind 1) carries one value
 * for a channel that is already defined. Since version 3, every message entry also carries the time
 * it was logged, in nanoseconds, so readers can line up channels without a timestamp channel.
 *
 * ## Timestamps
 *
 * [write] without a timestamp stamps the entry with the time elapsed on [timeSource] since this
 * writer was created. Only differences between stamps mean anything. A writer fed by a
 * [gay.zharel.fateweaver.telemetry.FateLogBackend] gets its stamps from the registry instead.
 *
 * ## Threading
 *
 * A writer can be shared between threads. The sink receives entries one at a time, and a channel's
 * schema entry always comes before its first message.
 *
 * ## Example
 *
 * ```kotlin
 * FateLogWriter.create("mylog.fate").use { writer ->
 *     val userChannel = writer.createChannel("users", User::class)
 *     val eventChannel = writer.createChannel("events", Event::class)
 *
 *     userChannel.put(User("alice", 25))
 *     eventChannel.put(Event("login", System.currentTimeMillis()))
 * }
 * ```
 *
 * @param sink Where the log bytes go
 * @param timeSource Stamps entries written without a timestamp. Defaults to [TimeSource.Monotonic].
 */
class FateLogWriter @JvmOverloads constructor(
    val sink: FateSink,
    val timeSource: TimeSource = TimeSource.Monotonic
) : AutoCloseable {
    /** Creates a writer that writes to [stream], as in file logging. */
    @JvmOverloads
    constructor(stream: OutputStream, timeSource: TimeSource = TimeSource.Monotonic) :
        this(StreamSink(stream), timeSource)

    private val origin = timeSource.markNow()

    /** Nanoseconds elapsed on [timeSource] since this writer was created. */
    fun timestamp(): Long = origin.elapsedNow().inWholeNanoseconds

    init {
        val headerBuffer = ByteBuffer.allocate(4)
            .put(MAGIC.toByteArray(Charsets.UTF_8))
            .putShort(VERSION)
        sink.header(headerBuffer.array())
    }

    /** Serializes every call into [sink] and every channel registration. */
    private val lock = Any()

    /**
     * Appended to only under [lock]. It is copy-on-write so the write path can read it without the
     * lock. Channels are added rarely and written to constantly.
     */
    private val channels = CopyOnWriteArrayList<WriterChannel<*>>()

    /**
     * Registers [channel] and writes its schema entry to the sink right away.
     *
     * The entry holds the channel name and the full schema for its type.
     *
     * @param T The type of objects written to this channel
     * @param channel The channel to add
     * @return [channel]
     * @throws IllegalArgumentException if a channel with the same name already exists
     * @throws IllegalArgumentException if the channel belongs to a different log writer
     */
    fun <T> addChannel(channel: WriterChannel<T>): WriterChannel<T> = synchronized(lock) {
        require(channels.none { it.name == channel.name }) {
            "Channel with name '${channel.name}' already exists"
        }

        // WriterChannel doesn't override equals, so == is identity. This rejects channels that
        // belong to another writer.
        require(channel.writer == this) {
            "Channel belongs to a different log writer"
        }

        // Encode before registering. If the schema fails to encode, no half-added channel is left
        // behind to shift the index of every later channel.
        val chBytes = channel.name.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + chBytes.size + channel.schema.schemaSize)
        buffer.putInt(0) // schema entry
        buffer.putInt(chBytes.size)
        buffer.put(chBytes)
        channel.schema.encodeSchema(buffer)
        require(!buffer.hasRemaining()) {
            "encoded schema does not match reported size: ${buffer.remaining()} bytes remaining"
        }

        channel.index = channels.size
        channels.add(channel)
        sink.schemaEntry(buffer.array())

        channel
    }

    /**
     * Returns this writer's channel with the same name as [channel].
     *
     * If there is none, this registers a new [WriterChannel] with [channel]'s name and schema and
     * writes its schema entry.
     *
     * @param T The type of objects written to this channel
     * @param channel The channel to look up or add
     * @return A [WriterChannel] bound to this writer
     */
    fun <T> addChannel(channel: LogChannel<T>) = boundChannel(channel)

    /**
     * Creates a channel with the given name and schema, and adds it to the log.
     *
     * @param T The type of objects written to this channel
     * @param name The unique name for the channel
     * @param schema The schema that encodes objects of type [T]
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists
     */
    fun <T> createChannel(name: String, schema: FateSchema<T>) =
        addChannel(WriterChannel(name, schema))

    /**
     * Creates a channel with the given name and a schema inferred from [cls] by reflection.
     *
     * @param T The type of objects written to this channel
     * @param name The unique name for the channel
     * @param cls The class to infer the schema from
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists
     */
    fun <T : Any> createChannel(name: String, cls: Class<T>) =
        createChannel(name, FateSchema.schemaOfClass(cls))

    /**
     * Creates a channel with the given name and a schema inferred from [cls] by reflection.
     *
     * Type arguments are lost here. Use the reified overload for a type like `Map<String, Double>`.
     *
     * @param T The type of objects written to this channel
     * @param name The unique name for the channel
     * @param cls The class to infer the schema from
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists
     */
    fun <T : Any> createChannel(name: String, cls: KClass<T>) =
        createChannel(name, FateSchema.schemaOfClass(cls.java))

    /**
     * Creates a channel whose schema is inferred from the reified type [T], type arguments
     * included. Prefer this to the [KClass] overload, which loses them.
     *
     * ```kotlin
     * val powers = writer.createChannel<Map<String, Double>>("Motors/Powers")
     * powers.put(mapOf("fl" to 0.5))
     * ```
     *
     * @param T The type of objects written to this channel
     * @param name The unique name for the channel
     * @return A new [WriterChannel] bound to this writer
     * @throws IllegalArgumentException if a channel with the same name already exists, or if [T]
     *   cannot be schematized
     * @see FateSchema.schemaOfType
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> createChannel(name: String): WriterChannel<T> =
        createChannel(name, FateSchema.schemaOfType(typeOf<T>()) as FateSchema<T>)

    /**
     * Writes an object to the specified channel, stamped with the current [timestamp].
     *
     * If this writer has no channel with that name yet, it adds one first.
     *
     * @param T The type of the object being written
     * @param channel The channel to write to
     * @param obj The object to write
     */
    fun <T> write(channel: LogChannel<T>, obj: T) = write(channel, obj, timestamp())

    /**
     * Writes an object to the specified channel, stamped with [timestamp].
     *
     * Use this when the value was produced earlier than it is written, or when several writers must
     * share one timestamp. Take it from the same clock for every write to a log, such as this
     * writer's [timestamp], because readers compare them.
     *
     * @param T The type of the object being written
     * @param channel The channel to write to
     * @param obj The object to write
     * @param timestamp When the value was produced, in nanoseconds
     */
    fun <T> write(channel: LogChannel<T>, obj: T, timestamp: Long) {
        val index = boundChannel(channel).index

        // Encode outside the lock. It is the expensive part and touches no shared state. Only the
        // hand-off to the sink needs ordering.
        val objSize = channel.schema.objSize(obj)
        val buffer = ByteBuffer.allocate(MESSAGE_PREFIX_SIZE + objSize)
        buffer.putInt(1) // message entry
        buffer.putInt(index) // channel index
        buffer.putLong(timestamp)
        channel.schema.encodeObject(buffer, obj)
        require(!buffer.hasRemaining()) {
            "encoded object does not match reported size: ${buffer.remaining()} bytes remaining"
        }
        synchronized(lock) { sink.messageEntry(buffer.array()) }
    }

    /**
     * The parameterized type each by-name channel was created from.
     *
     * A channel's schema is fixed at creation. Without this, writing a differently parameterized
     * value later would encode against the wrong schema silently. Only parameterized types are
     * recorded. Other channels take their schema from the runtime class, which can't disagree
     * with itself.
     */
    private val declaredTypes = ConcurrentHashMap<String, KType>()

    /**
     * Writes an object to a channel identified by name, inferring the schema from the object's
     * runtime class.
     *
     * Use this when the channel type isn't known at compile time. If the channel doesn't exist, this
     * creates it from the object's class first.
     *
     * Java sees this as `write`. It is named `writeErased` in Kotlin so it doesn't clash with the
     * reified `write` extension, which Kotlin callers should use because it also recovers type
     * arguments. A runtime class has no type arguments, so this rejects a [Map] or [Collection].
     * Create the channel with an explicit schema instead, for example
     * `createChannel("k", new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE))`.
     *
     * The cast to the existing channel's type is unchecked. Writing the wrong type to an existing
     * channel fails at encode time, not compile time. Prefer the typed [write] where you can.
     *
     * @param channelName The name of the channel to write to
     * @param obj The object to write
     */
    @JvmName("write")
    fun writeErased(channelName: String, obj: Any) = writeResolved(channelName, obj, null)

    /**
     * Writes an object to a channel identified by name, inferring the schema from [type] instead of
     * from [obj]'s erased runtime class.
     *
     * The reified `write` extension calls this. Call it directly if you already have a [KType].
     *
     * [type] only matters when it has type arguments, which the runtime class can't supply.
     * Otherwise this uses [FateSchema.schemaOfClass] on `obj.javaClass`. A value whose static type
     * is a supertype such as `Any`, `Number` or an interface therefore still gets its concrete
     * schema.
     *
     * @param channelName The name of the channel to write to
     * @param obj The object to write
     * @param type The static type of [obj]
     * @throws IllegalArgumentException if the channel already exists and was created from a
     *   different parameterized type
     */
    fun write(channelName: String, obj: Any, type: KType) =
        writeResolved(channelName, obj, type.takeIf { it.arguments.isNotEmpty() })

    /**
     * Shared body of [writeErased] and [write]. [parameterized] is a type with type arguments, or
     * `null` to infer from the runtime class.
     */
    private fun writeResolved(channelName: String, obj: Any, parameterized: KType?) {
        // Look up and create under one lock, so two threads writing a new name can't both create it.
        val channel = synchronized(lock) {
            val existingChannel = channels.find { it.name == channelName }

            if (existingChannel != null) {
                checkDeclaredType(channelName, parameterized)

                @Suppress("UNCHECKED_CAST")
                existingChannel as WriterChannel<Any>
            } else {
                @Suppress("UNCHECKED_CAST")
                val schema = if (parameterized != null) {
                    FateSchema.schemaOfType(parameterized)
                } else {
                    FateSchema.schemaOfClass(obj.javaClass)
                } as FateSchema<Any>

                val newChannel = addChannel(WriterChannel(channelName, schema))
                if (parameterized != null) declaredTypes[channelName] = parameterized
                newChannel
            }
        }

        write(channel, obj)
    }

    /**
     * Throws if [channelName] was created from a parameterized type that differs from [type].
     *
     * It does nothing when either side is unparameterized. A channel built from a runtime class has
     * no recorded type to contradict. An unparameterized write to a parameterized channel is either
     * a subtype that encodes fine or a mismatch the element schemas throw on anyway.
     */
    private fun checkDeclaredType(channelName: String, type: KType?) {
        val declared = declaredTypes[channelName] ?: return
        if (type == null || type == declared) return

        throw IllegalArgumentException(
            "Channel '$channelName' was created for '$declared', but this write passed '$type'. " +
                    "A channel's schema is fixed when the channel is created, so every write to " +
                    "'$channelName' must have the same type. Use a different channel name, or " +
                    "create the channel up front with an explicit schema."
        )
    }

    /**
     * Flushes and closes the sink. Don't write after this.
     */
    override fun close() {
        sink.flush()
        sink.close()
    }

    /**
     * A [LogChannel] bound to one [FateLogWriter]. Writes to it go to that writer.
     *
     * @param T The type of objects written to this channel
     * @param name The unique name of this channel
     * @param schema The schema that encodes objects for this channel
     */
    inner class WriterChannel<T> internal constructor(
        override val name: String,
        override val schema: FateSchema<T>
    ) : LogChannel<T> {
        /** The [FateLogWriter] that owns this channel. */
        val writer: FateLogWriter get() = this@FateLogWriter

        /**
         * Position in the writer's channel list, which is the index messages carry on the wire.
         * It is `-1` until the channel is added. The writer sets it once, under its lock, before any
         * other thread can see the channel.
         */
        internal var index: Int = -1

        /**
         * Writes an object to this channel, the same as [FateLogWriter.write] with this channel.
         *
         * @param obj The object to write to the channel
         */
        override fun put(obj: T) = write(this, obj)

        /**
         * Alias for [put].
         *
         * @param obj The object to write to the channel
         */
        fun write(obj: T) = put(obj)

        override fun toString() = "Channel($name, $schema, $writer)"
    }

    /**
     * Returns this writer's [WriterChannel] with the same name as [channel], adding one with
     * [channel]'s schema if none exists.
     *
     * @param T The type of the channel
     * @param channel The channel to bind
     * @return A [WriterChannel] bound to this writer
     */
    private fun <T> boundChannel(channel: LogChannel<T>): WriterChannel<T> {
        // Fast path for a channel this writer made and already registered. No name scan, no lock.
        if (channel is WriterChannel<*> && channel.writer === this && channel.index >= 0) {
            @Suppress("UNCHECKED_CAST")
            return channel as WriterChannel<T>
        }

        return synchronized(lock) {
            val found = channels.find { it.name == channel.name }

            if (found != null) {
                @Suppress("UNCHECKED_CAST")
                found as WriterChannel<T>
            } else {
                addChannel(WriterChannel(channel.name, channel.schema))
            }
        }
    }

    override fun toString() = "LogWriter($sink)"

    /**
     * Returns whether this writer has a channel with the same name as [channel].
     */
    operator fun contains(channel: LogChannel<*>) = channels.any { it.name == channel.name }

    companion object {
        /**
         * Creates a writer that logs to [file]. The file is created if it doesn't exist and
         * overwritten if it does.
         *
         * @param file The file to write the log to
         * @return A new [FateLogWriter] instance
         * @throws java.io.IOException if the file cannot be created or opened for writing
         */
        fun create(file: File): FateLogWriter {
            return FateLogWriter(FileOutputStream(file))
        }

        /**
         * Creates a writer that logs to the file at [filePath], as [create] does for a [File].
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
 * Writes an object to a channel identified by name, inferring the schema from [T], type arguments
 * included.
 *
 * This is the Kotlin form of [FateLogWriter.writeErased], and the only way to write a parameterized
 * value by channel name:
 *
 * ```kotlin
 * writer.write("Motors/Powers", mapOf("fl" to 0.5))   // Map<String, Double>
 * writer.write("Path/Points", listOf(a, b, c))        // List<Pose>
 * ```
 *
 * This is an extension because a member would erase to `write(String, Object)`, the same JVM
 * signature `writeErased` is exported under. Giving `writeErased` the Kotlin name `write` would
 * fix the clash, but then it would win overload resolution and this version would never run.
 *
 * @see FateLogWriter.write
 */
@JvmSynthetic
inline fun <reified T : Any> FateLogWriter.write(channelName: String, obj: T) =
    write(channelName, obj, typeOf<T>())