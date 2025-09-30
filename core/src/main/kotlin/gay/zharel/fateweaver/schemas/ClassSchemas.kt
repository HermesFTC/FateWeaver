package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
import kotlin.collections.iterator
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

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