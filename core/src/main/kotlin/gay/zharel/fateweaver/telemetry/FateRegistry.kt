package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.schemas.FateSchema
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.TimeSource

/** Receives the warnings telemetry reports instead of throwing. */
fun interface FateWarningHandler {
    fun warn(message: String)
}

/**
 * The set of [FateBackend]s that logged values are routed to, plus the time source that stamps them.
 *
 * Most code uses [Fate], which wraps one shared registry. This is a class so a test can build its
 * own with a [kotlin.time.TestTimeSource] and a [MockFateBackend] without affecting anything else.
 *
 * ## Timestamps
 *
 * Every value is stamped in nanoseconds with the time elapsed on [timeSource] since the registry
 * was created or last restarted. Only differences between stamps mean anything.
 *
 * ## Backends
 *
 * Every registered backend receives every value. Backends can change while other threads are
 * logging. A table notices the change on its next log and rebuilds its entries against the new set.
 *
 * ## Warnings
 *
 * Telemetry reports a wrong type, a schema it can't infer, or a backend that threw through
 * [warningHandler], then carries on. A logging mistake should not end a match. Each distinct message
 * is reported once, so a bad value logged every loop does not flood the output.
 */
class FateRegistry @JvmOverloads constructor(
    /** Stamps every logged value. Defaults to [TimeSource.Monotonic]. */
    val timeSource: TimeSource = TimeSource.Monotonic
) {
    @Volatile
    private var origin = timeSource.markNow()

    /** Nanoseconds elapsed on [timeSource] since this registry was created, or last restarted. */
    internal fun timestamp(): Long = origin.elapsedNow().inWholeNanoseconds

    /**
     * Makes the next timestamp count from zero again.
     *
     * Call this when a new log begins, so its first message is stamped near zero instead of with
     * the time the program has been running. Stamps from before and after a restart have no common
     * origin, so don't restart while a log is still open.
     */
    fun restartClock() {
        origin = timeSource.markNow()
    }

    private val backends = CopyOnWriteArrayList<FateBackend>()

    /** Bumped whenever [backends] changes, which is how tables learn their entries are stale. */
    private val generationCounter = AtomicLong()
    internal val generation: Long get() = generationCounter.get()

    private val warned: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Where warnings go. The default prints to standard error. A handler must not throw, and may be
     * called from any thread.
     */
    @Volatile
    var warningHandler: FateWarningHandler = FateWarningHandler { System.err.println("[FateWeaver] $it") }

    /** The root table, whose path is empty. */
    val root: FateTable = FateTable(this, "")

    /** Returns the table at [name] under the root. */
    fun table(name: String): FateTable = root.table(name)

    /** How many backends are registered. */
    val backendCount: Int get() = backends.size

    /**
     * Adds [backend]. It receives every value logged after this call, including on paths that were
     * already being logged.
     */
    fun register(backend: FateBackend) {
        backends.add(backend)
        generationCounter.incrementAndGet()
    }

    /**
     * Removes [backend] without closing it.
     *
     * @return `true` if it was registered
     */
    fun unregister(backend: FateBackend): Boolean {
        val removed = backends.remove(backend)
        if (removed) generationCounter.incrementAndGet()
        return removed
    }

    /**
     * Closes and removes every backend, and forgets which warnings have been reported.
     *
     * A backend that throws on close is skipped with a warning, so one bad backend cannot leave the
     * rest open.
     */
    fun reset() {
        val closing = backends.toList()
        backends.clear()
        generationCounter.incrementAndGet()
        warned.clear()

        for (backend in closing) {
            try {
                backend.close()
            } catch (e: Exception) {
                warn("Backend $backend failed to close: $e")
            }
        }
    }

    /**
     * Reports [message] to [warningHandler], unless this exact message has been reported before.
     * After 1000 distinct messages, new ones are dropped.
     */
    fun warn(message: String) {
        // Past the cap, drop new messages instead of remembering them, so the set can't grow without
        // bound. A robot with this many distinct warnings has bigger problems than a repeated line.
        if (warned.size >= MAX_REMEMBERED_WARNINGS || !warned.add(message)) return

        try {
            warningHandler.warn(message)
        } catch (_: Exception) {
            // The handler is documented not to throw. If it does, there is nowhere left to report to.
        }
    }

    /**
     * Builds the entry a table uses for [path]. It wraps one entry per backend, so a table makes a
     * single call.
     *
     * A backend that can't make its entry is skipped with a warning, and the rest still receive
     * values.
     */
    internal fun <T> entryFor(path: String, schema: FateSchema<T>): FateEntry<T> {
        val entries = backends.mapNotNull { backend ->
            try {
                backend.entry(path, schema)
            } catch (e: Exception) {
                warn("Backend $backend could not create an entry for '$path': $e")
                null
            }
        }

        return when (entries.size) {
            0 -> DiscardEntry()
            1 -> entries.single()
            else -> MultiEntry(entries, path, this)
        }
    }

    private companion object {
        const val MAX_REMEMBERED_WARNINGS = 1000
    }
}

/** Used when no backend is registered. Drops every value. */
private class DiscardEntry<T> : FateEntry<T> {
    override fun log(value: T, timestamp: Long) {}
}

/** Sends each value to every backend's entry, so one failing backend cannot starve the others. */
private class MultiEntry<T>(
    private val entries: List<FateEntry<T>>,
    private val path: String,
    private val registry: FateRegistry
) : FateEntry<T> {
    override fun log(value: T, timestamp: Long) {
        for (entry in entries) {
            try {
                entry.log(value, timestamp)
            } catch (e: Exception) {
                registry.warn("Backend entry $entry failed to log to '$path': $e")
            }
        }
    }
}
