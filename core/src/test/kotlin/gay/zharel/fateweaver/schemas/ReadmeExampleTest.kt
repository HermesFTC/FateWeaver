package gay.zharel.fateweaver.schemas

import gay.zharel.fateweaver.log.FateLogEntry
import gay.zharel.fateweaver.log.FateLogReader
import gay.zharel.fateweaver.log.FateLogWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The `RobotCommand` example from README.md, which has a `Map` field and an `AS_TYPE`
 * discriminator. It was documented but not actually loggable before MAP support existed.
 */
class ReadmeExampleTest {

    data class RobotCommand(
        val type: String,
        val timestamp: Long,
        val parameters: Map<String, String>,
    ) {
        companion object {
            const val AS_TYPE = "RobotCommand"
        }
    }

    @Test
    fun testRobotCommandRoundTrips() {
        val schema = FateSchema.schemaOfClass(RobotCommand::class)
        assertEquals(TypedClassSchema::class, schema::class)

        val command = RobotCommand("MOVE_FORWARD", 1234L, mapOf("speed" to "0.8"))

        val os = ByteArrayOutputStream()
        FateLogWriter(os).use { it.createChannel("commands", schema).put(command) }

        val messages = FateLogReader(os.toByteArray().inputStream()).use { it.readAll() }
            .filterIsInstance<FateLogEntry.Message<*>>()

        assertEquals(1, messages.size)
        assertEquals(
            mapOf(
                ".type" to "RobotCommand",
                "parameters" to mapOf("speed" to "0.8"),
                "timestamp" to 1234L,
                "type" to "MOVE_FORWARD",
            ),
            messages[0].data,
        )
    }
}
