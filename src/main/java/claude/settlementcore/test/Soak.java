package claude.settlementcore.test;

import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.SettlementBlockEntity;
import claude.settlementcore.settlement.SettlementSpawns;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.ToIntFunction;

/**
 * The soak sampler: what a long run of a settlement does to the server over time, so a slow leak shows in a test instead of in the user's
 * world after an evening (2026-11-13, "close every gap between the GameTests and survival"). A soak test calls {@link #sample} every N ticks
 * ({@link #run} schedules it); each sample measures
 * <ul>
 * <li>the time a tick takes: the wall clock over the ticks since the last sample ({@code wall}, which the verdict reads: the GameTest server
 * never sleeps, so it is the work a tick costs) and the server's own average of its last 100 ticks ({@code mspt});</li>
 * <li>the used heap after one hint to collect (once a sample, never in a hot path);</li>
 * <li>every loaded entity by type, the block entities in loaded chunks and the ticking ones, the loaded chunks;</li>
 * <li>the engine's own static registries and caches ({@link #ENGINE}, read by name: a name that is gone is reported by {@link #missing}, and
 * the engine's quick tests fail on it), its saved data per level, and the loaded settlements' parts summed (roll, reserve, crews, works,
 * hauls, ledger, spoils, buildings);</li>
 * <li>whatever a race asks to be watched ({@link #watch}).</li>
 * </ul>
 * Each sample is logged as one line, {@code SOAK <name> tick=<t> wall=<ms> mspt=<ms> heap=<MB> <key>=<n> ...}, and tools/soak.py reads them
 * back. The verdict ({@link #judge}) fails a run when a count rises without levelling off (every step of the last {@link Options#window}
 * samples up or level, at least half of them up, by more than the margin) or the tick time more than doubles from its early baseline.
 *
 * <p><b>A race's own soak</b> (docs/testing-beyond-gametests.md): in its own GameTest, gated on {@link #ticks}, set up a settlement of its
 * own, call {@link #watch} for any registry of its own, then {@link #run}. Soak names no race.
 */
public final class Soak {

    private static final Logger LOG = LogUtils.getLogger();

    /** Ticks a soak test runs: unset (or 0) and soak tests pass at once; {@code true} or anything not a number is {@link #DEFAULT_TICKS}. */
    public static final String PROPERTY = "settlementcore.soak";
    /** An hour of game time. */
    public static final int DEFAULT_TICKS = 72_000;
    /** The longest soak a test's timeout allows ({@code timeoutTicks} must be a constant): six hours of game time. */
    public static final int MAX_TICKS = 432_000;
    /** A minute of game time between samples. */
    public static final int DEFAULT_EVERY = 1_200;

    /**
     * The engine's static registries and caches, read by name: {class, field}, and a third "sum" where the field is a map of collections whose
     * entries are what grows (the settlements by level). A race's are added with {@link #watch}.
     */
    static final String[][] ENGINE = {
            {"claude.settlementcore.room.RoomRelight", "CACHE"},
            {"claude.settlementcore.room.RoomRelight", "FAILED"},
            {"claude.settlementcore.room.RoomTemplates", "CACHE"},
            {"claude.settlementcore.room.RoomTemplates", "HAND"},
            {"claude.settlementcore.room.RoomTemplates", "FAILED"},
            {"claude.settlementcore.room.RoomTemplates", "MOD_READS"},
            {"claude.settlementcore.room.RoomTemplates", "PRINTS"},
            {"claude.settlementcore.room.Rooms", "BY_ID"},
            {"claude.settlementcore.room.Rooms", "PLANS"},
            {"claude.settlementcore.room.Rooms", "OTHERS"},
            {"claude.settlementcore.room.Rooms", "RAISED"},
            {"claude.settlementcore.room.Rooms", "FAMILIES"},
            {"claude.settlementcore.room.Rooms", "MADE"},
            {"claude.settlementcore.room.Rooms", "NAMESPACE"},
            {"claude.settlementcore.room.WallStyle", "STYLES"},
            {"claude.settlementcore.settlement.Currencies", "ALL"},
            {"claude.settlementcore.settlement.Currencies", "ALSO"},
            {"claude.settlementcore.settlement.Settlements", "FINDERS"},
            {"claude.settlementcore.settlement.SettlementSpawns", "HEARTS", "sum"},
            {"claude.settlementcore.unit.GeoEvents", "WARNED"},
            {"claude.settlementcore.food.Fare", "RICH"},
            {"claude.settlementcore.work.PensWork", "NOT_LIVESTOCK"},
            {"claude.settlementcore.work.RoomWorks", "BY_FUNCTION"},
    };

