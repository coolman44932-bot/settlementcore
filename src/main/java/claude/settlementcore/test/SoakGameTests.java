package claude.settlementcore.test;

import claude.settlementcore.Core;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The soak sampler's own checks ({@link Soak}), quick, in every run: its verdict fails a count that climbs without levelling off and a tick
 * time that doubles, passes a count that climbs to a cap and stays, and every engine field it watches by name is still there (a rename would
 * otherwise leave a registry unwatched without a word). The long soak itself is the test race's (testrace/test/TestRaceSoakGameTests), run
 * with -Psoak (tools/soak.py).
 */
@GameTestHolder(Core.MODID)
public class SoakGameTests {

    /** {@code n} samples a minute apart: the count {@code key} at {@code count(i)}, every tick {@code wall(i)} ms, the heap steady. */
    private static List<Soak.Sample> series(int n, String key, java.util.function.IntUnaryOperator count, java.util.function.IntToDoubleFunction wall) {
        List<Soak.Sample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> counts = new java.util.TreeMap<>();
            counts.put("entities", 40 + (i % 5));
            counts.put(key, count.applyAsInt(i));
            out.add(new Soak.Sample(1200L * (i + 1), wall.applyAsDouble(i), wall.applyAsDouble(i), 900 + (i % 3), counts));
        }
        return out;
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "soakjudge")
    public static void aCountThatClimbsToACapAndStaysPasses(GameTestHelper h) {
        // a settlement filling its reserve: up 3 a minute to 24, then level, with the tick time a little noisy
        List<String> p = Soak.judge(series(40, "settlement/reserve", i -> Math.min(24, 3 * i), i -> 1.0 + (i % 4) * 0.1), Soak.Options.DEFAULT);
        h.assertTrue(p.isEmpty(), "a count that levels off at its cap was judged a leak: " + p);
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "soakjudge")
    public static void aCountThatNeverStopsClimbingFails(GameTestHelper h) {
        // a cache keyed by something new every minute, all the way
        List<String> p = Soak.judge(series(40, "engine/RoomTemplates.PRINTS", i -> 5 + i, i -> 1.0), Soak.Options.DEFAULT);
        h.assertTrue(p.size() == 1 && p.get(0).startsWith("engine/RoomTemplates.PRINTS keeps rising"), "the leak was not the one problem found: " + p);
        // one more every other sample over an hour: too slow to pass the margin in a window, but each third of the run is above the last
        List<String> slow = Soak.judge(series(60, "settlement/roll_not_loaded", i -> i / 2, i -> 1.0), Soak.Options.DEFAULT);
        h.assertTrue(slow.size() == 1 && slow.get(0).contains("slowly"), "the slow leak was not the one problem found: " + slow);
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "soakjudge")
    public static void aCountThatRisesAndFallsPasses(GameTestHelper h) {
        // dropped items that pile up and are picked up again: rises, but falls inside every window
        List<String> p = Soak.judge(series(40, "entity/minecraft:item", i -> 10 + 4 * (i % 6), i -> 1.0), Soak.Options.DEFAULT);
        h.assertTrue(p.isEmpty(), "a count that rises and falls was judged a leak: " + p);
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "soakjudge")
    public static void aTickTimeThatDoublesFails(GameTestHelper h) {
        List<String> slow = Soak.judge(series(40, "entities", i -> 40, i -> i < 20 ? 3.0 : 7.0), Soak.Options.DEFAULT);
        h.assertTrue(slow.size() == 1 && slow.get(0).startsWith("a tick takes"), "a tick time from 3 to 7 ms was not the one problem: " + slow);
        List<String> tiny = Soak.judge(series(40, "entities", i -> 40, i -> i < 20 ? 0.3 : 0.9), Soak.Options.DEFAULT);
        h.assertTrue(tiny.isEmpty(), "a tick under the 2 ms floor was judged slow: " + tiny);
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "soakjudge")
    public static void tooFewSamplesJudgeNothing(GameTestHelper h) {
        List<String> p = Soak.judge(series(15, "entities", i -> 10 * i, i -> 1.0 + i), Soak.Options.DEFAULT);
        h.assertTrue(p.isEmpty(), "15 samples (fewer than the warm-up and two windows) were judged: " + p);
        h.succeed();
    }

    /** Every engine field the sampler reads by name is still there, and a sample of this server reads them and the settlements. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "soakjudge")
    public static void theSoakSamplerReadsEveryEngineRegistry(GameTestHelper h) {
        List<String> gone = Soak.missing();
        h.assertTrue(gone.isEmpty(), "Soak.ENGINE names fields that are gone (renamed? follow them): " + gone);
        Map<String, Integer> c = Soak.counts(h.getLevel().getServer());
        for (String[] f : Soak.ENGINE) {
            String key = "engine/" + f[0].substring(f[0].lastIndexOf('.') + 1) + "." + f[1];
            h.assertTrue(c.containsKey(key), "a sample has no " + key + " (not a map or a collection any more?): " + c.keySet());
        }
        h.assertTrue(c.getOrDefault("chunks", 0) > 0 && c.containsKey("be/all") && c.containsKey("settlement/loaded"),
                "a sample is missing its chunks, block entities or settlements: " + c);
        h.succeed();
    }
}
