package gay.zharel.fateweaver.schemas

import java.nio.ByteBuffer

/**
 * Encodes an enum constant as its 4-byte ordinal.
 *
 * The schema lists the constant names, so a reader can show names instead of ordinals. The value
 * type is [T], not `Enum<T>`, so a channel is a `LogChannel<MyEnum>` and the schema works as a map
 * key or array element.
 */
class EnumSchema<T : Enum<T>>(val enumClass: Class<out Enum<T>>) : FateSchema<T> {
    init {
        require(enumClass.isEnum) { "Class must be an enum" }
    }

    override val tag: Int = FateSchema.TypeRegistry.ENUM.value

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

    override fun objSize(obj: T): Int = Int.SIZE_BYTES

    override fun encodeObject(buffer: ByteBuffer, obj: T) {
        buffer.putInt(obj.ordinal)
    }
}

/**
 * Writes the same bytes as [EnumSchema], but takes the constant name as a String instead of an
 * enum value. Throws [IllegalArgumentException] for a name that is not in [constantNames].
 */
class DynamicEnumSchema(val constantNames: List<String>) : FateSchema<String> {
    override val tag: Int = FateSchema.TypeRegistry.ENUM.value
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