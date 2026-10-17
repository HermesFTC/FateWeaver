package gay.zharel.fateweaver.flight

import gay.zharel.fateweaver.telemetry.Fate
import org.firstinspires.ftc.robotcore.external.Func
import org.firstinspires.ftc.robotcore.external.Telemetry
import java.util.Locale

/**
 * Adapts [Fate] to the FTC SDK [Telemetry] interface, so it can be handed to anything that takes a
 * `Telemetry`, such as FTC Dashboard's `MultipleTelemetry`:
 *
 * ```kotlin
 * telemetry = MultipleTelemetry(telemetry, FateFtcTelemetry)
 * ```
 *
 * Like the SDK's own telemetry, `addData` only queues an item. Nothing is logged until [update],
 * which evaluates every value producer and logs each item to the root table of [Fate] under its
 * caption. A caption with slashes (`"Drive/leftPower"`) goes into the matching child table.
 *
 * - An item with a format is logged as the formatted string. Otherwise the raw value is logged and
 *   its schema is inferred as for [Fate.logErased]. `null` values are skipped.
 * - Lines have no counterpart in a log, so a line's items are logged like any other item.
 * - Log messages go to the string entry `Telemetry/Log`.
 * - Display-only settings (separators, display format, transmission interval) are stored but
 *   have no effect, and [speak] does nothing.
 */
object FateFtcTelemetry : Telemetry {
  private const val LOG_PATH = "Telemetry/Log"

  private val lock = Any()
  private val items = mutableListOf<ItemImpl>()
  private val lineItems = mutableMapOf<LineImpl, MutableList<ItemImpl>>()
  private val actions = mutableListOf<Runnable>()

  private var autoClear = true
  private var msTransmissionInterval = 250
  private var itemSeparator = " | "
  private var captionValueSeparator = " : "
  private var displayFormat = Telemetry.DisplayFormat.CLASSIC

  private val logImpl = LogImpl()

  private class ValueSource(
    val format: String?,
    val args: Array<out Any?>?,
    val producer: Func<*>?,
    val value: Any?,
  ) {
    fun resolve(): Any? {
      if (producer != null) {
        val produced = producer.value()
        return if (format != null) String.format(Locale.getDefault(), format, produced) else produced
      }
      return if (format != null) String.format(Locale.getDefault(), format, *(args ?: emptyArray())) else value
    }
  }

  private class ItemImpl(
    private val owner: FateFtcTelemetry,
    private var caption: String,
    private var source: ValueSource,
  ) : Telemetry.Item {
    private var retained = false

    // Where this item sits for removal: null for top-level items.
    var line: LineImpl? = null

    override fun getCaption() = caption

    override fun setCaption(caption: String?): Telemetry.Item {
      this.caption = caption.orEmpty()
      return this
    }

    override fun setValue(format: String?, vararg args: Any?): Telemetry.Item {
      source = ValueSource(format, args, null, null)
      return this
    }

    override fun setValue(value: Any?): Telemetry.Item {
      source = ValueSource(null, null, null, value)
      return this
    }

    override fun <T : Any?> setValue(valueProducer: Func<T?>?): Telemetry.Item {
      source = ValueSource(null, null, valueProducer, null)
      return this
    }

    override fun <T : Any?> setValue(format: String?, valueProducer: Func<T?>?): Telemetry.Item {
      source = ValueSource(format, null, valueProducer, null)
      return this
    }

    override fun setRetained(retained: Boolean?): Telemetry.Item {
      this.retained = retained ?: false
      return this
    }

    override fun isRetained() = retained

    // Item.addData adds a sibling item to the same line, or the top level if there is none.
    override fun addData(caption: String?, format: String?, vararg args: Any?): Telemetry.Item =
      owner.add(line, caption, ValueSource(format, args, null, null))

    override fun addData(caption: String?, value: Any?): Telemetry.Item =
      owner.add(line, caption, ValueSource(null, null, null, value))

    override fun <T : Any?> addData(caption: String?, valueProducer: Func<T?>?): Telemetry.Item =
      owner.add(line, caption, ValueSource(null, null, valueProducer, null))

    override fun <T : Any?> addData(
      caption: String?,
      format: String?,
      valueProducer: Func<T?>?
    ): Telemetry.Item = owner.add(line, caption, ValueSource(format, null, valueProducer, null))

    fun logValue() {
      if (caption.isEmpty()) return
      val value = try {
        source.resolve()
      } catch (e: Exception) {
        // A throwing producer or a bad format string shouldn't take the OpMode loop down.
        Fate.registry.warn("Could not evaluate telemetry item '$caption': $e")
        return
      } ?: return
      Fate.logErased(caption, value)
    }
  }

  private class LineImpl(val owner: FateFtcTelemetry) : Telemetry.Line {
    override fun addData(caption: String?, format: String?, vararg args: Any?): Telemetry.Item =
      owner.add(this, caption, ValueSource(format, args, null, null))

    override fun addData(caption: String?, value: Any?): Telemetry.Item =
      owner.add(this, caption, ValueSource(null, null, null, value))

