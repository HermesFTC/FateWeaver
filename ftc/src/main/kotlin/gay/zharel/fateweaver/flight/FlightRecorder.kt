package gay.zharel.fateweaver.flight

import android.content.Context
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier
import com.qualcomm.robotcore.util.RobotLog
import com.qualcomm.robotcore.util.WebHandlerManager
import fi.iki.elonen.NanoHTTPD
import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.log.LogChannel
import gay.zharel.fateweaver.schemas.LongSchema
import gay.zharel.fateweaver.schemas.StringSchema
import org.firstinspires.ftc.ftccommon.external.WebHandlerRegistrar
import org.firstinspires.ftc.ftccommon.internal.manualcontrol.ManualControlOpMode
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.robotcore.internal.system.AppUtil
import org.firstinspires.ftc.robotcore.internal.webserver.WebHandler
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import kotlin.collections.filter
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.collections.sortBy
import kotlin.collections.sortedByDescending
import kotlin.collections.sumOf
import kotlin.jvm.javaClass
import kotlin.run
import kotlin.text.contains
import kotlin.text.endsWith
import kotlin.text.replace

object FlightRecorder : OpModeManagerNotifier.Notifications, Telemetry {
    internal var writer: FateLogWriter? = null
    internal var timestampChannel: LogChannel<Long>? = null

    internal var items = mutableMapOf<String, FlightLogItem>()

