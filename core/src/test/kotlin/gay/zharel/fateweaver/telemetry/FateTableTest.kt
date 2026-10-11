package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.schemas.DoubleSchema
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.schemas.MapSchema
import gay.zharel.fateweaver.schemas.StringSchema
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private class Pose(val x: Double, val y: Double)

/** A fixture: a registry on a fake clock, with a mock backend and every warning collected. */
private class Env {
    val time = TestTimeSource()
    val registry = FateRegistry(time)
    val mock = MockFateBackend().also(registry::register)
    val warnings = mutableListOf<String>()

    init {
        registry.warningHandler = FateWarningHandler { synchronized(warnings) { warnings += it } }
    }
}

private class Failing(private val failEntry: Boolean = false, private val failLog: Boolean = false) : FateBackend {
    var closed = false

    override fun <T> entry(path: String, schema: FateSchema<T>): FateEntry<T> {
        if (failEntry) throw IllegalStateException("entry boom")
        return object : FateEntry<T> {
            override fun log(value: T, timestamp: Long) {
                if (failLog) throw IllegalStateException("log boom")
            }
        }
    }

    override fun close() {
        closed = true
    }
}

class FateTableTest {
    @Test
    fun `logs values at their full path`() {
        val env = Env()
        env.registry.root.log("battery", 12.4)
        val drive = env.registry.table("Drive")
        drive.log("left", 1.5)
        drive.log("enabled", true)
        drive.log("state", "RUNNING")
        drive.log("count", 3)

        assertEquals(listOf("battery", "Drive/left", "Drive/enabled", "Drive/state", "Drive/count"), env.mock.paths)
        assertEquals(listOf<Any?>(12.4), env.mock.valuesAt("battery"))
        assertEquals(listOf<Any?>(1.5), env.mock.valuesAt("Drive/left"))
        assertEquals(listOf<Any?>(true), env.mock.valuesAt("Drive/enabled"))
        assertEquals(listOf<Any?>("RUNNING"), env.mock.valuesAt("Drive/state"))
        assertEquals(listOf<Any?>(3), env.mock.valuesAt("Drive/count"))
        assertTrue(env.warnings.isEmpty(), env.warnings.toString())
    }

    @Test
    fun `nested tables and slashed names reach the same path`() {
        val env = Env()
        env.registry.table("a").table("b").log("x", 1)
        env.registry.table("a/b").log("y", 2)
        env.registry.root.log("a/b/z", 3)
        env.registry.table("/a//b/").log("w", 4)

        assertEquals(listOf("a/b/x", "a/b/y", "a/b/z", "a/b/w"), env.mock.paths)
        assertEquals("a/b", env.registry.table("a/b").path)
        assertEquals("", env.registry.root.path)
    }

    @Test
    fun `stamps each value with the time since the registry was created`() {
        val env = Env()
        val table = env.registry.table("T")

        table.log("v", 1.0)
        env.time += 5.milliseconds
        table.log("v", 2.0)
        env.time += 10.milliseconds
        table.log("v", 3.0)

        assertEquals(listOf(0L, 5_000_000L, 15_000_000L), env.mock.records.map { it.timestamp })
    }

    @Test
    fun `restarting the clock counts from zero again`() {
        val env = Env()
        val table = env.registry.table("T")

        env.time += 50.milliseconds
        table.log("v", 1.0)
        env.registry.restartClock()
        env.time += 3.milliseconds
        table.log("v", 2.0)

        assertEquals(listOf(50_000_000L, 3_000_000L), env.mock.records.map { it.timestamp })
    }

    @Test
    fun `reified log keeps type arguments`() {
        val env = Env()
        env.registry.table("Motors").log("powers", mapOf("fl" to 0.5, "fr" to -0.5))

        assertIs<MapSchema<*, *>>(env.mock.schemaAt("Motors/powers"))
        assertEquals(listOf<Any?>(mapOf("fl" to 0.5, "fr" to -0.5)), env.mock.valuesAt("Motors/powers"))
    }

