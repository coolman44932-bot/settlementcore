package claude.settlementcore.test;

import claude.settlementcore.Core;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchListener;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.gametest.framework.GameTestTimeoutException;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.SimulationChunkTracker;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The GameTest server's watchdog: fails a test that never starts, and says why.
 *
 * <p>In 1.21.1 a GameTest does not tick, so it cannot time out either, until every chunk of its structure is
 * entity-ticking (the {@code chunksLoaded} gate in {@link GameTestInfo#tick}). If one chunk never gets there, the
 * test never starts and its batch, and so the whole run, never ends: on 2026-09-25 a -Pcompat run sat 28 minutes
 * in the 'warpstone' batch with nothing logged but the progress bar. Here a test still not started
 * {@value #STUCK_TICKS} ticks (and at least {@value #STUCK_MS} ms: this server ticks as fast as it can, while the
 * chunk workers go at their own pace) after it joined the ticker at its batch's start is failed, with a report
 * of each of its structure's chunks: tickets, levels, the chunk holder's futures, the entity sections, the
 * neighbours an entity-ticking chunk waits for, the states it went through while the test waited, and who last
 * un-forced it.
 *
 * <p>Who un-forced a chunk: the forced-chunk set is swapped for one that notes, at each un-force, the tick, the
 * batch and the caller; and letting go of a chunk under a test that is not done is logged at once, since a test
 * whose chunk loses its ticket before it has ticked never starts. Every batch's end is also where tests can let
 * go of what they held ({@link #afterEachBatch}): the runner un-forces every forced chunk then.
 *
 * <p>{@code ./gradlew runGameTestServer -Pwatchdogselftest=<batch>} checks the watchdog itself: it un-forces a
 * chunk of that batch's first test as the batch starts, and that test should then fail with the report.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class TestWatchdog {

    /** Ticks a test may wait to start after its batch began; a test's own setup ticks come on top. */
    static final int STUCK_TICKS = 1200;
    /** ... and at least this long on the clock. */
    static final long STUCK_MS = 30_000;

    private static final Logger LOG = LogUtils.getLogger();
    private static final String SELF_TEST = System.getProperty("settlementcore.watchdog.selftest", "").trim();

    private static final List<String> MISSING = new ArrayList<>();
    private static final Field TICKER_TESTS = field(GameTestTicker.class, "testInfos");
    private static final Field TICKER_RUNNER = field(GameTestTicker.class, "runner");
    private static final Field INFO_CHUNKS_LOADED = field(GameTestInfo.class, "chunksLoaded");
    private static final Field INFO_PLACED = field(GameTestInfo.class, "placedStructure");
    private static final Field LEVEL_ENTITIES = field(ServerLevel.class, "entityManager");
    private static final Field ENTITIES_VISIBILITY = field(PersistentEntitySectionManager.class, "chunkVisibility");
    private static final Field ENTITIES_LOADS = field(PersistentEntitySectionManager.class, "chunkLoadStatuses");
    private static final Field MAP_UPDATING = field(ChunkMap.class, "updatingChunkMap");
    private static final Field MAP_PENDING_UNLOADS = field(ChunkMap.class, "pendingUnloads");
    private static final Field MAP_TO_DROP = field(ChunkMap.class, "toDrop");
    private static final Field CACHE_TICKETS = field(ServerChunkCache.class, "ticketStorage");
    private static final Field DISTANCE_SIMULATION = field(DistanceManager.class, "simulationChunkTracker");
    private static final Field TICKETS_SIMULATION_LISTENER = field(TicketStorage.class, "simulationChunkUpdatedListener");

    private static final List<Consumer<ServerLevel>> AFTER_EACH_BATCH = new ArrayList<>();

    private static MinecraftServer server;
    /** The ticket stores whose updates are watched for forces and un-forces. */
    private static final java.util.Set<TicketStorage> WATCHED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** The chunks with a forced ticket, as the watch saw them come and go. */
    private static final LongOpenHashSet FORCED = new LongOpenHashSet();
    private static GameTestBatch batch;
    private static long batchTick;
    private static long batchNanos;
    private static boolean selfTested;
    private static boolean warned;

    private static final Map<GameTestInfo, Wait> WAITING = new IdentityHashMap<>();
    private static final Map<GameTestInfo, BoundingBox> BOXES = new WeakHashMap<>();  // GameTestInfo has identity equals
    private static final Long2ObjectMap<Unforced> UNFORCED = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap FORCED_AT = new Long2LongOpenHashMap();
    private static final Long2ObjectMap<String> FORCED_BY = new Long2ObjectOpenHashMap<>();

    private static long longestTicks = -1;
    private static long longestMs;
    private static String longestTest = "";
    private static int liveUnforces;
    private static int foreignUnforces;
    private static int failed;

    /**
     * Run {@code hook} as each batch ends, after its tests and its own {@code @AfterBatch}, just before the runner
     * un-forces every forced chunk: for tests to let go of what they held for that batch.
     */
    public static void afterEachBatch(Consumer<ServerLevel> hook) {
        AFTER_EACH_BATCH.add(hook);
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (!(e.getServer() instanceof GameTestServer s)) {
            return;
        }
        server = s;
        if (TICKER_TESTS == null || TICKER_RUNNER == null) {
            if (!warned) {
                warned = true;
                LOG.error("GameTest watchdog is OFF: cannot reach {} (has Minecraft changed?)", MISSING);
            }
            return;
        }
        ServerLevel level = s.overworld();
        long now = s.getTickCount();
        record(level);
        caughtUp(level, now);
        GameTestRunner runner = get(TICKER_RUNNER, GameTestTicker.SINGLETON);
        if (runner == null) {
            return;
        }
        List<GameTestInfo> tests = ticking();
        for (GameTestInfo t : tests) {
            if (t.isDone()) {
                continue;
            }
            Wait w = WAITING.get(t);
            if (t.hasStarted()) {
                if (w != null) {
                    WAITING.remove(t);
                    w.started(t, now);
                }
                continue;
            }
            if (w == null) {
                WAITING.put(t, w = new Wait(now, System.nanoTime(), batch == null ? "?" : name(batch)));
            }
            w.sample(level, t, now);
            if (now - w.tick >= STUCK_TICKS + t.getTest().setupTicks() && System.nanoTime() - w.nanos >= STUCK_MS * 1_000_000L) {
                WAITING.remove(t);
                fail(level, runner, t, w, now);
            }
        }
        for (var it = WAITING.entrySet().iterator(); it.hasNext(); ) {    // done in the ticker's own tick, or dropped
            var en = it.next();
            if (!tests.contains(en.getKey())) {
                if (en.getKey().hasStarted()) {
                    en.getValue().started(en.getKey(), now);
                }
                it.remove();
            }
        }
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        if (e.getServer() instanceof GameTestServer) {
            LOG.info("GameTest watchdog: the longest a test waited to start was {} ticks ({} ms, {}); {} chunk(s) un-forced under a test "
                    + "that was not done; {} un-forced by someone other than who forced them; {} test(s) failed for never starting",
                    longestTicks, longestMs, longestTest, liveUnforces, foreignUnforces, failed);
        }
    }

    /** The tests the GameTest ticker is ticking now: the running batch's, and those waiting to start. */
    @SuppressWarnings("unchecked")
    private static List<GameTestInfo> ticking() {
        Collection<GameTestInfo> c = get(TICKER_TESTS, GameTestTicker.SINGLETON);
        return c == null ? List.of() : List.copyOf(c);
    }

    /** A batch starts (its structures placed, its tests about to tick): GameTests' batch listener, from the batch's first tick on. */
    static void batchStarting(ServerLevel level, GameTestBatch b) {
        server = level.getServer();
        record(level);
        began(level, b);
    }

    /** A batch's last test is done, before the runner un-forces every forced chunk: the after-batch hooks. */
    static void batchFinished(ServerLevel level, GameTestBatch b) {
        for (Consumer<ServerLevel> hook : AFTER_EACH_BATCH) {
            try {
                hook.accept(level);
            } catch (RuntimeException ex) {
                LOG.error("GameTest watchdog: an after-batch hook failed at the end of '{}'", name(b), ex);
            }
        }
    }

    /** A batch as the log names it: its environment, and which part of it when it is split (50 tests a part). */
    static String name(GameTestBatch b) {
        return b.environment().getRegisteredName() + (b.index() > 0 ? "#" + b.index() : "");
    }

    private static void began(ServerLevel level, GameTestBatch b) {
        batch = b;
        batchTick = level.getServer().getTickCount();
        batchNanos = System.nanoTime();
        if (!selfTested && !SELF_TEST.isEmpty() && b.environment().unwrapKey().map(k -> k.identifier().getPath()).orElse("").startsWith(SELF_TEST.toLowerCase())) {
            selfTested = true;
            GameTestInfo t = b.gameTestInfos().iterator().next();
            BoundingBox box = box(t);
            if (box != null) {
                ChunkPos c = new ChunkPos(box.minX() >> 4, box.minZ() >> 4);
                LOG.warn("GameTest watchdog self-test: un-forcing chunk {} of {} as batch '{}' starts; the watchdog should fail it",
                        c, t.id(), name(b));
                level.setChunkForced(c.x(), c.z(), false);
            }
        }
    }

    /** One test waiting to start: since when, and the states its chunks went through meanwhile. */
    private static final class Wait {
        final long tick;
        final long nanos;
        final String batch;
        final Map<Long, List<String>> history = new LinkedHashMap<>();
        final Map<Long, String> last = new java.util.HashMap<>();

        Wait(long tick, long nanos, String batch) {
            this.tick = tick;
            this.nanos = nanos;
            this.batch = batch;
        }

        void sample(ServerLevel level, GameTestInfo t, long now) {
            BoundingBox box = box(t);
            if (box == null) {
                return;
            }
            box.intersectingChunks().forEach(c -> {
                long k = c.pack();
                String s = state(level, k);
                if (!s.equals(last.put(k, s))) {
                    List<String> h = history.computeIfAbsent(k, x -> new ArrayList<>());
                    if (h.size() >= 16) {
                        h.remove(1);                                  // keep the first, and the latest
                    }
                    h.add("t+" + (now - tick) + " " + s);
                }
            });
        }

        void started(GameTestInfo t, long now) {
            long ms = (System.nanoTime() - nanos) / 1_000_000L;
            if (now - tick > longestTicks) {
                longestTicks = now - tick;
                longestMs = ms;
                longestTest = String.valueOf(t.id());
            }
        }
    }

    private static void fail(ServerLevel level, GameTestRunner runner, GameTestInfo t, Wait w, long now) {
        String report = report(level, t, w, now);
        failed++;
        t.fail(new GameTestTimeoutException(Component.literal(report)));
        // what GameTestInfo.tick does for a test that finishes: the report, the batch's tracker (which starts the next batch)
        for (GameTestListener l : t.getListeners().toList()) {
            l.testFailed(t, runner);
        }
    }

    private static String report(ServerLevel level, GameTestInfo t, Wait w, long now) {
        StringBuilder sb = new StringBuilder();
        long ms = (System.nanoTime() - w.nanos) / 1_000_000L;
        Boolean loaded = get(INFO_CHUNKS_LOADED, t);
        sb.append("never started: ").append(Boolean.TRUE.equals(loaded)
                ? "its chunks were all entity-ticking once, but it still had not started"
                : "its structure's chunks were not all entity-ticking").append(' ').append(now - w.tick).append(" ticks (")
                .append(ms).append(" ms) after its batch '").append(w.batch).append("' began (GameTest watchdog: a test starts, and can time out, only once they are)");
        BoundingBox box = box(t);
        if (box == null) {
            return sb.append("; its structure block is gone").toString();
        }
        List<ChunkPos> chunks = box.intersectingChunks().toList();
        sb.append("\n  structure ").append(box).append(" over chunks ").append(chunks)
                .append(", placed ").append(get(INFO_PLACED, t) instanceof Boolean p ? p : "?");
        for (ChunkPos c : chunks) {
            chunkReport(sb, level, c, w);
        }
        GameTestBatch b = batch;
        if (b != null) {
            sb.append("\n  batch '").append(name(b)).append("' began ").append(now - batchTick).append(" ticks (")
                    .append((System.nanoTime() - batchNanos) / 1_000_000L).append(" ms) ago; its tests: ")
                    .append(b.gameTestInfos().stream().map(x -> x.id() + " (" + testState(x) + ")").collect(Collectors.joining(", ")));
        }
        ChunkMap map = level.getChunkSource().chunkMap;
        Map<?, ?> pending = get(MAP_PENDING_UNLOADS, map);
        LongSet drop = get(MAP_TO_DROP, map);
        PersistentEntitySectionManager<?> entities = get(LEVEL_ENTITIES, level);
        sb.append("\n  server: tick ").append(now).append(", forced chunks ").append(level.getForceLoadedChunks().size())
                .append(", chunk holders ").append(map.size()).append(" (pending unload ").append(pending == null ? "?" : pending.size())
                .append(", to drop ").append(drop == null ? "?" : drop.size()).append("), main-thread chunk tasks ")
                .append(level.getChunkSource().getPendingTasksCount()).append(", ticket throttler ").append(map.getDistanceManager().getDebugStatus())
                .append(", entity manager ").append(entities == null ? "?" : entities.gatherStats());
        return sb.toString();
    }

    private static void chunkReport(StringBuilder sb, ServerLevel level, ChunkPos c, Wait w) {
        long k = c.pack();
        ChunkMap map = level.getChunkSource().chunkMap;
        DistanceManager dm = map.getDistanceManager();
        boolean ticking = level.isPositionEntityTicking(c.getWorldPosition());
        boolean forced = level.getForceLoadedChunks().contains(k);
        List<String> hist = w.history.get(k);
        if (ticking) {                                               // the healthy ones in a line, so the stuck ones stand out
            String since = hist == null || hist.isEmpty() ? "?" : hist.get(hist.size() - 1).split(" ", 2)[0];
            sb.append("\n  chunk ").append(c).append(": entity-ticking since ").append(since).append(forced ? ", forced" : ", NOT forced");
            return;
        }
        sb.append("\n  chunk ").append(c).append(" (blocks ").append(c.getMinBlockX()).append("..").append(c.getMaxBlockX()).append(", ")
                .append(c.getMinBlockZ()).append("..").append(c.getMaxBlockZ()).append("): NOT ENTITY-TICKING");
        sb.append("\n    forced: ").append(forced ? "yes" : "no");
        if (FORCED_AT.containsKey(k)) {
            sb.append(", last forced at tick ").append(FORCED_AT.get(k));
        }
        Unforced u = UNFORCED.get(k);
        if (u != null) {
            sb.append(", last un-forced at tick ").append(u.tick).append(" (batch '").append(u.batch).append("') by ").append(u.by);
        }
        TicketStorage tickets = get(CACHE_TICKETS, level.getChunkSource());
        Collection<?> mine = tickets == null ? null : tickets.getTickets(k);
        SimulationChunkTracker tracker = get(DISTANCE_SIMULATION, dm);
        int simLevel = tracker == null ? -1 : tracker.getLevel(c);
        sb.append("\n    tickets: ").append(tickets == null ? "?" : mine == null || mine.isEmpty() ? "none" : mine)
                .append("; simulation level ").append(simLevel < 0 ? "?" : simLevel).append(" -> in entity-ticking range: ")
                .append(dm.inEntityTickingRange(k) ? "yes" : "no");
        ChunkHolder h = holder(map, k);
        if (h == null) {
            sb.append("\n    holder: none");
        } else {
            sb.append("\n    holder: level ").append(h.getTicketLevel()).append(" (").append(ChunkLevel.fullStatus(h.getTicketLevel()))
                    .append("), status ").append(h.getFullStatus()).append(", latest ").append(h.getLatestStatus())
                    .append("; futures full ").append(future(h.getFullChunkFuture())).append(", ticking ").append(future(h.getTickingChunkFuture()))
                    .append(", entity-ticking ").append(future(h.getEntityTickingChunkFuture()));
            if (h != map.getVisibleChunkIfPresent(k)) {
                sb.append("; not yet visible");
            }
        }
        Map<?, ?> pending = get(MAP_PENDING_UNLOADS, map);
        LongSet drop = get(MAP_TO_DROP, map);
        if (pending != null && pending.containsKey(k) || drop != null && drop.contains(k)) {
            sb.append("; unloading (").append(pending != null && pending.containsKey(k) ? "pending unload" : "to drop").append(')');
        }
        sb.append("\n    entity sections: ").append(entities(level, k));
        sb.append("\n    within 2 (it ticks entities only once all 25 are FULL; E entity-ticking, B block-ticking, F full, i inaccessible, - none), north to south:");
        for (int dz = -2; dz <= 2; dz++) {
            sb.append("\n      ");
            for (int dx = -2; dx <= 2; dx++) {
                ChunkHolder n = holder(map, ChunkPos.pack(c.x() + dx, c.z() + dz));
                sb.append(n == null ? '-' : switch (n.getFullStatus()) {
                    case ENTITY_TICKING -> 'E';
                    case BLOCK_TICKING -> 'B';
                    case FULL -> 'F';
                    default -> 'i';
                }).append(dx == 2 ? "" : " ");
            }
        }
        if (hist != null) {
            sb.append("\n    while the test waited: ").append(String.join("; ", hist));
        }
    }

    /** A chunk's state in one line, for the history of a waiting test's chunks. */
    private static String state(ServerLevel level, long k) {
        ChunkMap map = level.getChunkSource().chunkMap;
        ChunkPos c = ChunkPos.unpack(k);
        StringBuilder s = new StringBuilder(level.isPositionEntityTicking(c.getWorldPosition()) ? "ENTITY-TICKING" : "waiting");
        s.append(level.getForceLoadedChunks().contains(k) ? " forced" : " not-forced");
        ChunkHolder h = holder(map, k);
        if (h == null) {
            s.append(" no-holder");
        } else {
            s.append(" level ").append(h.getTicketLevel()).append(' ').append(h.getFullStatus())
                    .append(" futures ").append(future(h.getFullChunkFuture())).append('/').append(future(h.getTickingChunkFuture()))
                    .append('/').append(future(h.getEntityTickingChunkFuture()));
        }
        s.append(" sim-range ").append(map.getDistanceManager().inEntityTickingRange(k) ? "yes" : "no").append(" entities ").append(entities(level, k));
        return s.toString();
    }

    /**
     * The state of the chunk holding block ({@code blockX}, {@code blockZ}) in one line (forced, ticket level, futures,
     * entity sections), for a test's failure message: an entity put in a chunk that does not tick yet is not seen by
     * {@code Level#getEntity} until it does.
     */
    public static String chunkState(ServerLevel level, int blockX, int blockZ) {
        return state(level, ChunkPos.pack(blockX >> 4, blockZ >> 4));
    }

    private static String entities(ServerLevel level, long k) {
        PersistentEntitySectionManager<?> m = get(LEVEL_ENTITIES, level);
        if (m == null) {
            return "?";
        }
        Map<Long, ?> vis = get(ENTITIES_VISIBILITY, m);
        Map<Long, ?> loads = get(ENTITIES_LOADS, m);
        Object v = vis == null ? "?" : vis.get(k);
        Object l = loads == null ? "?" : loads.get(k);
        return (v == null ? "HIDDEN" : v) + "/" + (l == null ? "FRESH" : l);
    }

    private static ChunkHolder holder(ChunkMap map, long k) {
        Map<Long, ChunkHolder> updating = get(MAP_UPDATING, map);
        ChunkHolder h = updating == null ? null : updating.get(k);
        return h != null ? h : map.getVisibleChunkIfPresent(k);
    }

    private static String future(CompletableFuture<? extends ChunkResult<?>> f) {
        if (f == null) {
            return "-";
        }
        if (!f.isDone()) {
            return "pending";
        }
        if (f.isCancelled()) {
            return "cancelled";
        }
        if (f.isCompletedExceptionally()) {
            return "threw";
        }
        ChunkResult<?> r = f.getNow(null);
        return r == null ? "null" : r == ChunkHolder.UNLOADED_LEVEL_CHUNK ? "none" : r.isSuccess() ? "ok" : "failed(" + r.getError() + ")";
    }

    private static String testState(GameTestInfo t) {
        if (t.hasSucceeded()) {
            return "passed";
        }
        if (t.hasFailed()) {
            return "failed";
        }
        if (t.hasStarted()) {
            return "running";
        }
        return Boolean.TRUE.equals(get(INFO_CHUNKS_LOADED, t)) ? "placing its structure" : "waiting for its chunks";
    }

    private static BoundingBox box(GameTestInfo t) {
        BoundingBox b = BOXES.get(t);
        if (b == null) {
            try {
                b = t.getTestInstanceBlockEntity().getStructureBoundingBox();
                BOXES.put(t, b);
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return b;
    }

    // ---- who un-forces what

    private record Unforced(long tick, String batch, String by) {
    }

    /**
     * Watches the level's ticket store for forced tickets coming and going. Since 26.1 a forced chunk is a ticket in the
     * store, and its forced-chunk set is made anew at every un-force, so the set can't be swapped for a recording one as on
     * 1.21.1; the store's simulation listener (a forced ticket simulates) hears every add that raises a chunk and every
     * remove, with the caller still on the stack.
     */
    private static void record(ServerLevel level) {
        TicketStorage tickets = get(CACHE_TICKETS, level.getChunkSource());
        if (tickets == null || TICKETS_SIMULATION_LISTENER == null || !WATCHED.add(tickets)) {
            return;
        }
        FORCED.addAll(level.getForceLoadedChunks());
        TicketStorage.ChunkUpdated inner = get(TICKETS_SIMULATION_LISTENER, tickets);
        TicketStorage.ChunkUpdated watch = (node, newLevelFrom, onlyDecreased) -> {
            if (inner != null) {
                inner.update(node, newLevelFrom, onlyDecreased);
            }
            boolean forced = false;
            for (Ticket t : tickets.getTickets(node)) {
                forced |= t.getType() == TicketType.FORCED;
            }
            if (forced && FORCED.add(node) && server != null) {
                FORCED_AT.put(node, server.getTickCount());
                FORCED_BY.put(node, caller());
            } else if (!forced && FORCED.remove(node)) {
                unforced(node);
            }
        };
        try {
            TICKETS_SIMULATION_LISTENER.set(tickets, watch);
        } catch (ReflectiveOperationException ex) {
            LOG.error("GameTest watchdog: cannot watch the forced chunks", ex);
        }
    }

    /**
     * The forces the store's listener did not hear: one on a chunk that already had a lower simulating ticket (a player's) raises
     * nothing, and the first batch's are made before the watch begins. Seen here a tick late, with no caller.
     */
    private static void caughtUp(ServerLevel level, long now) {
        for (long k : level.getForceLoadedChunks()) {
            if (FORCED.add(k)) {
                FORCED_AT.put(k, now);
                FORCED_BY.put(k, "? (seen forced at tick " + now + ")");
            }
        }
    }

    /** Who is calling setChunkForced: the first few frames past it, innermost first. */
    private static String caller() {
        return StackWalker.getInstance().walk(s -> s
                .dropWhile(f -> f.getClassName().startsWith(TestWatchdog.class.getName()) && !f.getMethodName().equals("began")
                        || f.getClassName().startsWith("it.unimi") || f.getClassName().equals(TicketStorage.class.getName())
                        || f.getClassName().equals(ServerChunkCache.class.getName()) || f.getMethodName().equals("setChunkForced"))
                .limit(6).map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" < "))).intern();
    }

    /** The top-level class a caller() line starts in: "SurvivalGameTests" for "SurvivalGameTests$Unhold.tick:124 < ...". */
    private static String owner(String caller) {
        int end = caller.length();
        for (char ch : new char[] {'$', '.'}) {
            int i = caller.indexOf(ch);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        return caller.substring(0, end);
    }

    private static void unforced(long k) {
        String by = caller();
        long tick = server == null ? -1 : server.getTickCount();
        String b = batch == null ? "?" : name(batch);
        UNFORCED.put(k, new Unforced(tick, b, by));
        ChunkPos c = ChunkPos.unpack(k);
        // whoever forced a chunk lets go of it, or the runner, which lets go of every forced chunk as a batch ends
        String forcer = FORCED_BY.remove(k);
        if (forcer != null && !forcer.startsWith("?") && !owner(forcer).equals(owner(by)) && !owner(by).equals("GameTestRunner")) {
            foreignUnforces++;
            LOG.warn("GameTest watchdog: chunk {} forced at tick {} by {} was un-forced at tick {} (batch '{}') by {}",
                    c, FORCED_AT.get(k), forcer, tick, b, by);
        }
        // the tests not done: in the ticker, and the batch starting now (its tests join the ticker after its listeners hear of it)
        Map<GameTestInfo, Boolean> live = new IdentityHashMap<>();
        ticking().forEach(t -> live.put(t, true));
        if (batch != null) {
            batch.gameTestInfos().forEach(t -> live.put(t, true));
        }
        for (GameTestInfo t : live.keySet()) {
            BoundingBox box = t.isDone() ? null : box(t);
            if (box != null && c.x() >= box.minX() >> 4 && c.x() <= box.maxX() >> 4 && c.z() >= box.minZ() >> 4 && c.z() <= box.maxZ() >> 4) {
                liveUnforces++;
                LOG.warn("GameTest watchdog: chunk {} under {} ({}, batch '{}') was un-forced at tick {} by {}{}", c, t.id(), testState(t), b, tick, by,
                        t.hasStarted() ? "" : ": until something else loads it to entity-ticking, the test cannot start");
            }
        }
    }

    // ---- reflection

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            MISSING.add(owner.getSimpleName() + "." + name);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Field f, Object from) {
        if (f == null || from == null) {
            return null;
        }
        try {
            return (T) f.get(from);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private TestWatchdog() {
    }
}
