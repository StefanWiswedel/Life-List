package dk.lifelist.core

/**
 * How a score stands against the threshold it had to clear.
 *
 * Split out from the yes/no of §4 on purpose. The app's answer is still binary — it will
 * either stand behind a name or it will not — but hiding the number behind that decision threw
 * away the one thing a person in a field can act on. A magpie at 0.77 against a threshold of
 * 0.82 is a different situation from a raven at 0.12, and "not sure enough" said both.
 *
 * So: the claim stays honest, and the number is shown anyway, coloured by how close it came.
 */
enum class Standing {
    /** At or above the threshold. The app will stand behind this. */
    CLEARS,

    /** Short, but close enough that another few seconds of song might settle it. */
    NEAR,

    /** Not close. Worth showing, not worth waiting for. */
    SHORT,
}

/** How far below a threshold still counts as near. */
const val NEAR_MARGIN = 0.15f

fun standing(probability: Float, threshold: Float): Standing = when {
    probability >= threshold -> Standing.CLEARS
    probability >= threshold - NEAR_MARGIN -> Standing.NEAR
    else -> Standing.SHORT
}
