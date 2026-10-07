package net.coderbot.iris.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.coderbot.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;
import net.minecraftforge.client.event.ClientChatEvent;

/** Optional local diagnostics: preallocated frame buffers, asynchronous GPU queries and JFR stacks/pauses. */
public final class FrameTimeRecorder {
	public static final int TASKS = 0;
	public static final int TICK = 1;
	public static final int RENDER = 2;
	public static final int WORLD = 3;
	public static final int SHADOW = 4;
	public static final int DISPLAY = 5;
	public static final int SWAP = 6;
	public static final int LIMITER = 7;
	private static volatile Session active;
	private static boolean saving;

	private FrameTimeRecorder() {
	}

	public static void onChat(ClientChatEvent event) {
		String[] words = event.getMessage().trim().split("\\s+");
		if (!words[0].equalsIgnoreCase("/irisframetime")) {
			return;
		}
		event.setCanceled(true);
		if (words.length == 2 && words[1].equalsIgnoreCase("stop")) {
			if (active == null) {
				message("当前没有帧时间记录。");
			} else {
				finish("手动结束");
			}
			return;
		}
		int seconds = 60;
		try {
			if (words.length > 2) {
				throw new NumberFormatException();
			}
			if (words.length == 2) {
				seconds = Integer.parseInt(words[1]);
			}
			if (seconds < 10 || seconds > 120) {
				throw new NumberFormatException();
			}
		} catch (NumberFormatException e) {
			message("用法：/irisframetime [10～120 秒]，或 /irisframetime stop");
			return;
		}
		if (active != null || saving) {
			message("已有记录正在采集或保存，请等待完成。");
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			message("请进入世界后开始记录。");
			return;
		}
		active = new Session(seconds, client.gameDirectory.toPath().resolve("logs/frame-times"));
		message("10 秒后开始记录 " + seconds + " 秒帧时间、调用栈、等待和 GC，请关闭聊天框后正常游玩；完成后自动保存到 logs/frame-times。");
		if (active.jfr == null) {
			message("当前 JFR 不可用；本次仅记录帧与渲染阶段，调用栈、等待及 GC 归因不可用，详情会写入报告。");
		}
	}

	public static void beginPhase(int phase) {
		Session session = active;
		if (session != null && Thread.currentThread() == session.owner) {
			session.phases.begin(phase, System.nanoTime());
			session.gpu.beginPhase(phase);
		}
	}

	public static void endPhase(int phase) {
		Session session = active;
		if (session != null && Thread.currentThread() == session.owner) {
			session.gpu.endPhase(phase);
			session.phases.end(phase, System.nanoTime());
		}
	}

	/** Primitive token, no per-call object. Zero means diagnostics are disabled or warming up. */
	public static long beginDetail(int scope) {
		FrameTimeDetails.registerHook(scope);
		Session session = active;
		if (session == null || !session.recording) { return 0; }
		// Tick/packet diagnostics describe work actually executed by the client thread.
		// Packet handlers also run briefly on Netty to schedule that work; exclude those transfers.
		if (clientThreadOnly(scope) && Thread.currentThread() != session.owner) { return 0; }
		return session.details.begin(scope, System.nanoTime(), Thread.currentThread());
	}

	static boolean clientThreadOnly(int scope) {
		// Later categories (including split map maintenance) retain background diagnostics.
		return scope >= FrameTimeDetails.CLIENT_TICK && scope <= FrameTimeDetails.PACKET_LIGHT
				|| scope >= FrameTimeDetails.MAIN_TASK && scope <= FrameTimeDetails.KEYBOARD_INPUT;
	}

	public static void endDetail(int scope, long start, boolean failed) {
		endWorkDetail(scope, start, failed, null);
	}

	public static void endWorkDetail(int scope, long start, boolean failed, Object work) {
		if (start == 0) { return; }
		Session session = active;
		// A background call can outlive its capture. Never attach it to a later session.
		if (session != null && session.recording && start - session.frames.start >= 0) {
			session.details.end(scope, start, System.nanoTime(), Thread.currentThread(), failed, work);
		}
	}

	/** Called after the whole client frame, including presentation and the FPS limiter. */
	public static void onFrame() {
		Session session = active;
		if (session == null || Thread.currentThread() != session.owner) {
			return;
		}
		session.gpu.finishFrame(session.frames.started ? session.frames.count : -1);
		long now = System.nanoTime();
		Minecraft client = Minecraft.getInstance();
		int flags = (client.level == null ? FrameTimeCapture.NO_WORLD : 0)
				| (!client.isWindowActive() ? FrameTimeCapture.UNFOCUSED : 0)
				| (client.screen != null || client.getOverlay() != null ? FrameTimeCapture.SCREEN_OPEN : 0)
				| (client.isPaused() ? FrameTimeCapture.PAUSED : 0);
		int index = session.frames.count;
		long duration = now - session.frames.previous;
		boolean done = session.frames.record(now, flags);
		session.phases.finishFrame(session.frames.count > index ? index : -1, duration);
		session.details.finishFrame(session.frames.count > index ? index : -1, duration);
		if (session.frames.started) { session.recording = true; }
		if (done || client.level == null) {
			finish(client.level == null ? "离开世界" : session.frames.count == session.frames.ends.length ? "达到帧数上限" : "采集完成");
		}
	}

