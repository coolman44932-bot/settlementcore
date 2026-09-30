package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.Reshape;
import claude.underempire.core.room.Reshape.Frame;
import claude.underempire.core.room.Reshape.Kind;
import claude.underempire.core.room.Reshape.Rect;
import claude.underempire.core.room.Reshape.Result;
import claude.underempire.core.room.Reshape.Side;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Reshape, the height solve of the surface builder (docs/plans/surface-builder.md, section 9), checked with no world: it is
 * arrays in and arrays out, so these need no blocks, and run in the engine's own namespace with its copy of the plot
 * (settlementcore:digsite). The template is only what a GameTest needs to start.
 *
 * <p>The checks are written here, on the answer alone, and not taken from Reshape: a check that came from the code it
 * checks would pass whatever the code did. Each one is shown able to fail (the clamp's, in
 * {@link #aKeptColumnIsUnchangedAndTheRampGoesRoundIt}, on the field before the clamp).
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class ReshapeGameTests {

    // --- the scenes --------------------------------------------------------------------------------------------------

    /** A grid to reshape: its frame (with its kinds), the land as it is, and the floor the pad is held at. */
    private record Scene(Frame f, int[] ground, int floorY) {
        int w() {
            return f.w();
        }

        int h() {
            return f.h();
        }

        Kind[] kinds() {
            return f.kinds();
        }

        int at(int gx, int gz) {
            return gz * f.w() + gx;
        }

        Result plan(int[] floors, int margin) {
            return Reshape.plan(w(), h(), ground, kinds(), floors, floorY, margin);
        }

        int[] beforeClamp(int[] floors) {
            return Reshape.beforeClamp(w(), h(), ground, kinds(), floors, floorY);
        }

        String where(int i) {
            return "(" + (f.x0() + i % w()) + "," + (f.z0() + i / w()) + ")";
        }
    }

    private static Scene flat(Frame f, int y) {
        int[] g = new int[f.w() * f.h()];
        Arrays.fill(g, y);
        return new Scene(f, g, 0);
    }

    /**
     * A 9x9 pad at floor 0 in a 21x21 area (a margin of 6), away from the world's origin so that a mixed-up index shows. The
     * margin has a dip of -3 and a hump of +4, and the land rises 5 at the east side, a block a column from the pad's edge.
     * The pad's own ground is a knoll and a hole: it is held at the floor all the same.
     */
    private static Scene hillside() {
        Scene s = flat(Reshape.frame(new Rect(-9, 40, -1, 48), 6), 0);
        for (int gz = 0; gz < s.h(); gz++) {
            for (int gx = 0; gx < s.w(); gx++) {
                s.ground()[s.at(gx, gz)] = Math.max(0, Math.min(5, gx - 14));
            }
        }
        s.ground()[s.at(3, 4)] = -3;
        s.ground()[s.at(4, 15)] = 4;
        s.ground()[s.at(10, 10)] = 2;
        s.ground()[s.at(7, 7)] = -1;
        return s;
    }

    // --- the checks, on the answer alone -----------------------------------------------------------------------------

    /** Null when every pair of 4-neighbours with a free column in it is within 1; else how many aren't, and the first. */
    private static String stepProblem(Scene s, int[] a) {
        int bad = 0;
        String first = null;
        for (int z = 0; z < s.h(); z++) {
            for (int x = 0; x < s.w(); x++) {
                int i = s.at(x, z);
                for (int j : new int[]{x < s.w() - 1 ? i + 1 : -1, z < s.h() - 1 ? i + s.w() : -1}) {
                    if (j < 0 || (s.kinds()[i].held() && s.kinds()[j].held()) || Math.abs(a[i] - a[j]) <= 1) {
                        continue;
                    }
                    bad++;
                    if (first == null) {
                        first = a[i] + " to " + a[j] + " between " + s.where(i) + " and " + s.where(j);
                    }
                }
            }
        }
        return bad == 0 ? null : bad + " steps over 1 between free columns and their neighbours, the first " + first;
    }

    /**
     * Null when every pair on the ground walked is within 1: a free column and a free, pad or border neighbour. A kept
     * column or a pond is walked round, so its own steps don't count here (the result's heldSteepest has them).
     */
    private static String walkedStepProblem(Scene s, int[] a) {
        for (int z = 0; z < s.h(); z++) {
            for (int x = 0; x < s.w(); x++) {
                int i = s.at(x, z);
                for (int j : new int[]{x < s.w() - 1 ? i + 1 : -1, z < s.h() - 1 ? i + s.w() : -1}) {
                    if (j < 0 || (s.kinds()[i].held() && s.kinds()[j].held()) || round(s.kinds()[i]) || round(s.kinds()[j])) {
                        continue;
                    }
                    if (Math.abs(a[i] - a[j]) > 1) {
                        return "a step of " + a[i] + " to " + a[j] + " on the ground walked, between " + s.where(i) + " and " + s.where(j);
                    }
                }
            }
        }
        return null;
    }

    private static boolean round(Kind k) {
        return k == Kind.KEPT || k == Kind.FLUID;
    }

    /** Null when the ramp is walkable in itself: every free column within 1 of its free and pad neighbours. */
    private static String rampStepProblem(Scene s, int[] a) {
        for (int z = 0; z < s.h(); z++) {
            for (int x = 0; x < s.w(); x++) {
                int i = s.at(x, z);
                for (int j : new int[]{x < s.w() - 1 ? i + 1 : -1, z < s.h() - 1 ? i + s.w() : -1}) {
                    if (j < 0) {
                        continue;
                    }
                    Kind ki = s.kinds()[i];
                    Kind kj = s.kinds()[j];
                    boolean ramp = (ki == Kind.FREE && (kj == Kind.FREE || kj == Kind.PAD)) || (kj == Kind.FREE && ki == Kind.PAD);
                    if (ramp && Math.abs(a[i] - a[j]) > 1) {
                        return "a step of " + a[i] + " to " + a[j] + " in the ramp, between " + s.where(i) + " and " + s.where(j);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Null when the bank pass left level land alone and moved the slope one way only: every free column whose land is at
     * the floor, as are its four neighbours' (flat land, not the foot of the slope), is unchanged; every free column above
     * the floor is only ever cut (a hill is cut into, never heaped up), and every one below it only ever filled.
     */
    private static String bankProblem(Scene s, int[] a) {
        int floor = s.floorY();
        for (int z = 1; z < s.h() - 1; z++) {
            for (int x = 1; x < s.w() - 1; x++) {
                int i = s.at(x, z);
                if (s.kinds()[i] != Kind.FREE) {
                    continue;
                }
                int g = s.ground()[i];
                boolean flat = g == floor;
                for (int j : new int[]{i - 1, i + 1, i - s.w(), i + s.w()}) {
                    flat &= s.ground()[j] == floor;
                }
                if (flat && a[i] != g) {
                    return "flat land at " + s.where(i) + " was moved from " + g + " to " + a[i];
                }
                if (g > floor && a[i] > g) {
                    return "the hill at " + s.where(i) + " was heaped up from " + g + " to " + a[i];
                }
                if (g < floor && a[i] < g) {
                    return "the drop at " + s.where(i) + " was dug down from " + g + " to " + a[i];
                }
            }
        }
        return null;
    }

    /** Null when a walker on {@code from} gets out to the land: over free and pad columns, steps of 1, onto a border cell. */
    private static String walkOutProblem(Scene s, int[] a, int from) {
        boolean[] seen = new boolean[a.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % s.w();
            int z = i / s.w();
            for (int j : new int[]{x > 0 ? i - 1 : -1, x < s.w() - 1 ? i + 1 : -1, z > 0 ? i - s.w() : -1, z < s.h() - 1 ? i + s.w() : -1}) {
                if (j < 0 || seen[j] || Math.abs(a[i] - a[j]) > 1 || round(s.kinds()[j])) {
                    continue;
                }
                if (s.kinds()[j] == Kind.BORDER) {
                    return null;
                }
                seen[j] = true;
                queue.add(j);
            }
        }
        return "no walk from " + s.where(from) + " out to the land at the border";
    }

    /**
     * Null when no free column is strictly under, or over, all four of its neighbours. A column held up by its own floor
     * (floors, if given) is the floor's doing and is let off.
     */
    private static String speckProblem(Scene s, int[] a, int[] floors) {
        for (int z = 1; z < s.h() - 1; z++) {
            for (int x = 1; x < s.w() - 1; x++) {
                int i = s.at(x, z);
                if (s.kinds()[i].held()) {
                    continue;
                }
                int[] nb = {a[i - 1], a[i + 1], a[i - s.w()], a[i + s.w()]};
                boolean under = true;
                boolean over = true;
                for (int n : nb) {
                    under &= a[i] < n;
                    over &= a[i] > n;
                }
                boolean floored = floors != null && a[i] == floors[i];
                if (under || (over && !floored)) {
                    return "the column " + s.where(i) + " is " + a[i] + ", " + (under ? "under" : "over") + " all of "
                            + Arrays.toString(nb);
                }
            }
        }
        return null;
    }

    /** Null when the pad is at the floor and every other held column at its own height. */
    private static String heldProblem(Scene s, int[] a) {
        for (int i = 0; i < a.length; i++) {
            Kind k = s.kinds()[i];
            if (k == Kind.PAD && a[i] != s.floorY()) {
                return "the pad column " + s.where(i) + " is " + a[i] + ", not the floor " + s.floorY();
            }
            if (k.held() && k != Kind.PAD && a[i] != s.ground()[i]) {
                return "the " + k + " column " + s.where(i) + " is " + a[i] + ", not its own " + s.ground()[i];
            }
        }
        return null;
    }

    /**
     * Null when a walker on cell {@code from} steps (up or down at most 1, over no KEPT column if {@code avoidKept}) to every
     * free column, and to every border column that has a free neighbour (the ring's corners touch only the ring).
     */
    private static String walkProblem(Scene s, int[] a, int from, boolean avoidKept) {
        boolean[] seen = new boolean[a.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen[from] = true;
        queue.add(from);
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % s.w();
            int z = i / s.w();
            for (int j : new int[]{x > 0 ? i - 1 : -1, x < s.w() - 1 ? i + 1 : -1, z > 0 ? i - s.w() : -1, z < s.h() - 1 ? i + s.w() : -1}) {
                if (j >= 0 && !seen[j] && Math.abs(a[i] - a[j]) <= 1 && !(avoidKept && s.kinds()[j] == Kind.KEPT)) {
                    seen[j] = true;
                    queue.add(j);
                }
            }
        }
        for (int i = 0; i < a.length; i++) {
            int x = i % s.w();
            int z = i / s.w();
            boolean nextToFree = (x > 0 && !s.kinds()[i - 1].held()) || (x < s.w() - 1 && !s.kinds()[i + 1].held())
                    || (z > 0 && !s.kinds()[i - s.w()].held()) || (z < s.h() - 1 && !s.kinds()[i + s.w()].held());
            boolean mustReach = s.kinds()[i] == Kind.FREE || (s.kinds()[i] == Kind.BORDER && nextToFree);
            if (mustReach && !seen[i]) {
                return "no walk to the " + s.kinds()[i] + " column " + s.where(i) + " from " + s.where(from);
            }
        }
        return null;
    }

    /** Null when the result's cut and fill counts are what the arrays say. */
    private static String tallyProblem(Scene s, Result r) {
        int cutColumns = 0;
        int fillColumns = 0;
        int cutBlocks = 0;
        int fillBlocks = 0;
        for (int i = 0; i < s.ground().length; i++) {
            int d = r.height()[i] - s.ground()[i];
            cutColumns += d < 0 ? 1 : 0;
            fillColumns += d > 0 ? 1 : 0;
            cutBlocks += Math.max(0, -d);
            fillBlocks += Math.max(0, d);
        }
        int[] want = {cutColumns, fillColumns, cutBlocks, fillBlocks};
        int[] got = {r.cutColumns(), r.fillColumns(), r.cutBlocks(), r.fillBlocks()};
        return Arrays.equals(want, got) ? null : "cut and fill (columns, columns, blocks, blocks) are " + Arrays.toString(got)
                + ", the arrays say " + Arrays.toString(want);
    }

    private static String dump(Scene s, int[] a) {
        StringBuilder sb = new StringBuilder("\n(x " + s.f().x0() + ", z " + s.f().z0() + "; P pad, b border, K kept, ~ fluid)");
        for (int z = 0; z < s.h(); z++) {
            sb.append('\n');
            for (int x = 0; x < s.w(); x++) {
                int i = s.at(x, z);
                char mark = switch (s.kinds()[i]) {
                    case PAD -> 'P';
                    case BORDER -> 'b';
                    case KEPT -> 'K';
                    case FLUID -> '~';
                    default -> ' ';
                };
                sb.append(String.format(Locale.ROOT, "%3d%c", a[i], mark));
            }
        }
        return sb.toString();
    }

    /** Fails with the problem and the area's heights, or passes when there is none. */
    private static void expect(GameTestHelper h, Scene s, int[] a, String problem) {
        h.assertTrue(problem == null, problem == null ? "" : problem + dump(s, a));
    }

    // --- the tests ---------------------------------------------------------------------------------------------------

    /**
     * The main case: land with a dip, a hump and a rise round a pad. The ramp is the field, not the old ground: the dip is
     * filled and the hump cut, no free column is a pit or a bump, no step is over 1 between free columns and their
     * neighbours, and a walker gets from the pad to every column. The pad (whatever its ground was) and the border are as
     * they were held.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aReshapeIsWalkableAllRound(GameTestHelper h) {
        Scene s = hillside();
        Result r = s.plan(null, 6);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep() && r.steepest() <= 1 && r.margin() == 6 && r.spotX() < 0 && r.spotZ() < 0,
                "reported too steep (" + r.steepest() + " at " + r.spotX() + "," + r.spotZ() + ") or the wrong margin " + r.margin() + dump(s, a));
        expect(h, s, a, stepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, null));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, walkProblem(s, a, s.at(10, 10), false));
        expect(h, s, a, tallyProblem(s, r));
        h.assertTrue(a[s.at(3, 4)] > s.ground()[s.at(3, 4)] && a[s.at(4, 15)] < s.ground()[s.at(4, 15)],
                "the dip is " + a[s.at(3, 4)] + " (was -3) and the hump " + a[s.at(4, 15)] + " (was 4): they should be filled and cut" + dump(s, a));
        h.assertTrue(r.cutColumns() > 0 && r.fillColumns() > 0 && r.cutBlocks() >= r.cutColumns() && r.fillBlocks() >= r.fillColumns(),
                "the cut and fill counts are empty: " + r);
        h.succeed();
    }

    /**
     * A player's posts in the ramp (KEPT columns, 3 and 2 up, the first 3 columns from the pad and 3 from the border):
     * unchanged, and the ramp goes round them, walkable, in a cone that comes down 1 a column. Also the clamp's proof: on
     * the field before the clamp the step check fails (the harmonic field is a bump of about 1 round a post of 3), so the
     * check can fail, and it is the clamp that makes it pass.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aKeptColumnIsUnchangedAndTheRampGoesRoundIt(GameTestHelper h) {
        Scene s = flat(Reshape.frame(new Rect(200, -30, 208, -22), 6), 0);
        s.kinds()[s.at(17, 10)] = Kind.KEPT;
        s.ground()[s.at(17, 10)] = 3;
        s.kinds()[s.at(3, 12)] = Kind.KEPT;
        s.ground()[s.at(3, 12)] = 2;
        Result r = s.plan(null, 6);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep(), "the ramp round the posts is reported too steep (" + r.steepest() + " at " + r.spotX() + "," + r.spotZ() + ")" + dump(s, a));
        h.assertTrue(a[s.at(17, 10)] == 3 && a[s.at(3, 12)] == 2, "a post moved" + dump(s, a));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, stepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, null));
        expect(h, s, a, walkProblem(s, a, s.at(10, 10), true));
        // without the clamp, the same check fails
        int[] before = s.beforeClamp(null);
        String unclamped = stepProblem(s, before);
        h.assertTrue(unclamped != null, "the field before the clamp is already walkable: the scene shows nothing of the clamp" + dump(s, before));
        h.succeed();
    }

    /**
     * A 3x3 pond (surface 2) in a margin the ramp cuts down 2, to the pad: its banks (the 12 columns beside it) are never
     * under its surface, the water's own columns are as they were, and the ramp is otherwise as walkable as ever. The same
     * pond as plain kept columns (no banks to hold) is cut under its own water: the check can fail.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aPondKeepsItsBanks(GameTestHelper h) {
        Scene s = pond(Kind.FLUID);
        Result r = s.plan(null, 8);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep(), "the ramp by the pond is reported too steep (" + r.steepest() + " at " + r.spotX() + "," + r.spotZ() + ")" + dump(s, a));
        h.assertTrue(banksUnder(s, a) == 0, banksUnder(s, a) + " banks are cut under the pond's surface" + dump(s, a));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, stepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, null));
        expect(h, s, a, walkProblem(s, a, s.at(12, 12), false));
        h.assertTrue(a[s.at(7, 12)] < 2, "the ramp isn't cut beside the pond at all: the scene shows nothing" + dump(s, a));
        // the control: the same pond, as kept columns
        Scene plain = pond(Kind.KEPT);
        int[] b = plain.plan(null, 8).height();
        h.assertTrue(banksUnder(plain, b) > 0, "with no banks to hold, no bank was cut under the water: the check can't fail" + dump(plain, b));
        h.succeed();
    }

    /** A pad 8 columns in from the border, the land at 2 with a pond of 3x3 at grid (2..4, 11..13); the pad is held at 0. */
    private static Scene pond(Kind kind) {
        Scene s = flat(Reshape.frame(new Rect(50, 50, 58, 58), 8), 2);
        for (int gz = 11; gz <= 13; gz++) {
            for (int gx = 2; gx <= 4; gx++) {
                s.kinds()[s.at(gx, gz)] = kind;
            }
        }
        return s;
    }

    /** How many free columns beside the pond (4-neighbours of its cells) are lower than the water. */
    private static int banksUnder(Scene s, int[] a) {
        int n = 0;
        for (int gz = 11; gz <= 13; gz++) {
            for (int gx = 2; gx <= 4; gx++) {
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int i = s.at(gx + d[0], gz + d[1]);
                    if (s.kinds()[i] == Kind.FREE && a[i] < s.ground()[s.at(gx, gz)]) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** A pad at 0 with flat land to the west and a cliff 16 high beginning 6 columns east of its edge. */
    private static Scene cliff(int margin) {
        Rect pad = new Rect(100, 100, 108, 108);
        Frame f = Reshape.frame(pad, margin);
        Scene s = flat(f, 0);
        for (int gz = 0; gz < s.h(); gz++) {
            for (int gx = 0; gx < s.w(); gx++) {
                if (f.x0() + gx >= pad.x1() + 7) {
                    s.ground()[s.at(gx, gz)] = 16;
                }
            }
        }
        return s;
    }

    /**
     * A cliff 16 high, 6 columns from the pad's edge. Short of the widest margin no ramp of 1 a column meets it: reported too
     * steep, with a spot on the cliff's side that is a free column, so the caller widens. At the widest (12), where
     * widening ends, the ramp is banked into the cliff instead of refused: walkable in itself, a walk leads out to the land
     * (west, where it is flat), and the bank's height is reported. The first margin for a gap of 16 is the widest.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aCliffTooHighToRampIsBankedAtTheWidest(GameTestHelper h) {
        h.assertTrue(Reshape.firstMargin(16) == Reshape.MARGIN_MAX && Reshape.widen(Reshape.MARGIN_MAX) == Reshape.MARGIN_MAX,
                "the first margin for a gap of 16 is " + Reshape.firstMargin(16) + ", and widening the widest gives " + Reshape.widen(Reshape.MARGIN_MAX));
        Scene narrow = cliff(10);
        Result n = narrow.plan(null, 10);
        h.assertTrue(n.tooSteep() && !n.seamed() && n.steepest() >= 2, "short of the widest margin the cliff was not reported too steep (steepest "
                + n.steepest() + ", banked " + n.seamed() + ")" + dump(narrow, n.height()));
        h.assertTrue(n.spotX() >= 0 && n.spotZ() >= 0, "no spot for the too-steep ramp: " + n.spotX() + "," + n.spotZ());
        int spot = narrow.at(n.spotX(), n.spotZ());
        int spotX = narrow.f().x0() + n.spotX();
        h.assertTrue(narrow.kinds()[spot] == Kind.FREE && spotX > 108 && spotX <= 115,
                "the spot " + narrow.where(spot) + " (" + narrow.kinds()[spot] + ") isn't a free column on the cliff's side");
        Scene s = cliff(Reshape.MARGIN_MAX);
        Result r = s.plan(null, Reshape.MARGIN_MAX);
        int[] a = r.height();
        h.assertTrue(r.seamed() && !r.tooSteep() && r.margin() == Reshape.MARGIN_MAX && r.spotX() < 0,
                "at the widest margin the cliff was not banked (banked " + r.seamed() + ", steepest " + r.steepest() + ")" + dump(s, a));
        expect(h, s, a, rampStepProblem(s, a));
        expect(h, s, a, walkOutProblem(s, a, s.at(16, 16)));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, bankProblem(s, a));
        h.assertTrue(r.seamSteepest() >= 2, "the bank into the cliff is reported as " + r.seamSteepest() + ": the scene should leave one" + dump(s, a));
        h.succeed();
    }

    /**
     * The land falling away 3 a column east of a pad on flat ground: at the widest margin it is built out over, an embankment
     * coming down 1 a column from the pad (filled, never dug), and the flat land beside it is left alone. (A ramp that only
     * cut dug a trench down through the flat land toward the drop; one held to the whole border heaped the ramp over the
     * flat land beside a hill: the review of 81f1f2a.)
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aDropIsBuiltOutOverAndFlatLandLeftAlone(GameTestHelper h) {
        Frame f = Reshape.frame(new Rect(0, 0, 10, 10), new Rect(-2, 4, -1, 6), Side.WEST, Reshape.MARGIN_MAX);
        Scene s = flat(f, 0);
        for (int gz = 0; gz < s.h(); gz++) {
            for (int gx = 0; gx < s.w(); gx++) {
                s.ground()[s.at(gx, gz)] = -Math.max(0, Math.min(40, 3 * (f.x0() + gx - 11)));
            }
        }
        Result r = s.plan(null, Reshape.MARGIN_MAX);
        int[] a = r.height();
        h.assertTrue(r.seamed() && !r.tooSteep(), "the drop was not built out over (banked " + r.seamed() + ", too steep " + r.tooSteep() + ")" + dump(s, a));
        expect(h, s, a, rampStepProblem(s, a));
        expect(h, s, a, bankProblem(s, a));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, walkOutProblem(s, a, s.at(s.w() / 2, s.h() / 2)));
        int filled = 0;
        for (int i = 0; i < a.length; i++) {
            filled += s.kinds()[i] == Kind.FREE && a[i] > s.ground()[i] ? 1 : 0;
        }
        h.assertTrue(filled > 0 && r.fillBlocks() > 0, "nothing was filled: the scene shows no embankment" + dump(s, a));
        // an embankment, not a platform: along the pad's middle row it comes down a block a column from the pad's east edge
        // (a flat platform at the floor, walled 33 high at the border, passed every check above: the review of ae64922)
        int row = s.at(0, s.h() / 2) / s.w();
        for (int d = 2; 10 + d < f.x0() + s.w() - 1; d++) {                  // world x 10 + d, short of the border
            int i = s.at(10 - f.x0() + d, row);
            if (s.kinds()[i] == Kind.FREE) {
                h.assertTrue(a[i] <= s.floorY() - d + 1, "the embankment stands " + a[i] + " " + d + " columns out from the pad, "
                        + "not coming down: a platform, not a ramp" + dump(s, a));
            }
        }
        h.succeed();
    }

    /**
     * Flat land off the floor's height, beside a hill and beside a chasm. With the floor 5 over flat land and a hill rising 3 a
     * column to 40 east, and the mirror (the floor 5 under it, a chasm falling to 40 under), the flat land more than its
     * own gap to the floor from the pad and the slope is left as it is: every column moves only toward the floor, and only
     * as far as it must to walk. (The review of ae64922: a smooth fill from the hill heaped some 140 far columns up to the
     * floor, and the mirror dug them down.)
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void flatLandOffTheFloorIsLeftAloneBesideAHillOrAChasm(GameTestHelper h) {
        for (int sign : new int[]{1, -1}) {
            Frame f = Reshape.frame(new Rect(0, 0, 10, 10), new Rect(-2, 4, -1, 6), Side.WEST, Reshape.MARGIN_MAX);
            Scene s = new Scene(f, new int[f.w() * f.h()], 5 * sign);
            for (int gz = 0; gz < s.h(); gz++) {
                for (int gx = 0; gx < s.w(); gx++) {
                    s.ground()[s.at(gx, gz)] = sign * Math.min(40, 3 * Math.max(0, f.x0() + gx - 11));
                }
            }
            Result r = s.plan(null, Reshape.MARGIN_MAX);
            int[] a = r.height();
            String which = sign > 0 ? "beside the hill (floor 5 over the flat)" : "beside the chasm (floor 5 under the flat)";
            h.assertTrue(!r.tooSteep(), which + ": too steep (" + r.steepest() + ")" + dump(s, a));
            expect(h, s, a, rampStepProblem(s, a));
            int moved = 0;
            String first = null;
            for (int i = 0; i < a.length; i++) {
                int x = f.x0() + i % s.w();
                int z = f.z0() + i / s.w();
                int fromPad = Math.max(0, Math.max(-x, x - 10)) + Math.max(0, Math.max(-z, z - 10));
                int fromSlope = 11 - x;
                if (s.kinds()[i] == Kind.FREE && s.ground()[i] == 0 && fromPad > 6 && fromSlope > 6 && a[i] != 0) {
                    moved++;
                    first = first == null ? s.where(i) + " to " + a[i] : first;
                }
            }
            h.assertTrue(moved == 0, which + ": " + moved + " columns of far flat land were moved, the first " + first + dump(s, a));
        }
        h.succeed();
    }

    /**
     * Flat land beside a rise to 16 east of the pad: the rise is cut into a ramp of 1 a column from the pad, and the flat
     * land north and south of it, and the level border round it, are left alone: every free column beside a border column at
     * the floor is at the floor. (The review of 81f1f2a: the ramp stood 5 over flat land beside the level border.)
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aRiseIsCutIntoAndTheFlatBesideItLeftLevel(GameTestHelper h) {
        Frame f = Reshape.frame(new Rect(0, 0, 10, 10), new Rect(-2, 4, -1, 6), Side.WEST, Reshape.MARGIN_MAX);
        Scene s = flat(f, 0);
        for (int gz = 0; gz < s.h(); gz++) {
            for (int gx = 0; gx < s.w(); gx++) {
                s.ground()[s.at(gx, gz)] = Math.max(0, Math.min(16, 3 * (f.x0() + gx - 11)));
            }
        }
        Result r = s.plan(null, Reshape.MARGIN_MAX);
        int[] a = r.height();
        h.assertTrue(r.seamed() && !r.tooSteep(), "the rise was not cut into (banked " + r.seamed() + ", too steep " + r.tooSteep() + ")" + dump(s, a));
        expect(h, s, a, rampStepProblem(s, a));
        expect(h, s, a, bankProblem(s, a));
        for (int z = 0; z < s.h(); z++) {
            for (int x = 0; x < s.w(); x++) {
                int b = s.at(x, z);
                if (s.kinds()[b] != Kind.BORDER || s.ground()[b] != 0) {
                    continue;
                }
                for (int j : new int[]{x > 0 ? b - 1 : -1, x < s.w() - 1 ? b + 1 : -1, z > 0 ? b - s.w() : -1, z < s.h() - 1 ? b + s.w() : -1}) {
                    if (j >= 0 && s.kinds()[j] == Kind.FREE) {
                        h.assertTrue(Math.abs(a[j]) <= 1, "beside the level border at " + s.where(b) + " the ramp stands " + a[j] + dump(s, a));
                    }
                }
            }
        }
        h.succeed();
    }

    /**
     * A hillside rising 2 a column (steeper than anything walks) under a pad at its middle: no margin meets it level, and
     * before the bank at the widest every such site was refused. At the widest it is terraced: the ramp is walkable in itself,
     * cut into the hill on one side and built out on the other, and a walk leads out to the land along the slope.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aHillsideSteeperThanAWalkIsTerraced(GameTestHelper h) {
        Scene narrow = hill(8);
        h.assertTrue(narrow.plan(null, 8).tooSteep(), "a slope of 2 a column fitted a margin of 8: the scene shows nothing");
        Scene s = hill(Reshape.MARGIN_MAX);
        Result r = s.plan(null, Reshape.MARGIN_MAX);
        int[] a = r.height();
        h.assertTrue(r.seamed() && !r.tooSteep(), "the hillside was not terraced at the widest margin (banked " + r.seamed()
                + ", steepest " + r.steepest() + ")" + dump(s, a));
        expect(h, s, a, rampStepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, null));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, walkOutProblem(s, a, s.at(s.w() / 2, s.h() / 2)));
        h.succeed();
    }

    /**
     * The second pass (kept things set aside) still keeps a pond's banks: land at 6 round a pad cut to 0, a post by the pad
     * that sends the plan to the second pass, and a pond of surface 6 across the margin. Every bank stays at the water.
     * (The review of 2cce8c2: with no floors in the second pass, five banks came out under 6.)
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void theSecondPassStillKeepsThePondsBanks(GameTestHelper h) {
        Scene s = flat(Reshape.frame(new Rect(0, 0, 8, 8), 12), 6);
        s.kinds()[s.at(22, 16)] = Kind.KEPT;
        for (int gz = 15; gz <= 17; gz++) {
            for (int gx = 2; gx <= 4; gx++) {
                s.kinds()[s.at(gx, gz)] = Kind.FLUID;
            }
        }
        Result r = s.plan(null, 12);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep() && r.heldSteepest() >= 2, "the scene didn't take the second pass (too steep " + r.tooSteep()
                + ", the post stands " + r.heldSteepest() + " over the ramp)" + dump(s, a));
        int under = 0;
        for (int gz = 15; gz <= 17; gz++) {
            for (int gx = 2; gx <= 4; gx++) {
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int i = s.at(gx + d[0], gz + d[1]);
                    under += s.kinds()[i] == Kind.FREE && a[i] < 6 ? 1 : 0;
                }
            }
        }
        h.assertTrue(under == 0, under + " of the pond's banks are cut under its water in the second pass" + dump(s, a));
        expect(h, s, a, walkedStepProblem(s, a));
        h.succeed();
    }

    /**
     * A pond is walked round as a kept thing is: a pad raised to 6 over land at 0, and a pond (surface 0) one column from its
     * edge. The ramp can't come down to the water within a column, and no margin changes that: it goes round, and the step
     * down to the pond is reported, not refused. (Before the second pass and the repair: too steep, 3. Either one now
     * carries it, so this shows the behaviour, not which of them gives it.)
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aPondBesideARaisedPadIsWalkedRound(GameTestHelper h) {
        Frame f = Reshape.frame(new Rect(0, 0, 8, 8), 10);
        Scene s = new Scene(f, new int[f.w() * f.h()], 6);
        for (int gz = 13; gz <= 15; gz++) {
            for (int gx = 20; gx <= 22; gx++) {
                s.kinds()[s.at(gx, gz)] = Kind.FLUID;
            }
        }
        Result r = s.plan(null, 10);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep() && r.steepest() <= 1 && r.heldSteepest() >= 2, "the pond by the raised pad: too steep "
                + r.tooSteep() + " (" + r.steepest() + "), it stands " + r.heldSteepest() + " under the ramp" + dump(s, a));
        expect(h, s, a, walkedStepProblem(s, a));
        expect(h, s, a, heldProblem(s, a));
        h.succeed();
    }

    /**
     * Beside a tall post the speck pass looks past the post as the clamp does: land 4 round a pad at 0, a post of 7 three
     * columns from its edge. No free column is left a bump over all of its walked neighbours (the review of 2cce8c2: with
     * the speck pass counting the post, the column east of it was kept a block over its three walked neighbours).
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void noBumpIsLeftBesideATallPost(GameTestHelper h) {
        Scene s = flat(Reshape.frame(new Rect(0, 0, 8, 8), 10), 4);
        int post = s.at(21, 11);
        s.kinds()[post] = Kind.KEPT;
        s.ground()[post] = 7;
        Result r = s.plan(null, 10);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep(), "the post made the ramp too steep (" + r.steepest() + ")" + dump(s, a));
        for (int z = 1; z < s.h() - 1; z++) {
            for (int x = 1; x < s.w() - 1; x++) {
                int i = s.at(x, z);
                if (s.kinds()[i] != Kind.FREE) {
                    continue;
                }
                int over = 0;
                int walkedNb = 0;
                for (int j : new int[]{i - 1, i + 1, i - s.w(), i + s.w()}) {
                    if (!round(s.kinds()[j])) {
                        walkedNb++;
                        over += a[i] > a[j] ? 1 : 0;
                    }
                }
                h.assertTrue(walkedNb == 0 || over < walkedNb, "the column " + s.where(i) + " is " + a[i]
                        + ", a bump over all " + walkedNb + " of its walked neighbours" + dump(s, a));
            }
        }
        h.succeed();
    }

    /** A frame needs a ring and a ramp: a margin under 2 is refused, with or without a landing. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aMarginUnderTwoIsRefused(GameTestHelper h) {
        Rect pad = new Rect(0, 0, 8, 8);
        for (int m : new int[]{0, 1}) {
            boolean plain = false;
            boolean landed = false;
            try {
                Reshape.frame(pad, m);
            } catch (IllegalArgumentException e) {
                plain = true;
            }
            try {
                Reshape.frame(pad, new Rect(9, 3, 10, 5), Side.EAST, m);
            } catch (IllegalArgumentException e) {
                landed = true;
            }
            h.assertTrue(plain && landed, "a margin of " + m + " was taken (plain " + !plain + ", with a landing " + !landed + ")");
        }
        h.assertTrue(Reshape.frame(pad, 2).w() == 13, "a margin of 2 was refused or laid out wrong");
        h.succeed();
    }

    /** A 9x9 pad at 0 on land rising 2 a column eastward, 0 at the pad's middle. */
    private static Scene hill(int margin) {
        Scene s = flat(Reshape.frame(new Rect(-4, -4, 4, 4), margin), 0);
        for (int gz = 0; gz < s.h(); gz++) {
            for (int gx = 0; gx < s.w(); gx++) {
                s.ground()[s.at(gx, gz)] = 2 * (s.f().x0() + gx);
            }
        }
        return s;
    }

    /** The same arrays give the same heights and counts, twice over and from copies; and the input isn't touched. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aReshapeIsDeterministic(GameTestHelper h) {
        Scene s = hillside();
        int[] ground = s.ground().clone();
        Kind[] kinds = s.kinds().clone();
        Result first = s.plan(null, 6);
        Result second = s.plan(null, 6);
        Result fresh = hillside().plan(null, 6);
        h.assertTrue(Arrays.equals(first.height(), second.height()) && Arrays.equals(first.height(), fresh.height()),
                "two runs on the same arrays gave different heights");
        h.assertTrue(first.steepest() == second.steepest() && first.cutBlocks() == fresh.cutBlocks() && first.fillBlocks() == fresh.fillBlocks()
                && first.spotX() == second.spotX() && first.spotZ() == second.spotZ(), "two runs gave different counts: " + first + " and " + second);
        h.assertTrue(Arrays.equals(s.beforeClamp(null), hillside().beforeClamp(null)), "the field before the clamp differs between runs");
        h.assertTrue(Arrays.equals(ground, s.ground()) && Arrays.equals(kinds, s.kinds()), "the input arrays were changed");
        h.succeed();
    }

    /**
     * Natural ground is rough: 3 heights, at random, round a pad. What the blur, the rounding and the clamp leave as
     * 1-block pits and bumps beside the ring is levelled (these 5 scenes had 1 to 3 without it), and nothing else is
     * disturbed: the ramp is still walkable and no step is over 1.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aRoughBorderLeavesNoPitsOrBumps(GameTestHelper h) {
        for (long seed : new long[]{18, 59, 111, 182, 271}) {
            Scene s = flat(Reshape.frame(new Rect(0, 0, 8, 8), 6), 0);
            Random rnd = new Random(seed);
            for (int i = 0; i < s.ground().length; i++) {
                s.ground()[i] = rnd.nextInt(3) - 1;
            }
            Result r = s.plan(null, 6);
            int[] a = r.height();
            h.assertTrue(!r.tooSteep(), "seed " + seed + ": reported too steep (" + r.steepest() + ")" + dump(s, a));
            expect(h, s, a, stepProblem(s, a));
            expect(h, s, a, speckProblem(s, a, null));
            expect(h, s, a, heldProblem(s, a));
            expect(h, s, a, walkProblem(s, a, s.at(10, 10), false));
        }
        h.succeed();
    }

    /**
     * A column's floor (a pond's bank): a line of columns in the margin that the ramp would cut under the land's 2 are held
     * at 2, and the rest of the ramp is walkable round them. Without the floors they are cut: the check can fail.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aColumnsFloorIsNeverCutUnder(GameTestHelper h) {
        Scene s = flat(Reshape.frame(new Rect(-40, -40, -32, -32), 6), 2);
        int[] floors = new int[s.ground().length];
        Arrays.fill(floors, Reshape.NO_FLOOR);
        for (int gz = 5; gz <= 15; gz++) {
            floors[s.at(3, gz)] = 2;
        }
        int[] a = s.plan(floors, 6).height();
        for (int gz = 5; gz <= 15; gz++) {
            h.assertTrue(a[s.at(3, gz)] >= 2, "the column " + s.where(s.at(3, gz)) + " is " + a[s.at(3, gz)] + ", under its floor 2" + dump(s, a));
        }
        expect(h, s, a, stepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, floors));
        expect(h, s, a, walkProblem(s, a, s.at(10, 10), false));
        int[] loose = s.plan(null, 6).height();
        boolean cut = false;
        for (int gz = 5; gz <= 15; gz++) {
            cut |= loose[s.at(3, gz)] < 2;
        }
        h.assertTrue(cut, "with no floors the line was not cut either: the check can't fail" + dump(s, loose));
        // a lone column floored at 3 in land of 2: the clamp brings its neighbours up to 2, and it stands 1 over all four,
        // a bump the speck pass would level to 2 but for its floor
        Scene lone = flat(Reshape.frame(new Rect(-40, -40, -32, -32), 6), 2);
        int[] one = new int[lone.ground().length];
        Arrays.fill(one, Reshape.NO_FLOOR);
        one[lone.at(2, 2)] = 3;
        int[] b = lone.plan(one, 6).height();
        h.assertTrue(b[lone.at(2, 2)] >= 3, "a lone column floored at 3 came out " + b[lone.at(2, 2)] + dump(lone, b));
        expect(h, lone, b, stepProblem(lone, b));
        expect(h, lone, b, speckProblem(lone, b, one));
        h.succeed();
    }

    /**
     * A player's post, as high as the land (6), 2 columns from a pad cut down to 0: the ramp can't come within 1 of it, and
     * no margin changes that (the post is where it is). The ramp goes round it and the post stands on its own column: not
     * too steep, walkable on the ground walked, the post unchanged, and how far it stands over the ramp reported. (Before
     * the second pass this was reported too steep at every margin: the review of f1a38a0.)
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aPostByADeepCutStandsOnItsOwnColumn(GameTestHelper h) {
        Scene s = flat(Reshape.frame(new Rect(200, 200, 208, 208), 10), 6);
        int post = s.at(20, 14);                                 // the pad is grid 10..18: 2 columns east of its edge
        s.kinds()[post] = Kind.KEPT;
        Result r = s.plan(null, 10);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep() && r.steepest() <= 1, "a post by the cut made the ramp too steep (" + r.steepest() + " at "
                + r.spotX() + "," + r.spotZ() + "): a kept column is never a refusal" + dump(s, a));
        expect(h, s, a, walkedStepProblem(s, a));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, walkProblem(s, a, s.at(14, 14), true));
        h.assertTrue(a[post] == 6 && r.heldSteepest() >= 2, "the post is " + a[post] + " and stands " + r.heldSteepest()
                + " over the ramp: the scene should leave it standing well over it" + dump(s, a));
        h.succeed();
    }

    /**
     * A pond on a rise (surface 3, the rise 7x7 at 3) in land of 0 round a pad at 0: the ramp falls away from it on every
     * side, so each of its four banks would be cut under the water but for their floors. Checked side by side; the same
     * pond as kept columns is cut on every side (the control).
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aPondOnARiseKeepsAllFourBanks(GameTestHelper h) {
        Scene s = rise(Kind.FLUID);
        Result r = s.plan(null, 10);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep(), "the ramp round the pond is reported too steep (" + r.steepest() + ")" + dump(s, a));
        String[] sides = {"west", "east", "north", "south"};
        int[] under = banksUnderBySide(s, a);
        for (int k = 0; k < 4; k++) {
            h.assertTrue(under[k] == 0, "the pond's " + sides[k] + " bank is cut under its surface in " + under[k] + " columns" + dump(s, a));
        }
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, walkedStepProblem(s, a));
        Scene plain = rise(Kind.KEPT);
        int[] b = plain.plan(null, 10).height();
        int[] cut = banksUnderBySide(plain, b);
        for (int k = 0; k < 4; k++) {
            h.assertTrue(cut[k] > 0, "as kept columns the pond's " + sides[k] + " bank wasn't cut: that side proves nothing" + dump(plain, b));
        }
        h.succeed();
    }

    /** A 5x5 pad at 0 with a margin of 10, land of 0, and a 7x7 rise of 3 at grid (2..8, 8..14) round a 3x3 pond (4..6, 10..12). */
    private static Scene rise(Kind kind) {
        Scene s = flat(Reshape.frame(new Rect(300, 300, 304, 304), 10), 0);
        for (int gz = 8; gz <= 14; gz++) {
            for (int gx = 2; gx <= 8; gx++) {
                s.ground()[s.at(gx, gz)] = 3;
            }
        }
        for (int gz = 10; gz <= 12; gz++) {
            for (int gx = 4; gx <= 6; gx++) {
                s.kinds()[s.at(gx, gz)] = kind;
            }
        }
        return s;
    }

    /** The rise's pond banks lower than its surface (3), counted per side: west, east, north, south. */
    private static int[] banksUnderBySide(Scene s, int[] a) {
        int[] n = new int[4];
        for (int d = 10; d <= 12; d++) {
            n[0] += a[s.at(3, d)] < 3 ? 1 : 0;
            n[1] += a[s.at(7, d)] < 3 ? 1 : 0;
        }
        for (int d = 4; d <= 6; d++) {
            n[2] += a[s.at(d, 9)] < 3 ? 1 : 0;
            n[3] += a[s.at(d, 13)] < 3 ? 1 : 0;
        }
        return n;
    }

    /**
     * The frame for a pad, its landing and a margin: the size (the margin on every side and 2 more on the door's), the ring,
     * the pad and landing held, and the world's columns mapped to cells. A plan on it takes the landing to the floor and
     * ramps out from it, walkable.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aFrameLaysOutPadLandingAndRing(GameTestHelper h) {
        Rect pad = new Rect(10, 20, 18, 28);
        Rect landing = new Rect(13, 29, 15, 30);                 // 3 wide and 2 out, on the south side
        Frame f = Reshape.frame(pad, landing, Side.SOUTH, 5);
        h.assertTrue(f.x0() == 5 && f.z0() == 15 && f.w() == 19 && f.h() == 23,
                "the frame is " + f.w() + "x" + f.h() + " from " + f.x0() + "," + f.z0() + ", not 19x23 from 5,15");
        int pads = 0;
        int ring = 0;
        int free = 0;
        for (int gz = 0; gz < f.h(); gz++) {
            for (int gx = 0; gx < f.w(); gx++) {
                Kind k = f.kinds()[gz * f.w() + gx];
                boolean onRing = gx == 0 || gz == 0 || gx == f.w() - 1 || gz == f.h() - 1;
                h.assertTrue((k == Kind.BORDER) == onRing, "the column " + (f.x0() + gx) + "," + (f.z0() + gz) + " is " + k + ", ring or not " + onRing);
                boolean inPad = k == Kind.PAD;
                boolean shouldBe = inside(pad, f.x0() + gx, f.z0() + gz) || inside(landing, f.x0() + gx, f.z0() + gz);
                h.assertTrue(inPad == shouldBe, "the column " + (f.x0() + gx) + "," + (f.z0() + gz) + " is " + k + ", in the pad or landing is " + shouldBe);
                pads += inPad ? 1 : 0;
                ring += k == Kind.BORDER ? 1 : 0;
                free += k == Kind.FREE ? 1 : 0;
            }
        }
        h.assertTrue(pads == 81 + 6 && ring == 2 * 19 + 2 * 21 && free == 19 * 23 - pads - ring,
                "the frame has " + pads + " pad, " + ring + " ring and " + free + " free columns");
        h.assertTrue(f.index(5, 15) == 0 && f.index(23, 37) == 19 * 23 - 1 && f.index(24, 37) == -1 && f.index(4, 15) == -1
                        && f.index(5, 38) == -1 && f.index(10, 20) == 5 * 19 + 5,
                "index maps the world's columns to the wrong cells");
        h.assertTrue(Reshape.frame(pad, 5).h() == 19 && Reshape.frame(pad, 5).w() == 19, "with no landing the frame is not 19x19");
        // the land rises 2 along the door's side, and the pad is held level with the landing
        Scene s = new Scene(f, new int[f.w() * f.h()], 0);
        for (int gz = 0; gz < f.h(); gz++) {
            Arrays.fill(s.ground(), gz * f.w(), (gz + 1) * f.w(), gz > 17 ? 2 : 0);
        }
        Result r = s.plan(null, 5);
        int[] a = r.height();
        h.assertTrue(!r.tooSteep(), "the ramp from the landing is reported too steep (" + r.steepest() + " at " + r.spotX() + "," + r.spotZ() + ")" + dump(s, a));
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, stepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, null));
        expect(h, s, a, walkProblem(s, a, s.at(14 - f.x0(), 29 - f.z0()), false));
        h.succeed();
    }

    private static boolean inside(Rect r, int x, int z) {
        return x >= r.x0() && x <= r.x1() && z >= r.z0() && z <= r.z1();
    }

    /**
     * The caller's loop: a pad sunk 5 below flat land, tried at the least margin, is too steep, and widened by 2 until it
     * fits. What it ends on ramps 1 a column and reports the margin it was solved with; the first margin from a gap is the
     * gap + 2, within the least and the most.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "reshape")
    public static void aTooNarrowMarginIsWidenedUntilItFits(GameTestHelper h) {
        h.assertTrue(Reshape.firstMargin(0) == Reshape.MARGIN_MIN && Reshape.firstMargin(1) == Reshape.MARGIN_MIN
                && Reshape.firstMargin(5) == 7 && Reshape.firstMargin(99) == Reshape.MARGIN_MAX && Reshape.widen(3) == 5 && Reshape.widen(11) == 12,
                "firstMargin(0, 1, 5, 99) is " + Reshape.firstMargin(0) + ", " + Reshape.firstMargin(1) + ", " + Reshape.firstMargin(5) + ", "
                        + Reshape.firstMargin(99) + " and widen(3, 11) " + Reshape.widen(3) + ", " + Reshape.widen(11));
        int margin = Reshape.MARGIN_MIN;
        int tries = 0;
        Scene s;
        Result r;
        while (true) {
            s = flat(Reshape.frame(new Rect(0, 0, 8, 8), margin), 5);
            r = s.plan(null, margin);
            tries++;
            if (!r.tooSteep() || margin == Reshape.MARGIN_MAX) {
                break;
            }
            margin = Reshape.widen(margin);
        }
        h.assertTrue(tries >= 2, "the least margin already fits a drop of 5: the scene shows nothing of the loop");
        h.assertTrue(!r.tooSteep() && r.margin() == margin && margin >= 5 && margin <= Reshape.MARGIN_MAX,
                "the loop ended at a margin of " + margin + " (reported " + r.margin() + "), too steep: " + r.tooSteep());
        int[] a = r.height();
        expect(h, s, a, heldProblem(s, a));
        expect(h, s, a, stepProblem(s, a));
        expect(h, s, a, speckProblem(s, a, null));
        expect(h, s, a, walkProblem(s, a, s.at(margin + 4, margin + 4), false));
        h.succeed();
    }
}
