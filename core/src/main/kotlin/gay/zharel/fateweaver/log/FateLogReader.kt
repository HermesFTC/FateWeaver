package gay.zharel.fateweaver.log

import gay.zharel.fateweaver.schemas.ArraySchema
import gay.zharel.fateweaver.schemas.BooleanSchema
import gay.zharel.fateweaver.schemas.CustomStructSchema
import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.DynamicEnumSchema
import gay.zharel.fateweaver.schemas.EnumSchema
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.schemas.IntSchema
import gay.zharel.fateweaver.schemas.LongSchema
import gay.zharel.fateweaver.schemas.MapSchema
import gay.zharel.fateweaver.schemas.JavaOptionalSchema
import gay.zharel.fateweaver.schemas.OptionalSchema
import gay.zharel.fateweaver.schemas.PrimitiveOptionalSchema
import gay.zharel.fateweaver.schemas.PrimitiveArraySchema
import gay.zharel.fateweaver.schemas.ReflectedClassSchema
import gay.zharel.fateweaver.schemas.StringSchema
import gay.zharel.fateweaver.schemas.TranslatedSchema
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.collections.iterator

/**
 * Represents a log entry read from a log file.
 */
sealed class FateLogEntry {
    /**
     * Schema definition entry for a channel.
     */
    data class Schema(
        val channelName: String,
        val schema: FateSchema<*>
    ) : FateLogEntry()

    /**
     * Message entry containing data for a channel.
     *
     * [timestamp] is when the value was logged, in nanoseconds on the logging clock, or `null` for
     * logs older than version 3, which did not record one.
     */
    data class Message<T>(
        val channelIndex: Int,
        val channelName: String,
        val data: T,
        val timestamp: Long? = null
    ) : FateLogEntry()
}

/**
 * Reads log files created by [FateLogWriter].
 */
class FateLogReader(private val stream: InputStream) : AutoCloseable, Iterator<FateLogEntry> {

    private val channels = mutableListOf<ChannelInfo>()
    private var headerRead = false
    private var version: Short = VERSION
    private var nextEntry: FateLogEntry? = null
    private var hasNextCached = false

    data class ChannelInfo(
        val name: String,
        val schema: FateSchema<*>
    )

    init {
        readHeader()
    }

    private fun readHeader() {
        val headerBytes = ByteArray(4)
        val bytesRead = stream.read(headerBytes)
        if (bytesRead != 4) {
            throw IllegalArgumentException("Invalid log file: could not read header")
        }

        val magic = String(headerBytes, 0, 2, Charsets.UTF_8)
        if (magic != MAGIC) {
            throw IllegalArgumentException("Invalid log file: wrong magic bytes. Expected '$MAGIC', got '$magic'")
        }

        version = ByteBuffer.wrap(headerBytes, 2, 2).short
        if (version !in SUPPORTED_VERSIONS) {
            throw IllegalArgumentException(
                "Unsupported log version: $version. Supported: ${SUPPORTED_VERSIONS.sorted().joinToString(", ")}"
            )
        }

        headerRead = true
    }

    override fun hasNext(): Boolean {
        if (hasNextCached) {
            return nextEntry != null
        }

        nextEntry = readNextEntry()
        hasNextCached = true
        return nextEntry != null
    }

    override fun next(): FateLogEntry {
        if (!hasNext()) {
            throw NoSuchElementException("No more log entries")
        }

        val entry = nextEntry!!
        nextEntry = null
        hasNextCached = false
        return entry
    }

    private fun readNextEntry(): FateLogEntry? {
        try {
            // Read entry type (4 bytes)
            val entryTypeBytes = ByteArray(4)
            val bytesRead = stream.read(entryTypeBytes)
            if (bytesRead != 4) {
                return null // End of file
            }

            return when (val entryType = ByteBuffer.wrap(entryTypeBytes).int) {
                0 -> readSchemaEntry()
                1 -> readMessageEntry()
                else -> throw IllegalArgumentException("Unknown entry type: $entryType")
            }
        } catch (e: IllegalArgumentException) {
            // A malformed entry is not the same as a truncated one: entries are not
            // length-prefixed, so everything after it would be misread. Surface it.
            throw e
        } catch (_: Exception) {
            return null // End of file or error
        }
    }

