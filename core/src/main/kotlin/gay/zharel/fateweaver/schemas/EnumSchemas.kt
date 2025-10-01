package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

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

/**
 * A schema for enums that preserves the constant names for decoding.
 */
class DynamicEnumSchema(val constantNames: List<String>) : FateSchema<String> {
    override val tag: Int = FateSchema.Registry.ENUM.value
    override val schemaSize: Int = Int.SIZE_BYTES + Int.SIZE_BYTES + constantNames.sumOf {
        Int.SIZE_BYTES + it.toByteArray(Charsets.UTF_8).size
    }

    override val schema: ByteArray by lazy {
        val buffer = ByteBuffer.allocate(schemaSize)
        buffer.putInt(tag)
        buffer.putInt(constantNames.size)
        for (constantName in constantNames) {
            val bytes = constantName.toByteArray(Charsets.UTF_8)
            buffer.putInt(bytes.size)
            buffer.put(bytes)
        }
        buffer.array()
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