package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

object IntSchema : FateSchema<Int> {
    override val tag: Int = FateSchema.TypeRegistry.INT.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Int): Int = Int.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Int) {
        buffer.putInt(obj)
    }
}

object LongSchema : FateSchema<Long> {
    override val tag: Int = FateSchema.TypeRegistry.LONG.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Long): Int = Long.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Long) {
        buffer.putLong(obj)
    }
}

object DoubleSchema : FateSchema<Double> {
    override val tag: Int = FateSchema.TypeRegistry.DOUBLE.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Double): Int = Double.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Double) {
        buffer.putDouble(obj)
    }
}

object StringSchema : FateSchema<String> {
    override val tag: Int = FateSchema.TypeRegistry.STRING.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: String): Int = Int.SIZE_BYTES + obj.toByteArray(Charsets.UTF_8).size

    override fun encodeObject(buffer: ByteBuffer, obj: String) {
        val bytes = obj.toByteArray(Charsets.UTF_8)
        buffer.putInt(bytes.size)
        buffer.put(bytes)
    }
}

object BooleanSchema : FateSchema<Boolean> {
    override val tag: Int = FateSchema.TypeRegistry.BOOLEAN.value
    override val schemaSize: Int = Int.SIZE_BYTES

    override fun objSize(obj: Boolean): Int = Byte.SIZE_BYTES
    override fun encodeObject(buffer: ByteBuffer, obj: Boolean) {
        buffer.put(if (obj) 1.toByte() else 0.toByte())
    }
}