    private fun readSchemaEntry(): FateLogEntry.Schema {
        // Read channel name length
        val nameLengthBytes = ByteArray(4)
        stream.read(nameLengthBytes)
        val nameLength = ByteBuffer.wrap(nameLengthBytes).int

        // Read channel name
        val nameBytes = ByteArray(nameLength)
        stream.read(nameBytes)
        val channelName = String(nameBytes, Charsets.UTF_8)

        // Read schema
        val schema = readSchema()

        // Add to channels list
        val channelInfo = ChannelInfo(channelName, schema)
        channels.add(channelInfo)

        return FateLogEntry.Schema(channelName, schema)
    }

    private fun readSchema(): FateSchema<*> {
        // Read schema tag
        val tagBytes = ByteArray(4)
        stream.read(tagBytes)
        return when (val tag = ByteBuffer.wrap(tagBytes).int) {
            FateSchema.TypeRegistry.CUSTOM.value -> readStructSchema()
            FateSchema.TypeRegistry.INT.value -> IntSchema
            FateSchema.TypeRegistry.LONG.value -> LongSchema
            FateSchema.TypeRegistry.DOUBLE.value -> DoubleSchema
            FateSchema.TypeRegistry.STRING.value -> StringSchema
            FateSchema.TypeRegistry.BOOLEAN.value -> BooleanSchema
            FateSchema.TypeRegistry.ENUM.value -> readEnumSchema()
            FateSchema.TypeRegistry.ARRAY.value -> readArraySchema()
            FateSchema.TypeRegistry.MAP.value -> requireVersion2(tag, "MAP") { readMapSchema() }
            FateSchema.TypeRegistry.OPTIONAL.value -> requireVersion2(tag, "OPTIONAL") { readOptionalSchema() }
            else -> throw IllegalArgumentException("Unknown schema tag: $tag")
        }
    }

    /**
     * Rejects a schema tag introduced after the header version claims to support it. A version 1
     * header promises only tags 0-7, so a MAP or OPTIONAL tag there means the stream is misaligned
     * or the header is wrong, and decoding further would produce garbage.
     */
    private fun <T> requireVersion2(tag: Int, name: String, read: () -> T): T {
        if (version < 2) {
            throw IllegalArgumentException(
                "Schema tag $tag ($name) requires log version 2, but the header declares version $version"
            )
        }
        return read()
    }

    private fun readEnumSchema(): DynamicEnumSchema {
        // Read number of enum constants
        val countBytes = ByteArray(4)
        stream.read(countBytes)
        val count = ByteBuffer.wrap(countBytes).int

        val constantNames = mutableListOf<String>()
        repeat(count) {
            // Read constant name length
            val nameLengthBytes = ByteArray(4)
            stream.read(nameLengthBytes)
            val nameLength = ByteBuffer.wrap(nameLengthBytes).int

            // Read constant name
            val nameBytes = ByteArray(nameLength)
            stream.read(nameBytes)
            val constantName = String(nameBytes, Charsets.UTF_8)
            constantNames.add(constantName)
        }

        // Create a dynamic enum schema that can map ordinals to names
        return DynamicEnumSchema(constantNames)
    }