    override fun <T : Any?> addData(caption: String?, valueProducer: Func<T?>?): Telemetry.Item =
      owner.add(this, caption, ValueSource(null, null, valueProducer, null))

    override fun <T : Any?> addData(
      caption: String?,
      format: String?,
      valueProducer: Func<T?>?
    ): Telemetry.Item = owner.add(this, caption, ValueSource(format, null, valueProducer, null))
  }

  private class LogImpl : Telemetry.Log {
    private var capacity = 9
    private var displayOrder = Telemetry.Log.DisplayOrder.OLDEST_FIRST

    override fun getCapacity() = capacity

    override fun setCapacity(capacity: Int) {
      this.capacity = capacity
    }

    override fun getDisplayOrder() = displayOrder

    override fun setDisplayOrder(displayOrder: Telemetry.Log.DisplayOrder?) {
      this.displayOrder = displayOrder ?: Telemetry.Log.DisplayOrder.OLDEST_FIRST
    }

    // Entries are written through immediately: they are events, not state that update() refreshes.
    override fun add(entry: String?) {
      if (entry != null) Fate.logErased(LOG_PATH, entry)
    }

    override fun add(format: String?, vararg args: Any?) {
      if (format != null) add(String.format(Locale.getDefault(), format, *args))
    }

    override fun clear() {}
  }

  private fun add(line: LineImpl?, caption: String?, source: ValueSource): Telemetry.Item {
    val item = ItemImpl(this, caption.orEmpty(), source)
    item.line = line
    synchronized(lock) {
      items += item
      if (line != null) lineItems.getOrPut(line) { mutableListOf() } += item
    }
    return item
  }

  override fun addData(caption: String?, format: String?, vararg args: Any?): Telemetry.Item =
    add(null, caption, ValueSource(format, args, null, null))

  override fun addData(caption: String?, value: Any?): Telemetry.Item =
    add(null, caption, ValueSource(null, null, null, value))

  override fun <T : Any?> addData(caption: String?, valueProducer: Func<T?>?): Telemetry.Item =
    add(null, caption, ValueSource(null, null, valueProducer, null))

  override fun <T : Any?> addData(
    caption: String?,
    format: String?,
    valueProducer: Func<T?>?
  ): Telemetry.Item = add(null, caption, ValueSource(format, null, valueProducer, null))

  override fun removeItem(item: Telemetry.Item?): Boolean = synchronized(lock) {
    val impl = item as? ItemImpl ?: return false
    impl.line?.let { lineItems[it]?.remove(impl) }
    items.remove(impl)
  }

  override fun clear() {
    synchronized(lock) {
      val kept = items.filter { it.isRetained }
      items.clear()
      items += kept
      lineItems.values.forEach { it.retainAll(kept.toSet()) }
      lineItems.values.removeAll { it.isEmpty() }
    }
  }

  override fun clearAll() {
    synchronized(lock) {
      items.clear()
      lineItems.clear()
      actions.clear()
    }
  }

  override fun addAction(action: Runnable?): Any? {
    if (action == null) return null
    synchronized(lock) { actions += action }
    return action
  }

  override fun removeAction(token: Any?): Boolean = synchronized(lock) { actions.remove(token) }

  override fun speak(text: String?) {}

  override fun speak(text: String?, languageCode: String?, countryCode: String?) {}

  override fun update(): Boolean {
    val (pendingActions, pendingItems) = synchronized(lock) { actions.toList() to items.toList() }
    pendingActions.forEach { it.run() }
    pendingItems.forEach { it.logValue() }
    if (autoClear) clear()
    return true
  }

  override fun addLine(): Telemetry.Line = addLine("")

  override fun addLine(lineCaption: String?): Telemetry.Line = LineImpl(this).also {
    synchronized(lock) { lineItems[it] = mutableListOf() }
  }

  override fun removeLine(line: Telemetry.Line?): Boolean = synchronized(lock) {
    val members = lineItems.remove(line as? LineImpl ?: return false) ?: return false
    items.removeAll(members.toSet())
    true
  }

  override fun isAutoClear() = autoClear

  override fun setAutoClear(autoClear: Boolean) {
    this.autoClear = autoClear
  }

  override fun getMsTransmissionInterval() = msTransmissionInterval

  override fun setMsTransmissionInterval(msTransmissionInterval: Int) {
    this.msTransmissionInterval = msTransmissionInterval
  }

  override fun getItemSeparator() = itemSeparator

  override fun setItemSeparator(itemSeparator: String?) {
    this.itemSeparator = itemSeparator.orEmpty()
  }

  override fun getCaptionValueSeparator() = captionValueSeparator

  override fun setCaptionValueSeparator(captionValueSeparator: String?) {
    this.captionValueSeparator = captionValueSeparator.orEmpty()
  }

  override fun setDisplayFormat(displayFormat: Telemetry.DisplayFormat?) {
    this.displayFormat = displayFormat ?: Telemetry.DisplayFormat.CLASSIC
  }

  override fun log(): Telemetry.Log = logImpl
}
