package gay.zharel.fateweaver.log

import java.nio.ByteBuffer
import kotlin.collections.iterator
import kotlin.jvm.kotlin
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

sealed interface FateSchema<T> {

    /**
     * Unique identifier for the entry.
     */
    val tag: Int

    /**
     * Number of bytes used to store the schema.
     */
    val schemaSize: Int

    /**
     * The schema for this type as a ByteArray.
     * The default implementation writes the tag as an integer,
     * but this property should be overridden to include other information
     * for non-primitive schemas.
     */
    val schema: ByteArray
        get() {
            val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
            buffer.putInt(tag)
            return buffer.array()
        }

    /**
     * Encodes the entry's schema into [buffer]. Must start with the schema [tag].
     * The default implementation writes the schema property,
     * but this method can be overridden for custom behavior.
     */
    fun encodeSchema(buffer: ByteBuffer) {
        buffer.put(schema)
    }

    /**
     * The number of bytes used to store the object.
     */
    fun objSize(obj: T): Int

    /**
     * Encodes [obj] into [buffer].
     */
    fun encodeObject(buffer: ByteBuffer, obj: T)

    enum class Registry(val value: Int) {
        REFLECTED_CLASS(0),
        INT(1),
        LONG(2),
        DOUBLE(3),
        STRING(4),
        BOOLEAN(5),
        ENUM(6),
        ARRAY(7);
    }

    companion object Companion {
        /**
         * Returns the schema for [clazz].
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

object IntSchema : FateSchema<Int> {
    override val tag: Int = FateSchema.Registry.INT.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Int): Int = Int.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Int) {
        buffer.putInt(obj)
    }
}

object LongSchema : FateSchema<Long> {
    override val tag: Int = FateSchema.Registry.LONG.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Long): Int = Long.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Long) {
        buffer.putLong(obj)
    }
}

object DoubleSchema : FateSchema<Double> {
    override val tag: Int = FateSchema.Registry.DOUBLE.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Double): Int = Double.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Double) {
        buffer.putDouble(obj)
    }
}

object StringSchema : FateSchema<String> {
    override val tag: Int = FateSchema.Registry.STRING.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: String): Int = Int.SIZE_BYTES + obj.toByteArray(Charsets.UTF_8).size

    override fun encodeObject(buffer: ByteBuffer, obj: String) {
        val bytes = obj.toByteArray(Charsets.UTF_8)
        buffer.putInt(bytes.size)
        buffer.put(bytes)
    }
}

object BooleanSchema : FateSchema<Boolean> {
    override val tag: Int = FateSchema.Registry.BOOLEAN.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Boolean): Int = Byte.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Boolean) {
        buffer.put(if (obj) 1.toByte() else 0.toByte())
    }
}

class EnumSchema<T : Enum<T>>(val enumClass: Class<out Enum<T>>) : FateSchema<Enum<T>> {
    init {
        require(enumClass.isEnum) { "Class must be an enum" }
    }

    override val tag: Int = FateSchema.Registry.ENUM.value

    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES + enumClass.enumConstants.sumOf { constant ->
        Int.SIZE_BYTES + constant.name.toByteArray(Charsets.UTF_8).size
    }

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        val constants = enumClass.enumConstants
        buffer.putInt(constants.size)
        for (constant in constants) {
            val bytes = constant.name.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }
        buffer.array()
    }

    override fun objSize(obj: Enum<T>): Int = Int.SIZE_BYTES

    override fun encodeObject(buffer: ByteBuffer, obj: Enum<T>) {
        buffer.putInt(obj.ordinal)
    }
}

class ArraySchema<T>(val elementSchema: FateSchema<T>) : FateSchema<Array<T>> {
    override val tag: Int = FateSchema.Registry.ARRAY.value

    override val schemaSize: Int = Int.SIZE_BYTES + elementSchema.schemaSize

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.put(elementSchema.schema)
        buffer.array()
    }

    override fun objSize(obj: Array<T>): Int = Int.SIZE_BYTES + obj.sumOf {
        elementSchema.objSize(it)
    }

    fun cast(o: Any): Array<*> {
        return when (o) {
            is IntArray -> o.toTypedArray()
            is LongArray -> o.toTypedArray()
            is DoubleArray -> o.toTypedArray()
            is BooleanArray -> o.toTypedArray()
            is Array<*> -> o
            else -> throw IllegalArgumentException("unsupported array type: ${o.javaClass}")
        }
    }

    override fun encodeObject(buffer: ByteBuffer, obj: Array<T>) {
        buffer.putInt(obj.size)
        for (element in obj) {
            elementSchema.encodeObject(buffer, element)
        }
    }
}

class ReflectedClassSchema<T : Any>(
    val fields: Map<String, FateSchema<*>>,
) : FateSchema<T> {
    override val tag: Int = FateSchema.Registry.REFLECTED_CLASS.value

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
            val fields = cls.memberProperties.associate { field ->
                field.isAccessible = true
                field.name to FateSchema.schemaOfClass((field.returnType.classifier as KClass<*>).java)
            }
            return ReflectedClassSchema(fields)
        }

        fun <T : Any> createFromClass(cls: Class<T>): ReflectedClassSchema<T> = createFromClass(cls.kotlin)
    }
}
