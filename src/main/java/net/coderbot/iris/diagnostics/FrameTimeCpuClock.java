package net.coderbot.iris.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.LongSupplier;

/** Optional, capture-only CPU clock. Never enables a JVM-wide monitoring setting. */
final class FrameTimeCpuClock implements LongSupplier {
	private ThreadMXBean bean;
	String status;

	FrameTimeCpuClock() {
		try {
			ThreadMXBean candidate = ManagementFactory.getThreadMXBean();
			if (candidate.isCurrentThreadCpuTimeSupported() && candidate.isThreadCpuTimeEnabled()) {
				bean = candidate;
				status = "available (ThreadMXBean current-thread CPU time)";
			} else {
				status = "unavailable (unsupported or disabled by JVM)";
			}
		} catch (RuntimeException | LinkageError e) {
			status = "unavailable (" + e.getClass().getSimpleName() + ")";
		}
		getAsLong(); // Resolve the native entry point before the capture warmup.
	}

	@Override
	public long getAsLong() {
		if (bean == null) { return -1; }
		try {
			long value = bean.getCurrentThreadCpuTime();
			if (value >= 0) { return value; }
			status = "unavailable (JVM returned no CPU time)";
		} catch (RuntimeException | LinkageError e) {
			status = "unavailable (" + e.getClass().getSimpleName() + ")";
		}
		bean = null;
		return -1;
	}
}
