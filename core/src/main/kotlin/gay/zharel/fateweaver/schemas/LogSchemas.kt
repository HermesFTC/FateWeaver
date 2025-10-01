package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import kotlin.jvm.kotlin

/**
 * Core interface for defining serialization schemas in the FateWeaver logging system.
 *
 * FateSchema provides a type-safe, efficient binary serialization framework with the following features:
 * - **Type Safety**: Each schema is parameterized with the specific type it can serialize
 * - **Self-Describing**: Schemas embed their own structure information for compatibility
 * - **Efficient Encoding**: Optimized binary format with minimal overhead
 * - **Schema Evolution**: Version-aware serialization for backward compatibility
 * - **Extensible**: Support for primitive types, collections, enums, and custom classes
 *
 * ## Schema Types
 *
 * The system supports several categories of schemas:
 *
 * ### Primitive Schemas
 * - `IntSchema`: 32-bit signed integers
 * - `LongSchema`: 64-bit signed integers
 * - `DoubleSchema`: 64-bit floating point numbers
 * - `StringSchema`: UTF-8 encoded strings with length prefix
 * - `BooleanSchema`: Single-byte boolean values
 *
 * ### Collection Schemas
 * - `ArraySchema<T>`: Arrays of any supported type T
 * - `EnumSchema<T>`: Enumeration types with ordinal encoding
 *
 * ### Class Schemas
 * - `ReflectedClassSchema<T>`: Automatic serialization via reflection
 * - `TypedClassSchema<T>`: Reflection-based with embedded type information
 * - `CustomStructSchema<T>`: User-defined serialization with custom encoders
 *
 * ## Binary Format
 *
 * Each schema defines both its metadata format and object encoding format:
 *
 * **Schema Encoding** (written once per channel):
 * ```
 * [4 bytes: tag] [schema-specific metadata...]
 * ```
 *
 * **Object Encoding** (written for each logged object):
 * ```
 * [variable: object data as defined by schema]
 * ```
 *
 * ## Usage Example
 *
 * ```kotlin
 * // Automatic schema inference
 * val userSchema = FateSchema.schemaOfClass(User::class.java)
 *
 * // Manual schema creation
 * val customSchema = CustomStructSchema<Person>(
 *     type = "Person",
 *     componentNames = listOf("name", "age"),
 *     componentSchemas = listOf(StringSchema, IntSchema),
 *     encoder = { person -> listOf(person.name, person.age) }
 * )
 *
 * // Size calculation and encoding
 * val person = Person("Alice", 30)
 * val objSize = customSchema.objSize(person)
 * val buffer = ByteBuffer.allocate(objSize)
 * customSchema.encodeObject(buffer, person)
 * ```
 *
 * @param T The type of objects this schema can serialize and deserialize
 */
sealed interface FateSchema<T> {

    /**
     * Unique identifier for this schema type within the FateWeaver format.
     *
     * Each schema implementation has a distinct tag value that identifies its type
     * in the binary format. Tags are defined in the [Registry] enum and must be
     * consistent across all versions of the format.
     *
     * @see Registry
     */
    val tag: Int

    /**
     * Number of bytes required to encode this schema's metadata.
     *
     * This represents the size of the schema definition itself (not the objects it describes).
     * For primitive types, this is typically just 4 bytes (the tag). For complex types
     * like classes or arrays, this includes additional metadata such as field definitions,
     * element schemas, etc.
     *
     * The schema size must be consistent for the same schema configuration and is used
     * to allocate appropriate buffer space during encoding.
     */
    val schemaSize: Int

    /**
     * The complete schema definition as a byte array.
     *
     * This property provides the binary representation of the schema metadata that gets
     * written to log files.
     * The format always begins with the [tag] followed by any
     * schema-specific metadata.
     *
     * **Default Implementation**: For simple schemas, this just encodes the tag as a
     * 4-byte integer.
     * Complex schemas override this to include additional metadata
     * such as field definitions, element types, etc.
     *
     * **Format**:
     * ```
     * [4 bytes: tag] [variable: schema-specific metadata]
     * ```
     */
    val schema: ByteArray
        get() {
            val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
            buffer.putInt(tag)
            return buffer.array()
        }

    /**
     * Encodes this schema's metadata into the provided buffer.
     *
     * This method writes the complete schema definition to the buffer, starting with
     * the [tag] and followed by any schema-specific metadata.
     * The amount of data
     * written must exactly match [schemaSize].
     *
     * **Default Implementation**: Writes the [schema] property directly to the buffer.
     * This is suitable for most use cases but can be overridden for custom encoding logic.
     *
     * @param buffer The buffer to write schema data to. Must have at least [schemaSize] bytes remaining.
     * @throws java.nio.BufferOverflowException if the buffer doesn't have enough space
     */
    fun encodeSchema(buffer: ByteBuffer) {
        buffer.put(schema)
    }

    /**
     * Calculates the number of bytes required to encode the given object.
     *
     * This method analyzes the object and returns the exact number of bytes needed
     * for its binary representation.
     * The size calculation must be deterministic and
     * consistent with the actual encoding performed by [encodeObject].
     *
     * **Performance Note**: For variable-length data (strings, arrays, nested objects),
     * this may need to traverse the entire object structure.
     * Consider caching results
     * if the same object will be encoded multiple times.
     *
     * @param obj The object to calculate the encoded size for
     * @return The number of bytes required to encode this object
     */
    fun objSize(obj: T): Int

