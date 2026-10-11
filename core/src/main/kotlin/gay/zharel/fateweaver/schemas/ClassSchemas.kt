package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KVisibility
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.staticProperties
import kotlin.reflect.jvm.isAccessible

@MustBeDocumented
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class FateIgnore

open class ReflectedClassSchema<T : Any>(
    val fields: Map<String, FateSchema<*>>,
) : FateSchema<T> {
    override val tag: Int = FateSchema.TypeRegistry.CUSTOM.value

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

    /**
     * Resolved property accessors, keyed by the runtime class of the encoded object.
     *
     * A schema built from a superclass can receive subclass instances, so the key can't be the
     * class the schema was built from.
     */
    private val accessors = ConcurrentHashMap<KClass<*>, List<Accessor>>()

    /**
     * A property and the schema that encodes its value, resolved once per runtime class.
     */
    protected class Accessor(val property: KProperty1<Any, *>, val schema: FateSchema<Any?>)

    /**
     * Returns the accessors for [obj]'s runtime class, in [fields] iteration order.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun accessorsFor(obj: T): List<Accessor> = accessors.getOrPut(obj::class) {
        val properties = obj::class.memberProperties.associateBy { it.name }
        fields.map { (name, schema) ->
            val property = properties[name] ?: throw IllegalArgumentException(
                "Cannot encode ${obj::class.qualifiedName}: schema declares field '$name', " +
                        "but the class has no such property."
            )
            property.isAccessible = true
            Accessor(property as KProperty1<Any, *>, schema as FateSchema<Any?>)
        }
    }

    /**
     * Reads [accessor]'s value from [obj].
     *
     * Null is allowed only when the field's schema is an [OptionalSchema], which is what a property
     * declared nullable in Kotlin gets. Any other schema can't encode null, so this throws. In
     * practice that means a Java field or Kotlin platform type that is declared non-null but isn't.
     */
    protected fun valueOf(accessor: Accessor, obj: T): Any? {
        val value = accessor.property.get(obj)
        if (value == null && accessor.schema !is OptionalSchema<*>) {
            throw IllegalArgumentException(
                "Cannot encode ${obj::class.qualifiedName}.${accessor.property.name}: value is " +
                        "null, but the field is not declared nullable, so its schema has no " +
                        "encoding for null. Declare the property as nullable to log it as optional."
            )
        }
        return value
    }

    override fun objSize(obj: T): Int =
        accessorsFor(obj).sumOf { it.schema.objSize(valueOf(it, obj)) }

    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        for (accessor in accessorsFor(obj)) {
            accessor.schema.encodeObject(buffer, valueOf(accessor, obj))
        }
    }

    companion object Companion {
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> createFromClass(cls: KClass<T>): ReflectedClassSchema<T> {
            val type = if (cls.companionObject?.memberProperties?.any { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME } == true) {
                val typeProp = cls.companionObject!!.memberProperties.first { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME } as KProperty1<Any?, *>
                typeProp.get(cls.companionObjectInstance!!)
            } else if (cls.staticProperties.any { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME }) {
                // staticProperties yields KProperty0 because a static has no receiver, so get()
                // takes no argument.
                cls.staticProperties.first { it.name.uppercase() == TypedClassSchema.TYPE_FIELD_NAME }.get()
            } else null

            val owner = cls.qualifiedName ?: cls.java.name
            val fields = cls.memberProperties
                .filter { field ->
                    field.visibility == KVisibility.PUBLIC && field.returnType.classifier is KClass<*> &&
                            !field.hasAnnotation<FateIgnore>()
                }
                .associate { field ->
                    field.name to FateSchema.schemaOfType(field.returnType, "$owner.${field.name}")
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
    override val tag: Int = FateSchema.TypeRegistry.CUSTOM.value

    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES +
            Int.SIZE_BYTES + TYPE_FIELD.first.toByteArray().size + TYPE_FIELD.second.schemaSize +
            fields.map { (name, schema) ->
                Int.SIZE_BYTES + name.toByteArray(Charsets.UTF_8).size + schema.schemaSize
            }.sum()

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.putInt(fields.size + 1)
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

    override fun objSize(obj: T): Int = TYPE_FIELD.second.objSize(type) +
            accessorsFor(obj).sumOf { it.schema.objSize(valueOf(it, obj)) }

    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        TYPE_FIELD.second.encodeObject(buffer, type)
        for (accessor in accessorsFor(obj)) {
            accessor.schema.encodeObject(buffer, valueOf(accessor, obj))
        }
    }

    companion object {
        val TYPE_FIELD = ".type" to StringSchema

        val TYPE_FIELD_NAME = "AS_TYPE"
    }
}

/**
 * A schema you define by hand. You name the components and supply a function that produces them.
 *
 * Nothing is reflected. Use it for a class you can't modify, or to write fewer bytes than
 * reflection would. You can drop fields you never plot, round a double, convert units, or emit a
 * computed value that isn't a field.
 *
 * ## How it works
 *
 * [encoder] turns one object into a list of components. Each component has a name from
 * [componentNames] and a schema from [componentSchemas] at the same index. They are written in
 * that order after a leading type string.
 *
 * ## Binary format
 *
 * The schema:
 * ```
 * [4 bytes: tag] [4 bytes: component count]
 * [4 bytes: ".type" length] [".type" bytes] [StringSchema]
 * [4 bytes: name1 length] [name1 bytes] [schema1]
 * [4 bytes: name2 length] [name2 bytes] [schema2]
 * ...
 * ```
 *
 * An encoded object:
 * ```
 * [4 bytes: type length] [type string bytes]
 * [component1 data] [component2 data] ...
 * ```
 *
 * ## Examples
 *
 * ### Basic usage
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
 * ### Transforming values
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
 * ### Logging a subset of fields
 * ```kotlin
 * val minimalSchema = CustomStructSchema<ComplexObject>(
 *     type = "Essential",
 *     componentNames = listOf("id", "status"),
 *     componentSchemas = listOf(LongSchema, StringSchema),
 *     encoder = { obj -> listOf(obj.id, obj.currentStatus.name) }
 * )
 * ```
 *
 * @param T The type of objects this schema serializes
 * @param type Identifier for this object type, written to the log as the first value
 * @param componentNames Names of the components, in the order they are written
 * @param componentSchemas Schemas for each component, in the same order as [componentNames]
 * @param encoder Converts an object into its list of components
 *
 * @throws IllegalArgumentException if componentNames and componentSchemas have different sizes
 */
class CustomStructSchema<T : Any>(
    /** Identifier for this object type, written before the components in every encoded object. */
    val type: String,

    /** Names of the components, in the order they are written. They go into the schema entry. */
    val componentNames: List<String>,

    /**
     * Schemas for each component, in the same order as [componentNames].
     *
     * The list must line up with what [encoder] returns, in count and order.
     */
    val componentSchemas: List<FateSchema<*>>,

    /**
     * Converts an object into the values that [componentSchemas] encode.
     *
     * The returned list must have one element per schema, each compatible with its schema. It must
     * return the same components for the same object.
     *
     * The writer calls this twice per logged value, once to size the output and once to encode it.
     * Keep it to field reads and arithmetic, because anything expensive runs twice.
     */
    val encoder: (T) -> List<Any>
) : FateSchema<T> {
    init {
        require(componentNames.size == componentSchemas.size) {
            "componentNames (${componentNames.size}) and componentSchemas (${componentSchemas.size}) must have the same size"
        }
    }

    override val tag = FateSchema.TypeRegistry.CUSTOM.value

    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES +
            Int.SIZE_BYTES + TYPE_FIELD.first.toByteArray().size + TYPE_FIELD.second.schemaSize +
            componentNames.zip(componentSchemas).sumOf { (name, schema) ->
                Int.SIZE_BYTES + name.toByteArray(Charsets.UTF_8).size + schema.schemaSize
            }

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.putInt(componentNames.size + 1)
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

    /** The name and schema of the leading type string. */
    companion object {
        private val TYPE_FIELD = ".type" to StringSchema
    }
}

/**
 * A schema that converts a [T] to a [U] and encodes the result with [baseSchema].
 *
 * It reports the same tag and schema bytes as [baseSchema], so readers see a [U].
 *
 * @param baseSchema The schema for [U]
 * @param toBase Converts a [T] to a [U]
 */
class TranslatedSchema<T : Any, U : Any>(
    val baseSchema: FateSchema<U>,
    val toBase: (T) -> U,
) : FateSchema<T> {
    /**
     * Creates a schema for [baseClass] by inference and uses it as the base schema.
     *
     * @param baseClass The class of [U]
     * @param toBase Converts a [T] to a [U]
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