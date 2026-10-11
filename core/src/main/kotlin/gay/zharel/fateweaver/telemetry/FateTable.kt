package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.schemas.FateSchema
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * An object that knows how to publish itself into a [FateTable].
 *
 * Implement this for a type that is really several values, so call sites log one object and the
 * type decides what appears under it:
 *
 * ```kotlin
 * class DriveState(val left: Double, val right: Double, val pose: Pose) : FateLoggable {
 *     override fun logTo(table: FateTable) {
 *         table.log("left", left)
 *         table.log("right", right)
 *         table.log("pose", pose)
 *     }
 * }
 *
 * Fate.log("drive", driveState)   // Drive state lands under drive/left, drive/right, drive/pose
 * ```
 *
 * Logging a loggable is a series of separate writes, not one atomic snapshot. A reader can see some
 * fields from this loop and some from the last. If the fields must agree, copy them into an
 * immutable object first.
 */
fun interface FateLoggable {
    /** Writes this object's values into [table], which is the child table named by the caller. */
    fun logTo(table: FateTable)
}

/**
 * A namespace of logged values: a path like `Drive`, whose entries are `Drive/leftVelocity` and so
 * on, and whose children are tables like `Drive/Odometry`.
 *
 * Tables are cheap and cache what they need, so hold one in a field rather than looking it up every
 * loop:
 *
 * ```kotlin
 * private val table = Fate.table("Shooter")
 *
 * fun periodic() {
 *     table.log("wheelVelocity", encoder.velocity)
 *     table.log("ready", atSpeed())
 * }
 * ```
 *
 * A path holds one type of value for as long as the program runs. Logging a different type to it
 * reports a warning and skips the value rather than corrupting the log. Telemetry never throws into
 * the code that calls it: a value it can't log is reported through the registry's warning handler.
 *
 * A table may be used from any thread. A call is the unit of thread safety, and concurrent calls to
 * one path are safe but land in no guaranteed order. Telemetry does not guard the objects you hand
 * it, so don't mutate a value while it is being logged.
 *
 * The schema of a value comes from its type, the same way [gay.zharel.fateweaver.log.FateLogWriter]
 * infers it. In Kotlin, `log` is a reified extension, so a parameterized value such as a
 * `Map<String, Double>` keeps its type arguments. Java sees [logErased] as `log`, which only has the
 * runtime class to go on and so rejects maps and collections.
 *
 * @property path The slash-separated path of this table, empty for the root.
 */
