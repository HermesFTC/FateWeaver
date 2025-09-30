package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

/**
 * A schema for enums that preserves the constant names for decoding.
 */
class DynamicEnumSchema(val constantNames: List<String>) : FateSchema<String> {
    override val tag: Int = FateSchema.Registry.ENUM.value
    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES + constantNames.sumOf {
        Int.SIZE_BYTES + it.toByteArray(Charsets.UTF_8).size
    }

    override fun encodeSchema(buffer: ByteBuffer) {
        buffer.putInt(tag)
        buffer.putInt(constantNames.size)
        for (constantName in constantNames) {
            val bytes = constantName.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }
    }

    override fun objSize(obj: String): Int = Int.SIZE_BYTES

    override fun encodeObject(buffer: ByteBuffer, obj: String) {
        val ordinal = constantNames.indexOf(obj)
        if (ordinal == -1) {
            throw IllegalArgumentException("Unknown enum constant: $obj")
        }
        buffer.putInt(ordinal)
    }

    fun getConstantName(ordinal: Int): String {
        if (ordinal < 0 || ordinal >= constantNames.size) {
            throw IllegalArgumentException("Invalid enum ordinal: $ordinal. Valid range: 0-${constantNames.size - 1}")
        }
        return constantNames[ordinal]
    }
}