    // I'm tempted to use @OnCreate, but some of the hooks are unreliable and @WebHandlerRegistrar
    // seems to just work.
    @WebHandlerRegistrar
    @JvmStatic
    fun registerRoutes(context: Context, manager: WebHandlerManager) {
        OpModeManagerImpl.getOpModeManagerOfActivity(AppUtil.getInstance().activity)
                .registerListener(this)

        // Register web endpoints for listing and downloading flight recorder logs
        val listHandler = WebHandler {
            val files = LOG_ROOT.listFiles()?.filter { it.isFile && it.name.endsWith(".log") } ?: emptyList()
            // sort by last modified desc
            val sorted = files.sortedByDescending { it.lastModified() }
            val jsonItems = sorted.map { f ->
                val name = f.name
                val size = f.length()
                val lastMod = f.lastModified()
                "{\"name\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"size\":$size,\"lastModified\":$lastMod}"
            }
            val body = "[${jsonItems.joinToString(",")}]"
            val resp = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body)
            resp.addHeader("Cache-Control", "no-store")
            resp
        }
        val downloadHandler = WebHandler { session ->
            val params = session.parameters
            val nameList = params["file"]
            if (nameList == null || nameList.isEmpty()) {
                return@WebHandler NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "missing 'file' parameter")
            }
            val requested = nameList[0]
            // Security: disallow path traversal and restrict to .log files
            if (requested.contains("/") || requested.contains("\\") || !requested.endsWith(".log")) {
                return@WebHandler NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "invalid file name")
            }
            val file = File(LOG_ROOT, requested)
            if (!file.exists() || !file.isFile) {
                return@WebHandler NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "file not found")
            }
            val fis = FileInputStream(file)
            val resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", fis)
            val encoded = try { URLEncoder.encode(file.name, "UTF-8") } catch (_: Exception) { file.name }
            resp.addHeader("Content-Disposition", "attachment; filename=\"$encoded\"")
            resp.addHeader("Cache-Control", "no-store")
            resp
        }

        manager.register("/dash/logs/list", listHandler)
        manager.register("/dash/logs/download", downloadHandler)
    }

    override fun onOpModePreInit(opMode: OpMode?) {
        synchronized(this) {
            writer?.close()
            writer = null

            // clean up old files
            run {
                val fs = LOG_ROOT.listFiles() ?: return@run
                fs.sortBy { it.lastModified() }
                var totalSizeBytes = fs.sumOf { it.length() }

                var i = 0
                while (i < fs.size && totalSizeBytes >= 250 * 1000 * 1000) {
                    totalSizeBytes -= fs[i].length()
                    if (!fs[i].delete()) {
                        // avoid panicking here
                        RobotLog.setGlobalErrorMsg("Unable to delete file " + fs[i].absolutePath)
                    }
                    ++i
                }
            }

            if (opMode is OpModeManagerImpl.DefaultOpMode || opMode is ManualControlOpMode) {
                return
            }

            writer = FateLogManager.start(opMode?.javaClass?.simpleName ?: "UnknownOpMode")
            timestampChannel = createChannel("TIMESTAMP", LongSchema)

            write("OPMODE_PRE_INIT", System.nanoTime())
        }
    }

    override fun onOpModePreStart(opMode: OpMode?) {
        write("OPMODE_PRE_START", System.nanoTime())
    }

    override fun onOpModePostStop(opMode: OpMode?) {
        synchronized(this) {
            write("OPMODE_POST_STOP", System.nanoTime())

            writer?.close()
            writer = null
            timestampChannel = null
            items.clear()
        }
    }

    @JvmStatic
    fun write(channelName: String, obj: Any) {
        synchronized(this) {
            writer?.write(channelName, obj)
        }
    }

    @JvmStatic
    fun <T> write(channel: LogChannel<T>, obj: T) {
        synchronized(this) {
            writer?.write(channel, obj)
        }
    }

    /**
     * Creates a new log channel attached to the current OpMode's writer.
     */
    @JvmStatic
    fun <T> createChannel(name: String, schema: FateSchema<T>): FlightLogChannel<T> {
        check(writer != null) { "Channels can only be created during an OpMode" }
        return FlightLogChannel(name, schema)
    }

    /**
     * Creates a new log channel attached to the current OpMode's writer.
     */
    @JvmStatic
    fun <T : Any> createChannel(name: String, clazz: Class<T>): FlightLogChannel<T> {
        return createChannel(name, FateSchema.schemaOfClass(clazz))
    }

    /**
     * Adds a timestamp to the log if the timestamp channel is available.
     */
    @JvmStatic
    fun timestamp() {
        timestampChannel?.put(System.nanoTime())
    }

    override fun addData(caption: String, format: String, vararg args: Any): Telemetry.Item = if (caption in items.keys) {
        items[caption]!!.setValue(format, *args)
    } else {
        val item = FlightLogItem(createChannel(caption, StringSchema), String.format(format, *args))
        items[caption] = item
        item
    }

    override fun addData(caption: String, value: Any): Telemetry.Item = if (caption in items.keys) {
        items[caption]!!.setValue(value)
    } else {
        val item = FlightLogItem(createChannel(caption, StringSchema), value.toString())
        items[caption] = item
        item
    }

    override fun <T : Any> addData(caption: String, valueProducer: Func<T>): Telemetry.Item = if (caption in items.keys) {
        items[caption]!!.setValue(valueProducer)
    } else {
        val item = FlightLogItem(createChannel(caption, StringSchema), valueProducer.value().toString())
        items[caption] = item
        item
    }

    override fun <T : Any> addData(caption: String, format: String, valueProducer: Func<T>): Telemetry.Item = if (caption in items.keys) {
        items[caption]!!.setValue(format, valueProducer)
    } else {
        val item = FlightLogItem(createChannel(caption, StringSchema), String.format(format, valueProducer.value()))
        items[caption] = item
        item
    }

    override fun removeItem(item: Telemetry.Item): Boolean {
        unsupported("removeItem")
    }

    override fun clear() {
        unsupported("clear")
    }

    override fun clearAll() {
        unsupported("clearAll")
    }

    override fun addAction(action: Runnable): Any {
        unsupported("addAction")
    }

    override fun removeAction(token: Any?): Boolean {
        unsupported("removeAction")
    }

    override fun speak(text: String?) {
        unsupported("speak")
    }

    override fun speak(text: String?, languageCode: String?, countryCode: String?) {
        unsupported("speak")
    }

    override fun update(): Boolean {
        items.forEach { (_, item) ->
            item.write()
        }
        return true
    }

    override fun addLine(): Telemetry.Line = if ("" in items.keys) {
        items[""]!!
    } else {
        val item = FlightLogItem(createChannel("", StringSchema), "")
        items[""] = item
        item
    }

    override fun addLine(lineCaption: String): Telemetry.Line = if (lineCaption in items.keys) {
        items[lineCaption]!!
    } else {
        val item = FlightLogItem(createChannel(lineCaption, StringSchema), "")
        items[lineCaption] = item
        item
    }

    override fun removeLine(line: Telemetry.Line): Boolean {
        unsupported("removeLine")
    }

    override fun isAutoClear(): Boolean {
        unsupported("removeLine")
    }

    override fun setAutoClear(autoClear: Boolean) {
       unsupported("setAutoClear")
    }

    override fun getMsTransmissionInterval(): Int {
        unsupported("getMsTransmissionInterval")
    }

    override fun setMsTransmissionInterval(msTransmissionInterval: Int) {
        unsupported("setMsTransmissionInterval")
    }

    override fun getItemSeparator(): String {
        unsupported("getItemSeparator")
    }

    override fun setItemSeparator(itemSeparator: String?) {
        unsupported("setItemSeparator")
    }

    override fun getCaptionValueSeparator(): String? {
        unsupported("getCaptionValueSeparator")
    }

    override fun setCaptionValueSeparator(captionValueSeparator: String?) {
        unsupported("setCaptionValueSeparator")
    }

    override fun setDisplayFormat(displayFormat: Telemetry.DisplayFormat?) {
        unsupported("setDisplayFormat")
    }

    override fun log(): Telemetry.Log {
        unsupported("log")
    }

    internal fun unsupported(methodName: String): Nothing {
        throw UnsupportedOperationException("$methodName is not supported by FlightRecorder")
    }
}
