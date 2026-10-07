package net.coderbot.iris.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Optional CPU wall-clock diagnostics. Background work is never added to main-thread frame totals. */
public final class FrameTimeDetails {
	public static final int POLL_BEFORE = 0, POLL_AFTER = 1, RENDER_QUEUE = 2;
	public static final int CHUNK_UPDATE = 3, CHUNK_UPLOAD = 4, CHUNK_GL_UPLOAD = 5, CHUNK_DRAW = 6;
	public static final int DH_RENDER = 7, DH_UPLOAD = 8, MAP_UPDATE = 9, MAP_UPLOAD = 10, MAP_PBO_WRITE = 11;
	public static final int MAP_WRITE = 12, MAP_PREPARE = 13, MAP_BUFFER = 14, MAP_READBACK = 15;
	public static final int MAP_VRAM = 16, MAP_FLUSH = 17, MAP_GL_CHECK = 18, MAP_GL_STATE = 19;
	public static final int MAP_CLEANUP = 20, MAP_POST_UPLOAD = 21, MAP_CACHE = 22, MAP_TEXTURE_LOOKUP = 23;
	public static final int CLIENT_TICK = 24, TICK_PRE = 25, TICK_POST = 26, TICK_GAME_MODE = 27;
	public static final int TICK_ENTITIES = 28, TICK_LEVEL = 29, TICK_ANIMATE = 30, TICK_PARTICLES = 31;
	public static final int TICK_SOUND = 32, TICK_TEXTURES = 33, TICK_GUI = 34, TICK_RENDERER = 35;
	public static final int DS_AREA = 36, DS_DELTA = 37, DS_SCAN = 38, DS_BLOCK_UPDATE = 39;
	public static final int PACKET_CHUNK = 40, PACKET_BLOCK = 41, PACKET_LIGHT = 42;
	public static final int MAP_PROCESS_END = 43, MAP_LOADED_CHUNKS = 44, MAP_CONFIG = 45, MAP_CAVE = 46, MAP_RELOAD = 47;
	public static final int MAIN_TASK = 48, SWAP_BUFFERS = 49, PACKET_APPLY = 50;
	public static final int WINDOW_RESIZE = 51, WINDOW_FOCUS = 52, WINDOW_MOVE = 53, MOUSE_INPUT = 54, KEYBOARD_INPUT = 55;
	static final String[] NAMES = {"poll_before", "poll_after", "render_queue", "chunk_update", "chunk_upload",
			"chunk_gl_upload", "chunk_draw", "dh_render", "dh_upload", "map_update", "map_upload", "map_pbo_write",
			"map_write", "map_prepare", "map_buffer", "map_readback", "map_vram", "map_flush", "map_gl_check",
			"map_gl_state", "map_cleanup", "map_post_upload", "map_cache", "map_texture_lookup",
			"client_tick", "tick_pre", "tick_post", "tick_game_mode", "tick_entities", "tick_level", "tick_animate",
			"tick_particles", "tick_sound", "tick_textures", "tick_gui", "tick_renderer",
			"ds_area", "ds_delta", "ds_scan", "ds_block_update", "packet_chunk", "packet_block", "packet_light",
			"map_process_end", "map_loaded_chunks", "map_config", "map_cave", "map_reload",
			"main_task", "swap_buffers", "packet_apply",
			"window_resize", "window_focus", "window_move", "mouse_input", "keyboard_input"};
	static final int COUNT = NAMES.length;
	static final long SPAN_THRESHOLD = 1_000_000L;
	private static final AtomicLong HOOKS = new AtomicLong();

	// Safe to call from the Mixin plugin without loading any Minecraft classes.
	public static void registerHook(int scope) {
		long mask = 1L << scope, previous;
		do {
			previous = HOOKS.get();
			if ((previous & mask) != 0) { return; }
		} while (!HOOKS.compareAndSet(previous, previous | mask));
	}

	static long availableHooks() { return HOOKS.get(); }

	private final Thread owner;
	final long[][] totals, maxima;
	final int[][] calls, failures;
	final boolean[] valid;
	final long[] tickCpuNanos;
	final int[] tickCpuCalls;
	private final LongSupplier cpuClock;
	private final long[] cpuStarts = new long[COUNT];
	private long frameTickCpuNanos;
	private int frameTickCpuCalls;
	private final int[] depth = new int[COUNT], frameCalls = new int[COUNT], frameFailures = new int[COUNT];
	private final long[] starts = new long[COUNT], frameTotals = new long[COUNT], frameMaxima = new long[COUNT];
	private boolean balanced = true;
	private volatile boolean closed;
	private final long[] backgroundStarted = new long[COUNT], backgroundFinished = new long[COUNT];
	private final long[] backgroundNanos = new long[COUNT], backgroundMaxima = new long[COUNT], backgroundFailures = new long[COUNT];
	private final long[] spanStarts, spanEnds;
	private final long[] spanCpuNanos;
	private final int[] spanScopes;
	private final boolean[] spanFailures;
	private final Thread[] spanThreads;
	private final Class<?>[] spanWorkClasses;
	private int spanCount;
	private long droppedSpans;

