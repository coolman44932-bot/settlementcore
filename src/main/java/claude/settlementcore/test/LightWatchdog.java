package claude.settlementcore.test;

import claude.settlementcore.Core;
import com.mojang.logging.LogUtils;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.util.thread.AbstractConsecutiveExecutor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Restarts a GameTest server's light engine when its update is lost.
 *
 * <p>{@code ThreadedLevelLightEngine} runs one update at a time: {@code tryScheduleUpdate} sets {@code scheduled} and
 * tells its "light" mailbox a task that runs the update and then clears the flag. In -Pcompat runs of the "works" batch
 * (2026-09-27: 5 runs in 6) that task was lost: {@code scheduled} stayed true with 289 light tasks waiting, no thread in
 * any light code (a thread dump), nothing logged, and no light changed again in the level for the rest of the run. The
 * Slave Fields test's froglights then read light 0 on their own blocks (theFieldsFeedTheLarder). Vanilla runs never
 * showed it, and nothing in the mod works off the server thread.
 *
 * <p>So, on a GameTest server only: when {@code scheduled} has stayed true for {@link #STUCK_TICKS} ticks and
 * {@link #STUCK_MS} on the clock while the mailbox is idle (not scheduled, so no update is running or on its way),
 * the update is lost. If the mailbox still holds tasks, the wakeup was lost, and a no-op told to it sets it going again;
 * if it is empty, the update itself was dropped, and the flag is cleared and a new update scheduled. Each kick is
 * logged with which it was. A mailbox that stays scheduled that long is only logged: an update may really be running.
 */
@EventBusSubscriber(modid = Core.MODID)
public final class LightWatchdog {

    static final int STUCK_TICKS = 100;
    static final long STUCK_MS = 2_000;
    /** A scheduled mailbox this long is reported (once), not touched. */
    static final long LONG_UPDATE_MS = 30_000;

    private static final Logger LOG = LogUtils.getLogger();
    private static final Field SCHEDULED = field(ThreadedLevelLightEngine.class, "scheduled");
    private static final Field MAILBOX = field(ThreadedLevelLightEngine.class, "consecutiveExecutor");
    private static final Field STATUS = field(AbstractConsecutiveExecutor.class, "status");

    private static final Map<ServerLevel, Stuck> STUCK = new IdentityHashMap<>();
    private static int kicks;
    private static boolean warned;

    private record Stuck(long tick, long nanos, boolean[] reported) {
    }

    /** How many times the light engine has been set going again in this run (for tests and the log). */
    public static int kicks() {
        return kicks;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post e) {
        if (!(e.getServer() instanceof GameTestServer s)) {
            return;
        }
        if (SCHEDULED == null || MAILBOX == null || STATUS == null) {
            if (!warned) {
                warned = true;
                LOG.error("GameTest light watchdog is OFF: cannot reach ThreadedLevelLightEngine / ConsecutiveExecutor fields (has Minecraft changed?)");
            }
            return;
        }
        for (ServerLevel level : s.getAllLevels()) {
            watch(level, s.getTickCount());
        }
    }

    private static void watch(ServerLevel level, long now) {
        ThreadedLevelLightEngine engine = level.getChunkSource().getLightEngine();
        try {
            AtomicBoolean scheduled = (AtomicBoolean) SCHEDULED.get(engine);
            if (!scheduled.get()) {
                STUCK.remove(level);
                return;
            }
            Stuck st = STUCK.computeIfAbsent(level, l -> new Stuck(now, System.nanoTime(), new boolean[1]));
            long ms = (System.nanoTime() - st.nanos()) / 1_000_000L;
            if (now - st.tick() < STUCK_TICKS || ms < STUCK_MS) {
                return;
            }
            ConsecutiveExecutor box = (ConsecutiveExecutor) MAILBOX.get(engine);
            // 26.1's light executor (ConsecutiveExecutor, was ProcessorMailbox) is SLEEPING, RUNNING or CLOSED
            String status = String.valueOf(((java.util.concurrent.atomic.AtomicReference<?>) STATUS.get(box)).get());
            if (status.equals("RUNNING")) {
                if (ms >= LONG_UPDATE_MS && !st.reported()[0]) {
                    st.reported()[0] = true;
                    LOG.warn("GameTest light watchdog: {}'s light update has been running {} ms ({} ticks), mailbox status {}, {} queued",
                            level.dimension().identifier(), ms, now - st.tick(), status, box.size());
                }
                return;
            }
            kick(level, engine, scheduled, box, (now - st.tick()) + " ticks, " + ms + " ms");
            STUCK.remove(level);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            if (!warned) {
                warned = true;
                LOG.error("GameTest light watchdog is OFF", ex);
            }
        }
    }

    private static void kick(ServerLevel level, ThreadedLevelLightEngine engine, AtomicBoolean scheduled, ConsecutiveExecutor box, String how) {
        kicks++;
        int queued = box.size();
        if (queued > 0) {
            LOG.warn("GameTest light watchdog: {}'s light update was lost ({}): the mailbox sat idle with {} tasks; woken (kick {})",
                    level.dimension().identifier(), how, queued, kicks);
            box.schedule(() -> {
            });
        } else {
            LOG.warn("GameTest light watchdog: {}'s light update was dropped ({}): scheduled with an empty idle mailbox; scheduled "
                    + "again (kick {})", level.dimension().identifier(), how, kicks);
            scheduled.set(false);
            engine.tryScheduleUpdate();
        }
    }

    /**
     * Sets the engine going now if its update is lost, without waiting: for a test that took the flag itself, after its
     * batch, pass or fail. A self-test that failed at once (its spot lit already) once left the flag taken; the next batch's
     * test structure then waited in getChunk for light that never came, on the server thread, so this watchdog never ticked
     * again and the -Pcompat run hung for good (2026-09-27). Returns whether it did anything.
     */
    public static boolean release(ServerLevel level) {
        if (SCHEDULED == null || MAILBOX == null || STATUS == null) {
            return false;
        }
        ThreadedLevelLightEngine engine = level.getChunkSource().getLightEngine();
        try {
            AtomicBoolean scheduled = (AtomicBoolean) SCHEDULED.get(engine);
            ConsecutiveExecutor box = (ConsecutiveExecutor) MAILBOX.get(engine);
            if (!scheduled.get()) {
                return false;
            }
            // Busy or not: the engine also runs updates straight from its task queue once 1000 wait (addTask), without the
            // flag, and a release that waited for an idle mailbox found it busy with those and left the flag taken: the run
            // hung again (2026-09-27). The mailbox runs one task at a time, so one update more is all this can cause.
            kicks++;
            LOG.warn("GameTest light watchdog: {}'s light flag released after a test (kick {})", level.dimension().identifier(), kicks);
            scheduled.set(false);
            engine.tryScheduleUpdate();
            STUCK.remove(level);
            return true;
        } catch (ReflectiveOperationException | ClassCastException ex) {
            return false;
        }
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        if (e.getServer() instanceof GameTestServer && kicks > 0) {
            LOG.warn("GameTest light watchdog: the light engine was set going again {} times in this run", kicks);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private LightWatchdog() {
    }
}
