package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.schemas.FateSchema

/**
 * Where one path's values end up.
 *
 * A [FateTable] builds one entry per path the first time a value is logged there, fixing the
 * schema, and then feeds every later value for that path through it.
 *
 * Implementations must be safe to call from any thread, and must not throw: report a recoverable
 * problem through the registry's warning handler (or just drop the value) rather than letting it
 * reach the robot code that logged it.
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
     * schema every value on it will use. Calling this again for a path already created is expected,
     * because a table rebuilds its entries whenever the registry's backends change, and must hand
     * back an entry that still works rather than fail on the duplicate.
     */
    fun <T> entry(path: String, schema: FateSchema<T>): FateEntry<T>
}