    /**
     * Encodes the given object into the provided buffer.
     *
     * This method writes the binary representation of the object to the buffer using
     * the format defined by this schema. The amount of data written must exactly
     * match the value returned by [objSize] for the same object.
     *
     * **Buffer Requirements**: The buffer must have at least `objSize(obj)` bytes
     * of remaining capacity. The buffer's position will be advanced by exactly
     * that number of bytes.
     *
     * **Encoding Format**: The specific binary format depends on the schema type:
     * - Primitives: Direct binary representation (int, long, double, etc.)
     * - Strings: Length prefix (4 bytes) + UTF-8 encoded bytes
     * - Booleans: Single byte (0x00 for false, 0x01 for true)
     * - Arrays: Length prefix + encoded elements
     * - Classes: Encoded fields in alphabetical order
     *
     * @param buffer The buffer to write object data to
     * @param obj The object to encode
     * @throws java.nio.BufferOverflowException if the buffer doesn't have enough space
     */
    fun encodeObject(buffer: ByteBuffer, obj: T)

    /**
     * Registry of all supported schema types with their unique tag values.
     *
     * This enum defines the complete set of schema types supported by the FateWeaver
     * format. Each entry has a unique integer value that serves as the schema's tag
     * in the binary format.
     *
     * **Stability**: These tag values are part of the file format specification and
     * must remain stable across versions to ensure backward compatibility.
     *
     * @param value The unique integer tag for this schema type
     */
    enum class Registry(val value: Int) {
        /** Class-based schemas using reflection or custom encoding */
        CUSTOM(0),

        /** 32-bit signed integer schema */
        INT(1),

        /** 64-bit signed integer schema */
        LONG(2),

        /** 64-bit IEEE 754 floating point schema */
        DOUBLE(3),

        /** UTF-8 encoded string schema with length prefix */
        STRING(4),

        /** Single-byte boolean schema */
        BOOLEAN(5),

        /** Enumeration schema with ordinal encoding */
        ENUM(6),

        /** Array schema for collections of homogeneous elements */
        ARRAY(7);
    }

    companion object Companion {
        /**
         * Automatically creates an appropriate schema for the given class type.
         *
         * This factory method uses reflection and type inspection to determine the most
         * suitable schema for a given Java class. It handles the complete type system
         * including primitives, collections, enums, and custom classes.
         *
         * ## Supported Types
         *
         * **Primitive Types**:
         * - `int/Integer` → [IntSchema]
         * - `long/Long` → [LongSchema]
         * - `double/Double` → [DoubleSchema]
         * - `boolean/Boolean` → [BooleanSchema]
         * - `String` → [StringSchema]
         *
         * **Collection Types**:
         * - `T[]` → [ArraySchema]<T> (recursive schema inference for element type)
         * - `Enum<T>` → [EnumSchema]<T>
         *
         * **Class Types**:
         * - Classes with companion object `as_type` property → [TypedClassSchema]
         * - All other classes → [ReflectedClassSchema]
         *
         * ## Type Safety
         *
         * The method uses unchecked casts internally but maintains type safety through
         * careful type checking. The returned schema is guaranteed to be compatible
         * with the input class type.
         *
         * ## Performance Considerations
         *
         * - Primitive type mapping is constant-time
         * - Enum and array detection uses class introspection
         * - Class schema creation involves reflection and field analysis
         * - Consider caching schemas for frequently used types
         *
         * ## Usage Examples
         *
         * ```kotlin
         * // Primitive types
         * val intSchema = FateSchema.schemaOfClass(Int::class.java)        // IntSchema
         * val stringSchema = FateSchema.schemaOfClass(String::class.java)  // StringSchema
         *
         * // Collections
         * val arraySchema = FateSchema.schemaOfClass(Array<String>::class.java)  // ArraySchema<String>
         * val enumSchema = FateSchema.schemaOfClass(MyEnum::class.java)           // EnumSchema<MyEnum>
         *
         * // Custom classes
         * val userSchema = FateSchema.schemaOfClass(User::class.java)      // ReflectedClassSchema<User>
         * val typedSchema = FateSchema.schemaOfClass(TypedClass::class.java) // TypedClassSchema<TypedClass>
         * ```
         *
         * @param T The type parameter matching the class
         * @param clazz The Java class to create a schema for
         * @return A schema instance capable of serializing objects of type T
         * @throws IllegalArgumentException if the class type is not supported
         * @see IntSchema
         * @see StringSchema
         * @see ArraySchema
         * @see EnumSchema
         * @see ReflectedClassSchema
         * @see TypedClassSchema
         */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> schemaOfClass(clazz: Class<T>): FateSchema<T> = when (clazz) {
            Int::class.java, Integer::class.java -> IntSchema
            Long::class.java, java.lang.Long::class.java -> LongSchema
            Double::class.java, java.lang.Double::class.java -> DoubleSchema
            String::class.java -> StringSchema
            Boolean::class.java, java.lang.Boolean::class.java -> BooleanSchema
            else -> {
                if (clazz.isEnum) {
                    @Suppress("UNCHECKED_CAST")
                    EnumSchema(clazz as Class<out Enum<*>>)
                } else if (clazz.isArray) {
                    ArraySchema(schemaOfClass(clazz.componentType!!))
                } else {
                    ReflectedClassSchema.createFromClass(clazz.kotlin)
                }
            }
        } as FateSchema<T>
    }
}
