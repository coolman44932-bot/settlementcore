package claude.settlementcore.test;

import claude.settlementcore.Core;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * The test harness itself (core/test): what it does for every race's tests, checked. The engine's own GameTests, in its own
 * namespace (settlementcore:digsite is its copy of the plot), so they run with no race loaded's tests too
 * (tools/gametest.py all --ns settlementcore; docs/plans/core-step2.md, C8).
 */
@GameTestHolder(Core.MODID)
public class HarnessGameTests {

    /**
     * A light update that is lost leaves the level's light engine scheduled with nothing running: no light changes again
     * (theFieldsFeedTheLarder's froglights read 0 in -Pcompat runs, 2026-09-27). Made here on purpose (the flag taken as
     * a real schedule takes it, the update never sent), a glowstone set down must still light up: LightWatchdog sets the
     * engine going again. Nothing else ever clears the flag, so without the watchdog this cannot pass. Its own batch: the
     * whole level's light stops meanwhile.
     */
    @GameTest(template = "digsite", timeoutTicks = 1200, batch = "lightwatchdog")
    public static void aLostLightUpdateIsSetGoingAgain(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ThreadedLevelLightEngine engine = level.getChunkSource().getLightEngine();
        AtomicBoolean scheduled;
        try {
            var f = ThreadedLevelLightEngine.class.getDeclaredField("scheduled");
            f.setAccessible(true);
            scheduled = (AtomicBoolean) f.get(engine);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot reach ThreadedLevelLightEngine.scheduled", ex);
        }
        // a dark spot in the open air: it once stood where an earlier batch's test had left light, read lit at once, and
        // failed without the watchdog ever acting (the flag it took is given back after the batch: released)
        BlockPos lamp = null;
        for (int y = 150; y <= 230 && lamp == null; y += 10) {
            BlockPos p = h.absolutePos(new BlockPos(8, y, 8));
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                    && level.getBrightness(LightLayer.BLOCK, p) == 0 && level.getBrightness(LightLayer.BLOCK, p.above()) == 0) {
                lamp = p;
            }
        }
        h.assertTrue(lamp != null, "no dark spot over the test to set the glowstone in");
        BlockPos at = lamp;
        int[] kicks = {0};
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(scheduled.compareAndSet(false, true), "an update is running"))
                .thenExecute(() -> {
                    kicks[0] = LightWatchdog.kicks();
                    level.setBlock(at, Blocks.GLOWSTONE.defaultBlockState(), 3);
                })
                // Only the watchdog gives the flag back: the update it stands for was never sent. (The light itself may come
                // another way: the engine runs an update straight from its queue once 1000 tasks wait, flag or no flag; in
                // a loaded -Pcompat run it lit the glowstone before the watchdog's 2 s, 2026-09-27.)
                .thenWaitUntil(() -> {
                    if (scheduled.get()) {
                        LockSupport.parkNanos(5_000_000L);          // the watchdog waits 2 s on the clock too
                    }
                    h.assertTrue(!scheduled.get(), "the light flag is still taken");
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBrightness(LightLayer.BLOCK, at.above()) >= 14,
                        "the glowstone is dark: light " + level.getBrightness(LightLayer.BLOCK, at.above()) + " over it"))
                .thenExecute(() -> {
                    h.assertTrue(LightWatchdog.kicks() > kicks[0], "the flag came back, but not from the watchdog: " + kicks[0] + " -> " + LightWatchdog.kicks());
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                })
                .thenSucceed();
    }

    /** The flag the self-test took is given back, pass or fail, before the next batch loads a chunk (LightWatchdog#release). */
    @AfterBatch(batch = "lightwatchdog")
    public static void released(ServerLevel level) {
        LightWatchdog.release(level);
    }
}
