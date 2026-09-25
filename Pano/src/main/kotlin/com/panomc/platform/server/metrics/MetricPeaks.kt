package com.panomc.platform.server.metrics

import com.panomc.platform.server.dto.ServerMetricSample

/**
 * The highest CPU and traffic a server reported since its last stored minute.
 *
 * The per-minute row is otherwise one sample out of six, and a CPU spike or a download burst that
 * fell between two of them never reached the history: the live one-minute chart showed a 30 %
 * spike that the one-hour chart, drawn from the rows, then had no trace of. These three are the
 * figures a peak is the honest summary of; the rest of the row stays the newest sample.
 */
data class MetricPeaks(
    val cpu: Double? = null,
    val netRx: Long? = null,
    val netTx: Long? = null
) {
    /** These peaks with [sample] folded in; a value the sample does not carry leaves the peak alone. */
    fun with(sample: ServerMetricSample): MetricPeaks = MetricPeaks(
        cpu = higher(cpu, sample.cpu),
        netRx = higher(netRx, sample.netRx),
        netTx = higher(netTx, sample.netTx)
    )

    private companion object {
        fun <T : Comparable<T>> higher(current: T?, next: T?): T? = when {
            current == null -> next
            next == null -> current
            else -> maxOf(current, next)
        }
    }
}
