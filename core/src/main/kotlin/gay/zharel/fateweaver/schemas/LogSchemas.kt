package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import kotlin.jvm.kotlin
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.withNullability

/**
 * Describes how one type becomes bytes.
 *
 * A schema is generic over the type it encodes, so the compiler rejects a value that doesn't belong
 * on a channel. Every schema can also write a description of itself. The writer puts that
 * description in the log ahead of the data, so a reader can decode a file without the classes that
 * wrote it.
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
 * ### Optionals
 * - `OptionalSchema<T>`: a nullable `T?`, as a presence byte then the value
 * - `JavaOptionalSchema<T>`: `java.util.Optional<T>`, same tag and same bytes
 * - `OptionalIntSchema` and friends: `OptionalInt`, `OptionalLong`, `OptionalDouble`
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
     * The integer that identifies this schema's type in the log.
     *
     * Values come from [TypeRegistry] and never change between format versions. Schemas that write
     * the same bytes can share a tag, such as [OptionalSchema] and [JavaOptionalSchema].
     *
     * @see TypeRegistry
     */
    val tag: Int

    /**
     * Number of bytes in this schema's definition.
     *
     * This is the size of the schema, not of the objects it describes. A primitive schema is only
     * its 4-byte tag. A class or array adds field definitions and nested element schemas.
     *
     * It must not change for a given schema, because the writer allocates against it.
     */
    val schemaSize: Int

    /**
     * The schema definition as bytes, which the writer puts in the log.
     *
     * It starts with the [tag], followed by any metadata the schema needs. The default is the tag
     * alone. A schema with structure overrides it to add field definitions, element types and so on.
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
     * Writes the whole schema definition, starting with the [tag], into the buffer.
     *
     * The number of bytes written must equal [schemaSize]. The default writes [schema].
     *
     * @param buffer The buffer to write schema data to. Must have at least [schemaSize] bytes remaining.
     * @throws java.nio.BufferOverflowException if the buffer doesn't have enough space
     */
    fun encodeSchema(buffer: ByteBuffer) {
        buffer.put(schema)
    }

    /**
     * Returns the number of bytes [encodeObject] writes for the given object.
     *
     * The result must be exact and must match what [encodeObject] writes.
     *
     * For variable-length data such as strings, arrays and nested objects, this walks the whole
     * structure. The writer calls it once per logged value, in addition to [encodeObject].
     *
     * @param obj The object to calculate the encoded size for
     * @return The number of bytes required to encode this object
     */
    fun objSize(obj: T): Int

    /**
     * Writes the given object into the buffer.
     *
     * Writes exactly the number of bytes [objSize] reports for the same object and advances the
     * buffer's position by that much. The buffer needs that much room remaining.
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
     * The schema types FateWeaver knows, each with the integer tag it writes into the log.
     *
     * These values are part of the file format, so they never change.
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
        MAP(8),

        /** Optional schema: a presence byte followed by the value, if present */
        OPTIONAL(9);
    }

    companion object Companion {
        private val registry = mutableMapOf<KClass<*>, FateSchema<*>>()
        private val resolving = mutableSetOf<KClass<*>>()

        /**
         * Cache for schemas of parameterized types.
         *
         * The key is a [KType], not a [KClass], because a generic schema depends on its type
         * arguments. `Map<String, Int>` and `Map<String, Double>` need different schemas but share
         * `Map::class`, so they cannot live in [registry]. [KType] has structural equality, so it
         * works as a key.
         *
         * An entry is added only after its schema is fully built, so a failed resolution caches
         * nothing.
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
         * Creates the schema that fits the given class.
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
         * A [Class] has no type arguments, so `Map` and `Collection` are rejected here. Use
         * [schemaOfType] if you have a [kotlin.reflect.KType]. Reflected class fields go through it.
         * Otherwise build the schema yourself, as in `MapSchema(StringSchema, DoubleSchema)`.
         *
         * ### Classes
         * - An `AS_TYPE` constant, on a companion object or as a Java static → [TypedClassSchema]
         * - Anything else → [ReflectedClassSchema]
         *
         * The casts inside are unchecked, but the returned schema always matches the class.
         *
         * ## Cost
         *
         * A class schema reflects over every field. Results are cached per class in the registry, so
         * that cost is paid once.
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
         * Creates the schema that fits the given class.
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
         * A [Class] has no type arguments, so `Map` and `Collection` are rejected here. Use
         * [schemaOfType] if you have a [kotlin.reflect.KType]. Reflected class fields go through it.
         * Otherwise build the schema yourself, as in `MapSchema(StringSchema, DoubleSchema)`.
         *
         * ### Classes
         * - An `AS_TYPE` constant, on a companion object or as a Java static → [TypedClassSchema]
         * - Anything else → [ReflectedClassSchema]
         *
         * The casts inside are unchecked, but the returned schema always matches the class.
         *
         * ## Cost
         *
         * A class schema reflects over every field. Results are cached per class in the registry, so
         * that cost is paid once.
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
         * Creates a schema for a fully resolved Kotlin type, using its generic type arguments.
         *
         * [schemaOfClass] only sees the erased class. This also handles `Map<K, V>`, `List<T>`,
         * `Set<T>` and `Collection<T>`:
         *
         * - `Map<K, V>` becomes a [MapSchema] with key and value schemas inferred separately.
         * - `List`, `Set` and `Collection<T>` use the ARRAY format through a [TranslatedSchema] over
         *   an [ArraySchema], so their bytes match `T[]`.
         * - Anything else goes to [schemaOfClass].
         *
         * A schema registered for a class with [registerSchema] always wins, for every
         * parameterization of that class.
         *
         * @param type The type to build a schema for
         * @return A schema capable of serializing values of [type]
         * @throws IllegalArgumentException if [type] or any of its type arguments has no schema.
         *   Star projections, type variables and `Any` are rejected.
         */
        @JvmStatic
        fun schemaOfType(type: KType): FateSchema<*> = schemaOfType(type, type.toString())

        /**
         * Creates a schema for a fully resolved Kotlin type.
         *
         * @param path Where this type came from, such as `"com.example.Holder.items"`. Error
         *   messages include it.
         * @see schemaOfType
         */
        internal fun schemaOfType(type: KType, path: String): FateSchema<*> {
            // A nullable type is the non-null type behind a presence byte. Unwrap it first so the
            // rules below see the underlying type, then wrap the result.
            if (type.isMarkedNullable) {
                @Suppress("UNCHECKED_CAST")
                val valueSchema = schemaOfType(type.withNullability(false), path) as FateSchema<Any>
                return OptionalSchema(valueSchema)
            }

            val classifier = type.classifier
                ?: throw unschemable(path, type, "its classifier could not be resolved")

            if (classifier !is KClass<*>) {
                val name = (classifier as? KTypeParameter)?.name ?: classifier.toString()
                throw unschemable(
                    path, type, "'$name' is a type variable; FateWeaver needs a concrete type"
                )
            }

            // A registered schema wins for every combination of type arguments. This is how you
            // handle a Map, a Collection or anything else inference rejects.
            registry[classifier]?.let { return it }
            parameterizedRegistry[type]?.let { return it }

            // Optional<T> means the same as T?, so it has the same OPTIONAL tag and bytes.
            if (classifier == java.util.Optional::class) {
                requireArity(path, type, 1, "Optional")
                @Suppress("UNCHECKED_CAST")
                val valueSchema = schemaOfArgument(type, 0, path) as FateSchema<Any>
                return JavaOptionalSchema(valueSchema).also { parameterizedRegistry[type] = it }
            }

            val isMap = classifier.isSubclassOf(Map::class)
            val isCollection = classifier.isSubclassOf(Collection::class)

            // Primitives, enums, arrays (including primitive arrays) and plain classes are neither
            // a Map nor a Collection, so schemaOfClass handles them.
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
         * Resolves the schema for the type argument at [index] of [owner]. Throws if the argument
         * has too little type information to encode.
         *
         * [schemaOfType] wraps a nullable argument in an [OptionalSchema], the same as a nullable
         * property.
         */
        private fun schemaOfArgument(owner: KType, index: Int, path: String): FateSchema<*> {
            val position = "type argument #${index + 1}"

            val argType = owner.arguments[index].type ?: throw unschemable(
                path, owner, "$position is a star projection ('*'), which carries no type information"
            )

            val argClassifier = argType.classifier
            if (argClassifier !is KClass<*>) {
                val name = (argClassifier as? KTypeParameter)?.name ?: "<unresolved>"
                throw unschemable(
                    path, owner,
                    "$position ('$name') is a type variable; FateWeaver needs a concrete type"
                )
            }

            // `Any` is rejected only as a type argument, because a Map<String, Any> has no fixed
            // shape. The registry check comes first so a schema registered for Any::class still
            // wins.
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
                    "Use a concrete type; or mark the property with @FateIgnore; " +
                    "or register a schema explicitly with FateSchema.registerSchema(...)."
        )

        /**
         * Clears all inferred and registered schemas. Tests use this so a schema registered for a
         * common class does not leak into other tests in the same JVM.
         */
        internal fun clearInferenceCaches() {
            registry.clear()
            resolving.clear()
            parameterizedRegistry.clear()
        }

        /**
         * JVM primitives the format cannot encode, each mapped to the type to widen to.
         *
         * Without this guard they fall through to [ReflectedClassSchema], which finds no public
         * properties on a boxed primitive and encodes zero bytes. The write would succeed and the
         * value would be missing from the log. Arrays are covered too, because `short[]` resolves
         * its component type through here first.
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

            // OptionalInt, OptionalLong and OptionalDouble need no type argument, unlike
            // Optional<T>. Without these cases they would reflect to a class with no fields and
            // encode nothing.
            java.util.OptionalInt::class.java -> OptionalIntSchema
            java.util.OptionalLong::class.java -> OptionalLongSchema
            java.util.OptionalDouble::class.java -> OptionalDoubleSchema
            else -> {
                if (cls.isEnum) {
                    EnumSchema(cls as Class<out Enum<*>>)
                } else if (cls.isArray) {
                    // Checked before the map/collection guard below. A Class keeps an array's
                    // component type, so arrays need no type arguments.
                    ArraySchema(schemaOfClass(cls.componentType!!))
                } else if (cls == java.util.Optional::class.java) {
                    throw IllegalArgumentException(
                        "Cannot infer a FateWeaver schema for the raw class java.util.Optional: " +
                                "its value type is erased on Class. Infer from a declared property " +
                                "typed Optional<T>, call FateSchema.schemaOfType(typeOf<...>()), " +
                                "or construct the schema explicitly, e.g. " +
                                "JavaOptionalSchema(DoubleSchema)."
                    )
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
