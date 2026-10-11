package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.schemas.FateSchema

/**
 * Where one path's values end up.
 *
 * A [FateTable] builds one entry per path the first time a value is logged there, which fixes the
 * schema. Every later value for that path goes through the same entry.
 *
 * Implementations must be safe to call from any thread and must not throw. To report a problem,
 * use the registry's warning handler or drop the value. Don't let it reach the robot code that
 * logged it.
 *
 * @param T The type of value this entry accepts
 */
interface FateEntry<T> {
    /**
     * Records [value].
     *
     * @param value The value to record
     * @param timestamp When the value was produced, in nanoseconds since the registry was created.
     *   Every backend a value reaches gets the same timestamp.
     */
    fun log(value: T, timestamp: Long)
}

/**
 * A destination for logged values: a `.fate` file, a live stream, a test double.
 *
 * Register one with [FateRegistry.register]. Robot code never sees backends. It logs to a
 * [FateTable], and the registry fans the value out to whatever is registered.
 *
 * Like [FateEntry], a backend must be thread-safe and must not throw from [entry].
 */
interface FateBackend : AutoCloseable {
    /**
     * Returns the entry for [path], creating it if this backend has not seen the path.
     *
     * [path] is the full, slash-separated name, such as `Drive/leftVelocity`, and [schema] is the
     * schema every value on it will use.
     *
     * A table calls this again for an existing path whenever the registry's backends change. Return
     * an entry that works instead of failing on the duplicate.
     */
    fun <T> entry(path: String, schema: FateSchema<T>): FateEntry<T>
}