    private fun readArraySchema(): ArraySchema<*> {
        val elementSchema = readSchema()
        return ArraySchema(elementSchema)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readOptionalSchema(): OptionalSchema<Any> =
        OptionalSchema(readSchema() as FateSchema<Any>)

    @Suppress("UNCHECKED_CAST")
    private fun readMapSchema(): MapSchema<Any, Any> {
        // Both calls consume from the same stream, and the writer emits the key schema first,
        // so these must stay as separate ordered statements.
        val keySchema = readSchema() as FateSchema<Any>
        val valueSchema = readSchema() as FateSchema<Any>
        return MapSchema(keySchema, valueSchema)
    }

    private fun readStructSchema(): ReflectedClassSchema<*> {
        // Read number of fields
        val fieldCountBytes = ByteArray(4)
        stream.read(fieldCountBytes)
        val fieldCount = ByteBuffer.wrap(fieldCountBytes).int

        val fields = mutableMapOf<String, FateSchema<*>>()
        repeat(fieldCount) {
            // Read field name length
            val nameLengthBytes = ByteArray(4)
            stream.read(nameLengthBytes)
            val nameLength = ByteBuffer.wrap(nameLengthBytes).int

            // Read field name
            val nameBytes = ByteArray(nameLength)
            stream.read(nameBytes)
            val fieldName = String(nameBytes, Charsets.UTF_8)

            // Read field schema
            val fieldSchema = readSchema()
            fields[fieldName] = fieldSchema
        }

        return ReflectedClassSchema<Any>(fields)
    }

    /** Reads a presence byte, then the value it guards. */
    private fun readOptional(valueSchema: FateSchema<*>): Any? {
        val presence = ByteArray(1)
        stream.read(presence)
        return if (presence[0] == 0.toByte()) null else readObject(valueSchema)
    }

    private fun readMessageEntry(): FateLogEntry.Message<*> {
        // Read channel index
        val indexBytes = ByteArray(4)
        stream.read(indexBytes)
        val channelIndex = ByteBuffer.wrap(indexBytes).int

        if (channelIndex >= channels.size) {
            throw IllegalArgumentException("Invalid channel index: $channelIndex. Only ${channels.size} channels registered.")
        }

        val timestamp = if (version >= TIMESTAMPED_VERSION) {
            val timestampBytes = ByteArray(8)
            stream.read(timestampBytes)
            ByteBuffer.wrap(timestampBytes).long
        } else {
            null
        }

        val channelInfo = channels[channelIndex]
        val data = readObject(channelInfo.schema)

        return FateLogEntry.Message(channelIndex, channelInfo.name, data, timestamp)
    }

    /**
     * Decodes one value.
     *
     * Returns null only for an absent [OptionalSchema] value, so every nullable in the decoded
     * tree corresponds to a nullable in the schema.
     */
    private fun readObject(schema: FateSchema<*>): Any? {
        return when (schema) {
            is IntSchema -> {
                val bytes = ByteArray(4)
                stream.read(bytes)
                ByteBuffer.wrap(bytes).int
            }
            is LongSchema -> {
                val bytes = ByteArray(8)
                stream.read(bytes)
                ByteBuffer.wrap(bytes).long
            }
            is DoubleSchema -> {
                val bytes = ByteArray(8)
                stream.read(bytes)
                ByteBuffer.wrap(bytes).double
            }
            is StringSchema -> {
                // Read string length
                val lengthBytes = ByteArray(4)
                stream.read(lengthBytes)
                val length = ByteBuffer.wrap(lengthBytes).int

                // Read string bytes
                val stringBytes = ByteArray(length)
                stream.read(stringBytes)
                String(stringBytes, Charsets.UTF_8)
            }
            is BooleanSchema -> {
                val bytes = ByteArray(1)
                stream.read(bytes)
                bytes[0] != 0.toByte()
            }
            is DynamicEnumSchema -> {
                val bytes = ByteArray(4)
                stream.read(bytes)
                val ordinal = ByteBuffer.wrap(bytes).int
                // Return the actual enum constant name instead of ordinal
                schema.getConstantName(ordinal)
            }
            is EnumSchema<*> -> {
                val bytes = ByteArray(4)
                stream.read(bytes)
                val ordinal = ByteBuffer.wrap(bytes).int
                // Return ordinal value for legacy EnumSchema
                ordinal
            }
            is ArraySchema<*> -> {
                // Read array length
                val lengthBytes = ByteArray(4)
                stream.read(lengthBytes)
                val length = ByteBuffer.wrap(lengthBytes).int

                // Read array elements
                val elements = mutableListOf<Any?>()
                repeat(length) {
                    elements.add(readObject(schema.elementSchema))
                }
                elements.toTypedArray()
            }
            is PrimitiveArraySchema<*> -> {
                // Same wire format as ArraySchema. The reader builds ArraySchema from the ARRAY
                // tag and never constructs these itself, so this only runs when a caller hands
                // readObject a writer-side schema. Decode to a boxed array either way.
                val lengthBytes = ByteArray(4)
                stream.read(lengthBytes)
                val length = ByteBuffer.wrap(lengthBytes).int

                val elements = mutableListOf<Any?>()
                repeat(length) {
                    elements.add(readObject(schema.elementSchema))
                }
                elements.toTypedArray()
            }
            is OptionalSchema<*> -> readOptional(schema.valueSchema)
            // Optional<T> shares the OPTIONAL tag, so it decodes the same way. The reader always
            // builds OptionalSchema from the tag and only sees these if handed a writer schema.
            is JavaOptionalSchema<*> -> readOptional(schema.valueSchema)
            is PrimitiveOptionalSchema<*> -> readOptional(schema.valueSchema)
            is MapSchema<*, *> -> {
                // Read entry count
                val countBytes = ByteArray(4)
                stream.read(countBytes)
                val count = ByteBuffer.wrap(countBytes).int
                require(count >= 0) { "Invalid map entry count: $count" }

                // Read entries in on-wire order. The pre-sized capacity is capped so a corrupt
                // count cannot allocate its way to an OutOfMemoryError; the loop below fails
                // naturally once the stream runs dry.
                val entries = LinkedHashMap<Any?, Any?>(minOf(count, 1024))
                repeat(count) {
                    val key = readObject(schema.keySchema)
                    val value = readObject(schema.valueSchema)
                    entries[key] = value
                }
                entries
            }
            is ReflectedClassSchema<*> -> {
                val fieldValues = mutableMapOf<String, Any?>()
                for ((fieldName, fieldSchema) in schema.fields) {
                    val fieldValue = readObject(fieldSchema)
                    fieldValues[fieldName] = fieldValue
                }
                // Return a map of field values since we can't reconstruct the actual class
                fieldValues
            }
            is CustomStructSchema<*> -> {
                val components = mutableMapOf<String, Any?>()
                for ((name, componentSchema) in schema.componentNames.zip(schema.componentSchemas)) {
                    val fieldValue = readObject(componentSchema)
                    components[name] = fieldValue
                }
                // Return a map of field values since we can't reconstruct the actual class
                components
            }
            is TranslatedSchema<*, *> -> {
                readObject(schema.baseSchema)
            }
        }
    }

    /**
     * Returns all channels that have been registered in the log.
     */
    fun getChannels(): List<ChannelInfo> = channels.toList()

    /**
     * Returns the channel info for the given index.
     */
    fun getChannel(index: Int): ChannelInfo? = channels.getOrNull(index)

    /**
     * Returns all entries in the log as a list.
     */
    fun readAll(): List<FateLogEntry> {
        val entries = mutableListOf<FateLogEntry>()
        while (hasNext()) {
            entries.add(next())
        }
        return entries
    }

    /**
     * Returns all message entries for the given channel name.
     */
    fun readMessagesForChannel(channelName: String): List<FateLogEntry.Message<*>> {
        return readAll().filterIsInstance<FateLogEntry.Message<*>>()
            .filter { it.channelName == channelName }
    }

    override fun close() {
        stream.close()
    }

    companion object Companion {
        /**
         * Creates a LogReader for the given file.
         */
        fun create(file: File): FateLogReader {
            return FateLogReader(FileInputStream(file))
        }

        /**
         * Creates a LogReader for the given file path.
         */
        fun create(filePath: String): FateLogReader {
            return create(File(filePath))
        }
    }
}