	private static void finish(String reason) {
		Session session = active;
		if (session == null || Thread.currentThread() != session.owner) { return; }
		active = null;
		session.details.freeze();
		// GL queries belong to this thread/context, never the asynchronous file writer.
		session.gpu.close();
		saving = true;
		Thread writer = new Thread(() -> {
			try {
				String summary = session.save(reason);
				Iris.logger.info("[FrameTime] " + summary);
				Minecraft.getInstance().execute(() -> message(summary));
			} catch (Exception e) {
				Iris.logger.error("Failed to save local frame times", e);
				Minecraft.getInstance().execute(() -> message("帧时间文件保存失败，详情见 latest.log。"));
			} finally {
				session.close();
				Minecraft.getInstance().execute(() -> saving = false);
			}
		}, "Iris frame time writer");
		writer.setDaemon(true);
		writer.start();
	}

	private static void message(String text) {
		Minecraft.getInstance().gui.getChat().addMessage(new TextComponent("[帧时间] " + text));
	}

	private static final class Session {
		final FrameTimeCapture frames;
		final FrameTimePhases phases;
		final FrameTimeDetails details;
		final FrameTimeGpuCapture gpu;
		final FrameTimeCpuClock cpuClock;
		volatile boolean recording;
		final Thread owner = Thread.currentThread();
		final Path directory;
		final String shaderPack;
		final String presentationSettings;
		final FrameTimeJfr jfr;
		String jfrStatus;

		Session(int seconds, Path directory) {
			this.directory = directory;
			this.shaderPack = Iris.getCurrentPackName();
			Minecraft client = Minecraft.getInstance();
			presentationSettings = "采集启动时画面设置：VSync=" + client.options.enableVsync
					+ "；FPS 上限=" + client.options.framerateLimit + "；全屏=" + client.getWindow().isFullscreen()
					+ "；窗口像素=" + client.getWindow().getWidth() + "x" + client.getWindow().getHeight();
			cpuClock = new FrameTimeCpuClock();
			phases = new FrameTimePhases(seconds * 2048, cpuClock);
			details = new FrameTimeDetails(seconds * 2048, 32768, owner, cpuClock);
			gpu = new FrameTimeGpuCapture(seconds * 2048, 32, new FrameTimeGpuDriver());
			FrameTimeJfr sampler = null;
			try {
				sampler = new FrameTimeJfr();
				jfrStatus = "JFR 已启用；分配采样=" + sampler.allocationSampling;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				jfrStatus = "JFR 不可用：" + e;
				Iris.logger.warn("Frame-time JFR unavailable; keeping frame/phase capture", e);
			}
			jfr = sampler;
			// JFR startup/class loading and buffer allocation happen before the 10-second warmup.
			frames = new FrameTimeCapture(seconds, System.nanoTime() + 10_000_000_000L);
		}

		void close() {
			if (jfr != null) {
				jfr.close();
			}
		}

