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
 * Streams the FateWeaver log to connected AdvantageScope clients over a WebSocket.
 *
 * The bytes are the same ones a `.fate` file would contain, so AdvantageScope decodes a live stream
 * with its file decoder. A client that connects partway through an OpMode first receives the header
 * and every schema entry declared so far, in order. Channel indices are positional, and a message
 * entry means nothing without its schema entry.
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
     * How many entries can queue for one client before it is dropped.
     *
     * A client that stops reading is disconnected instead of blocking the OpMode thread.
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
     * Turns live streaming on or off and saves the choice across robot restarts.
     *
     * Turning it off closes the server and unbinds [port].
     */
    @JvmStatic
    fun setEnabled(value: Boolean) {
        LiveSettings.enabled = value
        if (value) start() else stop()
    }

    /**
     * Registers an OpMode that toggles live streaming from the Driver Station.
     *
     * The web dashboard toggle needs a laptop on the robot's network, and a team does not always
     * have one before a match. The Driver Station is always at hand. FTC Dashboard registers an
     * equivalent OpMode for the same reason.
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
     * The launch-activity guard matches FTC Dashboard's. The SDK also fires this when USB hardware
     * is attached, and stopping the server then would leave streaming dead until the next restart.
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
        // A client can only join at a header, so clients still waiting start receiving here.
        broadcast(bytes, primes = true)
    }

    override fun schemaEntry(bytes: ByteArray) {
        synchronized(lock) { schemaEntries.add(bytes) }
        broadcast(bytes)
    }

    override fun messageEntry(bytes: ByteArray) = broadcast(bytes)

    override fun flush() {}

    /**
     * Does nothing. The projector outlives any one [gay.zharel.fateweaver.log.FateLogWriter], so
     * closing an OpMode's writer must not stop the server or drop clients.
     */
    override fun close() {}

    // --- internals ---

    /**
     * Sends [bytes] to every client that has had a header.
     *
     * Clients that attached while no log was in progress are skipped until [primes] marks them
     * ready. Entries sent before a header can't be decoded and would only make a reader disconnect
     * and retry.
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
     * Registers [client] and sends it the current header and schema entries.
     *
     * This runs under [lock] so no entry can be broadcast between taking the snapshot and adding
     * the client to the list. Otherwise a message entry could reach the client before its schema
     * entry.
     *
     * With no header to replay, between OpModes or when streaming was turned on partway through
     * one, the client attaches idle and starts receiving at the next header.
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

        /** Whether this client has been sent a header and can decode what follows. */
        @Volatile
        var primed = false

        private val queue = ArrayBlockingQueue<ByteArray>(CLIENT_QUEUE_DEPTH)

        @Volatile
        private var running = true

        /**
         * Drains [queue] onto the socket.
         *
         * Sending happens off the OpMode thread because NanoWSD writes synchronously. A client that
         * stops reading would otherwise block the thread that logged the value.
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

        // Start the pump before attach(), which queues the preamble. Nothing would drain it
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

        /** Clients are read-only, so incoming messages are ignored. */
        override fun onMessage(message: NanoWSD.WebSocketFrame) {}

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) = shutdown()
    }
}
