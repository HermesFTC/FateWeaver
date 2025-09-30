package gay.zharel.fateweaver.flight

import android.annotation.SuppressLint
import gay.zharel.fateweaver.log.FateLogWriter
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import java.text.SimpleDateFormat

val LOG_ROOT = AppUtil.ROOT_FOLDER.resolve("FateWeaver/Logs")
const val EXT = ".fate.log"

@SuppressLint("SimpleDateFormat")
private val DATE_FORMAT = SimpleDateFormat("yyyy_MM_dd__HH_mm_ss_SSS")

object FateLogManager {
    fun start(fileName: String): FateLogWriter {
        var currentFile = LOG_ROOT.resolve(fileName + EXT)

        if (!LOG_ROOT.exists()) {
            LOG_ROOT.mkdirs()
        }

        if (currentFile.exists()) {
            currentFile = LOG_ROOT.resolve(fileName + "_copy" + EXT)
        } else {
            currentFile.createNewFile()
        }

        return FateLogWriter.create(currentFile)
    }

    fun startWithTimestamp(fileName: String): FateLogWriter =
        start("${DATE_FORMAT.format(System.currentTimeMillis())}__$fileName$EXT")
}