	FrameTimeDetails(int frames, int spanCapacity, Thread owner) {
		this(frames, spanCapacity, owner, () -> -1);
	}

	FrameTimeDetails(int frames, int spanCapacity, Thread owner, LongSupplier cpuClock) {
		this.owner = owner;
		this.cpuClock = cpuClock;
		Arrays.fill(cpuStarts, -1);
		totals = new long[COUNT][frames]; maxima = new long[COUNT][frames];
		calls = new int[COUNT][frames]; failures = new int[COUNT][frames]; valid = new boolean[frames];
		tickCpuNanos = new long[frames]; tickCpuCalls = new int[frames];
		spanStarts = new long[spanCapacity]; spanEnds = new long[spanCapacity]; spanScopes = new int[spanCapacity];
		spanCpuNanos = new long[spanCapacity];
		spanFailures = new boolean[spanCapacity]; spanThreads = new Thread[spanCapacity];
		spanWorkClasses = new Class<?>[spanCapacity];
	}

	long begin(int scope, long now, Thread thread) {
		if (closed) { return 0; }
		if (thread == owner) {
			if (depth[scope]++ == 0) {
				starts[scope] = now;
				if (hasCpuClock(scope)) { cpuStarts[scope] = cpuClock.getAsLong(); }
			}
		} else {
			synchronized (this) {
				if (closed) { return 0; }
				backgroundStarted[scope]++;
			}
		}
		return now;
	}

	void end(int scope, long start, long end, Thread thread, boolean failed) {
		end(scope, start, end, thread, failed, null);
	}

	void end(int scope, long start, long end, Thread thread, boolean failed, Object work) {
		if (start == 0 || closed) { return; }
		long elapsed = end - start;
		if (thread == owner) {
			if (elapsed < 0 || depth[scope] == 0) { balanced = false; return; }
			long cpuNanos = -1;
			if (hasCpuClock(scope) && depth[scope] == 1) {
				long cpuEnd = cpuClock.getAsLong();
				if (cpuStarts[scope] >= 0 && cpuEnd >= cpuStarts[scope]) {
					cpuNanos = cpuEnd - cpuStarts[scope];
					if (scope == CLIENT_TICK) {
						frameTickCpuNanos += cpuNanos;
						frameTickCpuCalls++;
					}
				}
				cpuStarts[scope] = -1;
			}
			frameCalls[scope]++;
			frameMaxima[scope] = Math.max(frameMaxima[scope], elapsed);
			if (failed) { frameFailures[scope]++; }
			// Nested calls to the same category count separately, but their wall time is a union.
			if (--depth[scope] == 0) { frameTotals[scope] += end - starts[scope]; }
			if (elapsed >= SPAN_THRESHOLD || failed) { append(scope, start, end, thread, failed, cpuNanos, work); }
		} else {
			synchronized (this) {
				if (closed || elapsed < 0) { return; }
				backgroundFinished[scope]++;
				backgroundNanos[scope] += elapsed;
				backgroundMaxima[scope] = Math.max(backgroundMaxima[scope], elapsed);
				if (failed) { backgroundFailures[scope]++; }
				if (elapsed >= SPAN_THRESHOLD || failed) { append(scope, start, end, thread, failed, -1, work); }
			}
		}
	}

	private synchronized void append(int scope, long start, long end, Thread thread, boolean failed, long cpuNanos, Object work) {
		if (closed) { return; }
		if (spanCount == spanStarts.length) { droppedSpans++; return; }
		int index = spanCount++;
		spanScopes[index] = scope; spanStarts[index] = start; spanEnds[index] = end;
		spanThreads[index] = thread; spanFailures[index] = failed;
		spanCpuNanos[index] = cpuNanos;
		// Retain only the type, never the task/packet and its captured world or network buffers.
		spanWorkClasses[index] = work == null ? null : work.getClass();
	}

	void finishFrame(int index, long duration) {
		if (index >= 0) {
			for (int scope = 0; scope < COUNT; scope++) {
				totals[scope][index] = frameTotals[scope]; maxima[scope][index] = frameMaxima[scope];
				calls[scope][index] = frameCalls[scope]; failures[scope][index] = frameFailures[scope];
				balanced &= depth[scope] == 0 && frameTotals[scope] >= 0 && frameTotals[scope] <= duration;
			}
			valid[index] = balanced;
			tickCpuNanos[index] = frameTickCpuNanos; tickCpuCalls[index] = frameTickCpuCalls;
		}
		Arrays.fill(depth, 0); Arrays.fill(frameTotals, 0); Arrays.fill(frameMaxima, 0);
		Arrays.fill(frameCalls, 0); Arrays.fill(frameFailures, 0); balanced = true;
		Arrays.fill(cpuStarts, -1); frameTickCpuNanos = 0; frameTickCpuCalls = 0;
	}

