package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

class ArraySchema<T>(val elementSchema: FateSchema<T>) : FateSchema<Array<T>> {
    override val tag: Int = FateSchema.TypeRegistry.ARRAY.value

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