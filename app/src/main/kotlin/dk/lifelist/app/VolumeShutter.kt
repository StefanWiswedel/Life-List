package dk.lifelist.app

/**
 * The volume keys, wired to the shutter.
 *
 * Asked for directly: "in my normal camera app, I use the volume button as the shutter, can we
 * do that here too?" Every camera app on the phone does this, and a photograph of an insect is
 * usually taken one-handed at an angle where the on-screen button is the hardest thing to
 * reach on the device.
 *
 * A key press arrives at the Activity, and the thing that knows how to take a photograph is a
 * composable several layers down. Rather than thread a callback through every screen, the
 * capture screen leaves one here for as long as it is on screen and takes it back when it
 * leaves. One listener, not a list: two capture screens at once is not a state this app has.
 *
 * The Activity must return true when [press] does, or Android will also change the volume —
 * a shutter that beeps its way up to maximum is not a shutter anybody wants.
 */
object VolumeShutter {

    private var listener: (() -> Unit)? = null

    /** Whether the capture screen is on. The Activity swallows the key-up to match. */
    val listening: Boolean get() = listener != null

    /** Called by the capture screen while it is composed; null on the way out. */
    fun listen(onPress: (() -> Unit)?) {
        listener = onPress
    }

    /** True if somebody was listening, which is also "consume this key press". */
    fun press(): Boolean {
        val waiting = listener ?: return false
        waiting()
        return true
    }
}
