package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.staticProperties
import kotlin.reflect.jvm.isAccessible

open class ReflectedClassSchema<T : Any>(
    val fields: Map<String, FateSchema<*>>,
) : FateSchema<T> {
    override val tag: Int = FateSchema.Registry.CUSTOM.value

    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES + fields.map { (name, schema) ->
        Int.SIZE_BYTES + name.toByteArray(Charsets.UTF_8).size + schema.schemaSize
    }.sum()

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.putInt(fields.size)
        for ((name, schema) in fields) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
            buffer.put(schema.schema)
        }
        buffer.array()
    }

    @Suppress("UNCHECKED_CAST")
    override fun objSize(obj: T): Int = fields.map { (name, schema) ->
        val field: KProperty1<T, *> = obj::class.memberProperties.find { it.name == name }!! as KProperty1<T, *>
        val fieldValue = field.get(obj)!!
        (schema as FateSchema<Any>).objSize(fieldValue)
    }.sum()

    @Suppress("UNCHECKED_CAST")
    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        for ((name, schema) in fields) {
            val field: KProperty1<T, *> = obj::class.memberProperties.find { it.name == name }!! as KProperty1<T, *>
            val fieldValue = field.get(obj)!!
            (schema as FateSchema<Any>).encodeObject(buffer, fieldValue)
        }
    }

    companion object Companion {
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> createFromClass(cls: KClass<T>): ReflectedClassSchema<T> {
            val type = if (cls.companionObject?.memberProperties?.any { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME } == true) {
                val typeProp = cls.companionObject!!.memberProperties.first { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME } as KProperty1<Any?, *>
                typeProp.get(cls.companionObjectInstance!!)
            } else if (cls.staticProperties.any { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME }) {
                val typeProp = cls.staticProperties.first { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME } as KProperty1<Any?, *>
                typeProp.get(null)
            } else null

            val fields = cls.memberProperties.associate { field ->
                field.isAccessible = true
                field.name to FateSchema.schemaOfClass((field.returnType.classifier as KClass<*>).java)
            }

            return when (type) {
                is String -> TypedClassSchema(type, fields)
                else -> ReflectedClassSchema(fields)
            }
        }

        fun <T : Any> createFromClass(cls: Class<T>): ReflectedClassSchema<T> = createFromClass(cls.kotlin)
    }
}

class TypedClassSchema<T : Any>(
    val type: String,
    fields: Map<String, FateSchema<*>>,
) : ReflectedClassSchema<T>(fields) {
    override val tag: Int = FateSchema.Registry.CUSTOM.value

    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES +
            Int.SIZE_BYTES + TYPE_FIELD.first.toByteArray().size + TYPE_FIELD.second.schemaSize +
            fields.map { (name, schema) ->
                Int.SIZE_BYTES + name.toByteArray(Charsets.UTF_8).size + schema.schemaSize
            }.sum()

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.putInt(fields.size)
        TYPE_FIELD.first.toByteArray().let {
            buffer.putInt(it.size)
            buffer.put(it)
        }
        buffer.put(TYPE_FIELD.second.schema)
        for ((name, schema) in fields) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
            buffer.put(schema.schema)
        }
        buffer.array()
    }

    @Suppress("UNCHECKED_CAST")
    override fun objSize(obj: T): Int = TYPE_FIELD.second.objSize(type) + fields.map { (name, schema) ->
        val field: KProperty1<T, *> = obj::class.memberProperties.find { it.name == name }!! as KProperty1<T, *>
        val fieldValue = field.get(obj)!!
        (schema as FateSchema<Any>).objSize(fieldValue)
    }.sum()

    @Suppress("UNCHECKED_CAST")
    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        TYPE_FIELD.second.encodeObject(buffer, type)
        for ((name, schema) in fields) {
            val field: KProperty1<T, *> = obj::class.memberProperties.find { it.name == name }!! as KProperty1<T, *>
            val fieldValue = field.get(obj)!!
            (schema as FateSchema<Any>).encodeObject(buffer, fieldValue)
        }
    }

    companion object {
        val TYPE_FIELD = ".type" to StringSchema

        val TYPE_FIELD_NAME = "AS_TYPE"
    }
}

