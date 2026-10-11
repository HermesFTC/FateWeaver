package gay.zharel.fateweaver.flight

import android.annotation.SuppressLint
import android.content.Context
import com.qualcomm.ftccommon.FtcEventLoop
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier
import com.qualcomm.robotcore.util.RobotLog
import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.log.MultiSink
import gay.zharel.fateweaver.log.StreamSink
import gay.zharel.fateweaver.telemetry.Fate
import gay.zharel.fateweaver.telemetry.FateLogBackend
import gay.zharel.fateweaver.telemetry.log
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop
import org.firstinspires.ftc.ftccommon.internal.manualcontrol.ManualControlOpMode
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.FileOutputStream
import java.text.SimpleDateFormat

val LOG_ROOT = AppUtil.ROOT_FOLDER.resolve("FateWeaver/Logs")
const val EXT = ".fate"

private const val TAG = "FateLogManager"

/** Old logs are deleted, oldest first, once the folder holds this many bytes. */
private const val MAX_LOG_BYTES = 250L * 1000 * 1000

@SuppressLint("SimpleDateFormat")
private val DATE_FORMAT = SimpleDateFormat("yyyy_MM_dd__HH_mm_ss_SSS")

/** Where an OpMode's log goes. */
enum class LogMode {
    /** Record to a `.fate` file only. */
    FILE,

    /** Broadcast to connected AdvantageScope clients only. */
    LIVE,

    /** Both, the default. */
    BOTH
}

/**
 * Gives every OpMode a log. It registers a [FateLogBackend] on [Fate.registry] when the OpMode is
 * initialized and closes it when the OpMode stops.
 *
 * Nothing needs setting up. The Robot Controller calls [registerOpModeListener] at startup, and from
 * then on whatever robot code logs through [Fate] lands in the running OpMode's log. Values logged
 * while no OpMode is running, such as between OpModes, are not written to a log.
 *
 * Only the backend this class registers is ever removed, so a backend you register yourself on
 * [Fate.registry] keeps working across OpModes.
 */
object FateLogManager : OpModeManagerNotifier.Notifications {
    /**
     * Whether OpMode logs go to a `.fate` file, to connected AdvantageScope clients, or both.
     *
     * Setting this to [LogMode.FILE] in code keeps OpMode logs from streaming and stops this class
     * from starting [FlightProjector]. The dashboard toggle backed by [LiveSettings] closes the
     * server itself and persists across robot restarts.
     */
    @JvmStatic
    var mode: LogMode = LogMode.BOTH

    @SuppressLint("StaticFieldLeak")
    private lateinit var opModeManager: OpModeManagerImpl

    /** The backend for the OpMode that is running, or `null` between OpModes. */
    private var backend: FateLogBackend? = null

    @OnCreateEventLoop
    @JvmStatic
    fun registerOpModeListener(context: Context, eventLoop: FtcEventLoop) {
        opModeManager = eventLoop.opModeManager
        opModeManager.registerListener(this)

        // start() does nothing when live streaming has been switched off, so the saved setting
        // holds even when mode is BOTH.
        if (mode != LogMode.FILE) FlightProjector.start()
    }

    override fun onOpModePreInit(opMode: OpMode?) {
        synchronized(this) {
            closeCurrent()
            trimOldLogs()

            if (opMode is OpModeManagerImpl.DefaultOpMode || opMode is ManualControlOpMode) {
                return
            }

            val started = try {
                startWithTimestamp(opModeManager.activeOpModeName, mode)
            } catch (e: Exception) {
                // Losing a log is bad; losing the OpMode over it is worse.
                RobotLog.ee(TAG, e, "unable to start a log for %s", opModeManager.activeOpModeName)
                return
            }

            backend = started
            Fate.registry.register(started)

            Fate.table("OpMode").log("Event", "PRE_INIT")
        }
    }

    override fun onOpModePreStart(opMode: OpMode?) {
        Fate.table("OpMode").log("Event", "PRE_START")
    }

    override fun onOpModePostStop(opMode: OpMode?) {
        synchronized(this) {
            Fate.table("OpMode").log("Event", "POST_STOP")
            closeCurrent()
        }
    }

    /**
     * Returns a backend that writes to a log file named [fileName] and to the live stream, when
     * [mode] allows it and [FlightProjector] is running. In [LogMode.LIVE] the file is skipped
     * while the stream is running.
     *
     * The backend is not registered. The OpMode hooks above do that. Call this directly only to log
     * outside an OpMode, and register the result on [Fate.registry] yourself.
     */
    @JvmStatic
    @JvmOverloads
    fun start(fileName: String, mode: LogMode = LogMode.BOTH): FateLogBackend {
        var currentFile = LOG_ROOT.resolve(fileName + EXT)

        if (!LOG_ROOT.exists()) {
            LOG_ROOT.mkdirs()
        }

        if (currentFile.exists()) {
            currentFile = LOG_ROOT.resolve(fileName + "_copy" + EXT)
        } else {
            currentFile.createNewFile()
        }

        val live = mode != LogMode.FILE && FlightProjector.isRunning
        val sinks = buildList {
            // In LIVE mode the file is still written when the projector is not running, so a
            // disabled projector never costs a team their log.
            if (mode != LogMode.LIVE || !live) add(StreamSink(FileOutputStream(currentFile)))
            if (live) add(FlightProjector)
        }

        return FateLogBackend(FateLogWriter(MultiSink(sinks)))
    }

    /** Like [start], with the current date and time ahead of [fileName] so no log overwrites another. */
    @JvmStatic
    @JvmOverloads
    fun startWithTimestamp(fileName: String, mode: LogMode = LogMode.BOTH): FateLogBackend =
        start("${DATE_FORMAT.format(System.currentTimeMillis())}__$fileName", mode)

    private fun closeCurrent() {
        val current = backend ?: return
        backend = null

        Fate.registry.unregister(current)
        try {
            current.close()
        } catch (e: Exception) {
            RobotLog.ee(TAG, e, "unable to close the log")
        }
    }

    /** Deletes the oldest logs until the folder is back under [MAX_LOG_BYTES]. */
    private fun trimOldLogs() {
        val logs = LOG_ROOT.listFiles { file -> file.isFile && file.name.endsWith(EXT) } ?: return
        logs.sortBy { it.lastModified() }

        var totalBytes = logs.sumOf { it.length() }
        for (log in logs) {
            if (totalBytes < MAX_LOG_BYTES) break

            totalBytes -= log.length()
            if (!log.delete()) {
                // avoid panicking here
                RobotLog.setGlobalErrorMsg("Unable to delete file " + log.absolutePath)
            }
        }
    }
}