		String save(String reason) throws IOException {
			Files.createDirectories(directory);
			String name = "frames-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
			Path csv = directory.resolve(name + ".csv");
			FrameTimeJfr.Result timing = null;
			if (jfr != null) {
				try {
					timing = jfr.finish(directory.resolve(name + ".jfr"));
					jfrStatus += "；文件=" + name + ".jfr";
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					jfrStatus = "JFR 导出或校准失败，GC 归因不可用：" + e;
					Iris.logger.warn("Frame-time JFR export failed; keeping frame/phase CSV", e);
				}
			}
			List<FrameTimeJfr.Pause> pauses = timing == null ? Collections.emptyList() : timing.pauses;
			int gcEvents = 0;
			int longFrames = 0;
			int longFramesWithGc = 0;
			int invalidPhases = 0;
			try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
				out.write("type,start_ms,end_ms,duration_ms,flags," + FrameTimePhases.CSV_COLUMNS + ",phases_valid,gc_id,gc_name\n");
				long previous = frames.start;
				for (int i = 0; i < frames.count; i++) {
					write(out, "frame", previous, frames.ends[i], frames.flags[i]);
					for (int phase = 0; phase < FrameTimePhases.COUNT; phase++) {
						out.write("," + phases.samples[phase][i] / 1_000_000.0D);
					}
					out.write("," + (phases.valid[i] ? 1 : 0) + ",,\n");
					if (!phases.valid[i]) {
						invalidPhases++;
					}
					if (frames.flags[i] == 0 && frames.ends[i] - previous > 20_000_000L) {
						longFrames++;
						for (FrameTimeJfr.Pause pause : pauses) {
							if (pause.start < frames.ends[i] && pause.end > previous) {
								longFramesWithGc++;
								break;
							}
						}
					}
					previous = frames.ends[i];
				}
				for (FrameTimeJfr.Pause pause : pauses) {
					if (frames.started && pause.end > frames.start && pause.start < frames.previous) {
						write(out, "gc", pause.start, pause.end, 0);
						for (int i = 0; i <= FrameTimePhases.COUNT; i++) {
							out.write(",");
						}
						out.write("," + pause.id + ",\"" + pause.name.replace("\"", "\"\"") + "\"\n");
						gcEvents++;
					}
				}
			}
			FrameTimeCapture.Statistics stats = frames.statistics();
			String summary = String.format(Locale.ROOT,
					"%s：有效 %d 帧，平均 %.1f FPS，1%% low %.1f FPS，P99 %.2f ms，最长 %.2f ms，超过 50 ms %d 帧。文件：%s",
					reason, stats.frames, stats.averageFps, stats.lowOnePercentFps, stats.p99Millis,
					stats.maxMillis, stats.over50Millis, csv.getFileName());
			String gcDetails = timing != null && timing.aligned
					? "GC 来源=JFR GCPhasePause（顶层暂停）；区间内 " + gcEvents + " 次；超过 20 ms 的有效帧 "
							+ longFrames + "，其中与 GC 暂停严格重合 " + longFramesWithGc + "。\n时钟同步误差估计 ±"
							+ timing.uncertaintyMillis + " ms；首尾漂移 " + timing.driftMillis + " ms。"
					: "GC 时钟未校准，重合数量未知；不能将缺失 GC 数据解释成没有 GC 暂停。";
			String detailReport = this.details.save(directory, name, frames, FrameTimeDetails.availableHooks());
			String renderReport = gpu.save(directory, name, frames, phases);
			String details = summary + "\n格式版本：11（渲染阶段 CPU 与异步 GPU 时间戳，CSV 按列名读取）\n光影：" + shaderPack + "\n总记录帧数：" + frames.count
					+ "\n" + presentationSettings
					+ "\n客户端渲染/tick/窗口事件/输入回调/任务/收包/提交 CPU 时钟=" + cpuClock.status
					+ "\n客户端主线程=" + owner.getName() + "；Java 线程 ID=" + owner.getId()
					+ "\n1% low = 最慢 ceil(有效帧数 * 1%) 帧的平均耗时取倒数；P99 为最近秩百分位。"
					+ "\nflags：1=不在世界，2=失焦，4=界面打开，8=暂停；汇总只使用 flags=0 的帧，CSV 保留全部帧。"
					+ "\n时间为客户端完整帧之间的间隔，包含 VSync/FPS 限制等待；不等同于 GPU 硬件呈现时间。"
					+ "\n阶段列：tasks=主线程任务；tick=客户端逻辑；render=游戏渲染（含界面）；world=世界与手持物渲染；"
					+ "shadow=阴影；display=画面提交与窗口事件；swap=交换缓冲等待；limiter=限帧等待。"
					+ "\n阶段是包含子阶段的墙钟耗时（含 GC/调度等待），不是纯 CPU 或 GPU 时间；render 包含 world/shadow，display 包含 swap，不能直接全部相加。"
					+ "\nphases_valid=0 表示阶段未配对或越界，应忽略该帧的阶段归因，帧间隔仍保留；本次 " + invalidPhases + " 帧。"
					+ "\n" + gcDetails + "\n" + jfrStatus
					+ (jfr == null ? "" : "\n" + jfr.profilingDetails)
					+ (timing == null ? "" : "\n" + timing.coverageReport(frames.start, frames.previous, owner.getId()))
					+ "\n调用栈与等待记录位于 JFR，覆盖全部线程；分析客户端卡顿时按上述主线程 ID 和采集时间筛选。"
					+ "\n执行样本不是每个 tick 的完整跟踪，未采到调用栈不表示没有执行；等待事件按持续区间与帧取交集，不将整个等待时长累加到每帧。"
					+ "\n分配采样限流 100/s；JFR 包含预热与结束过程，分析时按下方采集起止过滤，并剔除各线程首个分配样本的历史累计权重。"
					+ (timing == null || !timing.aligned || !frames.started ? "" : "\n采集开始=" + timing.anchor.toInstant(frames.start)
							+ "\n采集结束=" + timing.anchor.toInstant(frames.previous))
					+ "\n时间重合不是独立的因果证明；GC 以外的停顿仍可能来自其他 safepoint、线程调度或渲染。\n"
					+ renderReport + detailReport;
			Files.write(directory.resolve(name + ".txt"), details.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
			return summary;
		}

		private void write(BufferedWriter out, String type, long start, long end, int flags) throws IOException {
			out.write(type + "," + (start - frames.start) / 1_000_000.0D + "," + (end - frames.start) / 1_000_000.0D
					+ "," + (end - start) / 1_000_000.0D + "," + flags);
		}
	}
}
