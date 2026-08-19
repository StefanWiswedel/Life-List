package dk.lifelist.app

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own black box.
 *
 * "It crashes whenever I open it. No error. Is there an error log somewhere?" There was not.
 * The only ways to see an Android stack trace are `adb logcat` from a computer, or Developer
 * options → Take bug report and then hunting through a zip — neither of which is reasonable to
 * ask of someone standing in a garden with a moth.
 *
 * So the app keeps its own. An uncaught exception is written to a file before the process dies,
 * and the next launch offers it up. The default handler is still called afterwards, so the
 * system dialog and Play's own reporting behave exactly as before — this only adds a copy the
 * user can actually reach.
 *
 * Writing to `filesDir` and not to a database on purpose: the process is already dying and the
 * heap may be the reason. One `File.writeText` in the handler is about as much as can be
 * trusted at that point.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val appContext = context.applicationContext

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread.name, error) }
            // Never swallow it. A crash that leaves no system trace is a crash nobody else can
            // see, and this file is a convenience, not a replacement.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, threadName: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        File(context.filesDir, FILE).writeText(
            buildString {
                appendLine("Life List crash")
                appendLine(stamp)
                appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("thread $threadName")
                appendLine()
                append(trace.toString())
            }
        )
    }

    /** The last crash, if there was one and it has not been dismissed. */
    fun last(context: Context): String? = runCatching {
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /** Hand it to whatever the user has — mail, chat, notes. */
    fun share(context: Context, report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Life List crash")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, "Send the crash report")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
