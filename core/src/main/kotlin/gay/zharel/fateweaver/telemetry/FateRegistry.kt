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
 * Most code never touches this directly: [Fate] wraps a single shared registry. It is a class, not
 * a singleton, so a test can build its own with a [kotlin.time.TestTimeSource] and a
 * [MockFateBackend] and not disturb anything else.
 *
 * ## Timestamps
 *
 * Every value is stamped with the time elapsed on [timeSource] since the registry was created, in
 * nanoseconds. Only differences between stamps mean anything, which is all a log reader needs.
 *
 * Every registered backend receives every value. The registry is safe to use while other threads
 * are logging. A table notices a change in the backends on its next log and rebuilds its entries
 * against the new set.
 *
 * ## Warnings
 *
 * Telemetry reports problems (a value of the wrong type, a schema that can't be inferred, a backend
 * that threw) through [warningHandler] and carries on, because a logging mistake should not end a
 * match. Each distinct message is reported once, so a bad value logged every loop does not flood the
 * output.
 */
class FateRegistry @JvmOverloads constructor(
    /** Stamps every logged value. Defaults to [TimeSource.Monotonic]. */
    val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val origin = timeSource.markNow()

    /** Nanoseconds elapsed on [timeSource] since this registry was created. */
    internal fun timestamp(): Long = origin.elapsedNow().inWholeNanoseconds

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
     * Adds [backend], so it receives every value logged from now on, plus the next value logged
     * to each path that has been logged before.
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

    /** Reports [message] to [warningHandler], unless this exact message has been reported before. */
    fun warn(message: String) {
        // Past the cap, stop remembering rather than grow without bound; a robot that has produced
        // this many distinct warnings has bigger problems than a repeated line.
        if (warned.size >= MAX_REMEMBERED_WARNINGS || !warned.add(message)) return

        try {
            warningHandler.warn(message)
        } catch (_: Exception) {
            // The handler is documented not to throw. If it does, there is nowhere left to report to.
        }
    }

    /**
     * Builds the entry a table uses for [path]: one entry per backend, behind a single call.
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

/** What a path logs to when no backend is registered: nowhere. */
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
