package gay.zharel.fateweaver.telemetry

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * The entry point for logging: a shared [FateRegistry] and its root table.
 *
 * ```kotlin
 * Fate.log("batteryVoltage", hardwareMap.voltageSensor.iterator().next().voltage)
 *
 * val drive = Fate.table("Drive")
 * drive.log("leftVelocity", left.velocity)
 * drive.log("pose", follower.pose)
 * ```
 *
 * Where the values go depends on which backends are registered on [registry]. On a robot that is
 * set up for you; in a unit test, register a [MockFateBackend] and read back what was logged.
 *
 * Everything here forwards to the root table, so see [FateTable] for how values are typed, what a
 * path holds, and what thread safety to expect.
 */
object Fate {
    /** The registry this facade logs through. */
    @JvmStatic
    val registry: FateRegistry = FateRegistry()

    /** The root table, which every other table is under. */
    @JvmStatic
    val root: FateTable get() = registry.root

    /** Returns the table [name], creating it if needed. Hold on to it instead of looking it up every loop. */
    @JvmStatic
    fun table(name: String): FateTable = registry.table(name)

    /**
     * Logs [value] at [name], inferring its schema from its runtime class.
     *
     * Java callers use this as `log`. Kotlin callers should use the reified `log` extension.
     *
     * @see FateTable.logErased
     */
    @JvmStatic
    @JvmName("log")
    fun logErased(name: String, value: Any) = root.logErased(name, value)

    /**
     * Logs [value] at [name], inferring its schema from the fully resolved [type].
     *
     * @see FateTable.logTyped
     */
    @JvmStatic
    fun logTyped(name: String, value: Any, type: KType) = root.logTyped(name, value, type)
}

/**
 * Logs [value] at [name] under the root table, inferring its schema from [T]'s static type, type
 * arguments included.
 *
 * @see FateTable.log
 */
@JvmSynthetic
inline fun <reified T : Any> Fate.log(name: String, value: T) = logTyped(name, value, typeOf<T>())
