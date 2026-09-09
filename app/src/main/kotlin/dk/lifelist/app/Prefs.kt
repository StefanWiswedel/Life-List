package dk.lifelist.app

import android.content.Context

/**
 * The one setting that changes what the app is willing to claim, remembered between launches.
 *
 * It was not remembered before: the target lived in a `remember` block, so every cold start put
 * it back to the default and a person who had decided they wanted 95% got 90% the next morning
 * without being told. A preference the user cannot make stick is not a preference.
 *
 * Stored as the *target accuracy*, not as the threshold it resolves to. The threshold depends on
 * the group and on the model, and both change under a phone that has been sitting in a pocket;
 * the accuracy the person asked for does not. Spec §4.4 already makes the rollup a display-time
 * decision for exactly this reason.
 */
object Prefs {

    private const val FILE = "lifelist.settings"
    private const val TARGET = "target_accuracy"

    /** What the person last asked for. 0.95 until they say otherwise. */
    const val DEFAULT_TARGET = 0.95f

    fun target(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(TARGET, DEFAULT_TARGET)

    fun setTarget(context: Context, target: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(TARGET, target)
            .apply()
    }
}
