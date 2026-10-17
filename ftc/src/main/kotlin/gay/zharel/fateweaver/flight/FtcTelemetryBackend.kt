package gay.zharel.fateweaver.flight

import gay.zharel.fateweaver.schemas.FateSchema
import gay.zharel.fateweaver.telemetry.Fate
import gay.zharel.fateweaver.telemetry.FateBackend
import gay.zharel.fateweaver.telemetry.FateEntry
import org.firstinspires.ftc.robotcore.external.Telemetry

class FtcTelemetryBackend(private val telemetry: Telemetry) : FateBackend {
  inner class FtcTelemetryEntry<T>(val key: String) : FateEntry<T> {
    override fun log(value: T, timestamp: Long) {
      telemetry.addData(key, value);
    }
  }

  override fun <T> entry(
    path: String,
    schema: FateSchema<T>
  ): FateEntry<T> = FtcTelemetryEntry(path)

  override fun close() {}
}