    @Test
    fun `an explicit schema is used instead of inference`() {
        val env = Env()
        val schema = MapSchema(StringSchema, DoubleSchema)
        val table = env.registry.table("Motors")

        table.log("powers", mapOf("fl" to 0.5), schema)
        table.log("powers", mapOf("fl" to 0.25), schema)

        assertTrue(env.mock.schemaAt("Motors/powers") === schema)
        assertEquals(listOf<Any?>(mapOf("fl" to 0.5), mapOf("fl" to 0.25)), env.mock.valuesAt("Motors/powers"))
        assertTrue(env.warnings.isEmpty(), env.warnings.toString())
    }

    @Test
    fun `a path set up with an explicit schema keeps it`() {
        val env = Env()
        val schema = MapSchema(StringSchema, DoubleSchema)
        val table = env.registry.table("Motors")

        table.log("powers", mapOf("fl" to 0.5), schema)
        // A second schema object, as Java code that builds one per call would pass
        table.log("powers", mapOf("fl" to 0.25), MapSchema(StringSchema, DoubleSchema))

        assertTrue(env.mock.schemaAt("Motors/powers") === schema)
        assertEquals(2, env.mock.valuesAt("Motors/powers").size)
        assertTrue(env.warnings.isEmpty(), env.warnings.toString())
    }

    @Test
    fun `erased log of a parameterized value warns once and never throws`() {
        val env = Env()
        val table = env.registry.table("Motors")

        repeat(3) { table.logErased("powers", mapOf("fl" to 0.5)) }

        assertEquals(1, env.warnings.size, env.warnings.toString())
        assertTrue("Motors/powers" in env.warnings.single())
        assertTrue(env.mock.records.isEmpty())
    }

    @Test
    fun `classes are logged through their inferred schema`() {
        val env = Env()
        env.registry.table("Path").log("pose", Pose(1.0, 2.0))

        assertEquals(1, env.mock.valuesAt("Path/pose").size)
        assertTrue(env.warnings.isEmpty(), env.warnings.toString())
    }

    @Test
    fun `loggable objects flatten into a child table`() {
        val env = Env()
        val drive = FateLoggable { table ->
            table.log("left", 1.0)
            table.log("right", 2.0)
            table.table("odometry").log("x", 3.0)
        }

        env.registry.root.log("drive", drive)

        assertEquals(listOf("drive/left", "drive/right", "drive/odometry/x"), env.mock.paths)
    }

    @Test
    fun `a loggable that throws is reported, not thrown`() {
        val env = Env()
        env.registry.root.log("drive", FateLoggable { error("kaboom") })

        assertEquals(1, env.warnings.size)
        assertTrue("drive" in env.warnings.single() && "kaboom" in env.warnings.single())
    }

    @Test
    fun `a path keeps its first type`() {
        val env = Env()
        val table = env.registry.table("T")

        table.log("v", 1.0)
        repeat(3) { table.log("v", "not a number") }
        table.log("v", 2.0)

        assertEquals(listOf<Any?>(1.0, 2.0), env.mock.valuesAt("T/v"))
        assertEquals(1, env.warnings.size, env.warnings.toString())
        assertTrue("T/v" in env.warnings.single())
    }

    @Test
    fun `a value needs a name`() {
        val env = Env()
        env.registry.root.log("", 1.0)
        env.registry.root.log("a/", 1.0)

        assertEquals(2, env.warnings.size)
        assertTrue(env.mock.records.isEmpty())
    }

    @Test
    fun `logging with no backend is harmless`() {
        val env = Env()
        env.registry.unregister(env.mock)

        env.registry.table("T").log("v", 1.0)

        assertTrue(env.warnings.isEmpty())
        assertEquals(0, env.registry.backendCount)
    }

    @Test
    fun `a backend registered later gets the next value on an already used path`() {
        val env = Env()
        val table = env.registry.table("T")
        table.log("v", 1.0)

        val late = MockFateBackend()
        env.registry.register(late)
        table.log("v", 2.0)

        assertEquals(listOf<Any?>(1.0, 2.0), env.mock.valuesAt("T/v"))
        assertEquals(listOf<Any?>(2.0), late.valuesAt("T/v"))
    }

    @Test
    fun `an unregistered backend stops receiving values`() {
        val env = Env()
        val table = env.registry.table("T")
        table.log("v", 1.0)

        assertTrue(env.registry.unregister(env.mock))
        table.log("v", 2.0)

        assertEquals(listOf<Any?>(1.0), env.mock.valuesAt("T/v"))
    }

