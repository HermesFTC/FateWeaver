package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer
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
    override val tag: Int = FateSchema.Registry.REFLECTED_CLASS.value

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