package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import kotlin.jvm.kotlin
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.isSubclassOf

/**
 * Describes how one type becomes bytes.
 *
 * A schema is parameterized by the type it encodes, so the compiler catches a value that doesn't
 * belong on a channel. Every schema can also write a description of itself, and that description
 * goes into the log ahead of the data, which is what lets a reader decode a file without the
 * classes that wrote it.
 *
 * ## The schemas
 *
 * ### Primitives
 * - `IntSchema`: 32-bit signed integers
 * - `LongSchema`: 64-bit signed integers
 * - `DoubleSchema`: 64-bit floating point numbers
 * - `StringSchema`: UTF-8 encoded strings with length prefix
 * - `BooleanSchema`: Single-byte boolean values
 *
 * There is no float type. `Float` widens to `DoubleSchema`, which is exact. Narrower primitives
 * (`Short`, `Byte`, `Char`) have no encoding and are rejected when the schema is built.
 *
 * ### Collections
 * - `ArraySchema<T>`: arrays, and the wire format `List`/`Set` use too
 * - `DoubleArraySchema` and friends: Kotlin's primitive arrays, same bytes without boxing
 * - `MapSchema<K, V>`: maps with independently typed keys and values
 * - `EnumSchema<T>`: enums, as a 4-byte ordinal
 *
 * ### Classes
 * - `ReflectedClassSchema<T>`: built by reflecting over public properties
 * - `TypedClassSchema<T>`: the same, plus the class's `AS_TYPE` name in the log
 * - `CustomStructSchema<T>`: components and an encoder you write yourself
 *
 * ## Binary format
 *
 * The schema, written once per channel:
 * ```
 * [4 bytes: tag] [schema-specific metadata...]
 * ```
 *
 * An object, written once per logged value:
 * ```
 * [variable: object data as defined by schema]
 * ```
 *
 * ## Example
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
     * in the binary format. Tags are defined in the [TypeRegistry] enum and must be
     * consistent across all versions of the format.
     *
     * @see TypeRegistry
     */
    val tag: Int

    /**
     * Number of bytes required to encode this schema's metadata.
     *
     * This is the size of the schema definition, not of the objects it describes. A primitive is
     * just its 4-byte tag; a class or array adds field definitions and nested element schemas.
     *
     * It must stay the same for a given schema, since the writer allocates against it.
     */
    val schemaSize: Int

    /**
     * The complete schema definition as a byte array.
     *
     * This is what gets written into the log file. It always starts with the [tag], followed by
     * whatever metadata the schema needs.
     *
     * The default just encodes the tag. Anything with structure overrides it to add field
     * definitions, element types and so on.
     *
     * Format:
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
     * Writes the whole schema definition, starting with the [tag]. The number of bytes written
     * must match [schemaSize] exactly.
     *
     * The default writes the [schema] property straight to the buffer.
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
     * Must be exact, deterministic, and consistent with what [encodeObject] actually writes.
     *
     * For variable-length data (strings, arrays, nested objects) this walks the whole structure,
     * and the writer calls it once per logged value on top of [encodeObject].
     *
     * @param obj The object to calculate the encoded size for
     * @return The number of bytes required to encode this object
     */
    fun objSize(obj: T): Int

    /**
     * Encodes the given object into the provided buffer.
     *
     * Writes exactly as many bytes as [objSize] reported for the same object, and advances the
     * buffer's position by that much. The buffer needs at least that much room remaining.
     *
     * The layout depends on the schema:
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
     * Every schema type FateWeaver knows, each with the integer tag it writes into the log.
     *
     * These values are part of the file format and
     * must remain stable across versions to ensure backward compatibility.
     *
     * @param value The unique integer tag for this schema type
     */
    enum class TypeRegistry(val value: Int) {
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
        ARRAY(7),

        /** Map schema with independently typed keys and values */
        MAP(8);
    }

    companion object Companion {
        private val registry = mutableMapOf<KClass<*>, FateSchema<*>>()
        private val resolving = mutableSetOf<KClass<*>>()

        /**
         * Cache for schemas of *parameterized* types.
         *
         * Keyed by [KType] rather than [KClass] because a generic schema depends on its type
         * arguments: `Map<String, Int>` and `Map<String, Double>` are distinct schemas that share
         * the classifier `Map::class`, so they cannot live in [registry]. [KType] has structural
         * equality, which makes it a sound key.
         *
         * Only fully resolved types reach this map, and entries are written only after a schema is
         * completely built, so a failed resolution leaves nothing cached.
         */
        private val parameterizedRegistry = mutableMapOf<KType, FateSchema<*>>()

        /**
         * Returns a map of all registered schemas.
         */
        @JvmStatic
        @get:JvmName("getRegisteredSchemas")
        val SCHEMA_REGISTRY: Map<KClass<*>, FateSchema<*>>
            get() = registry.toMap()

        /**
         * Automatically creates an appropriate schema for the given class type.
         *
         * Inspects the class and picks the schema that fits it.
         *
         * ## Supported types
         *
         * ### Primitives
         * - `int/Integer` → [IntSchema]
         * - `long/Long` → [LongSchema]
         * - `double/Double` → [DoubleSchema]
         * - `boolean/Boolean` → [BooleanSchema]
         * - `String` → [StringSchema]
         *
         * ### Collections
         * - `T[]` → [ArraySchema]<T>, inferring the element schema recursively
         * - `Enum<T>` → [EnumSchema]<T>
         *
         * ### Generics
         *
         * A [Class] carries no type arguments, so `Map` and `Collection` are rejected here. Use
         * [schemaOfType] when you have a [kotlin.reflect.KType] (which is what reflected class
         * *fields* go through), or build the schema yourself, e.g.
         * `MapSchema(StringSchema, DoubleSchema)`.
         *
         * ### Classes
         * - An `AS_TYPE` constant, on a companion object or as a Java static → [TypedClassSchema]
         * - Anything else → [ReflectedClassSchema]
         *
         * The casts inside are unchecked, but the returned schema always matches the class passed
         * in.
         *
         * ## Cost
         *
         * Primitives map in constant time. Enums and arrays need class introspection, and a class
         * schema means reflecting over every field, so results are cached per class in the
         * registry and the cost is paid once.
         *
         * ## Examples
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
         * @see MapSchema
         * @see EnumSchema
         * @see ReflectedClassSchema
         * @see TypedClassSchema
         * @see schemaOfType
         */
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        fun <T : Any> schemaOfClass(cls: Class<T>): FateSchema<T> = schemaOfClass(cls.kotlin)

        /**
         * Automatically creates an appropriate schema for the given class type.
         *
         * Inspects the class and picks the schema that fits it.
         *
         * ## Supported types
         *
         * ### Primitives
         * - `int/Integer` → [IntSchema]
         * - `long/Long` → [LongSchema]
         * - `double/Double` → [DoubleSchema]
         * - `boolean/Boolean` → [BooleanSchema]
         * - `String` → [StringSchema]
         *
         * ### Collections
         * - `T[]` → [ArraySchema]<T>, inferring the element schema recursively
         * - `Enum<T>` → [EnumSchema]<T>
         *
         * ### Generics
         *
         * A [Class] carries no type arguments, so `Map` and `Collection` are rejected here. Use
         * [schemaOfType] when you have a [kotlin.reflect.KType] (which is what reflected class
         * *fields* go through), or build the schema yourself, e.g.
         * `MapSchema(StringSchema, DoubleSchema)`.
         *
         * ### Classes
         * - An `AS_TYPE` constant, on a companion object or as a Java static → [TypedClassSchema]
         * - Anything else → [ReflectedClassSchema]
         *
         * The casts inside are unchecked, but the returned schema always matches the class passed
         * in.
         *
         * ## Cost
         *
         * Primitives map in constant time. Enums and arrays need class introspection, and a class
         * schema means reflecting over every field, so results are cached per class in the
         * registry and the cost is paid once.
         *
         * ## Examples
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
         * @param cls The class to create a schema for
         * @return A schema instance capable of serializing objects of type T
         * @throws IllegalArgumentException if the class type is not supported
         * @see IntSchema
         * @see StringSchema
         * @see ArraySchema
         * @see MapSchema
         * @see EnumSchema
         * @see ReflectedClassSchema
         * @see TypedClassSchema
         * @see schemaOfType
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> schemaOfClass(cls: KClass<T>): FateSchema<T> = if (cls in registry.keys) {
            registry[cls] as FateSchema<T>
        } else {
            if (cls in resolving) {
                throw IllegalArgumentException("Cyclic reference detected for class ${cls.qualifiedName}. FateWeaver schemas do not support cyclic or self-referential structures.")
            }
            resolving.add(cls)
            try {
                createSchemaForClass(cls.java).also {
                    registry[cls] = it
                }
            } finally {
                resolving.remove(cls)
            }
        }

        /**
         * Registers a custom schema for the given class type.
         */
        @JvmStatic
        fun <T : Any> registerSchema(cls: Class<T>, schema: FateSchema<T>) {
            registerSchema(cls.kotlin, schema)
        }

        /**
         * Registers a custom schema for the given class type.
         */
        @JvmStatic
        fun <T : Any> registerSchema(cls: KClass<T>, schema: FateSchema<T>) {
            registry[cls] = schema
        }

        /**
         * Convenience method for creating a schema from a reified type parameter.
         * @see schemaOfClass(Class<T>)
         */
        @JvmStatic
        inline fun <reified T : Any> schemaOfClass(): FateSchema<T> = schemaOfClass(T::class)

        /**
         * Convenience method for registering a custom schema from a reified type parameter.
         * @see registerSchema(Class<T>, FateSchema<T>)
         */
        @JvmStatic
        inline fun <reified T : Any> registerSchema(schema: FateSchema<T>) = registerSchema(T::class, schema)

        /**
         * Creates a schema for a fully resolved Kotlin type, honouring generic type arguments.
         *
         * Unlike [schemaOfClass], which only ever sees the erased class, this understands
         * `Map<K, V>`, `List<T>`, `Set<T>` and `Collection<T>`:
         *
         * - `Map<K, V>` becomes a [MapSchema] with independently inferred key and value schemas.
         * - `List`/`Set`/`Collection<T>` are encoded with the ARRAY wire format, via a
         *   [TranslatedSchema] over an [ArraySchema], so they are byte-identical to `T[]`.
         * - Everything else defers to [schemaOfClass], preserving its exact behavior.
         *
         * A schema registered for a class with [registerSchema] always wins, for every
         * parameterization of that class.
         *
         * @param type The type to build a schema for
         * @return A schema capable of serializing values of [type]
         * @throws IllegalArgumentException if [type], or any of its type arguments, cannot be
         *   schematized. Star projections, type variables, `Any`, and nullable type arguments are
         *   all rejected.
         */
        @JvmStatic
        fun schemaOfType(type: KType): FateSchema<*> = schemaOfType(type, type.toString())

        /**
         * Creates a schema for a fully resolved Kotlin type.
         *
         * @param path Human-readable description of where this type came from, used in diagnostics
         *   (for example `"com.example.Holder.items"`).
         * @see schemaOfType
         */
        internal fun schemaOfType(type: KType, path: String): FateSchema<*> {
            val classifier = type.classifier
                ?: throw unschemable(path, type, "its classifier could not be resolved")

            if (classifier !is KClass<*>) {
                val name = (classifier as? KTypeParameter)?.name ?: classifier.toString()
                throw unschemable(
                    path, type, "'$name' is a type variable; FateWeaver needs a concrete type"
                )
            }

            // An explicitly registered schema wins, for every combination of type arguments. This
            // is the escape hatch for Map, Collection and anything else inference rejects.
            registry[classifier]?.let { return it }
            parameterizedRegistry[type]?.let { return it }

            val isMap = classifier.isSubclassOf(Map::class)
            val isCollection = classifier.isSubclassOf(Collection::class)

            // Everything that is neither a Map nor a Collection keeps the pre-existing behavior:
            // primitives, enums, arrays (including primitive arrays), and plain classes.
            if (!isMap && !isCollection) return schemaOfClass(classifier)

            @Suppress("UNCHECKED_CAST")
            val schema: FateSchema<*> = if (isMap) {
                requireArity(path, type, 2, "Map")
                val keySchema = schemaOfArgument(type, 0, path) as FateSchema<Any>
                val valueSchema = schemaOfArgument(type, 1, path) as FateSchema<Any>
                MapSchema(keySchema, valueSchema)
            } else {
                requireArity(path, type, 1, "Collection")
                val elementSchema = schemaOfArgument(type, 0, path) as FateSchema<Any>
                TranslatedSchema<Collection<Any>, Array<Any>>(ArraySchema(elementSchema)) {
                    it.toTypedArray()
                }
            }

            parameterizedRegistry[type] = schema
            return schema
        }

        /**
         * Resolves the schema for the type argument at [index] of [owner], rejecting anything that
         * carries too little type information to encode.
         */
        private fun schemaOfArgument(owner: KType, index: Int, path: String): FateSchema<*> {
            val position = "type argument #${index + 1}"

            val argType = owner.arguments[index].type ?: throw unschemable(
                path, owner, "$position is a star projection ('*'), which carries no type information"
            )

            if (argType.isMarkedNullable) {
                throw unschemable(
                    path, owner,
                    "$position ('$argType') is nullable, and the FateWeaver binary format has no " +
                            "encoding for null"
                )
            }

            val argClassifier = argType.classifier
            if (argClassifier !is KClass<*>) {
                val name = (argClassifier as? KTypeParameter)?.name ?: "<unresolved>"
                throw unschemable(
                    path, owner,
                    "$position ('$name') is a type variable; FateWeaver needs a concrete type"
                )
            }

            // `Any` is rejected only in type-argument position: a heterogeneous Map<String, Any>
            // has no fixed shape. The registry check comes first so a user-registered schema for
            // Any::class still wins.
            if (argClassifier !in registry.keys && argClassifier == Any::class) {
                throw unschemable(
                    path, owner, "$position is 'kotlin.Any', which carries no field information"
                )
            }

            return schemaOfType(argType, "$path ($position)")
        }

        private fun requireArity(path: String, type: KType, expected: Int, kind: String) {
            if (type.arguments.size != expected) {
                throw unschemable(
                    path, type,
                    "it is a raw $kind with ${type.arguments.size} type arguments; $expected are required"
                )
            }
        }

        private fun unschemable(path: String, type: KType, reason: String) = IllegalArgumentException(
            "Cannot infer a FateWeaver schema for $path (declared type '$type'): $reason. " +
                    "Use a concrete, non-null type; or mark the property with @FateIgnore; " +
                    "or register a schema explicitly with FateSchema.registerSchema(...)."
        )

        /**
         * Clears all inferred and registered schemas. Intended for test isolation, since a schema
         * registered for a widely used class would otherwise leak across tests in the same JVM.
         */
        internal fun clearInferenceCaches() {
            registry.clear()
            resolving.clear()
            parameterizedRegistry.clear()
        }

        /**
         * JVM primitives the format cannot encode, mapped to the type to widen to.
         *
         * Without this guard they fall through to [ReflectedClassSchema], which finds no public
         * properties on a boxed primitive and encodes zero bytes. The write succeeds and the value
         * is silently missing from the log, which is the worst way to lose data. Their arrays are
         * covered too: `short[]` resolves its component type through here first.
         */
        private val UNSUPPORTED_PRIMITIVES: Map<Class<*>, String> = mapOf(
            Short::class.java to "Int", java.lang.Short::class.java to "Int",
            Byte::class.java to "Int", java.lang.Byte::class.java to "Int",
            Char::class.java to "String or Int", Character::class.java to "String or Int",
        )

        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> createSchemaForClass(cls: Class<T>) = when (cls) {
            Int::class.java, Integer::class.java -> IntSchema
            Long::class.java, java.lang.Long::class.java -> LongSchema
            Double::class.java, java.lang.Double::class.java -> DoubleSchema
            String::class.java -> StringSchema
            Boolean::class.java, java.lang.Boolean::class.java -> BooleanSchema
            Float::class.java, java.lang.Float::class.java ->
                TranslatedSchema<Float, Double>(DoubleSchema) { it.toDouble() }
            DoubleArray::class.java -> DoubleArraySchema
            IntArray::class.java -> IntArraySchema
            LongArray::class.java -> LongArraySchema
            BooleanArray::class.java -> BooleanArraySchema
            FloatArray::class.java -> FloatArraySchema
            else -> {
                if (cls.isEnum) {
                    EnumSchema(cls as Class<out Enum<*>>)
                } else if (cls.isArray) {
                    // Checked before the map/collection guard below: an array's component type is
                    // preserved on the Class, so arrays need no type-argument information.
                    ArraySchema(schemaOfClass(cls.componentType!!))
                } else if (java.util.Map::class.java.isAssignableFrom(cls) ||
                    java.util.Collection::class.java.isAssignableFrom(cls)
                ) {
                    throw IllegalArgumentException(
                        "Cannot infer a FateWeaver schema for the raw class ${cls.name}: map and " +
                                "collection element types are erased on Class, so they cannot be " +
                                "recovered here. From Kotlin, write the value through " +
                                "write(channelName, value) or createChannel<Map<String, Double>>" +
                                "(channelName), which infer from the static type at the call site. " +
                                "Otherwise infer from a declared property, call " +
                                "FateSchema.schemaOfType(typeOf<...>()), or construct the schema " +
                                "explicitly, e.g. MapSchema(StringSchema, DoubleSchema)."
                    )
                } else if (cls in UNSUPPORTED_PRIMITIVES) {
                    throw IllegalArgumentException(
                        "Cannot infer a FateWeaver schema for ${cls.name}: the format has no " +
                                "encoding for it. Widen the field to ${UNSUPPORTED_PRIMITIVES[cls]}, " +
                                "or register a schema for the declaring class with " +
                                "FateSchema.registerSchema(...)."
                    )
                } else {
                    ReflectedClassSchema.createFromClass(cls)
                }
            }
        } as FateSchema<T>
    }
}