    @Test
    fun `a path keeps its type across a backend change`() {
        val env = Env()
        val table = env.registry.table("T")
        table.log("v", 1.0)

        env.registry.register(MockFateBackend())
        table.log("v", "text")

        assertEquals(1, env.warnings.size, env.warnings.toString())
        assertEquals(listOf<Any?>(1.0), env.mock.valuesAt("T/v"))
    }

    @Test
    fun `one failing backend does not starve the others`() {
        val env = Env()
        env.registry.register(Failing(failLog = true))
        val table = env.registry.table("T")

        repeat(3) { table.log("v", it.toDouble()) }

        assertEquals(listOf<Any?>(0.0, 1.0, 2.0), env.mock.valuesAt("T/v"))
        assertEquals(1, env.warnings.size, env.warnings.toString())
    }

    @Test
    fun `a backend that cannot make an entry is skipped`() {
        val env = Env()
        env.registry.register(Failing(failEntry = true))

        env.registry.table("T").log("v", 1.0)

        assertEquals(listOf<Any?>(1.0), env.mock.valuesAt("T/v"))
        assertEquals(1, env.warnings.size, env.warnings.toString())
    }

    @Test
    fun `reset closes every backend`() {
        val env = Env()
        val failing = Failing()
        env.registry.register(failing)

        env.registry.reset()

        assertTrue(failing.closed)
        assertEquals(0, env.registry.backendCount)
    }

    @Test
    fun `a throwing warning handler is contained`() {
        val env = Env()
        env.registry.warningHandler = FateWarningHandler { error("handler boom") }

        env.registry.root.log("", 1.0)
    }

    @Test
    fun `log backend writes a readable v3 log`() {
        val bytes = ByteArrayOutputStream()
        val time = TestTimeSource()
        val registry = FateRegistry(time)
        registry.register(FateLogBackend(FateLogWriter(bytes)))

        val drive = registry.table("Drive")
        drive.log("left", 1.5)
        time += 20.milliseconds
        drive.log("left", 2.5)
        registry.root.log("state", "RUNNING")
        registry.reset()

        val entries = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
        assertEquals(listOf("Drive/left", "state"), entries.filterIsInstance<FateLogEntry.Schema>().map { it.channelName })

        val messages = entries.filterIsInstance<FateLogEntry.Message<*>>()
        assertEquals(listOf("Drive/left", "Drive/left", "state"), messages.map { it.channelName })
        assertEquals(listOf<Any?>(1.5, 2.5, "RUNNING"), messages.map { it.data })
        assertEquals(listOf(0L, 20_000_000L, 20_000_000L), messages.map { it.timestamp })
    }

    @Test
    fun `a file and a mock see the same timestamps`() {
        val env = Env()
        val bytes = ByteArrayOutputStream()
        env.registry.register(FateLogBackend(FateLogWriter(bytes)))

        env.time += 7.milliseconds
        env.registry.root.log("v", 1.0)
        env.registry.reset()

        val message = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use {
            it.readAll().filterIsInstance<FateLogEntry.Message<*>>().single()
        }
        assertNotNull(message.timestamp)
        assertEquals(env.mock.records.single().timestamp, message.timestamp)
    }

    @Test
    fun `concurrent logging to one new path loses nothing`() {
        val bytes = ByteArrayOutputStream()
        val registry = FateRegistry()
        val warnings = mutableListOf<String>()
        registry.warningHandler = FateWarningHandler { synchronized(warnings) { warnings += it } }
        registry.register(FateLogBackend(FateLogWriter(bytes)))

        val threads = 8
        val perThread = 500
        val table = registry.table("Shared")
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                start.await()
                for (i in 0 until perThread) table.log("v", t * perThread + i)
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        registry.reset()

        assertTrue(warnings.isEmpty(), warnings.toString())

        val entries = FateLogReader(ByteArrayInputStream(bytes.toByteArray())).use { it.readAll() }
        assertEquals(1, entries.filterIsInstance<FateLogEntry.Schema>().size)
        val values = entries.filterIsInstance<FateLogEntry.Message<*>>().map { it.data as Int }
        assertEquals((0 until threads * perThread).toList(), values.sorted())
    }

    @Test
    fun `schemas come from the value's type`() {
        val env = Env()
        env.registry.root.log("v", 1.0)

        assertEquals(DoubleSchema, env.mock.schemaAt("v"))
    }
}
