package com.panomc.node.agent

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue

/**
 * The lines typed into a Pano Agent's terminal, read by one thread for its whole life (SM-76):
 * while the first run's questions are [asking] they are the answers, and otherwise they go to
 * whichever worker runs, as before. One reader, because two over the same stdin would each swallow
 * lines the other was waiting for.
 *
 * Latin-1 both ways, which maps every byte to itself: what the admin typed reaches the worker
 * exactly as typed, in whatever encoding their terminal uses. An answer is decoded again with the
 * terminal's own charset, so a folder or jar name that is not ASCII reads as typed.
 */
class AgentTerminal(
    private val input: InputStream,
    private val charset: Charset = inputCharset()
) {
    private val answers = LinkedBlockingQueue<Any>()

    /**
     * Whether lines are answers right now, rather than the worker's. Set before [start] when the
     * first thing the agent does is ask, so a line typed while the JVM was still starting is not
     * lost. Answers typed ahead and never asked for are dropped when the asking ends.
     */
    @Volatile
    var asking = false
        set(value) {
            field = value

            if (!value) {
                answers.removeIf { it !== END }
            }
        }

    /** Starts reading; [forward] gets every line typed while nothing is [asking]. */
    fun start(forward: (String) -> Unit) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))

                while (true) {
                    val line = reader.readLine() ?: break

                    if (asking) {
                        answers.put(line)
                    } else {
                        forward(line)
                    }
                }
            } catch (_: Exception) {
            } finally {
                answers.put(END)
            }
        }, "pano-agent-input").apply { isDaemon = true }.start()
    }

    /** The next answer, or null once the input has ended. Blocks until one is typed. */
    fun readLine(): String? {
        val next = try {
            answers.take()
        } catch (_: InterruptedException) {
            return null
        }

        if (next === END) {
            // Left in place: every later question gets the same "no more input".
            answers.put(END)

            return null
        }

        return String((next as String).toByteArray(Charsets.ISO_8859_1), charset)
    }

    companion object {
        private val END = Any()

        /**
         * Whether stdin is already at its end before anything was read: `/dev/null`, which is what a
         * service manager, `nohup` and `docker run` without `-i` give a process. Nobody can answer
         * there, so nothing is asked. Only on systems with `/dev/stdin`; elsewhere the first
         * question finds out (see [AgentSetup.Outcome.NoInput]).
         */
        fun stdinIsNull(): Boolean = try {
            val stdin = Paths.get("/dev/stdin")
            val devNull = Paths.get("/dev/null")

            Files.exists(stdin) && Files.exists(devNull) && Files.isSameFile(stdin, devNull)
        } catch (_: Throwable) {
            false
        }

        /**
         * Whether a person sits at a terminal: [System.console] exists, and on Java 22 and newer
         * (where it exists without one) `Console.isTerminal()` says so.
         */
        fun isTerminal(): Boolean {
            val console = System.console() ?: return false

            return try {
                java.io.Console::class.java.getMethod("isTerminal").invoke(console) as? Boolean ?: true
            } catch (_: Throwable) {
                // Before Java 22 there is no isTerminal(), and a console exists only at a terminal.
                true
            }
        }

        /** The charset the terminal types in. */
        fun inputCharset(): Charset = try {
            System.console()?.charset()
                ?: System.getProperty("stdin.encoding")?.let { Charset.forName(it) }
                ?: System.getProperty("native.encoding")?.let { Charset.forName(it) }
                ?: Charset.defaultCharset()
        } catch (_: Throwable) {
            Charset.defaultCharset()
        }

        /** Whether [out] can print an em dash and an arrow, or needs ASCII stand-ins. */
        fun canPrintUnicode(out: PrintStream): Boolean = try {
            val charset = try {
                PrintStream::class.java.getMethod("charset").invoke(out) as Charset
            } catch (_: NoSuchMethodException) {
                System.getProperty("sun.stdout.encoding")?.let { Charset.forName(it) } ?: Charset.defaultCharset()
            }

            charset.newEncoder().canEncode("—→")
        } catch (_: Throwable) {
            false
        }
    }
}