    /** The engine's saved data per level, read by name: {class, static accessor or type field, collection field}. */
    static final String[][] ENGINE_DATA = {
            {"claude.settlementcore.settlement.Founding", "of", "jobs"},
            {"claude.settlementcore.settlement.Founding", "of", "halls"},
            {"claude.settlementcore.work.Works$OwedLoads", "TYPE", "loads"},
    };

    private static final Map<String, ToIntFunction<MinecraftServer>> WATCHED = new LinkedHashMap<>();
    private static final Field LEVEL_TICKERS = field(Level.class, "blockEntityTickers");
    private static final Field MAP_VISIBLE = field(ChunkMap.class, "visibleChunkMap");

    /** How the verdict judges: see {@link #judge}. */
    public record Options(int window, int warmup, int countMargin, double relMargin, int heapMarginMb, double msptFactor, double msptFloorMs) {
        /** Ten samples a window, the first two a warm-up, +8 (and 5%) for a count, +64 MB for the heap, twice the tick time and over 2 ms. */
        public static final Options DEFAULT = new Options(10, 2, 8, 0.05, 64, 2.0, 2.0);
    }

    /** One sample: the tick it was taken on, the tick time over the interval and the server's own, the heap after a collection, the counts. */
    public record Sample(long tick, double wallMs, double msptMs, long heapMb, Map<String, Integer> counts) {
    }

    private final String name;
    private final MinecraftServer server;
    private final Options options;
    private final List<Sample> samples = new ArrayList<>();
    private long lastNanos;
    private long lastTick;

    private Soak(String name, MinecraftServer server, Options options) {
        this.name = name;
        this.server = server;
        this.options = options;
    }

    /** A soak called {@code name} (the SOAK lines' second word) of {@code h}'s server. */
    public static Soak start(GameTestHelper h, String name) {
        return start(h, name, Options.DEFAULT);
    }

    public static Soak start(GameTestHelper h, String name, Options options) {
        if (!name.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("a soak's name is lower-case letters, digits, _ . - only: " + name);
        }
        Soak s = new Soak(name, h.getLevel().getServer(), options);
        s.lastNanos = System.nanoTime();
        s.lastTick = s.server.getTickCount();                     // the server's ticks, as each sample counts them
        return s;
    }

    /** The ticks a soak runs this time ({@link #PROPERTY}), at most {@link #MAX_TICKS} less a margin; 0: soak tests pass at once. */
    public static long ticks() {
        String v = System.getProperty(PROPERTY, "").trim();
        if (v.isEmpty() || v.equals("0") || v.equalsIgnoreCase("false")) {
            return 0;
        }
        long n;
        try {
            n = Long.parseLong(v);
        } catch (NumberFormatException e) {
            n = DEFAULT_TICKS;
        }
        return Math.max(0, Math.min(n, MAX_TICKS - 400));
    }

    /**
     * Watches a count of a race's own (a registry, a cache, a list in its saved data) in every sample under {@code race/<name>}; once per
     * name, a second call replaces the first. Called from a race's soak test before {@link #run}.
     */
    public static synchronized void watch(String name, ToIntFunction<MinecraftServer> size) {
        WATCHED.put("race/" + name, size);
    }

    /**
     * Runs the soak in {@code h}: a sample every {@code every} ticks for {@code total} ticks, {@code each} called with the sample's number
     * (1, 2 ...) just before it (top up food, send someone home: what keeps the settlement working), then the verdict, which fails the test
     * with its problems or passes it. Each step's exception fails the test, not the run.
     */
    public void run(GameTestHelper h, long total, int every, IntConsumer each) {
        long start = h.getTick();
        int n = (int) (total / every);
        for (int i = 1; i <= n; i++) {
            int number = i;
            h.runAtTickTime(start + (long) i * every, () -> {
                each.accept(number);
                sample();
            });
        }
        h.runAtTickTime(start + (long) n * every + 1, () -> {
            List<String> problems = problems();
            List<String> gone = missing();
            String verdict = problems.isEmpty() && gone.isEmpty() ? "pass" : "fail";
            LOG.info("SOAK {} VERDICT {}: {} samples over {} ticks{}{}", name, verdict, samples.size(), total,
                    problems.isEmpty() ? "" : "; " + String.join("; ", problems), gone.isEmpty() ? "" : "; gone from the engine: " + gone);
            h.assertTrue(gone.isEmpty(), "the soak watches engine fields that are gone (rename them in Soak.ENGINE): " + gone);
            int need = options.warmup() + 2 * options.window();
            h.assertTrue(samples.size() >= need, "the soak '" + name + "' took " + samples.size() + " samples, fewer than the " + need
                    + " its verdict needs: run it longer (-Psoak=" + (long) need * every + " or more)");
            h.assertTrue(problems.isEmpty(), "the soak '" + name + "' found " + problems.size() + " problem(s): " + String.join("; ", problems));
            h.succeed();
        });
    }

