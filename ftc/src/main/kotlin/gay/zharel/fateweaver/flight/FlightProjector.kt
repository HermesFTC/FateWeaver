package gay.zharel.fateweaver.flight

import android.content.Context
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManager
import com.qualcomm.robotcore.eventloop.opmode.OpModeRegistrar
import com.qualcomm.robotcore.util.RobotLog
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import gay.zharel.fateweaver.log.FateSink
import org.firstinspires.ftc.ftccommon.external.OnDestroy
import org.firstinspires.ftc.ftccommon.internal.FtcRobotControllerWatchdogService
import org.firstinspires.ftc.robotcore.internal.opmode.OpModeMeta
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "FlightProjector"

/**
 * Broadcasts the FateWeaver log stream to connected AdvantageScope clients over a WebSocket.
 *
 * The bytes on the wire are byte-for-byte the ones a `.fate` file would contain, so AdvantageScope
 * decodes a live stream with the same decoder it uses for files. A client that connects partway
 * through an OpMode is first sent the header and every schema entry declared so far, in order,
 * because channel indices are positional and a message entry is meaningless without the schema
 * entry it refers to.
 *
 * ## Competition use
 *
 * The FTC 2026-2027 competition manual prohibits live streaming from third-party libraries during
 * competition, so **this is for testing and tuning only**. Call [setEnabled] with `false`, or use
 * the toggle on the FateWeaver dashboard, to close the server and unbind the port; the choice is
 * remembered across robot restarts. See [LiveSettings]. File logging is unaffected either way.
 *
 * @see FateLogManager.mode
 */
object FlightProjector : FateSink {
    /** The port [start] binds, chosen to avoid 8080 (FTC web server) and 8000 (FTC Dashboard). */
    const val DEFAULT_PORT = 8110

    /** Changing this while the server is running has no effect until the next [start]. */
    @JvmStatic
    var port: Int = DEFAULT_PORT

    /**
     * How many entries a client may fall behind by before it is dropped.
     *
     * A laptop that stops reading must never stall the OpMode thread, so entries queue per client
     * and a client that overruns this is disconnected rather than allowed to apply backpressure.
     */
    private const val CLIENT_QUEUE_DEPTH = 2048

    private val lock = Any()
    private var header: ByteArray? = null
    private val schemaEntries = mutableListOf<ByteArray>()
    private val clients = CopyOnWriteArrayList<ProjectorSocket>()

    private var server: ProjectorServer? = null

    /** Whether the server is bound and accepting clients. */
    @JvmStatic
    val isRunning: Boolean get() = synchronized(lock) { server != null }

    /** How many AdvantageScope clients are currently attached. */
    @JvmStatic
    val clientCount: Int get() = clients.size

    /**
     * Binds [port] and begins accepting clients, unless [LiveSettings.enabled] is `false`.
     *
     * Does nothing if the server is already running.
     */
    @JvmStatic
    fun start() {
        synchronized(lock) {
            if (server != null) return
            if (!LiveSettings.enabled) {
                RobotLog.ii(TAG, "live streaming disabled, not binding port %d", port)
                return
            }
            try {
                server = ProjectorServer(port).also { it.start(0, true) }
                RobotLog.ii(TAG, "live streaming on port %d", port)
            } catch (e: IOException) {
                RobotLog.ee(TAG, e, "unable to bind port %d", port)
                server = null
            }
        }
    }

    /** Closes the server, unbinds the port and disconnects every client. */
    @JvmStatic
    fun stop() {
        val attached: List<ProjectorSocket>
        synchronized(lock) {
            server?.stop()
            server = null
            attached = clients.toList()
            clients.clear()
            header = null
            schemaEntries.clear()
        }
        attached.forEach { it.shutdown() }
    }

    /**
     * Turns live streaming on or off and remembers the choice across robot restarts.
     *
     * This is the switch to use before a competition match. Turning it off closes the server and
     * unbinds [port], so compliance is verifiable with a port scan rather than a promise.
     */
    @JvmStatic
    fun setEnabled(value: Boolean) {
        LiveSettings.enabled = value
        if (value) start() else stop()
    }

    /**
     * Registers an OpMode that toggles live streaming, so the switch is reachable from the Driver
     * Station.
     *
     * The web dashboard toggle needs a laptop joined to the robot's network, which is not the
     * situation a team is in before a match. The Driver Station is the one thing they always have
     * in front of them, so this is the toggle that will actually get used. FTC Dashboard registers
     * an equivalent OpMode for the same reason.
     */
    @OpModeRegistrar
    @JvmStatic
    fun registerToggleOpMode(manager: OpModeManager) {
        manager.register(
            OpModeMeta.Builder()
                .setName("Enable/Disable FateWeaver Live")
                .setFlavor(OpModeMeta.Flavor.TELEOP)
                .setGroup("FateWeaver")
                .build(),
            object : LinearOpMode() {
                override fun runOpMode() {
                    val turningOn = !LiveSettings.enabled
                    telemetry.addLine(
                        "FateWeaver live streaming is currently " +
                            "${if (LiveSettings.enabled) "ENABLED" else "DISABLED"}."
                    )
                    telemetry.addLine("Press START to ${if (turningOn) "enable" else "disable"} it.")
                    telemetry.addLine()
                    telemetry.addLine("Live streaming is prohibited during competition matches.")
                    telemetry.update()

                    waitForStart()
                    if (isStopRequested) return

                    setEnabled(turningOn)

                    telemetry.addLine(
                        "FateWeaver live streaming is now " +
                            "${if (LiveSettings.enabled) "ENABLED" else "DISABLED"}."
                    )
                    telemetry.addLine(
                        if (isRunning) "Listening on port $port." else "Port $port is closed."
                    )
                    telemetry.update()

                    while (opModeIsActive()) {
                        idle()
                    }
                }
            }
        )
    }

