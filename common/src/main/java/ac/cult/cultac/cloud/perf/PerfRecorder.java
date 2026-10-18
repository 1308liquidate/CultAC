package ac.cult.cultac.cloud.perf;

import ac.cult.cultac.cloud.intake.v3.PerfAllocCalibration;
import ac.cult.cultac.cloud.intake.v3.PerfClassAlloc;
import ac.cult.cultac.cloud.intake.v3.PerfOutcome;
import ac.cult.cultac.cloud.intake.v3.PerfProfile;
import ac.cult.cultac.platform.api.PlatformServer;
import ac.cult.cultac.utils.anticheat.LogThrottle;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Records one JFR window of this process and reduces it to a zstd {@link PerfProfile}. {@link #record} blocks its caller,
 * which is never the server's tick thread; the tick thread's own state is only read through
 * {@link PlatformServer#recentTickNanos()}.
 */
public final class PerfRecorder {
    /** Allocated classes a profile names, by bytes. */
    static final int TOP_ALLOC_CLASSES = 50;
    /** Allocated classes tracked apart; past it new classes are counted as one. */
    static final int MAX_ALLOC_CLASSES = 4096;

    private static final String OTHER_CLASSES = "(other)";
    private static final String UNKNOWN = "(unknown)";
    private static final long READ_EVERY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long POLL_MILLIS = 100;
    /** Room left in the profile budget for everything but the stacks. */
    private static final int HEADER_SLACK = 4 * 1024;

    /** {@code PerfProfile.cpu_sampler} of a window sampled by {@code jdk.CPUTimeSample}. */
    static final String CPU_TIME = "cpu-time";
    /** {@code PerfProfile.cpu_sampler} of a window sampled by {@code jdk.ExecutionSample}. */
    static final String EXECUTION = "execution";

    private static final String CPU_TIME_EVENT = "jdk.CPUTimeSample";
    private static final String CPU_TIME_LOST_EVENT = "jdk.CPUTimeSamplesLost";
    private static final boolean LINUX = System.getProperty("os.name", "").startsWith("Linux");
    private static final ProcCpu PROC = new ProcCpu(Path.of("/proc"));

    /** Set once a cpu-time window sampled nothing; every later window of this JVM samples execution. */
    static volatile boolean cpuTimeFailed;

    private static final LogThrottle FAILURES = new LogThrottle();

    public sealed interface Result {
        /**
         * {@code reason}: the last part's, naming the node cap when the profile's stacks hit it and the cpu-time
         * sampler's shortfall, joined by {@code "; "}; else empty.
         */
        record Done(byte[] zstdProfile, String reason) implements Result {
            public Done(byte[] zstdProfile) {
                this(zstdProfile, "");
            }
        }

        record Skipped(PerfOutcome outcome, String reason) implements Result {}
    }

    private final PlatformServer platform;
    private final AgentInfo agent;
    private final Clock clock;
    private final Supplier<RecordingStream> streams;

    public PerfRecorder(PlatformServer platform, AgentInfo agent, Clock clock) {
        this(platform, agent, clock, PerfRecorder::newStream);
    }

    PerfRecorder(PlatformServer platform, AgentInfo agent, Clock clock, Supplier<RecordingStream> streams) {
        this.platform = platform;
        this.agent = agent;
        this.clock = clock;
        this.streams = streams;
    }

    private static RecordingStream newStream() {
        return new RecordingStream();
    }

    /**
     * Records for {@code settings.seconds()} or until {@code cancelled}: the profile, or why there is none. A cancelled
     * or interrupted recording ends within {@link #POLL_MILLIS} and is {@code FAILED}.
     */
    public Result record(PerfSettings settings, BooleanSupplier cancelled) {
        try {
            long[] ticks = platform.recentTickNanos();
            if (lagging(ticks, settings.skipMsptMicros())) {
                return new Result.Skipped(
                        PerfOutcome.PERF_OUTCOME_SKIPPED_LAG,
                        String.format(
                                Locale.ROOT,
                                "mean tick %.1f ms is over %.1f ms",
                                meanTickNanos(ticks) / 1e6,
                                settings.skipMsptMicros() / 1e3));
            }
            return run(settings, cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result.Skipped(PerfOutcome.PERF_OUTCOME_FAILED, "interrupted");
        } catch (RuntimeException | LinkageError | InternalError failure) {
            String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            FAILURES.warn("perf", "Cloud perf recording failed; the anticheat is unaffected: " + reason, null);
            return new Result.Skipped(PerfOutcome.PERF_OUTCOME_FAILED, reason);
        }
    }

    private Result run(PerfSettings settings, BooleanSupplier cancelled) throws InterruptedException {
        Window window = new Window(Thread.currentThread().threadId());
        Load load = new Load();
        long startMillis = clock.millis();
        long start = System.nanoTime();
        long processCpu = processCpuNanos();
        long ownCpu = ownCpuNanos();
        Map<Long, Long> ownAlloc = ownAllocatedBytes();
        Map<Long, ThreadBytes> threadAlloc = threadAllocatedBytes();
        ProcCpu.Snapshot procStart = procCpu();
        load.players(platform, start);
        long dispatchCpu;
        long[] exactAlloc;
        String sampler;
        try (RecordingStream stream = streams.get()) {
            stream.setReuse(true);
            stream.setOrdered(false);
            sampler = cpuSampler(System.getProperty("os.name"), eventNames());
            if (sampler.equals(CPU_TIME)) {
                stream.enable(CPU_TIME_EVENT).with("throttle", settings.executionPeriodMs() + "ms");
                stream.enable(CPU_TIME_LOST_EVENT);
                stream.onEvent(CPU_TIME_EVENT, event -> window.sample(event, Window.CPU_TIME));
                stream.onEvent(CPU_TIME_LOST_EVENT, window::lost);
            } else {
                stream.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(settings.executionPeriodMs()));
                stream.onEvent("jdk.ExecutionSample", event -> window.sample(event, Window.CPU));
            }
            stream.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(settings.nativePeriodMs()));
            stream.enable("jdk.ObjectAllocationSample").with("throttle", settings.allocationPerSecond() + "/s");
            stream.enable("jdk.GarbageCollection");
            stream.enable("jdk.GCHeapSummary");
            stream.onEvent("jdk.NativeMethodSample", event -> window.sample(event, Window.NATIVE));
            stream.onEvent("jdk.ObjectAllocationSample", event -> window.sample(event, Window.ALLOC));
            stream.onEvent("jdk.GarbageCollection", window::gc);
            stream.onEvent("jdk.GCHeapSummary", window::heap);
            stream.onError(window::failed);
            stream.startAsync();
            long end = start + TimeUnit.SECONDS.toNanos(settings.seconds());
            long nextRead = start + READ_EVERY_NANOS;
            while (true) {
                if (cancelled.getAsBoolean()) return cancelled();
                window.rethrow();
                long now = System.nanoTime();
                if (now - end >= 0) break;
                if (now - nextRead >= 0) {
                    load.read(platform, now);
                    nextRead += READ_EVERY_NANOS;
                }
                Thread.sleep(Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(end - now) + 1));
            }
            dispatchCpu = window.dispatchCpuNanos();
            exactAlloc = exactAllocated(
                    threadAlloc, threadAllocatedBytes(), window.recordingThread, window.dispatchThread());
            stream.stop();
        }
        window.rethrow();
        long wall = System.nanoTime() - start;
        ProcCpu.Snapshot procEnd = procCpu();
        load.read(platform, start + wall);
        Window.Calibration calibration = window.calibrate(exactAlloc);
        PerfProfile header = PerfProfile.newBuilder()
                .setPlatform(nonNull(agent.platform()))
                .setServerId(nonNull(agent.serverId()))
                .setCommit(nonNull(agent.commit()))
                .setVersion(nonNull(agent.version()))
                .setConfigHash(nonNull(agent.configHash()))
                .setMode(nonNull(agent.mode()))
                .setStartMillis(startMillis)
                .setWallNanos(wall)
                .setProcessCpuNanos(Math.max(0, processCpuNanos() - processCpu))
                .setCores(Runtime.getRuntime().availableProcessors())
                .setRecorderCpuNanos(Math.max(0, ownCpuNanos() - ownCpu) + dispatchCpu)
                .setOwnThreadAllocBytes(allocatedSince(ownAlloc))
                .setPlayerSeconds(load.playerSeconds)
                .setPlayersMin(load.playersMax < 0 ? 0 : load.playersMin)
                .setPlayersMax(Math.max(0, load.playersMax))
                .addAllTickMicrosP50P95Max(
                        Arrays.stream(tickMicros(load.ticks)).boxed().toList())
                .setGcCount(window.gcCount)
                .setGcPauseNanos(window.gcPauseNanos)
                .setHeapUsedAfterGcBytes(window.heapAfterGc)
                .setJvm(System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"))
                .addAllJvmFlags(jvmFlags())
                .addAllAllocClasses(window.topClasses(settings.wholeJvm()))
                .setCpuSampler(sampler)
                .setCpuSamplePeriodNanos(
                        window.samplingPeriodNanos > 0
                                ? window.samplingPeriodNanos
                                : TimeUnit.MILLISECONDS.toNanos(settings.executionPeriodMs()))
                .setCpuSamplesLost(window.lostSamples)
                .setProcessUserNanos(ProcCpu.userNanos(procStart, procEnd))
                .setProcessSysNanos(ProcCpu.sysNanos(procStart, procEnd))
                .addAllJvmThreadCpu(ProcCpu.threadCpu(procStart, procEnd))
                .setAllocCalibrated(calibration.calibrated())
                .addAllAllocCalibration(calibration.groups())
                .build();
        if (sampler.equals(CPU_TIME) && window.trie.cpuTotal() == 0) cpuTimeFailed = true;
        return withReason(
                profile(window.trie, header, settings, cancelled),
                samplerReason(sampler, window.trie.cpuTotal(), window.lostSamples));
    }

    /** {@code result} with {@code reason} joined to a {@code Done}'s by {@code "; "}; anything else as it is. */
    static Result withReason(Result result, String reason) {
        if (reason.isEmpty() || !(result instanceof Result.Done done)) return result;
        return new Result.Done(done.zstdProfile(), done.reason().isEmpty() ? reason : done.reason() + "; " + reason);
    }

    /** {@link #CPU_TIME} on Linux when JFR has {@code jdk.CPUTimeSample} and it has not failed here; else execution. */
    static String cpuSampler(String osName, Collection<String> eventNames) {
        return !cpuTimeFailed && osName != null && osName.startsWith("Linux") && eventNames.contains(CPU_TIME_EVENT)
                ? CPU_TIME
                : EXECUTION;
    }

    private static List<String> eventNames() {
        return FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .map(EventType::getName)
                .toList();
    }

    /** What a window's CPU samples lack, joined by {@code "; "}; empty for execution. */
    static String samplerReason(String sampler, long cpuSamples, long lostSamples) {
        if (!sampler.equals(CPU_TIME)) return "";
        List<String> reasons = new ArrayList<>();
        if (cpuSamples == 0) reasons.add("cpu-time sampler produced no samples");
        if (lostSamples * 10 > cpuSamples) {
            reasons.add(String.format(Locale.ROOT, "cpu-time lost %d of %d", lostSamples, cpuSamples));
        }
        return String.join("; ", reasons);
    }

    private static ProcCpu.Snapshot procCpu() {
        return LINUX ? PROC.read() : ProcCpu.NONE;
    }

    /** {@code trie}'s stacks with {@code header} as zstd within the profile budget; {@code FAILED} once cancelled. */
    static Result profile(StackTrie trie, PerfProfile header, PerfSettings settings, BooleanSupplier cancelled) {
        long budget = settings.profileBytes() - StackTrie.zstd(header.toByteArray()).length - HEADER_SLACK;
        while (true) {
            if (cancelled.getAsBoolean()) return cancelled();
            PerfProfile.Builder stacks = trie.encode(settings.wholeJvm(), budget, cancelled);
            if (cancelled.getAsBoolean()) return cancelled();
            byte[] profile = StackTrie.zstd(stacks.mergeFrom(header).build().toByteArray());
            if (cancelled.getAsBoolean()) return cancelled();
            if (profile.length <= settings.profileBytes()) {
                String reason = trie.capped() == 0
                        ? ""
                        : String.format(Locale.ROOT, "nodes=%d capped=%d", trie.nodes(), trie.capped());
                return new Result.Done(profile, reason);
            }
            if (budget <= 0) throw new IllegalStateException("profile over " + settings.profileBytes() + " bytes");
            budget -= profile.length - settings.profileBytes() + HEADER_SLACK;
        }
    }

    private static Result cancelled() {
        return new Result.Skipped(PerfOutcome.PERF_OUTCOME_FAILED, "cancelled");
    }

    /** Whether the mean of the non-zero recent ticks exceeds {@code skipMsptMicros}; false when none are known. */
    static boolean lagging(long[] tickNanos, int skipMsptMicros) {
        long mean = meanTickNanos(tickNanos);
        return mean >= 0 && mean > TimeUnit.MICROSECONDS.toNanos(skipMsptMicros);
    }

    /** The mean of the non-zero entries in nanoseconds; -1 when there are none. */
    static long meanTickNanos(long[] tickNanos) {
        if (tickNanos == null) return -1;
        long sum = 0;
        int count = 0;
        for (long tick : tickNanos) {
            if (tick > 0) {
                sum += tick;
                count++;
            }
        }
        return count == 0 ? -1 : sum / count;
    }

    /** p50, p95 and max of the non-zero ticks read, in microseconds; empty when there are none. */
    static int[] tickMicros(List<long[]> reads) {
        long[] ticks = reads.stream()
                .flatMapToLong(Arrays::stream)
                .filter(tick -> tick > 0)
                .sorted()
                .toArray();
        int n = ticks.length;
        if (n == 0) return new int[0];
        return new int[] {micros(ticks[n / 2]), micros(ticks[(n * 95 - 1) / 100]), micros(ticks[n - 1])};
    }

    private static int micros(long nanos) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMicros(nanos));
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    /** The JVM's -X sizing and -XX flags; system properties may carry secrets and are left out. */
    private static List<String> jvmFlags() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(flag -> flag.startsWith("-XX:") || flag.startsWith("-Xm") || flag.startsWith("-Xss"))
                .toList();
    }

    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os
                ? Math.max(0, os.getProcessCpuTime())
                : 0;
    }

    private static long ownCpuNanos() {
        return Math.max(0, ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime());
    }

    /**
     * Allocated bytes so far of each live {@code cult-*} thread but the calling recording thread, by thread id;
     * empty when the JVM cannot tell.
     */
    static Map<Long, Long> ownAllocatedBytes() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threads)
                || !threads.isThreadAllocatedMemorySupported()
                || !threads.isThreadAllocatedMemoryEnabled()) {
            return Map.of();
        }
        long recording = Thread.currentThread().threadId();
        Map<Long, Long> bytes = new HashMap<>();
        for (ThreadInfo info : threads.getThreadInfo(threads.getAllThreadIds(), 0)) {
            if (info == null
                    || info.getThreadId() == recording
                    || Attribution.threadGroup(info.getThreadName()) != Attribution.OWN) {
                continue;
            }
            long allocated = threads.getThreadAllocatedBytes(info.getThreadId());
            if (allocated >= 0) bytes.put(info.getThreadId(), allocated);
        }
        return bytes;
    }

    /** What {@code cult-*} threads allocated since {@code before}; a thread that ended in between is lost. */
    private static long allocatedSince(Map<Long, Long> before) {
        long total = 0;
        for (Map.Entry<Long, Long> now : ownAllocatedBytes().entrySet()) {
            total += Math.max(0, now.getValue() - before.getOrDefault(now.getKey(), 0L));
        }
        return total;
    }

    record ThreadBytes(String name, long bytes) {}

    /** Name and allocated bytes so far of every live thread, by thread id; null when the JVM cannot tell or fails. */
    static Map<Long, ThreadBytes> threadAllocatedBytes() {
        return ManagementFactory.getThreadMXBean() instanceof ThreadMXBean threads
                ? threadAllocatedBytes(threads)
                : null;
    }

    static Map<Long, ThreadBytes> threadAllocatedBytes(ThreadMXBean threads) {
        try {
            if (!threads.isThreadAllocatedMemorySupported() || !threads.isThreadAllocatedMemoryEnabled()) return null;
            long[] ids = threads.getAllThreadIds();
            long[] allocated = threads.getThreadAllocatedBytes(ids);
            ThreadInfo[] infos = threads.getThreadInfo(ids, 0);
            Map<Long, ThreadBytes> bytes = new HashMap<>();
            for (int i = 0; i < ids.length; i++) {
                if (infos[i] != null && allocated[i] >= 0) {
                    bytes.put(ids[i], new ThreadBytes(infos[i].getThreadName(), allocated[i]));
                }
            }
            return bytes;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Bytes allocated between {@code start} and {@code end} by thread group, of the threads alive at {@code end}: a
     * thread absent at {@code start} counts from 0, one that ended in between is lost. Null when either is unknown.
     */
    static long[] exactAllocated(
            Map<Long, ThreadBytes> start, Map<Long, ThreadBytes> end, long recordingThread, long dispatchThread) {
        if (start == null || end == null) return null;
        long[] bytes = new long[Attribution.GROUPS.size()];
        for (Map.Entry<Long, ThreadBytes> thread : end.entrySet()) {
            ThreadBytes before = start.get(thread.getKey());
            int group =
                    Attribution.threadGroup(thread.getValue().name(), thread.getKey(), recordingThread, dispatchThread);
            bytes[group] += Math.max(0, thread.getValue().bytes() - (before == null ? 0 : before.bytes()));
        }
        return bytes;
    }

    /** Players and tick times read every {@link #READ_EVERY_NANOS} on the recording thread. */
    private static final class Load {
        final List<long[]> ticks = new ArrayList<>();
        double playerSeconds;
        int playersMin = Integer.MAX_VALUE;
        int playersMax = -1;
        private int lastPlayers = -1;
        private long lastNanos;

        void read(PlatformServer platform, long now) {
            long[] recent = platform.recentTickNanos();
            if (recent != null) ticks.add(recent);
            players(platform, now);
        }

        void players(PlatformServer platform, long now) {
            if (lastPlayers >= 0) playerSeconds += lastPlayers * ((now - lastNanos) / 1e9);
            int players = platform.onlinePlayers();
            lastPlayers = players;
            lastNanos = now;
            if (players < 0) return;
            playersMin = Math.min(playersMin, players);
            playersMax = Math.max(playersMax, players);
        }
    }

    /** What the stream's dispatch thread aggregates; read by the recording thread once the stream has stopped. */
    static final class Window {
        static final int CPU = 0;
        static final int NATIVE = 1;
        static final int ALLOC = 2;
        static final int CPU_TIME = 3;

        /** {@code groups}: one per thread group with sampled or exact bytes. */
        record Calibration(boolean calibrated, List<PerfAllocCalibration> groups) {}

        final StackTrie trie = new StackTrie();
        /**
         * Allocated bytes by class, at {@code 2 * group}: on stacks with a CultAC frame, at {@code 2 * group + 1}: on
         * the others.
         */
        private final Map<String, long[]> classes = new HashMap<>();
        /** Sampled allocated bytes by thread group. */
        private final long[] sampledAlloc = new long[Attribution.GROUPS.size()];
        /** Threads whose first allocation sample, weighted since before the window, has been dropped. */
        private final Set<Long> allocThreads = new HashSet<>();

        int gcCount;
        long gcPauseNanos;
        long heapAfterGc;
        long lostSamples;
        /** The longest {@code samplingPeriod} of the cpu-time samples; JFR samples at the shortest any recording asks. */
        long samplingPeriodNanos;

        final long recordingThread;
        private final Frames frames = new Frames();
        private long dispatchThread = -1;
        private Throwable failure;

        Window(long recordingThread) {
            this.recordingThread = recordingThread;
        }

        synchronized void sample(RecordedEvent event, int kind) {
            dispatchThread = Thread.currentThread().threadId();
            RecordedStackTrace trace = event.getStackTrace();
            Frames stack = frames.reset(trace == null ? List.of() : trace.getFrames());
            RecordedThread thread =
                    kind == CPU || kind == NATIVE ? event.getThread("sampledThread") : event.getThread();
            int group = thread == null
                    ? Attribution.OTHER
                    : Attribution.threadGroup(
                            thread.getJavaName(), thread.getJavaThreadId(), recordingThread, dispatchThread);
            switch (kind) {
                case CPU -> trie.add(stack, group, 1, 0, 0);
                case CPU_TIME -> {
                    trie.add(stack, group, 1, 0, 0);
                    samplingPeriodNanos = Math.max(
                            samplingPeriodNanos,
                            event.getDuration("samplingPeriod").toNanos());
                }
                case NATIVE -> trie.add(stack, group, 0, 1, 0);
                default -> {
                    RecordedClass allocated = event.getClass("objectClass");
                    alloc(
                            stack,
                            group,
                            thread == null ? -1 : thread.getJavaThreadId(),
                            allocated == null ? null : allocated.getName(),
                            Math.max(0, event.getLong("weight")));
                }
            }
        }

        /** One allocation sample; the first of each thread, {@code threadId} >= 0, is dropped. */
        synchronized void alloc(StackTrie.StackTraceView stack, int group, long threadId, String type, long bytes) {
            if (threadId >= 0 && allocThreads.add(threadId)) return;
            trie.add(stack, group, 0, 0, bytes);
            sampledAlloc[group] += bytes;
            if (type == null) type = OTHER_CLASSES;
            if (!classes.containsKey(type) && classes.size() >= MAX_ALLOC_CLASSES) type = OTHER_CLASSES;
            int side = group != Attribution.PERF && Attribution.hasOwnFrame(stack) ? 0 : 1;
            classes.computeIfAbsent(type, ignored -> new long[2 * Attribution.GROUPS.size()])[2 * group + side] +=
                    bytes;
        }

        /**
         * Scales the stacks and classes of each group with sampled bytes to its {@code exact} bytes, where those are
         * known: non-null and above 0. The trie's total becomes the exact bytes where known, else the sampled.
         */
        synchronized Calibration calibrate(long[] exact) {
            boolean calibrated = exact != null;
            long total = 0;
            List<PerfAllocCalibration> groups = new ArrayList<>();
            for (int group = 0; group < sampledAlloc.length; group++) {
                long sampled = sampledAlloc[group];
                long known = exact == null ? 0 : exact[group];
                if (sampled > 0 && known > 0) {
                    double factor = (double) known / sampled;
                    trie.scaleAlloc(group, factor);
                    for (long[] bytes : classes.values()) {
                        bytes[2 * group] = Math.round(bytes[2 * group] * factor);
                        bytes[2 * group + 1] = Math.round(bytes[2 * group + 1] * factor);
                    }
                } else if (sampled > 0) {
                    calibrated = false;
                }
                total += known > 0 ? known : sampled;
                if (sampled > 0 || known > 0) {
                    groups.add(PerfAllocCalibration.newBuilder()
                            .setThreadGroup(group)
                            .setSampledBytes(sampled)
                            .setExactBytes(known)
                            .build());
                }
            }
            trie.allocTotal(total);
            return new Calibration(calibrated, groups);
        }

        synchronized void lost(RecordedEvent event) {
            lostSamples += Math.max(0, event.getLong("lostSamples"));
        }

        synchronized void gc(RecordedEvent event) {
            gcCount++;
            gcPauseNanos += event.getDuration("sumOfPauses").toNanos();
        }

        synchronized void heap(RecordedEvent event) {
            if ("After GC".equals(event.getString("when"))) heapAfterGc = event.getLong("heapUsed");
        }

        synchronized void failed(Throwable failure) {
            if (this.failure == null) this.failure = failure;
        }

        synchronized void rethrow() {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new IllegalStateException(failure.toString(), failure);
        }

        /** The CPU the stream's dispatch thread has used so far; 0 before its first event. */
        synchronized long dispatchCpuNanos() {
            if (dispatchThread < 0) return 0;
            return Math.max(0, ManagementFactory.getThreadMXBean().getThreadCpuTime(dispatchThread));
        }

        /** The stream's dispatch thread; -1 before its first event. */
        synchronized long dispatchThread() {
            return dispatchThread;
        }

        /** The classes that allocated most; without {@code wholeJvm} only bytes on stacks with a CultAC frame. */
        synchronized List<PerfClassAlloc> topClasses(boolean wholeJvm) {
            List<PerfClassAlloc> all = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : classes.entrySet()) {
                long own = 0;
                long other = 0;
                for (int i = 0; i < entry.getValue().length; i += 2) {
                    own += entry.getValue()[i];
                    other += entry.getValue()[i + 1];
                }
                if (own > 0) all.add(classAlloc(entry.getKey(), own, true));
                if (other > 0 && wholeJvm) all.add(classAlloc(entry.getKey(), other, false));
            }
            all.sort(Comparator.comparingLong(PerfClassAlloc::getBytes).reversed());
            return all.size() > TOP_ALLOC_CLASSES ? all.subList(0, TOP_ALLOC_CLASSES) : all;
        }

        private static PerfClassAlloc classAlloc(String type, long bytes, boolean own) {
            return PerfClassAlloc.newBuilder()
                    .setClassName(type)
                    .setBytes(bytes)
                    .setOwnStack(own)
                    .build();
        }
    }

    /** One JFR stack trace's frames at a time, leaf first, read through JFR's own name strings. */
    private static final class Frames implements StackTrie.StackTraceView {
        private List<RecordedFrame> frames = List.of();

        Frames reset(List<RecordedFrame> frames) {
            this.frames = frames;
            return this;
        }

        @Override
        public int size() {
            return frames.size();
        }

        @Override
        public String type(int fromLeaf) {
            RecordedMethod method = frames.get(fromLeaf).getMethod();
            return method == null ? UNKNOWN : method.getType().getName();
        }

        @Override
        public String method(int fromLeaf) {
            RecordedMethod method = frames.get(fromLeaf).getMethod();
            return method == null ? UNKNOWN : method.getName();
        }
    }
}
