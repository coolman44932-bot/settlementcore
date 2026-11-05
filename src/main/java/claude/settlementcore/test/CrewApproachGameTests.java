package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.unit.CrewGoal;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * When a crew settler gives its chore up as out of reach (unit/CrewGoal.Approach): after {@link CrewGoal.Approach#GIVE_UP}
 * ticks without progress, never while it is still closing in. To 0.5.5 it counted every tick out of reach, so a chore over
 * 20 s away (some 48 blocks at a settler's pace) was given up on the way. The walks here are fed to the counter tick by tick,
 * as CrewGoal#tick feeds it: a real walk that long needs an arena over 50 blocks across, which no test template is.
 */
@GameTestHolder(Core.MODID)
public class CrewApproachGameTests {

    /** A settler's pace, blocks a tick (speed 0.3: some 2.4 blocks a second). */
    private static final double PACE = 0.12;

    /** The tick the counter gives up at, or -1 if it hasn't by {@code ticks} or the settler got within a block of the chore first. */
    private interface Walk {
        /** Where the settler is at tick {@code t}: x, y, z, its distance to the chore, and whether its path advanced. */
        double[] at(int t);
    }

    private static int givesUpAt(Walk walk, int ticks) {
        CrewGoal.Approach a = new CrewGoal.Approach();
        for (int t = 0; t < ticks; t++) {
            double[] p = walk.at(t);
            if (p[3] <= 1.0) {
                return -1;                                       // there: in reach, the walk is over
            }
            if (a.tick(p[3], p[0], p[1], p[2], p[4] > 0)) {
                return t;
            }
        }
        return -1;
    }

    /** 100 blocks straight at the chore (over 800 ticks, twice the old limit): never given up. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aSettlerStillClosingInOnAFarChoreNeverGivesItUp(GameTestHelper h) {
        double far = 100;
        int ticks = (int) (far / PACE);
        int at = givesUpAt(t -> new double[]{t * PACE, 0, 0, Math.max(0, far - t * PACE), 0}, ticks);
        h.assertTrue(at < 0, "a settler walking " + far + " blocks straight at its chore gave it up at tick " + at + " of " + ticks);
        h.succeed();
    }

    /**
     * Down a winze's stair (a spiral round the shaft, 40 levels down, some 1300 ticks), with no help from its path: the chore
     * at the foot gets nearer lap by lap, though each lap swings it away and back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aSettlerWindingDownAStairToItsChoreNeverGivesItUp(GameTestHelper h) {
        int ticks = 3000;
        int at = givesUpAt(t -> {
            double angle = t * PACE / 3.0;                       // a lap of radius 3 round the shaft
            double x = 3 * Math.cos(angle), z = 3 * Math.sin(angle);
            double y = -Math.min(40, t * PACE / 4.0);            // a block down for every 4 walked
            double dx = x - 3, dy = y + 40, dz = z;              // the chore at the stair's foot, (3, -40, 0)
            return new double[]{x, y, z, Math.sqrt(dx * dx + dy * dy + dz * dz), 0};
        }, ticks);
        h.assertTrue(at < 0, "a settler winding down a stair to its chore gave it up at tick " + at);
        h.succeed();
    }

    /**
     * A detour that leads away first, round a wall to a chore 20 blocks off: 30 out, 20 across, 30 back (some 670 ticks, no
     * closer than at the start for the first 500), on a path that reaches the chore and advances as it goes.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aDetourOnAPathThatReachesTheChoreIsNotGivenUp(GameTestHelper h) {
        int ticks = (int) (80 / PACE) + 10;
        int at = givesUpAt(t -> {
            double w = t * PACE, x, z;
            if (w < 30) {
                x = 0;
                z = w;                                           // out along the wall
            } else if (w < 50) {
                x = w - 30;
                z = 30;                                          // round its end
            } else {
                x = 20;
                z = Math.max(0, 80 - w);                         // and back down the far side
            }
            return new double[]{x, 0, z, Math.sqrt((x - 20) * (x - 20) + z * z), 1};
        }, ticks);
        h.assertTrue(at < 0, "a settler on a detour to its chore gave it up at tick " + at);
        h.succeed();
    }

    /** Standing stuck (no path, not moving): given up after GIVE_UP ticks, as before. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aStuckSettlerStillGivesUpOnTime(GameTestHelper h) {
        int at = givesUpAt(t -> new double[]{0, 0, 0, 30, 0}, 2000);
        h.assertTrue(at == CrewGoal.Approach.GIVE_UP + 1, "a settler stuck 30 blocks from its chore gave it up at tick " + at
                + ", not " + (CrewGoal.Approach.GIVE_UP + 1));
        h.succeed();
    }

    /** Pacing back and forth with no path to the chore (penned): it gets no closer, so it gives up after GIVE_UP ticks. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aSettlerPacingInAPenGivesUp(GameTestHelper h) {
        int at = givesUpAt(t -> {
            double x = 3 * Math.sin(t * 0.05);                   // to and fro along a 6-block pen
            return new double[]{x, 0, 0, 30 - x, 0};
        }, 2000);
        h.assertTrue(at > 0 && at <= CrewGoal.Approach.GIVE_UP + 60, "a settler pacing in a pen gave its chore up at tick " + at);
        h.succeed();
    }

    /** Walking in circles on a path that claims to reach the chore and keeps advancing: given up once the detour allowance is spent. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aSettlerCirclingOnAPathItNeverFinishesGivesUp(GameTestHelper h) {
        int at = givesUpAt(t -> {
            double angle = t * PACE / 5.0;
            double x = 5 * Math.cos(angle), z = 5 * Math.sin(angle);
            return new double[]{x, 0, z, Math.sqrt((x - 30) * (x - 30) + z * z), 1};
        }, 10_000);
        int most = CrewGoal.Approach.DETOUR + CrewGoal.Approach.GIVE_UP + 1;
        h.assertTrue(at > 0 && at <= most, "a settler circling on an endless path gave its chore up at tick " + at + " (at most " + most + ")");
        h.succeed();
    }

    /** A new leg (reset) starts the count afresh: a settler that gave nothing up yet keeps its whole allowance. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aNewLegStartsTheCountAfresh(GameTestHelper h) {
        CrewGoal.Approach a = new CrewGoal.Approach();
        for (int t = 0; t < CrewGoal.Approach.GIVE_UP; t++) {
            h.assertTrue(!a.tick(10, 0, 0, 0, false), "gave up at tick " + t + " of the first leg");
        }
        a.reset();
        for (int t = 0; t <= CrewGoal.Approach.GIVE_UP; t++) {
            h.assertTrue(!a.tick(10, 0, 0, 0, false), "gave up at tick " + t + " of the second leg, though it was reset");
        }
        h.assertTrue(a.tick(10, 0, 0, 0, false), "did not give up after " + (CrewGoal.Approach.GIVE_UP + 1) + " idle ticks");
        h.succeed();
    }
}
