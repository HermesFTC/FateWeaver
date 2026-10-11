package gay.zharel.fateweaver.flight

import android.content.Context
import android.content.SharedPreferences
import org.firstinspires.ftc.robotcore.internal.system.AppUtil

private const val PREFS_NAME = "gay.zharel.fateweaver"
private const val KEY_LIVE_ENABLED = "liveEnabled"

/**
 * Whether [FlightProjector] is allowed to bind its port, remembered across robot restarts.
 *
 * The FTC 2026-2027 competition manual prohibits live streaming from third-party libraries during
 * competition, so live streaming is for testing and tuning only. Switching it off has to survive
 * every restart for the rest of an event, or a team would have to remember to disable it again
 * every time the Robot Controller comes back up.
 *
 * This mirrors how FTC Dashboard remembers its own enable/disable state: an Android
 * [SharedPreferences] entry, defaulting to on, written by the same call that starts or stops the
 * server. FateWeaver keeps its own preferences file rather than writing into the Robot
 * Controller's default one.
 *
 * @see FlightProjector.setEnabled
 */
object LiveSettings {
    private val prefs: SharedPreferences by lazy {
        AppUtil.getDefContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether live streaming is permitted. Writing this persists the choice immediately.
     *
     * Setting this alone does not start or stop a running server; use [FlightProjector.setEnabled],
     * which does both.
     */
    @JvmStatic
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LIVE_ENABLED, value).apply()
        }
}
