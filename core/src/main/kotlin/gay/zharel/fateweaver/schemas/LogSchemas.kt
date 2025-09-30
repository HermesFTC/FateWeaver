package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import kotlin.jvm.kotlin

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

