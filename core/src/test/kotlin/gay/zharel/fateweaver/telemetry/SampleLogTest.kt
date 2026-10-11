package gay.zharel.fateweaver.telemetry

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

private data class SamplePose(val x: Double, val y: Double, val heading: Double)

private enum class SampleAlliance { RED, BLUE }

private class SampleIntake(val position: Double, val jammed: Boolean) : FateLoggable {
    override fun logTo(table: FateTable) {
        table.log("Position", position)
        table.log("Jammed", jammed)
    }
}

/**
 * Writes a real log to `core/build/sample-logs/sample.fate`, for opening in AdvantageScope or any
 * other reader while working on one.
 *
 * The contents are deterministic: a fake clock stamps every value, so the file is the same bytes
 * on every run. It covers one of each kind of channel the format has: numbers, booleans, strings, an
 * enum, a map, an array, a pose, and a table flattened from a [FateLoggable].
 *
 * The OpMode runs 3 seconds at 50 Hz after a half-second init, and the robot drives a circle of
 * radius 24 inches, so the pose channel draws something recognizable on a field view.
 */
class SampleLogTest {
    @Test
    fun `writes a sample log to disk and reads it back`() {
        val file = File("build/sample-logs/sample.fate")
        file.parentFile.mkdirs()

        val time = TestTimeSource()
        val registry = FateRegistry(time)
        registry.register(FateLogBackend(FateLogWriter.create(file)))
        val events = registry.table("OpMode")
        val robot = registry.table("Robot")
        val drive = registry.table("Drive")

        events.log("Event", "PRE_INIT")
        time += 500.milliseconds
        events.log("Event", "PRE_START")

        val loops = 150
        for (i in 0 until loops) {
            time += 20.milliseconds
            val angle = 2 * PI * i / loops

            registry.root.log("Loop/Count", i)
            registry.root.log("Battery/Voltage", 12.6 - 0.002 * i + 0.05 * sin(i * 0.7))
            registry.root.log(
                "Status",
                when {
                    i < 50 -> "SEARCHING"
                    i < 120 -> "TRACKING"
                    else -> "DONE"
                }
            )

            drive.log("Left/Velocity", 30 * sin(angle))
            drive.log("Right/Velocity", 30 * cos(angle))
            drive.log("Enabled", (i / 50) % 2 == 0)
            drive.log("Motors", mapOf("fl" to sin(angle), "fr" to cos(angle), "bl" to -sin(angle), "br" to -cos(angle)))

            robot.log("Pose", SamplePose(24 * cos(angle), 24 * sin(angle), angle + PI / 2))
            robot.log("Alliance", if (i < 75) SampleAlliance.RED else SampleAlliance.BLUE)
            robot.log("Distances", doubleArrayOf(10 + 5 * sin(angle), 20 + 5 * cos(angle), 30.0))
            robot.log("Intake", SampleIntake(position = i / 150.0, jammed = i in 100..110))
        }

        events.log("Event", "POST_STOP")
        registry.reset()

        assertTrue(file.length() > 0, "no log was written to ${file.absolutePath}")

        val entries = FateLogReader.create(file).use { it.readAll() }
        val channels = entries.filterIsInstance<FateLogEntry.Schema>().map { it.channelName }
        assertEquals(
            setOf(
                "OpMode/Event", "Loop/Count", "Battery/Voltage", "Status",
                "Drive/Left/Velocity", "Drive/Right/Velocity", "Drive/Enabled", "Drive/Motors",
                "Robot/Pose", "Robot/Alliance", "Robot/Distances", "Robot/Intake/Position", "Robot/Intake/Jammed"
            ),
            channels.toSet()
        )

        val messages = entries.filterIsInstance<FateLogEntry.Message<*>>()
        assertEquals(listOf<Any?>("PRE_INIT", "PRE_START", "POST_STOP"), messages.filter { it.channelName == "OpMode/Event" }.map { it.data })

        // The first message is at time zero, and the last pose is at init + start-up + 150 loops
        assertEquals(0L, messages.first().timestamp)
        val poses = messages.filter { it.channelName == "Robot/Pose" }
        assertEquals(loops, poses.size)
        assertEquals(500_000_000L + loops * 20_000_000L, poses.last().timestamp)
    }
}
