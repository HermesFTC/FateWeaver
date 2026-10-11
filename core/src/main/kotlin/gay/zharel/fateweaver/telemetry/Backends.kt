package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.schemas.FateSchema
import java.util.concurrent.ConcurrentHashMap

/**
 * Records logged values to a [FateLogWriter], one channel per path.
 *
 * What the writer's sink is decides where that goes: a file, a live stream, or both through a
 * [gay.zharel.fateweaver.log.MultiSink]. Closing this backend closes the writer.
 *
 * Each value is written with the timestamp the table gave it, so two backends fed the same value
 * agree on when it happened.
 */
class FateLogBackend(val writer: FateLogWriter) : FateBackend {
    private val entries = ConcurrentHashMap<String, ChannelEntry<*>>()

    override fun <T> entry(path: String, schema: FateSchema<T>): FateEntry<T> {
        @Suppress("UNCHECKED_CAST")
        return entries.computeIfAbsent(path) {
            ChannelEntry(writer, writer.createChannel(path, schema))
        } as FateEntry<T>
    }

    override fun close() = writer.close()

    override fun toString() = "FateLogBackend($writer)"

    private class ChannelEntry<T>(
        private val writer: FateLogWriter,
        private val channel: FateLogWriter.WriterChannel<T>
    ) : FateEntry<T> {
        override fun log(value: T, timestamp: Long) = writer.write(channel, value, timestamp)
    }
}

/**
 * Keeps everything it is given, for tests that want to check what robot code logged.
 *
 * ```kotlin
 * val time = TestTimeSource()
 * val registry = FateRegistry(time)
 * val mock = MockFateBackend().also(registry::register)
 *
 * registry.table("Drive").log("left", 1.5)
 *
 * assertEquals(listOf(1.5), mock.valuesAt("Drive/left"))
 * ```
 *
 * It is safe to log from several threads. The inspection methods return copies, so what they hand
 * back never changes under you, but they can be stale if logging is still running.
 */
class MockFateBackend : FateBackend {
    /** One logged value. */
    data class Record(val path: String, val value: Any?, val timestamp: Long)

    private val lock = Any()
    private val log = mutableListOf<Record>()
    private val schemas = LinkedHashMap<String, FateSchema<*>>()

    /** Every value logged, in the order the backend received them. */
    val records: List<Record> get() = synchronized(lock) { log.toList() }

    /** The paths that have an entry, in the order they were created. */
    val paths: List<String> get() = synchronized(lock) { schemas.keys.toList() }

    /** The values logged to [path], oldest first. */
    fun valuesAt(path: String): List<Any?> = synchronized(lock) {
        log.filter { it.path == path }.map { it.value }
    }

    /** The schema [path] was created with, or `null` if nothing has created it. */
    fun schemaAt(path: String): FateSchema<*>? = synchronized(lock) { schemas[path] }

    /** Forgets every logged value, keeping the entries. */
    fun clear() = synchronized(lock) { log.clear() }

    override fun <T> entry(path: String, schema: FateSchema<T>): FateEntry<T> {
        synchronized(lock) { schemas[path] = schema }

        return object : FateEntry<T> {
            override fun log(value: T, timestamp: Long) {
                synchronized(lock) { log += Record(path, value, timestamp) }
            }
        }
    }

    override fun close() {}
}
