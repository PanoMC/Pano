package com.panomc.node.agent

import com.panomc.node.util.NodeLogger
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.system.exitProcess

/**
 * The parts of a Pano Agent's worker that have nothing to do with Pano (SM-74): keeping the
 * libraries' own logging out of the server console, and not outliving the launcher.
 */
object AgentWorkerSupport {
    /**
     * Sends `java.util.logging` — what Vert.x and Netty log through here — to the agent's log instead
     * of stderr, which is the admin's server console. Only a SEVERE record reaches the terminal, as
     * an error.
     *
     * SLF4J is bound first, silently: it is on the classpath without a provider, and the first
     * library to ask for it would otherwise print three lines of "no providers" into the console
     * every time the worker starts. Done once, on the main thread, before any other thread exists.
     */
    fun routeJavaLogging(logger: NodeLogger) {
        val original = System.err

        try {
            System.setErr(PrintStream(OutputStream.nullOutputStream()))

            Class.forName("org.slf4j.LoggerFactory").getMethod("getILoggerFactory").invoke(null)
        } catch (_: Throwable) {
        } finally {
            System.setErr(original)
        }

        val root = Logger.getLogger("")

        root.handlers.forEach { root.removeHandler(it) }

        root.addHandler(object : Handler() {
            private val format = SimpleFormatter()

            override fun publish(record: LogRecord) {
                if (!isLoggable(record)) {
                    return
                }

                val message = "${record.loggerName ?: "jul"}: ${format.formatMessage(record)}" +
                    (record.thrown?.let { " ($it)" } ?: "")

                if (record.level.intValue() >= Level.SEVERE.intValue()) {
                    logger.error(message)
                } else {
                    logger.info(message)
                }
            }

            override fun flush() {}

            override fun close() {}
        })
    }

    /**
     * Stops the server and exits when the launcher that started this worker is gone.
     *
     * The launcher only ever exits after its worker, so this fires only when it was killed outright
     * (`kill -9`, a crashed JVM). The worker then does what a Ctrl+C would have made it do; left
     * alone, it would keep a server running that nobody can see or stop from the terminal, and the
     * next start of the agent would be refused because this one still holds the folder.
     */
    fun exitWithParent(logger: NodeLogger) {
        val parent = ProcessHandle.current().parent().orElse(null) ?: return
        val fired = AtomicBoolean(false)

        val orphaned = {
            if (fired.compareAndSet(false, true)) {
                logger.warn("The Pano Agent's launcher is gone; stopping the server and exiting.")

                Thread({ exitProcess(1) }, "pano-agent-orphaned").start()
            }
        }

        parent.onExit().thenRun { orphaned() }

        // onExit alone misses a launcher that died but was not reaped yet: a zombie still counts
        // as alive, and whatever started the launcher may never collect it. This worker is
        // re-parented the moment the launcher dies, reaped or not, so a new parent id says the same
        // thing without waiting on anyone.
        Thread({
            while (!fired.get()) {
                try {
                    Thread.sleep(PARENT_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }

                if (ProcessHandle.current().parent().map { it.pid() }.orElse(-1L) != parent.pid()) {
                    orphaned()
                }
            }
        }, "pano-agent-parent-watch").apply { isDaemon = true }.start()
    }

    /** How often the worker checks that the launcher that started it is still its parent. */
    private const val PARENT_POLL_MILLIS = 2_000L
}