	private static boolean hasCpuClock(int scope) {
		return scope == CLIENT_TICK || scope == POLL_BEFORE || scope == POLL_AFTER
				|| scope >= MAIN_TASK && scope <= KEYBOARD_INPUT;
	}

	/** Seal before the export thread starts. Late background completions cannot mutate the report. */
	synchronized void freeze() { closed = true; }

	String save(Path directory, String name, FrameTimeCapture frames, long availableMask) throws IOException {
		if (!closed) { throw new IllegalStateException("unsealed frame details"); }
		try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(name + ".details.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
			out.write("frame,start_ms,end_ms,duration_ms,flags,details_valid");
			for (String scope : NAMES) { out.write("," + scope + "_ms," + scope + "_calls," + scope + "_max_ms," + scope + "_failures"); }
			out.write(",client_tick_cpu_ms,client_tick_cpu_calls\n");
			long previous = frames.start;
			for (int i = 0; i < frames.count; i++) {
				out.write(i + "," + millis(previous - frames.start) + "," + millis(frames.ends[i] - frames.start)
						+ "," + millis(frames.ends[i] - previous) + "," + frames.flags[i] + "," + (valid[i] ? 1 : 0));
				for (int scope = 0; scope < COUNT; scope++) {
					out.write("," + millis(totals[scope][i]) + "," + calls[scope][i] + "," + millis(maxima[scope][i]) + "," + failures[scope][i]);
				}
				out.write("," + (tickCpuCalls[i] == 0 ? "" : millis(tickCpuNanos[i])) + "," + tickCpuCalls[i] + "\n"); previous = frames.ends[i];
			}
		}
		int written = 0;
		try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(name + ".spans.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
			out.write("scope,thread_id,thread_name,is_main,start_ms,end_ms,duration_ms,failed,cpu_ms,work_class\n");
			for (int i = 0; i < spanCount; i++) {
				if (!frames.started || spanStarts[i] >= frames.previous || spanEnds[i] <= frames.start) { continue; }
				Thread thread = spanThreads[i];
				out.write(NAMES[spanScopes[i]] + "," + thread.getId() + ",\"" + thread.getName().replace("\"", "\"\"") + "\","
						+ (thread == owner ? 1 : 0) + "," + millis(spanStarts[i] - frames.start) + "," + millis(spanEnds[i] - frames.start)
						+ "," + millis(spanEnds[i] - spanStarts[i]) + "," + (spanFailures[i] ? 1 : 0)
						+ "," + (spanCpuNanos[i] < 0 ? "" : millis(spanCpuNanos[i]))
						+ ",\"" + (spanWorkClasses[i] == null ? "" : spanWorkClasses[i].getName().replace("\"", "\"\"")) + "\"\n");
				written++;
			}
		}
		StringBuilder report = new StringBuilder("细分计时文件=" + name + ".details.csv；长调用区间=" + name + ".spans.csv\n");
		report.append("细分类别：poll_before/poll_after=交换缓冲前/后的窗口事件；render_queue=RenderSystem 提交队列；")
				.append("chunk_update=区块更新/可见性准备；chunk_upload=区块上传批次；chunk_gl_upload=区块缓冲上传/复制/分配；chunk_draw=区块绘制；")
				.append("dh_render=DH 渲染入口；dh_upload=DH 缓冲上传；map_update=Xaero 地图更新；map_upload=地图上传队列；map_pbo_write=地图像素上传缓冲写入。\n")
				.append("地图内部：map_write=描图；map_prepare=纹理准备/像素着色；map_buffer=纹理缓冲处理（含上传与回读分支）；map_readback=PBO 回读完成处理；")
				.append("map_vram=显存查询；map_flush=进入地图处理前提交已有渲染批次；map_gl_check=原有 GL 错误检查；map_gl_state=原有 GL 状态设置；")
				.append("map_cleanup=区域 GL 缓冲释放；map_post_upload=上传后维护；map_process_end=区域处理收尾；map_loaded_chunks=已加载区块维护；map_config=配置同步；map_cave=洞穴层更新；map_reload=整图重载；map_cache=请求缓存；map_texture_lookup=查找或创建区域纹理。\n")
				.append("格式 8 将原 map_maintenance 的调用分别归入上述六项，只分类原有计时点；与旧采样比较时应按区间并集汇总，不能再额外叠加旧分类。\n")
				.append("tick 内部：client_tick=单次客户端 tick；tick_pre/tick_post=Forge 前/后事件；tick_game_mode=交互控制与连接处理；")
				.append("tick_entities=实体；tick_level=世界；tick_animate=随机显示更新；tick_particles=粒子；tick_sound=声音/音乐；tick_textures=动态纹理；tick_gui=HUD；tick_renderer=渲染器逻辑更新/拾取。\n")
				.append("Dynamic Surroundings：ds_area=区域效果总计；ds_delta=移动后的增量扫描；ds_scan=分批/随机扫描；ds_block_update=方块变化通知。")
				.append("packet_chunk/packet_block/packet_light=主线程区块/方块/光照收包处理；网络线程转交不计入这些项。\n")
				.append("client_tick_calls 区分一帧的补 tick 数量，client_tick_max_ms 为最长单次 tick。client_tick_cpu_ms 只统计有有效 CPU 时钟的最外层 tick，client_tick_cpu_calls 为该部分调用数。")
				.append("格式 9 新增 main_task=队列中单个主线程任务、packet_apply=已转交主线程的具体收包执行、swap_buffers=交换缓冲；main_task 与 packet_apply 可能嵌套，不能相加。\n")
				.append("spans.csv 的 work_class 仅记录慢任务/收包的实际类型，不保留实例或内容；通用包装类不等于最终业务来源。收包统计保留原来的连接检查，未执行的包不计入。\n")
				.append("spans.csv 的 cpu_ms 支持主线程最外层 client_tick、poll_before、poll_after、main_task、packet_apply、swap_buffers 和下述窗口/输入回调，其余留空；留空是不可用，不是零。CPU 时钟分辨率由 JVM/系统决定，短调用可为零或大于墙钟，不能用单个短调用判定等待。\n")
				.append("格式 10 新增 window_resize=窗口/帧缓冲尺寸回调、window_focus=焦点/鼠标进出窗口回调、window_move=窗口位置回调、mouse_input=鼠标输入处理、keyboard_input=键盘输入处理；这些项也记录 cpu_ms。\n")
				.append("回调可能嵌套在窗口事件、交换缓冲或主线程任务中，不能相加；输入也可能稍后由任务队列执行，应按 spans 区间交集核对。仅计原有 Java 处理方法，不替换 GLFW 回调、不改变输入顺序、不记录按键或鼠标内容。\n")
				.append("较长 tick/窗口事件调用的墙钟与 CPU 差值可提示未运行的时间，但仍需结合 GC、safepoint 和等待事件，不能直接归因为某个模组、驱动或线程竞争。窗口事件 CPU 不加入 client_tick_cpu_ms。\n")
				.append("details.csv 按 frame 从 0 与主 CSV 对齐，只包含主线程；各项 _ms 是同类嵌套去重后的墙钟耗时，_calls 是完成调用数，_max_ms 是最长单次调用，_failures 是异常退出数。\n")
				.append("不同类别可能嵌套（例如 chunk_update 包含 chunk_upload，后者包含 chunk_gl_upload；map_update 包含 map_upload），不得直接相加；details_valid=0 时忽略该帧细分归因。\n")
				.append("map_buffer 可包含 map_readback/map_pbo_write；地图内部计时仅包裹原有调用，保留上传预算的跳过与重试；统计显存与 GL 检查不会新增实际查询。\n")
				.append("长调用仅保留 >=1 ms 或异常退出的已完成调用；区间可能乱序、嵌套或跨帧，须排序并按交集分析。")
				.append("后台调用另列，不计入主线程帧耗时；后台并发/嵌套耗时之和不等于帧停顿。\n")
				.append("上述细分均为 CPU 侧墙钟时间，含 GC、驱动与调度等待，不是 GPU 执行时间；这些细分入口不加入 GPU 查询或额外同步。格式 11 的异步 GPU 时间戳单独见 render.csv。\n")
				.append("长调用保留=").append(written).append("；缓冲容量丢弃=").append(droppedSpans).append("。\n");
		for (int scope = 0; scope < COUNT; scope++) {
			report.append(NAMES[scope]).append(": hook=").append((availableMask & (1L << scope)) != 0 ? "available" : "unavailable")
					.append("；后台开始=").append(backgroundStarted[scope]).append("；完成=").append(backgroundFinished[scope])
					.append("；截止时未完成=").append(backgroundStarted[scope] - backgroundFinished[scope])
					.append("；累计_ms=").append(millis(backgroundNanos[scope])).append("；最长_ms=").append(millis(backgroundMaxima[scope]))
					.append("；异常=").append(backgroundFailures[scope]).append('\n');
		}
		report.append("unavailable 表示该计时点未挂载或未执行到，不应把零值解释成没有工作；截止时未完成的后台调用没有完整区间。\n");
		return report.toString();
	}

	private static double millis(long nanos) { return nanos / 1_000_000.0; }
}
