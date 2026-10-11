package gay.zharel.fateweaver.flight

import android.annotation.SuppressLint
import android.content.Context
import com.qualcomm.ftccommon.FtcEventLoop
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerNotifier
import com.qualcomm.robotcore.util.RobotLog
import gay.zharel.fateweaver.log.FateLogWriter
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.log.LogChannel
import gay.zharel.fateweaver.schemas.LongSchema
import gay.zharel.fateweaver.schemas.StringSchema
import org.firstinspires.ftc.ftccommon.external.OnCreateEventLoop
import org.firstinspires.ftc.ftccommon.internal.manualcontrol.ManualControlOpMode
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry
import kotlin.collections.sortBy
import kotlin.collections.sumOf
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.run

object FlightRecorder : OpModeManagerNotifier.Notifications, Telemetry {
    @SuppressLint("StaticFieldLeak")
    internal lateinit var opModeManager: OpModeManagerImpl

    internal var writer: FateLogWriter? = null

    internal var timestampChannel: LogChannel<Long>? = null

    internal var items = mutableMapOf<String, FlightLogItem>()

    /**
     * Whether OpMode logs go to a `.fate` file, to connected AdvantageScope clients, or both.
     *
     * Setting this to [LogMode.FILE] is the compile-time way to guarantee nothing ever streams.
     * The dashboard toggle backed by [LiveSettings] is the runtime way, and it persists across
     * robot restarts. Either one alone is enough to keep [FlightProjector] from binding its port,
     * which is what the FTC 2026-2027 competition manual requires during competition.
     */
    @JvmStatic
    var mode: LogMode = LogMode.BOTH

    @OnCreateEventLoop
    @JvmStatic
    fun registerOpModeListener(context: Context, eventLoop: FtcEventLoop) {
        opModeManager = eventLoop.opModeManager
        opModeManager.registerListener(this)

        // start() is itself a no-op when live streaming has been switched off, so the persisted
        // setting holds even when mode was left at BOTH.
        if (mode != LogMode.FILE) FlightProjector.start()
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

            writer = FateLogManager.start(opModeManager.activeOpModeName, mode)
            timestampChannel = createChannel("TIMESTAMP", LongSchema)

            writeErased("OPMODE_PRE_INIT", System.nanoTime())
        }
    }

    override fun onOpModePreStart(opMode: OpMode?) {
        writeErased("OPMODE_PRE_START", System.nanoTime())
    }

    override fun onOpModePostStop(opMode: OpMode?) {
        synchronized(this) {
            writeErased("OPMODE_POST_STOP", System.nanoTime())

            writer?.close()
            writer = null
            timestampChannel = null
            items.clear()
        }
    }

    /**
     * Writes an object to a channel identified by name, inferring the schema from the object's
     * runtime class.
     *
     * Java sees this as `write`. Kotlin callers should reach for the reified [write] extension
     * instead, which also recovers type arguments. A runtime class has no type arguments, so a [Map]
     * or [Collection] passed here is rejected. Create the channel with an explicit schema instead:
     * `createChannel("k", new MapSchema<>(StringSchema.INSTANCE, DoubleSchema.INSTANCE))`.
     */
    @JvmStatic
    @JvmName("write")
    fun writeErased(channelName: String, obj: Any) {
        synchronized(this) {
            writer?.writeErased(channelName, obj)
        }
    }

    /**
     * Writes an object to a channel identified by name, inferring the schema from the fully
     * resolved type [type] rather than from [obj]'s erased runtime class.
     *
     * This is what the reified [write] extension calls. Call it directly if you got a [KType] some
     * other way.
     *
     * @see gay.zharel.fateweaver.log.FateLogWriter.write
     */
    @JvmStatic
    fun write(channelName: String, obj: Any, type: KType) {
        synchronized(this) {
            writer?.write(channelName, obj, type)
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
    fun <T : Any> createChannel(name: String, cls: Class<T>): FlightLogChannel<T> {
        return createChannel(name, FateSchema.schemaOfClass(cls))
    }

    /**
     * Creates a new log channel attached to the current OpMode's writer, with a schema inferred
     * from the reified type [T], type arguments included.
     *
     * ```kotlin
     * val powers = FlightRecorder.createChannel<Map<String, Double>>("Motors/Powers")
     * powers.put(mapOf("fl" to 0.5))
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> createChannel(name: String): FlightLogChannel<T> =
        createChannel(name, FateSchema.schemaOfType(typeOf<T>()) as FateSchema<T>)

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

/**
 * Writes an object to a channel identified by name, inferring the schema from [T]'s static type,
 * type arguments included.
 *
 * This is the Kotlin form of [FlightRecorder.writeErased], and the only way to write a
 * parameterized value by channel name:
 *
 * ```kotlin
 * FlightRecorder.write("Motors/Powers", mapOf("fl" to 0.5))   // Map<String, Double>
 * FlightRecorder.write("Path/Points", listOf(a, b, c))        // List<Pose>
 * ```
 *
 * The JVM leaves no choice about it being an extension. As a member it would erase to
 * `write(String, Object)` and clash with [FlightRecorder.writeErased]. Give that member the name
 * `write` instead and it wins overload resolution at every call site, so this reified version never
 * runs.
 *
 * @see FlightRecorder.write
 */
@JvmSynthetic
inline fun <reified T : Any> FlightRecorder.write(channelName: String, obj: T) =
    write(channelName, obj, typeOf<T>())
