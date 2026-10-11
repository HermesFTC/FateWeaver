package gay.zharel.fateweaver.flight

import android.content.Context
import android.content.SharedPreferences
import org.firstinspires.ftc.robotcore.internal.system.AppUtil

private const val PREFS_NAME = "gay.zharel.fateweaver"
private const val KEY_LIVE_ENABLED = "liveEnabled"

/**
 * Whether [FlightProjector] is allowed to bind its port, remembered across robot restarts.
 *
 * @see FlightProjector.setEnabled
 */
object LiveSettings {
    private val prefs: SharedPreferences by lazy {
        AppUtil.getDefContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether live streaming is permitted. Defaults to `true`. Writing this saves the choice.
     *
     * Setting this does not start or stop the server. Use [FlightProjector.setEnabled] for that.
     */
    @JvmStatic
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_ENABLED, value).apply()
        }
}