class FateTable internal constructor(
    private val registry: FateRegistry,
    val path: String
) {
    private val children = ConcurrentHashMap<String, FateTable>()
    private val slots = ConcurrentHashMap<String, Slot>()

    /**
     * What a table remembers about one path.
     *
     * [key] is what the path was first logged as: the [KType] when it had type arguments, else the
     * value's class. [entry] is `null` for a path whose schema could not be inferred, so a value
     * that can never be logged is skipped cheaply instead of failing the same way every loop.
     */
    private class Slot(
        val generation: Long,
        val key: Any,
        val schema: FateSchema<*>?,
        val entry: FateEntry<Any?>?
    )

    /**
     * Returns the child table [name], creating it if needed. A name with slashes, like `a/b`, is
     * the same as asking for `a` and then `b`.
     */
    fun table(name: String): FateTable {
        var table = this
        for (part in name.split('/')) {
            if (part.isEmpty()) continue
            val parent = table
            table = parent.children.computeIfAbsent(part) { FateTable(registry, join(parent.path, part)) }
        }
        return table
    }

    /**
     * Logs [value] at [name] in this table, inferring its schema from its runtime class.
     *
     * Java callers use this as `log`. Kotlin callers should use the reified `log` extension instead,
     * which also sees type arguments.
     *
     * If [value] is a [FateLoggable], it is logged into the child table [name] instead.
     */
    @JvmName("log")
    fun logErased(name: String, value: Any) = guarded(name) { logValue(name, value, null) }

    /**
     * Logs [value] at [name] in this table, inferring its schema from the fully resolved [type]
     * rather than from [value]'s erased runtime class.
     *
     * This is what the reified `log` extension calls. Call it directly if you got a [KType] some
     * other way. [type] only matters when it has type arguments, since that is the one thing the
     * runtime class can't supply.
     */
    fun logTyped(name: String, value: Any, type: KType) = guarded(name) { logValue(name, value, type) }

    private inline fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            registry.warn("Could not log '${join(path, name)}': $e")
        }
    }

    private fun logValue(name: String, value: Any, type: KType?) {
        val slash = name.lastIndexOf('/')
        if (slash >= 0) {
            // "a/b/c" is entry c of table a/b
            table(name.substring(0, slash)).logValue(name.substring(slash + 1), value, type)
            return
        }

        if (name.isEmpty()) {
            registry.warn("Could not log to '$path': a value needs a name")
            return
        }

        if (value is FateLoggable) {
            value.logTo(table(name))
            return
        }

        val key: Any = if (type != null && type.arguments.isNotEmpty()) type else value.javaClass
        val slot = slotFor(name, key, value, type) ?: return

        if (!compatible(slot.key, key, value)) {
            registry.warn(
                "Could not log '${join(path, name)}': it holds ${describe(slot.key)}, " +
                    "but this value is ${describe(key)}. A path keeps one type, so use a different name."
            )
            return
        }

        val entry = slot.entry ?: return
        entry.log(value, registry.timestamp())
    }

    /**
     * Returns the slot for [name], building or rebuilding it as needed, or `null` if the path can't
     * be logged to.
     */
    private fun slotFor(name: String, key: Any, value: Any, type: KType?): Slot? {
        val generation = registry.generation
        val existing = slots[name]
        if (existing != null && existing.generation == generation) return existing

        val fresh = if (existing != null) {
            // The backends changed. Keep the type the path was established with, and only rebuild
            // its entry against the new backends.
            rebuild(name, existing, generation)
        } else {
            create(name, key, value, type, generation)
        }

        // Two threads can race to set up one path; the first one in wins, and the loser's value is
        // checked against the winner's type like any other later value.
        return slots.compute(name) { _, current ->
            if (current != null && current.generation == generation) current else fresh
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun create(name: String, key: Any, value: Any, type: KType?, generation: Long): Slot {
        val fullPath = join(path, name)
        val schema = try {
            if (key is KType) FateSchema.schemaOfType(type!!) else FateSchema.schemaOfClass(value.javaClass)
        } catch (e: Exception) {
            registry.warn("Could not log '$fullPath': no schema for ${describe(key)}. ${e.message}")
            return Slot(generation, key, null, null)
        }

        return Slot(generation, key, schema, registry.entryFor(fullPath, schema as FateSchema<Any?>))
    }

    @Suppress("UNCHECKED_CAST")
    private fun rebuild(name: String, old: Slot, generation: Long): Slot {
        val schema = old.schema ?: return Slot(generation, old.key, null, null)
        return Slot(generation, old.key, schema, registry.entryFor(join(path, name), schema as FateSchema<Any?>))
    }

    /**
     * Whether [value], of type [key], belongs on a path established as [slotKey].
     *
     * Two parameterized types must match exactly. Otherwise a path that was set up from a runtime
     * class accepts instances of it, and one set up from a parameterized type accepts anything,
     * since an unparameterized value has no type arguments to contradict it and a wrong one fails
     * when it is encoded.
     */
    private fun compatible(slotKey: Any, key: Any, value: Any): Boolean = when {
        slotKey == key -> true
        slotKey is KType && key is KType -> false
        slotKey is Class<*> -> slotKey.isInstance(value)
        else -> true
    }

    private fun describe(key: Any): String = if (key is Class<*>) key.name else key.toString()

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    override fun toString() = "FateTable(${path.ifEmpty { "/" }})"
}

/**
 * Logs [value] at [name], inferring its schema from [T]'s static type, type arguments included.
 *
 * This is the Kotlin form of [FateTable.logErased], and the one that can log a parameterized value:
 *
 * ```kotlin
 * table.log("powers", mapOf("fl" to 0.5))    // Map<String, Double>
 * table.log("path", listOf(a, b, c))         // List<Pose>
 * table.log("voltage", battery.voltage)      // Double
 * ```
 *
 * It is an extension rather than a member because a member would erase to the same JVM signature as
 * [FateTable.logErased]. See [FateTable] for how a path's type is fixed.
 */
@JvmSynthetic
inline fun <reified T : Any> FateTable.log(name: String, value: T) = logTyped(name, value, typeOf<T>())