    /** Takes a sample now, logs its SOAK line and keeps it. */
    public Sample sample() {
        long tick = server.getTickCount();
        long now = System.nanoTime();
        double wall = tick > lastTick ? (now - lastNanos) / 1e6 / (tick - lastTick) : 0;
        double mspt = server.getAverageTickTimeNanos() / 1e6;
        Map<String, Integer> counts = counts(server);
        Runtime rt = Runtime.getRuntime();
        System.gc();                                              // a hint, once a sample; the heap read after it
        long heap = (rt.totalMemory() - rt.freeMemory()) >> 20;
        Sample s = new Sample(tick, wall, mspt, heap, counts);
        samples.add(s);
        StringBuilder b = new StringBuilder();
        counts.forEach((k, v) -> b.append(' ').append(k).append('=').append(v));
        LOG.info("SOAK {} tick={} wall={} mspt={} heap={}{}", name, tick, String.format(Locale.ROOT, "%.3f", wall),
                String.format(Locale.ROOT, "%.3f", mspt), heap, b);
        lastNanos = System.nanoTime();                            // the collection above is not the next interval's
        lastTick = server.getTickCount();
        return s;
    }

    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    /** What this run's samples say is wrong ({@link #judge}). */
    public List<String> problems() {
        return judge(samples, options);
    }

    // ------------------------------------------------------------------ the verdict

    /**
     * The problems in {@code samples}: none until there are enough to judge ({@code warmup} + two windows). The samples of the warm-up (class
     * loading, the first spawns) count for neither.
     * <ul>
     * <li>A count (any key, and the heap) climbs without levelling off, either way:
     * <ul>
     * <li>fast: over the last {@code window} samples every step is up or level, at least half of them up, and the rise is more than
     * {@code countMargin} (the heap: {@code heapMarginMb}) and more than {@code relMargin} of where it began;</li>
     * <li>slow: the run after the warm-up split in thirds, each third's lowest is at or above the highest of the third before, and the last
     * third's lowest is more than the margin above the first third's highest (a leak of one every other sample, too slow for a window).</li>
     * </ul>
     * A count that climbs to a cap and stays there passes both; one that rises and falls passes both.</li>
     * <li>The tick time doubles: the median wall time of the last window is more than {@code msptFactor} times the median of the first window
     * after the warm-up, and more than {@code msptFloorMs} (a tick of a fraction of a millisecond is noise).</li>
     * </ul>
     */
    public static List<String> judge(List<Sample> samples, Options o) {
        List<String> out = new ArrayList<>();
        int n = samples.size();
        if (n < o.warmup() + 2 * o.window()) {
            return out;
        }
        List<Sample> run = samples.subList(o.warmup(), n);
        java.util.Set<String> keys = new java.util.TreeSet<>();
        run.forEach(s -> keys.addAll(s.counts().keySet()));
        for (String k : keys) {
            String why = climbs(run.stream().mapToLong(s -> s.counts().getOrDefault(k, 0)).toArray(), o.window(), o.countMargin(), o.relMargin());
            if (why != null) {
                out.add(k + " keeps rising: " + why);
            }
        }
        String heap = climbs(run.stream().mapToLong(Sample::heapMb).toArray(), o.window(), o.heapMarginMb(), o.relMargin());
        if (heap != null) {
            out.add("the heap (MB, after a collection) keeps rising: " + heap);
        }
        double early = median(run.subList(0, o.window()).stream().mapToDouble(Sample::wallMs).toArray());
        double late = median(samples.subList(n - o.window(), n).stream().mapToDouble(Sample::wallMs).toArray());
        if (late > o.msptFloorMs() && late > o.msptFactor() * early) {
            out.add(String.format(Locale.ROOT, "a tick takes %.2f ms now against %.2f ms early on (the medians of %d samples): more than %.1f times",
                    late, early, o.window(), o.msptFactor()));
        }
        return out;
    }

