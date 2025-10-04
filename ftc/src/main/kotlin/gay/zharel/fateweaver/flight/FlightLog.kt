package gay.zharel.fateweaver.flight

import gay.zharel.fateweaver.log.LogChannel
import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.schemas.StringSchema
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry

data class FlightLogChannel<T>(
    override val name: String,
    override val schema: FateSchema<T>,
) : LogChannel<T> {
    override fun put(obj: T) = FlightRecorder.write(this, obj)
    fun write(obj: T) = put(obj)

    fun downsample(maxPeriod: Long): DownsampledChannel<T> {
        return DownsampledChannel(this, maxPeriod)
    }
}


class DownsampledChannel<T>(val channel: FlightLogChannel<T>, val maxPeriod: Long)
    : LogChannel<T> by channel {
    private var nextWriteTimestamp = 0L

    override fun put(obj: T) {
        val now = System.nanoTime()
        if (now >= nextWriteTimestamp) {
            nextWriteTimestamp = (now / maxPeriod + 1) * maxPeriod
            channel.put(obj)
        }
    }
}

/**
 * A telemetry item that writes to a flight log channel.
 * Unlike other [LogChannel]s, this one holds onto a value until [write] is called
 * due to the way telemetry items are used.
 *
 * Can only be created via [FlightRecorder.addData].
 */
@ConsistentCopyVisibility
data class FlightLogItem internal constructor(
    internal var channel: FlightLogChannel<String>,
    internal var value: String
) : LogChannel<String>, Telemetry.Item, Telemetry.Line {
    override val name get() = channel.name
    override val schema = StringSchema

    /**
     * Writes an object to this channel.
     *
     * @param obj The object to write to the channel
     */
    override fun put(obj: String) {
        this.value = obj
    }

    override fun getCaption(): String = name

    override fun setCaption(caption: String): Telemetry.Item = apply {
        this.channel = FlightLogChannel(caption, StringSchema)
    }

    override fun setValue(format: String, vararg args: Any): Telemetry.Item = apply {
        this.value = String.format(format, *args)
    }

    override fun setValue(value: Any?): Telemetry.Item = apply {
       this.value = value?.toString() ?: "null"
    }

    override fun <T : Any?> setValue(valueProducer: Func<T>): Telemetry.Item = apply {
        this.value = valueProducer.value()?.toString() ?: "null"
    }

    override fun <T : Any> setValue(format: String, valueProducer: Func<T>): Telemetry.Item = apply {
        this.value = String.format(format, valueProducer.value())
    }

    override fun setRetained(retained: Boolean?): Telemetry.Item = this

    override fun isRetained(): Boolean = true

    override fun addData(caption: String, format: String, vararg args: Any): Telemetry.Item =
        FlightRecorder.addData(caption, format, *args)

    override fun addData(caption: String, value: Any): Telemetry.Item =
        FlightRecorder.addData(caption, value)

    override fun <T : Any?> addData(caption: String, valueProducer: Func<T>): Telemetry.Item =
        FlightRecorder.addData(caption, valueProducer)

    override fun <T : Any?> addData(caption: String, format: String, valueProducer: Func<T>): Telemetry.Item =
        FlightRecorder.addData(caption, format, valueProducer)

    /**
     * Writes the current value to the channel.
     */
    fun write() {
        channel.put(value)
    }
}