/**
 * A flexible schema for custom serialization of objects with user-defined encoding logic.
 *
 * CustomStructSchema provides maximum flexibility for serializing objects by allowing users to
 * define exactly how their objects should be decomposed into primitive components. This is particularly
 * useful for:
 * - **Legacy Objects**: Classes that can't be modified to use reflection-based serialization
 * - **Optimized Encoding**: Custom logic to minimize serialized size or improve performance
 * - **Data Transformation**: Converting objects during serialization (e.g., coordinate transformations)
 * - **Selective Serialization**: Including only specific fields or computed values
 * - **Version Compatibility**: Maintaining backward compatibility with different object versions
 *
 * ## Architecture
 *
 * The schema uses a component-based approach where:
 * 1. Objects are decomposed into a list of primitive components via the [encoder] function
 * 2. Each component has a corresponding name and schema for serialization
 * 3. Components are serialized in the order they appear in [componentNames]
 * 4. A type string is automatically prepended to identify the object type
 *
 * ## Binary Format
 *
 * **Schema Encoding**:
 * ```
 * [4 bytes: tag] [4 bytes: component count]
 * [4 bytes: ".type" length] [".type" bytes] [StringSchema]
 * [4 bytes: name1 length] [name1 bytes] [schema1]
 * [4 bytes: name2 length] [name2 bytes] [schema2]
 * ...
 * ```
 *
 * **Object Encoding**:
 * ```
 * [4 bytes: type length] [type string bytes]
 * [component1 data] [component2 data] ...
 * ```
 *
 * ## Usage Examples
 *
 * ### Basic Usage
 * ```kotlin
 * data class Person(val name: String, val age: Int, val email: String)
 *
 * val personSchema = CustomStructSchema<Person>(
 *     type = "Person",
 *     componentNames = listOf("name", "age", "email"),
 *     componentSchemas = listOf(StringSchema, IntSchema, StringSchema),
 *     encoder = { person -> listOf(person.name, person.age, person.email) }
 * )
 * ```
 *
 * ### Data Transformation
 * ```kotlin
 * val transformedSchema = CustomStructSchema<Person>(
 *     type = "NormalizedPerson",
 *     componentNames = listOf("displayName", "ageCategory"),
 *     componentSchemas = listOf(StringSchema, StringSchema),
 *     encoder = { person ->
 *         listOf(
 *             person.name.uppercase(),
 *             when {
 *                 person.age < 18 -> "MINOR"
 *                 person.age < 65 -> "ADULT"
 *                 else -> "SENIOR"
 *             }
 *         )
 *     }
 * )
 * ```
 *
 * ### Selective Field Serialization
 * ```kotlin
 * val minimalSchema = CustomStructSchema<ComplexObject>(
 *     type = "Essential",
 *     componentNames = listOf("id", "status"),
 *     componentSchemas = listOf(LongSchema, StringSchema),
 *     encoder = { obj -> listOf(obj.id, obj.currentStatus.name) }
 * )
 * ```
 *
 * @param T The type of objects this schema can serialize
 * @param type A string identifier for this object type, written to the log for identification
 * @param componentNames Names of the components in serialization order. Used for schema documentation.
 * @param componentSchemas Schemas for each component, must match the order of [componentNames]
 * @param encoder Function that converts objects of type T into a list of primitive components
 *
 * @constructor Creates a new CustomStructSchema with the specified configuration
 *
 * @throws IllegalArgumentException if componentNames and componentSchemas have different sizes
 */
