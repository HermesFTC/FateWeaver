package gay.zharel.fateweaver.flight

import android.annotation.SuppressLint
import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.log.MultiSink
import gay.zharel.fateweaver.log.StreamSink
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.io.FileOutputStream
import java.text.SimpleDateFormat

val LOG_ROOT = AppUtil.ROOT_FOLDER.resolve("FateWeaver/Logs")
const val EXT = ".fate"

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

object FateLogManager {
    @JvmStatic
    @JvmOverloads
    fun start(fileName: String, mode: LogMode = LogMode.BOTH): FateLogWriter {
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
            // The file is kept even in LIVE mode when the projector turns out not to be running,
            // so a disabled projector can never cost a team their log.
            if (mode != LogMode.LIVE || !live) add(StreamSink(FileOutputStream(currentFile)))
            if (live) add(FlightProjector)
        }

        return FateLogWriter(MultiSink(sinks))
    }

    @JvmStatic
    @JvmOverloads
    fun startWithTimestamp(fileName: String, mode: LogMode = LogMode.BOTH): FateLogWriter =
        start("${DATE_FORMAT.format(System.currentTimeMillis())}__$fileName", mode)
}