    /** Why the series {@code v} (the warm-up left out) climbs without levelling off, fast or slow ({@link #judge}), or null. */
    static String climbs(long[] v, int window, int margin, double rel) {
        String fast = rising(java.util.Arrays.copyOfRange(v, Math.max(0, v.length - window), v.length), margin, rel);
        if (fast != null) {
            return fast;
        }
        int third = v.length / 3;
        if (third < 2) {
            return null;
        }
        long[] lo = new long[3];
        long[] hi = new long[3];
        for (int t = 0; t < 3; t++) {
            int from = t * third;
            int to = t == 2 ? v.length : (t + 1) * third;
            lo[t] = Long.MAX_VALUE;
            hi[t] = Long.MIN_VALUE;
            for (int i = from; i < to; i++) {
                lo[t] = Math.min(lo[t], v[i]);
                hi[t] = Math.max(hi[t], v[i]);
            }
        }
        if (lo[1] >= hi[0] && lo[2] >= hi[1] && lo[2] - hi[0] > margin) {
            return "slowly: its thirds of the run span " + lo[0] + "-" + hi[0] + ", " + lo[1] + "-" + hi[1] + " and " + lo[2] + "-" + hi[2]
                    + ", each above the last";
        }
        return null;
    }

    /** Why the window {@code v} rises without levelling off, or null. */
    static String rising(long[] v, int margin, double rel) {
        int up = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] < v[i - 1]) {
                return null;
            }
            if (v[i] > v[i - 1]) {
                up++;
            }
        }
        long rise = v[v.length - 1] - v[0];
        if (up * 2 < v.length - 1 || rise <= margin || rise <= rel * Math.max(1, v[0])) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (long x : v) {
            b.append(b.isEmpty() ? "" : " ").append(x);
        }
        return "up " + rise + " over the last " + v.length + " samples (" + b + ")";
    }

    static double median(double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        return s.length == 0 ? 0 : s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    // ------------------------------------------------------------------ the counts

    /** Every count of a sample, by key, sorted. */
    public static Map<String, Integer> counts(MinecraftServer server) {
        Map<String, Integer> out = new TreeMap<>();
        int entities = 0, blockEntities = 0, ticking = 0, chunks = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                entities++;
                out.merge("entity/" + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), 1, Integer::sum);
            }
            chunks += level.getChunkSource().getLoadedChunksCount();
            for (LevelChunk c : fullChunks(level)) {
                blockEntities += c.getBlockEntities().size();
            }
            ticking += size(get(LEVEL_TICKERS, level));
            settlements(level, out);
            engineData(level, out);
        }
        out.put("entities", entities);
        out.put("be/all", blockEntities);
        out.put("be/ticking", ticking);
        out.put("chunks", chunks);
        for (String[] f : ENGINE) {
            Object v = staticField(f[0], f[1]);
            int n = f.length > 2 && f[2].equals("sum") ? summed(v) : size(v);
            if (n >= 0) {
                out.put("engine/" + f[0].substring(f[0].lastIndexOf('.') + 1) + "." + f[1], n);
            }
        }
        synchronized (Soak.class) {
            WATCHED.forEach((k, size) -> {
                try {
                    out.put(k, size.applyAsInt(server));
                } catch (RuntimeException e) {
                    LOG.warn("Soak: the watch {} threw {}", k, e.toString());
                    out.put(k, -1);
                }
            });
        }
        return out;
    }

    /** The loaded settlements of {@code level}, their parts summed, under {@code settlement/...}. */
    private static void settlements(ServerLevel level, Map<String, Integer> out) {
        for (Settlement s : SettlementSpawns.loaded(level)) {
            out.merge("settlement/loaded", 1, Integer::sum);
            out.merge("settlement/people", s.peopleCount(), Integer::sum);
            out.merge("settlement/reserve", s.reserve(), Integer::sum);
            out.merge("settlement/buildings", s.buildings().size(), Integer::sum);
            out.merge("settlement/ledger_stores", s.ledgerStores().size(), Integer::sum);
            out.merge("settlement/spoils_stacks", s.spoils().size(), Integer::sum);
            out.merge("settlement/hauls", s.hauls(level.getGameTime()).size(), Integer::sum);
            var works = s.works();
            out.merge("settlement/crews", works.crewCount(), Integer::sum);
            out.merge("settlement/rooms_worked", works.rooms().size(), Integer::sum);
            out.merge("settlement/digs", works.digs().size(), Integer::sum);
            out.merge("settlement/sites", works.sites().size(), Integer::sum);
            out.merge("settlement/owed_lines", works.owedLines(), Integer::sum);
            if (s instanceof SettlementBlockEntity sbe) {
                int dangling = 0;
                for (UUID id : sbe.roll().ids()) {
                    Entity e = level.getEntity(id);
                    if (e == null || !e.isAlive()) {
                        dangling++;
                    }
                }
                // on the roll but not up and alive here: a unit in an unloaded chunk, or one whose death the roll missed
                out.merge("settlement/roll_not_loaded", dangling, Integer::sum);
            }
        }
        out.putIfAbsent("settlement/loaded", 0);
    }

    /** The engine's saved data in {@code level} ({@link #ENGINE_DATA}), under {@code data/...}. */
    private static void engineData(ServerLevel level, Map<String, Integer> out) {
        for (String[] d : ENGINE_DATA) {
            Object data = savedData(level, d[0], d[1]);
            int n = data == null ? 0 : size(get(field(data.getClass(), d[2]), data));
            if (n >= 0) {
                out.merge("data/" + d[0].substring(d[0].lastIndexOf('.') + 1) + "." + d[2], n, Integer::sum);
            }
        }
    }

    /** The loaded chunks of {@code level} that are whole (their block entities in place). */
    private static List<LevelChunk> fullChunks(ServerLevel level) {
        List<LevelChunk> out = new ArrayList<>();
        Object map = get(MAP_VISIBLE, level.getChunkSource().chunkMap);
        if (map instanceof Map<?, ?> m) {
            for (Object holder : m.values()) {
                if (holder instanceof ChunkHolder ch) {
                    LevelChunk c = ch.getChunkToSend();
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ reading the engine by name

    /** The engine's watched fields (and saved data) that are not there any more: a rename to follow in {@link #ENGINE}. */
    public static List<String> missing() {
        List<String> out = new ArrayList<>();
        for (String[] f : ENGINE) {
            try {
                Field x = Class.forName(f[0]).getDeclaredField(f[1]);
                if (!Modifier.isStatic(x.getModifiers())) {
                    out.add(f[0] + "." + f[1] + " (not static)");
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                out.add(f[0] + "." + f[1]);
            }
        }
        for (String[] d : ENGINE_DATA) {
            try {
                Class<?> c = Class.forName(d[0]);
                c.getDeclaredField(d[2]);
                if (d[1].equals("of")) {
                    c.getDeclaredMethod("of", ServerLevel.class);
                } else {
                    c.getDeclaredField(d[1]);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                out.add(d[0] + " " + d[1] + "/" + d[2]);
            }
        }
        if (LEVEL_TICKERS == null) {
            out.add("Level.blockEntityTickers");
        }
        if (MAP_VISIBLE == null) {
            out.add("ChunkMap.visibleChunkMap");
        }
        return out;
    }

    private static Object savedData(ServerLevel level, String cls, String access) {
        try {
            Class<?> c = Class.forName(cls);
            if (access.equals("of")) {
                var m = c.getDeclaredMethod("of", ServerLevel.class);
                m.setAccessible(true);
                return m.invoke(null, level);
            }
            Field t = c.getDeclaredField(access);
            t.setAccessible(true);
            return level.getDataStorage().get((net.minecraft.world.level.saveddata.SavedDataType<?>) t.get(null));
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            return null;
        }
    }

    private static Object staticField(String cls, String name) {
        try {
            Field f = Class.forName(cls).getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** The size of a map or a collection, -1 for anything else. */
    static int size(Object o) {
        if (o instanceof Map<?, ?> m) {
            return m.size();
        }
        if (o instanceof Collection<?> c) {
            return c.size();
        }
        return -1;
    }

    /** The summed sizes of a map's collections (the settlements of every level), -1 for anything else. */
    static int summed(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return -1;
        }
        int n = 0;
        for (Object v : m.values()) {
            n += Math.max(0, size(v));
        }
        return n;
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // up to the superclass
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Object get(Field f, Object on) {
        try {
            return f == null ? null : f.get(on);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
