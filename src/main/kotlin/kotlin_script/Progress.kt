package kotlin_script

import com.github.ajalt.mordant.animation.progress.*
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Spinner
import com.github.ajalt.mordant.widgets.progress.*

internal class Progress(
    private val t: Terminal? = null,
    private val trace: Boolean = false
) {
    private val spinner = Spinner.Lines()

    fun trace(vararg msg: String) {
        if (!trace) {
            return
        }
        if (t == null) {
            System.err.println("++ ${msg.joinToString(" ")}")
            return
        }
        t.println(
            buildString {
                append(TextStyles.bold.invoke("++"))
                append(" ")
                msg.forEachIndexed { index, arg ->
                    val part = if (arg.startsWith("/")) {
                        if (":" in arg) {
                            val separator = TextStyles.bold.invoke(":")
                            arg.split(":").joinToString(separator) {
                                TextStyles.italic.invoke(it)
                            }
                        } else {
                            TextStyles.italic.invoke(arg)
                        }
                    } else if (arg.startsWith("-")) {
                        TextStyles.bold.invoke(arg)
                    } else {
                        arg
                    }
                    append(part)
                    if (index < msg.size - 1) {
                        append(" ")
                    }
                }
            },
            stderr = true
        )
    }

    inline fun <reified T> withProgress(
        text: String,
        total: Long = 1L,
        block: (ProgressTask<Unit>?) -> T
    ): T {
        if (t == null) {
            return block(null)
        }
        val layout = progressBarLayout(alignColumns = false) {
            spinner(spinner)
            if (total > 1) {
                percentage()
            }
            text(text)
            if (total > 1) {
                progressBar()
                timeRemaining()
            }
        }
        val all = MultiProgressBarAnimation(
            t,
            clearWhenFinished = true
        ).animateOnThread()
        val main = all.addTask(layout, total = total)
        val future = all.execute()
        try {
            val result = block(main)
            main.update { completed = total }
            future.get()
            return result
        } catch (ex: Throwable) {
            future.cancel(true)
            throw ex
        }
    }
}