    /**
     * Releases the port when the Robot Controller app goes away.
     *
     * The launch-activity guard matches FTC Dashboard's: the SDK also fires this when USB hardware
     * is attached, and tearing the server down then would leave streaming silently dead until the
     * next restart.
     */
    @OnDestroy
    @JvmStatic
    fun onRobotControllerDestroy(context: Context) {
        if (!FtcRobotControllerWatchdogService.isLaunchActivity(AppUtil.getInstance().rootActivity)) {
            return
        }
        stop()
    }

    // --- FateSink ---

    override fun header(bytes: ByteArray) {
        synchronized(lock) {
            // A new OpMode means a new log on the same connection. AdvantageScope resets its
            // channel table when it sees "RR" mid-stream, so the old preamble goes with it.
            header = bytes
            schemaEntries.clear()
        }
        // A header is the one thing a client can join on, so this is where anyone still waiting
        // starts receiving.
        broadcast(bytes, primes = true)
    }

    override fun schemaEntry(bytes: ByteArray) {
        synchronized(lock) { schemaEntries.add(bytes) }
        broadcast(bytes)
    }

    override fun messageEntry(bytes: ByteArray) = broadcast(bytes)

    override fun flush() {}

    /**
     * Does nothing: the projector outlives any one [gay.zharel.fateweaver.log.FateLogWriter], so
     * closing an OpMode's writer must not tear down the server or drop connected clients.
     */
    override fun close() {}

    // --- internals ---

    /**
     * Sends [bytes] to every client that has had a header.
     *
     * Clients that attached while no log was in progress are skipped until [primes] marks them
     * ready, because entries before a header would be undecodable and would only make a reader
     * disconnect and retry.
     */
    private fun broadcast(bytes: ByteArray, primes: Boolean = false) {
        for (client in clients) {
            if (primes) client.primed = true
            if (!client.primed) continue
            if (!client.offer(bytes)) {
                RobotLog.ww(TAG, "client %s fell behind, dropping", client.name)
                client.shutdown()
            }
        }
    }

    /**
     * Registers [client] and hands it the current preamble.
     *
     * Held under [lock] so that no entry can be broadcast between snapshotting the preamble and
     * the client joining the list, which would otherwise let a message entry reach a client ahead
     * of the schema entry it depends on.
     *
     * With no header to replay — between OpModes, or when streaming was switched on partway
     * through one — the client attaches idle and starts receiving at the next header.
     */
    private fun attach(client: ProjectorSocket) {
        synchronized(lock) {
            val current = header
            if (current != null) {
                client.offer(current)
                schemaEntries.forEach { client.offer(it) }
                client.primed = true
            }
            clients.add(client)
        }
        RobotLog.vv(TAG, "client %s attached (%d total)", client.name, clientCount)
    }

    private fun detach(client: ProjectorSocket) {
        clients.remove(client)
    }

    private class ProjectorServer(port: Int) : NanoWSD(port) {
        override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): WebSocket =
            ProjectorSocket(handshake)
    }

    private class ProjectorSocket(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        val name: String = handshake.remoteIpAddress ?: "?"

        /** Whether this client has been sent a header and can make sense of what follows. */
        @Volatile
        var primed = false

        private val queue = ArrayBlockingQueue<ByteArray>(CLIENT_QUEUE_DEPTH)

        @Volatile
        private var running = true

        /**
         * Drains [queue] onto the socket.
         *
         * The send has to happen off the OpMode thread: NanoWSD writes synchronously, so a client
         * that stops reading would otherwise block whatever thread logged the value.
         */
        private val pump = Thread({
            try {
                while (running) send(queue.take())
            } catch (_: InterruptedException) {
                // shutdown() interrupted us; nothing to do
            } catch (e: IOException) {
                RobotLog.vv(TAG, "client %s disconnected: %s", name, e.message)
            } finally {
                shutdown()
            }
        }, "FlightProjector-$name").apply { isDaemon = true }

        /** Queues [bytes] for sending. Returns `false` if this client is too far behind. */
        fun offer(bytes: ByteArray): Boolean = !running || queue.offer(bytes)

        fun shutdown() {
            if (!running) return
            running = false
            FlightProjector.detach(this)
            pump.interrupt()
            try {
                close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "", false)
            } catch (_: IOException) {
                // already gone
            }
        }

        // pump.start() must precede attach(), which enqueues the preamble: nothing would drain it
        // otherwise.
        override fun onOpen() {
            pump.start()
            FlightProjector.attach(this)
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) = shutdown()

        /** Clients are read-only; nothing they send means anything. */
        override fun onMessage(message: NanoWSD.WebSocketFrame) {}

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) = shutdown()
    }
}