class CustomStructSchema<T : Any>(
    /** String identifier for this object type, included in the serialized data for type safety. */
    val type: String,

    /**
     * Names of the serialized components in order.
     *
     * These names are included in the schema metadata for documentation purposes and
     * potential future deserialization support. They should be descriptive and stable
     * across schema versions.
     */
    val componentNames: List<String>,

    /**
     * Schemas for each component in the same order as [componentNames].
     *
     * Each schema defines how the corresponding component from the [encoder] output
     * will be serialized. The number and order of schemas must exactly match the
     * components returned by the encoder function.
     */
    val componentSchemas: List<FateSchema<*>>,

    /**
     * Function that decomposes objects into serializable components.
     *
     * This function is called during serialization to convert objects of type [T] into
     * a list of primitive values that can be serialized using the [componentSchemas].
     * The returned list must:
     * - Have the same number of elements as [componentSchemas]
     * - Contain values compatible with the corresponding schemas
     * - Be deterministic (same object should always produce the same components)
     *
     * **Performance Note**: This function is called twice during serialization - once
     * for size calculation and once for actual encoding.
     * Avoid expensive computations
     * or consider caching results if the same object is serialized multiple times.
     */
    val encoder: (T) -> List<Any>
) : FateSchema<T> {
    init {
        require(componentNames.size == componentSchemas.size) {
            "componentNames (${componentNames.size}) and componentSchemas (${componentSchemas.size}) must have the same size"
        }
    }

    override val tag = FateSchema.Registry.CUSTOM.value

    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES +
            Int.SIZE_BYTES + TYPE_FIELD.first.toByteArray().size + TYPE_FIELD.second.schemaSize +
            componentNames.zip(componentSchemas).sumOf { (name, schema) ->
                Int.SIZE_BYTES + name.toByteArray(Charsets.UTF_8).size + schema.schemaSize
            }

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.putInt(componentNames.size)
        TYPE_FIELD.first.toByteArray().let {
            buffer.putInt(it.size)
            buffer.put(it)
        }
        buffer.put(TYPE_FIELD.second.schema)
        for ((name, schema) in componentNames.zip(componentSchemas)) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
            buffer.put(schema.schema)
        }
        buffer.array()
    }

    @Suppress("UNCHECKED_CAST")
    override fun objSize(obj: T): Int = TYPE_FIELD.second.objSize(type) + componentSchemas.zip(encoder(obj)).sumOf { (schema, value) ->
        schema as FateSchema<Any>
        schema.objSize(value)
    }

    @Suppress("UNCHECKED_CAST")
    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        TYPE_FIELD.second.encodeObject(buffer, type)
        for ((schema, value) in componentSchemas.zip(encoder(obj))) {
            schema as FateSchema<Any>
            schema.encodeObject(buffer, value)
        }
    }

    /** Internal type field definition used for schema metadata. */
    companion object {
        private val TYPE_FIELD = ".type" to StringSchema
    }
}

/**
 * A schema for converting objects of type [T] to another type [U] during serialization.
 *
 * @param baseSchema The schema for the base type [T]
 * @param toBase A function that converts objects of type [T] to objects of type [U]
 */
class TranslatedSchema<T : Any, U : Any>(
    val baseSchema: FateSchema<U>,
    val toBase: (T) -> U,
) : FateSchema<T> {
    /**
     * Creates a new TranslatedSchema with the specified configuration.
     *
     * @param baseClass The class of the base type [T]
     * @param toBase A function that converts objects of type [T] to objects of type [U]
     */
    constructor(baseClass: Class<U>, toBase: (T) -> U) :
            this(FateSchema.schemaOfClass(baseClass.kotlin), toBase)

    override val tag: Int = baseSchema.tag
    override val schemaSize: Int = baseSchema.schemaSize
    override val schema: ByteArray by lazy { baseSchema.schema }

    override fun objSize(obj: T): Int = baseSchema.objSize(toBase(obj))

    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        baseSchema.encodeObject(buffer, toBase(obj))
    }
}