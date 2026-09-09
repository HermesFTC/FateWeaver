package gay.zharel.fateweaver.schemas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * Covers how [ReflectedClassSchema] resolves and caches property accessors.
 */
class ReflectedAccessTest {

    open class Base(@JvmField val id: Int)

    class Derived(id: Int, @JvmField val extra: String) : Base(id)

    private class PrivateStruct(@JvmField val value: Int)

    class WithNullable(@JvmField val name: String?)

    @Test
    fun testSchemaFromSuperclassEncodesSubclassInstance() {
        val schema = ReflectedClassSchema.createFromClass(Base::class)

        assertEquals(setOf("id"), schema.fields.keys)

        // The accessor cache is keyed on the runtime class, so a Derived instance must resolve too.
        val objSize = schema.objSize(Derived(7, "ignored"))
        assertEquals(Int.SIZE_BYTES, objSize)

        val buffer = ByteBuffer.allocate(objSize)
        schema.encodeObject(buffer, Derived(7, "ignored"))
        buffer.flip()
        assertEquals(7, buffer.int)
    }

    @Test
    fun testEncodesInstanceOfNonPublicClass() {
        val schema = ReflectedClassSchema.createFromClass(PrivateStruct::class)
        val struct = PrivateStruct(42)

        // Before accessors were resolved with isAccessible = true, reflecting on a non-public
        // class threw IllegalCallableAccessException here.
        val buffer = ByteBuffer.allocate(schema.objSize(struct))
        schema.encodeObject(buffer, struct)
        buffer.flip()
        assertEquals(42, buffer.int)
    }

    @Test
    fun testRepeatedEncodesReuseCachedAccessors() {
        val schema = ReflectedClassSchema.createFromClass(Base::class)

        repeat(3) { i ->
            val obj = Base(i)
            val buffer = ByteBuffer.allocate(schema.objSize(obj))
            schema.encodeObject(buffer, obj)
            buffer.flip()
            assertEquals(i, buffer.int)
        }
    }

    @Test
    fun testNullableFieldEncodesAsOptional() {
        val schema = ReflectedClassSchema.createFromClass(WithNullable::class)

        assertInstanceOf(OptionalSchema::class.java, schema.fields["name"])
        assertEquals(1, schema.objSize(WithNullable(null)))
        assertEquals(1 + 4 + 2, schema.objSize(WithNullable("hi")))
    }

    @Test
    fun testNullInANonOptionalFieldIsRejectedWithFieldName() {
        // A schema built by hand, or one inferred from a Java field whose non-null declaration
        // lies, can pair a non-optional schema with a null value. That has no encoding, so it must
        // name the field rather than write something wrong.
        val schema = ReflectedClassSchema<WithNullable>(mapOf("name" to StringSchema))

        val error = assertThrows(IllegalArgumentException::class.java) {
            schema.objSize(WithNullable(null))
        }

        assertTrue(error.message!!.contains("WithNullable.name"), "was: ${error.message}")
        assertTrue(error.message!!.contains("not declared nullable"), "was: ${error.message}")
    }

    @Test
    fun testSchemaDeclaringUnknownFieldIsRejected() {
        val schema = ReflectedClassSchema<Base>(mapOf("notAField" to IntSchema))

        val error = assertThrows(IllegalArgumentException::class.java) {
            schema.objSize(Base(1))
        }

        assertTrue(error.message!!.contains("notAField"), "was: ${error.message}")
    }
}
