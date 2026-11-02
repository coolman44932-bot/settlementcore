package claude.settlementcore.room;

import java.util.Arrays;

/**
 * Reshaping the land round a raised building: the height solve (docs/plans/surface-builder.md, section 9), pure. Arrays in,
 * arrays out: no Level, no block, no Minecraft class. {@code Siting} reads the world into it and cuts and fills from what
 * it answers.
 *
 * <p>A port of blockwright's terrain/terraform.ts (mode blend, without its noise) over field.ts, so what was looked at
 * and tuned there is what runs here:
 * <ol>
 *   <li>The held columns keep their heights: the pad at the floor, the rest at their own.</li>
 *   <li>The others are solved as a harmonic field (Gauss-Seidel, coarse to fine). It has no interior extremum, so the
 *       ground between pad and land only rises or falls: no terrace, ring or pit.</li>
 *   <li>Blur, radius 1, held columns kept; round to whole blocks; then clamp every 4-neighbour step to
 *       {@link #MAX_STEP}, never under a column's floor.</li>
 * </ol>
 * A margin that is too narrow leaves a step the clamp can't take out; that is reported ({@link Result#tooSteep()}), and
 * the caller widens ({@link #widen}) and solves again. Deterministic: the same arrays give the same heights.
 *
 * <p>Grids are row-major with z as the outer axis, as in field.ts: the column (x, z) is cell {@code z * w + x}.
 */
public final class Reshape {

    /** The steepest step between 4-neighbours that is walked without a jump. */
    public static final int MAX_STEP = 1;
    /** A margin is at least this wide... */
    public static final int MARGIN_MIN = 3;
    /** ...and at most this: past it a site is refused as too steep to ramp. */
    public static final int MARGIN_MAX = 12;
    /** How much each widening adds. */
    public static final int MARGIN_STEP = 2;
    /** The door's side gets this much more margin, so its landing has the gentlest ramp. */
    public static final int DOOR_EXTRA = 2;

    /** No floor: a column that may be cut as low as it likes. */
    public static final int NO_FLOOR = Integer.MIN_VALUE;

    // field.ts's defaults, kept: sweeps per level, the residual it stops at, its cap, and the smallest grid still coarsened
    private static final int SWEEPS = 12;
    private static final double TOLERANCE = 1e-3;
    private static final int MAX_SWEEPS = 4000;
    private static final int CASCADE_MIN = 6;
    private static final int BLUR_RADIUS = 1;
    /** field.ts's clamp budget: cell updates it may spend, so a big grid isn't a thousand times a small one. */
    private static final int CLAMP_BUDGET = 30_000_000;

    private Reshape() {
    }

    /** What a column is to the solve. */
    public enum Kind {
        /** Solved. */
        FREE,
        /** The pad (box, berm, landing): held at the floor height. */
        PAD,
        /** The area's outermost ring: held at its own height, so the ramp meets the land with no seam. */
        BORDER,
        /** Something of the player's, or another building's: held at its own height, and the ramp flows round it. */
        KEPT,
        /**
         * Water, lava, ice: held at its own height, which is the fluid's surface, and its 4-neighbours are never lower
         * than that (a bank is never cut under the water it holds).
         */
        FLUID;

        public boolean held() {
            return this != FREE;
        }
    }

    /** A side of the area, as the compass (north is -z, east is +x). */
    public enum Side {
        NORTH, EAST, SOUTH, WEST
    }

    /** A rectangle of columns, both corners in it. */
    public record Rect(int x0, int z0, int x1, int z1) {
        public Rect {
            if (x1 < x0 || z1 < z0) {
                throw new IllegalArgumentException("an empty rectangle: " + x0 + "," + z0 + " to " + x1 + "," + z1);
            }
        }

        Rect union(Rect o) {
            return new Rect(Math.min(x0, o.x0), Math.min(z0, o.z0), Math.max(x1, o.x1), Math.max(z1, o.z1));
        }
    }

    /**
     * The grid for one margin: where it stands in the world, its size, and each column's kind (the pad and landing PAD, the
     * outer ring BORDER, the rest FREE). The caller marks the KEPT and FLUID columns in {@code kinds} it reads from the
     * world, and gives {@link #plan} the ground heights. (Arrays in a record compare by identity: use the fields.)
     */
    public record Frame(int x0, int z0, int w, int h, Kind[] kinds) {
        /** The cell of the world column (x, z); -1 outside the frame. */
        public int index(int x, int z) {
            int gx = x - x0;
            int gz = z - z0;
            return gx < 0 || gz < 0 || gx >= w || gz >= h ? -1 : gz * w + gx;
        }
    }

    /**
     * What a solve answers.
     *
     * @param height     the new height of every column: whole blocks; the held columns exactly as they were held (PAD at the
     *                   floor, the rest at their own)
     * @param margin     the margin the frame was built with (as passed to {@link #plan}), so the caller knows which pass this is
     * @param steepest   the steepest step left on the ground walked: between a FREE column and a FREE, PAD or BORDER
     *                   4-neighbour (two held columns are the land as it is, and not ours to shape; a KEPT or FLUID column
     *                   is walked round, not over)
     * @param spotX      where the steepest step is, when it is too steep: the FREE column of the pair, as a grid column (add
     *                   the frame's origin for the world's); -1 when it isn't too steep
     * @param spotZ      as spotX
     * @param cutColumns columns lower than they were, the pad's included
     * @param fillColumns columns higher than they were
     * @param cutBlocks  blocks taken off the tops of those columns, all together
     * @param fillBlocks blocks laid on the tops of the filled ones
     * @param heldSteepest the steepest step between a FREE column and a KEPT or FLUID neighbour: how far a kept thing now
     *                   stands over (or under) the ramp beside it. Never a refusal: the ramp goes round it.
     * @param seamSteepest the steepest step between a FREE column and the border ring: where the ramp meets the land
     * @param seamed     the land is steeper than a walker climbs, so at the widest margin the ramp meets it with a bank (cut
     *                   into it or built out over it) instead of level: {@code steepest} is then the ramp's own (a FREE
     *                   column against a FREE or PAD one), and the caller must find a walk from the door out to the land
     *                   (Siting#walk), refusing the site only where there is none
     */
    public record Result(int[] height, int margin, int steepest, int spotX, int spotZ,
                         int cutColumns, int fillColumns, int cutBlocks, int fillBlocks, int heldSteepest,
                         int seamSteepest, boolean seamed) {
        /**
         * A step over {@link #MAX_STEP} is left on the ground walked (a FREE column against a FREE, PAD or, unless
         * {@link #seamed}, BORDER neighbour): widen the margin.
         */
        public boolean tooSteep() {
            return steepest > MAX_STEP;
        }
    }

    /** The first margin to try, from the largest gap between the pad's height and the land's at the area's edge: gap + 2. */
    public static int firstMargin(int gap) {
        return firstMargin(gap, MARGIN_MAX);
    }

    /** {@link #firstMargin(int)} for a site whose widest margin is {@code max} (a big building's: Siting's AREA_MAX holds it in). */
    public static int firstMargin(int gap, int max) {
        return Math.max(MARGIN_MIN, Math.min(max, Math.max(0, gap) + 2));
    }

    /** The next margin to try after a too-steep one; the same again at {@link #MARGIN_MAX}, where the caller gives up. */
    public static int widen(int margin) {
        return widen(margin, MARGIN_MAX);
    }

    /** {@link #widen(int)} up to a site's own widest margin {@code max}. */
    public static int widen(int margin, int max) {
        return Math.min(max, margin + MARGIN_STEP);
    }

    /** A pad with no landing, its margin the same on every side. */
    public static Frame frame(Rect pad, int margin) {
        return frame(pad, null, null, margin);
    }

    /**
     * The frame for a pad, its landing and a margin: the pad's and the landing's bounds, {@code margin} columns of margin on
     * every side ({@link #DOOR_EXTRA} more on the door's), and a BORDER ring round the outside.
     *
     * @param landing the door's landing, level with the floor and part of the pad; null for none
     * @param door    the side of the pad the landing is on; null for none
     */
    public static Frame frame(Rect pad, Rect landing, Side door, int margin) {
        if (margin < 2) {
            throw new IllegalArgumentException("a margin of " + margin + ": the ring needs one, and the ramp one more");
        }
        Rect all = landing == null ? pad : pad.union(landing);
        int north = margin;
        int east = margin;
        int south = margin;
        int west = margin;
        if (door != null) {
            switch (door) {
                case NORTH -> north += DOOR_EXTRA;
                case EAST -> east += DOOR_EXTRA;
                case SOUTH -> south += DOOR_EXTRA;
                case WEST -> west += DOOR_EXTRA;
            }
        }
        int x0 = all.x0() - west;
        int z0 = all.z0() - north;
        int w = all.x1() - all.x0() + 1 + west + east;
        int h = all.z1() - all.z0() + 1 + north + south;
        Kind[] kinds = new Kind[w * h];
        Arrays.fill(kinds, Kind.FREE);
        for (int x = 0; x < w; x++) {
            kinds[x] = Kind.BORDER;
            kinds[(h - 1) * w + x] = Kind.BORDER;
        }
        for (int z = 0; z < h; z++) {
            kinds[z * w] = Kind.BORDER;
            kinds[z * w + w - 1] = Kind.BORDER;
        }
        stamp(kinds, w, x0, z0, pad);
        if (landing != null) {
            stamp(kinds, w, x0, z0, landing);
        }
        return new Frame(x0, z0, w, h, kinds);
    }

    private static void stamp(Kind[] kinds, int w, int x0, int z0, Rect r) {
        for (int z = r.z0(); z <= r.z1(); z++) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                kinds[(z - z0) * w + (x - x0)] = Kind.PAD;
            }
        }
    }

    /**
     * Solves one margin. Held columns keep their heights; every FREE column comes out within {@link #MAX_STEP} of each FREE,
     * PAD and BORDER 4-neighbour wherever the held columns allow it (a KEPT or FLUID one it goes round), and never under its
     * floor. Where they don't (the land rises too fast for this margin, or a pond's banks hold the ramp up) the steps left
     * are reported; at {@link #MARGIN_MAX} the land too steep to meet level is met with a bank ({@link Result#seamed}).
     *
     * @param ground the columns' current surface heights; for a FLUID column the surface of the fluid
     * @param kinds  what each column is
     * @param floors the lowest height each FREE column may be given (a pond's bank); null for none, {@link #NO_FLOOR} per
     *               column. Every FREE 4-neighbour of a FLUID column is floored at its surface as well.
     * @param floorY the height the pad is held at
     * @param margin the margin the frame was built with, reported back in the result
     */
    public static Result plan(int w, int h, int[] ground, Kind[] kinds, int[] floors, int floorY, int margin) {
        return plan(w, h, ground, kinds, floors, floorY, margin, MARGIN_MAX);
    }

    /** {@link #plan(int, int, int[], Kind[], int[], int, int)} for a site whose widest margin is {@code marginMax}: at it, the bank. */
    public static Result plan(int w, int h, int[] ground, Kind[] kinds, int[] floors, int floorY, int margin, int marginMax) {
        int[] lows = lows(w, h, ground, kinds, floors);
        double[] field = solved(w, h, ground, kinds, floorY);
        boolean[] held = held(kinds);
        Result first = settle(w, h, ground, kinds, floorY, margin, lows, field, held, null, false);
        if (!first.tooSteep()) {
            return first;
        }
        // A kept column (or a pond) the ramp can't come within 1 of: the clamp's pull toward it left a step on the ground
        // walked, and no margin fixes that (the post is where it is). Clamp again without them: the ramp goes round, and
        // the kept thing stands on its own column, over or under it. A margin too narrow for the pad and the land is left
        // too steep, and so are a pond's banks where they hold the ramp up beside the pad (no bank is cut under its water).
        boolean[] aside = new boolean[kinds.length];
        boolean any = false;
        for (int i = 0; i < kinds.length; i++) {
            aside[i] = walkedRound(kinds[i]);
            any |= aside[i];
        }
        Result best = first;
        if (any) {
            Result second = settle(w, h, ground, kinds, floorY, margin, lows, field, held, aside, false);
            best = second.steepest() < first.steepest() ? second : first;
        }
        if (!best.tooSteep() || margin < marginMax) {
            return best;                                      // too steep: the caller widens
        }
        // The widest margin, and still too steep: the land itself is steeper than a walker climbs (a hillside rising 2 a
        // column, a cliff), and no ramp of 1 a column can meet it level all round. Here the ramp is the land wherever the
        // land walks, and is cut or filled only where a walker from the pad couldn't manage it: it climbs into a hill 1 a
        // column and comes down a falling slope the same way, and flat land beside the hill is left as it is. The border is
        // not held: where the land already meets it walkably the ramp is that land and meets it level, and elsewhere the
        // step at the border is the bank, cut into the hill or built out over the slope, as a terrace is. (A smooth field
        // held to the whole ring, or to any part of the ring a ramp could reach, heaped the ramp up over the flat land to
        // meet a high edge: the review of 81f1f2a.) Whether a walk still leads out is the caller's to find (Siting#walk);
        // only a site with none is too steep.
        boolean[] banked = aside.clone();
        for (int i = 0; i < kinds.length; i++) {
            banked[i] |= kinds[i] == Kind.BORDER;
        }
        int[] a = new int[kinds.length];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (held[i]) {
                    a[i] = heldAt(kinds[i], ground[i], floorY);
                    continue;
                }
                // the land as it is, a pit of one column filled to a block under its lowest neighbour (so no crater is cut
                // round it), never under its floor
                int lowest = Integer.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    int j = neighbour(i, x, z, w, h, k);
                    if (j >= 0 && !banked[j]) {
                        lowest = Math.min(lowest, held[j] ? heldAt(kinds[j], ground[j], floorY) : ground[j]);
                    }
                }
                int land = lowest == Integer.MAX_VALUE ? ground[i] : Math.max(ground[i], lowest - MAX_STEP);
                a[i] = lows[i] == NO_FLOOR ? land : Math.max(lows[i], land);
            }
        }
        // Every column moves only toward the floor, never past it: land above it may be cut down as far as the floor and
        // land below it filled up as far as the floor, within a ramp's reach of the pad and never under a column's floor.
        // Among the walkable grounds that allows, the highest keeps the most of every hill and the lowest the most of every
        // drop: the hills are taken from the one, the drops from the other, and land at the floor stays there. So nothing
        // far away drags flat land up or down: a hill beside land under the floor is cut, and that land filled, only as far
        // as they must to meet (the review of ae64922: a smooth fill from the hill heaped flat land 5 under the floor up to
        // it over some 140 columns, and the mirror dug land over the floor down beside a chasm).
        int[][] reach = bounds(a, held, lows, w, h, banked);
        int n = a.length;
        int[] hi = new int[n];
        int[] lo = new int[n];
        for (int i = 0; i < n; i++) {
            if (held[i] || banked[i]) {
                hi[i] = a[i];
                lo[i] = a[i];
                continue;
            }
            lo[i] = Math.max(Math.min(a[i], floorY), reach[1][i]);
            hi[i] = Math.max(lo[i], Math.min(Math.max(a[i], floorY), reach[0][i]));
        }
        int[][] toward = {hi, lo};
        int[] keepHills = hi.clone();
        int[] keepDrops = lo.clone();
        relax(keepHills, held, w, h, banked, toward, true);
        relax(keepDrops, held, w, h, banked, toward, false);
        for (int i = 0; i < n; i++) {
            if (!held[i] && !banked[i]) {
                a[i] = a[i] > floorY ? keepHills[i] : a[i] < floorY ? keepDrops[i] : lo[i];   // at the floor, or a pond bank's floor over it
            }
        }
        // where a hill meets a drop the two can stand 2 apart (floor + 1 against floor - 1): lowered to walk, never past
        // the floor
        relax(a, held, w, h, banked, toward, true);
        levelSpecks(a, held, lows, w, h, banked);
        Result third = result(w, h, ground, kinds, a, margin, true);
        return third.steepest() < best.steepest() ? third : best;
    }

    /**
     * Rounds, clamps and levels the solved field; {@code aside} (null for none) are held columns the steps ignore, and
     * {@code seamed} says the border is among them (the ramp's own steps are then what counts).
     */
    private static Result settle(int w, int h, int[] ground, Kind[] kinds, int floorY, int margin, int[] lows,
                                 double[] field, boolean[] held, boolean[] aside, boolean seamed) {
        int n = w * h;
        // round before clamping: the limit is a whole-block promise about blocks that get placed, and a clamped continuous
        // field can still round into a 2-block stair
        double[] rounded = new double[n];
        for (int i = 0; i < n; i++) {
            rounded[i] = held[i] ? heldAt(kinds[i], ground[i], floorY) : Math.max(lows[i], Math.round(field[i]));
        }
        clampSteps(rounded, held, lows, w, h, aside);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = held[i] ? heldAt(kinds[i], ground[i], floorY) : (int) Math.max(lows[i], Math.round(rounded[i]));
        }
        int[] raw = out.clone();
        levelSpecks(out, held, lows, w, h, aside);
        Result r = result(w, h, ground, kinds, out, margin, seamed);
        if (!r.tooSteep()) {
            return r;
        }
        // The clamp is a heuristic: from some fields it settles on a staircase whose rows are out of step (a column between
        // 7 and 10 takes the midpoint), and leaves a step of 2 where a walkable ramp exists. Repair exactly, and keep the
        // repair only if it walks: where the held columns don't allow a ramp, the clamp's answer (and its spot) stands.
        repair(raw, held, lows, w, h, aside);
        levelSpecks(raw, held, lows, w, h, aside);
        Result fixed = result(w, h, ground, kinds, raw, margin, seamed);
        return fixed.steepest() < r.steepest() ? fixed : r;
    }

    /**
     * Makes the free columns exactly walkable wherever the held ones allow it, moving as few as it can. Over the columns the
     * steps count between (the free ones and the held ones not {@code aside}): each free column is bounded above by the
     * highest a walker's ramp can climb from the held ones (their heights plus the steps to them) and below by the lowest it
     * can come down to them, floors included. Put in those bounds, then lowered wherever it still stands more than 1 over a
     * neighbour, it is within 1 of every neighbour; its bounds keep it on every floor and within 1 of the held columns.
     * Where the bounds cross (land too steep for any ramp) the floor wins and the step stays, for the caller to report.
     */
    private static void repair(int[] a, boolean[] held, int[] lows, int w, int h, boolean[] aside) {
        int[][] reach = bounds(a, held, lows, w, h, aside);
        clampInto(a, held, aside, reach);
        relax(a, held, w, h, aside, reach, true);
    }

    /**
     * Each free column's bounds, {up, down}: the highest and lowest a walker's ramp reaches from the held columns not
     * {@code aside} (their heights plus or minus the steps to them), floors included in the lowest.
     */
    private static int[][] bounds(int[] a, boolean[] held, int[] lows, int w, int h, boolean[] aside) {
        int n = a.length;
        int[] up = new int[n];
        int[] down = new int[n];
        Arrays.fill(up, Integer.MAX_VALUE);
        Arrays.fill(down, Integer.MIN_VALUE);
        for (int i = 0; i < n; i++) {
            if (aside != null && aside[i]) {
                continue;
            }
            if (held[i]) {
                up[i] = a[i];
                down[i] = a[i];
            } else if (lows[i] != NO_FLOOR) {
                down[i] = lows[i];
            }
        }
        // the bounds spread a step a column over the free columns: Bellman-Ford sweeps, forward and back in turn
        for (boolean moved = true; moved; ) {
            moved = false;
            for (int pass = 0; pass < 2; pass++) {
                for (int s = 0; s < n; s++) {
                    int i = pass == 0 ? s : n - 1 - s;
                    if (held[i] || (aside != null && aside[i])) {
                        continue;
                    }
                    int x = i % w;
                    int z = i / w;
                    for (int k = 0; k < 4; k++) {
                        int j = neighbour(i, x, z, w, h, k);
                        if (j < 0 || (aside != null && aside[j])) {
                            continue;
                        }
                        if (up[j] != Integer.MAX_VALUE && up[j] + MAX_STEP < up[i]) {
                            up[i] = up[j] + MAX_STEP;
                            moved = true;
                        }
                        if (down[j] != Integer.MIN_VALUE && down[j] - MAX_STEP > down[i]) {
                            down[i] = down[j] - MAX_STEP;
                            moved = true;
                        }
                    }
                }
            }
        }
        return new int[][]{up, down};
    }

    /** Each free column put within its bounds (where they cross, the lower one wins: a floor is never cut under). */
    private static void clampInto(int[] a, boolean[] held, boolean[] aside, int[][] reach) {
        for (int i = 0; i < a.length; i++) {
            if (!held[i] && (aside == null || !aside[i])) {
                a[i] = Math.max(Math.min(a[i], reach[0][i]), reach[1][i]);
            }
        }
    }

    /**
     * Makes the free columns walkable by moving them one way only: {@code lower}, each column is lowered wherever it stands
     * more than 1 over a neighbour (the largest walkable ground under what it was, never under its lower bound, which already
     * walks); else each is raised wherever it stands more than 1 under one (the smallest over it, never over its upper bound).
     */
    private static void relax(int[] a, boolean[] held, int w, int h, boolean[] aside, int[][] reach, boolean lower) {
        int n = a.length;
        int[] up = reach[0];
        int[] down = reach[1];
        if (!lower) {
            for (boolean moved = true; moved; ) {
                moved = false;
                for (int pass = 0; pass < 2; pass++) {
                    for (int s = 0; s < n; s++) {
                        int i = pass == 0 ? s : n - 1 - s;
                        if (held[i] || (aside != null && aside[i])) {
                            continue;
                        }
                        int x = i % w;
                        int z = i / w;
                        int bed = a[i];
                        for (int k = 0; k < 4; k++) {
                            int j = neighbour(i, x, z, w, h, k);
                            if (j >= 0 && (aside == null || !aside[j])) {
                                bed = Math.max(bed, a[j] - MAX_STEP);
                            }
                        }
                        bed = Math.min(bed, up[i]);
                        if (bed > a[i]) {
                            a[i] = bed;
                            moved = true;
                        }
                    }
                }
            }
            return;
        }
        for (boolean moved = true; moved; ) {
            moved = false;
            for (int pass = 0; pass < 2; pass++) {
                for (int s = 0; s < n; s++) {
                    int i = pass == 0 ? s : n - 1 - s;
                    if (held[i] || (aside != null && aside[i])) {
                        continue;
                    }
                    int x = i % w;
                    int z = i / w;
                    int cap = a[i];
                    for (int k = 0; k < 4; k++) {
                        int j = neighbour(i, x, z, w, h, k);
                        if (j >= 0 && (aside == null || !aside[j])) {
                            cap = Math.min(cap, a[j] + MAX_STEP);
                        }
                    }
                    cap = Math.max(cap, down[i]);
                    if (cap < a[i]) {
                        a[i] = cap;
                        moved = true;
                    }
                }
            }
        }
    }

    /**
     * The heights as they stand just before the clamp: solved, blurred, rounded, floored. What the clamp is for: the tests
     * show a step over {@link #MAX_STEP} here that {@link #plan} takes out.
     */
    public static int[] beforeClamp(int w, int h, int[] ground, Kind[] kinds, int[] floors, int floorY) {
        int[] lows = lows(w, h, ground, kinds, floors);
        double[] field = solved(w, h, ground, kinds, floorY);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = kinds[i].held() ? heldAt(kinds[i], ground[i], floorY) : (int) Math.max(lows[i], Math.round(field[i]));
        }
        return out;
    }

    // --- the solve ---------------------------------------------------------------------------------------------------

    private static boolean[] held(Kind[] kinds) {
        boolean[] held = new boolean[kinds.length];
        for (int i = 0; i < held.length; i++) {
            held[i] = kinds[i].held();
        }
        return held;
    }

    private static int heldAt(Kind kind, int ground, int floorY) {
        return kind == Kind.PAD ? floorY : ground;
    }

    /** Each column's lowest height: its own floor, and for the FREE neighbours of a FLUID column that column's surface. */
    private static int[] lows(int w, int h, int[] ground, Kind[] kinds, int[] floors) {
        int n = w * h;
        if (w < 1 || h < 1 || ground.length != n || kinds.length != n || (floors != null && floors.length != n)) {
            throw new IllegalArgumentException("a " + w + "x" + h + " grid needs " + n + " values of ground, kinds and floors");
        }
        boolean any = false;
        int[] lows = new int[n];
        for (int i = 0; i < n; i++) {
            any |= kinds[i].held();
            lows[i] = floors == null ? NO_FLOOR : floors[i];
        }
        if (!any) {
            throw new IllegalArgumentException("nothing is held: no height to solve towards");
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                if (kinds[i] != Kind.FLUID) {
                    continue;
                }
                if (x > 0) {
                    lows[i - 1] = Math.max(lows[i - 1], ground[i]);
                }
                if (x < w - 1) {
                    lows[i + 1] = Math.max(lows[i + 1], ground[i]);
                }
                if (z > 0) {
                    lows[i - w] = Math.max(lows[i - w], ground[i]);
                }
                if (z < h - 1) {
                    lows[i + w] = Math.max(lows[i + w], ground[i]);
                }
            }
        }
        return lows;
    }

    /** The harmonic field between the held heights, blurred with the held columns kept (terraform.ts, planTerrain). */
    private static double[] solved(int w, int h, int[] ground, Kind[] kinds, int floorY) {
        return solved(w, h, ground, kinds, floorY, held(kinds));
    }

    /** As {@link #solved(int, int, int[], Kind[], int)}, holding only the {@code frozen} columns in the solve. */
    private static double[] solved(int w, int h, int[] ground, Kind[] kinds, int floorY, boolean[] frozen) {
        int n = w * h;
        double[] field = new double[n];
        for (int i = 0; i < n; i++) {
            field[i] = heldAt(kinds[i], ground[i], floorY);
            if (!frozen[i]) {
                field[i] = ground[i];                       // where the solve starts from
            }
        }
        solveLaplace(field, frozen, w, h);
        boxBlur(field, w, h, BLUR_RADIUS, frozen);
        return field;
    }

    // --- field.ts ----------------------------------------------------------------------------------------------------

    /**
     * One in-place Gauss-Seidel sweep: every free cell becomes the mean of its four neighbours (edges clamped, though the
     * outer ring is held in every frame). Returns the largest change, which for Gauss-Seidel is the largest residual.
     */
    private static double laplaceSweep(double[] data, boolean[] frozen, int w, int h, boolean forward) {
        double worst = 0;
        int z0 = forward ? 0 : h - 1;
        int zEnd = forward ? h : -1;
        int dz = forward ? 1 : -1;
        int x0 = forward ? 0 : w - 1;
        int xEnd = forward ? w : -1;
        int dx = forward ? 1 : -1;
        for (int z = z0; z != zEnd; z += dz) {
            for (int x = x0; x != xEnd; x += dx) {
                int i = z * w + x;
                if (frozen[i]) {
                    continue;
                }
                double l = data[z * w + (x > 0 ? x - 1 : x)];
                double r = data[z * w + (x < w - 1 ? x + 1 : x)];
                double u = data[(z > 0 ? z - 1 : z) * w + x];
                double d = data[(z < h - 1 ? z + 1 : z) * w + x];
                double next = (l + r + u + d) * 0.25;
                double delta = Math.abs(next - data[i]);
                data[i] = next;
                if (delta > worst) {
                    worst = delta;
                }
            }
        }
        return worst;
    }

    /** A grid and what is held in it. */
    private record Level(double[] data, boolean[] frozen, int w, int h) {
    }

    /**
     * Half-resolution copy: a coarse cell is held when any of its (up to 4) fine cells is, and takes the mean of those held
     * values, so the held heights survive the restriction.
     */
    private static Level coarsen(double[] data, boolean[] frozen, int w, int h) {
        int w2 = (w + 1) / 2;
        int h2 = (h + 1) / 2;
        double[] data2 = new double[w2 * h2];
        boolean[] frozen2 = new boolean[w2 * h2];
        for (int zi = 0; zi < h2; zi++) {
            for (int xi = 0; xi < w2; xi++) {
                double sum = 0;
                int count = 0;
                double fixedSum = 0;
                int fixedCount = 0;
                for (int dz = 0; dz < 2; dz++) {
                    int z = zi * 2 + dz;
                    if (z >= h) {
                        continue;
                    }
                    for (int dx = 0; dx < 2; dx++) {
                        int x = xi * 2 + dx;
                        if (x >= w) {
                            continue;
                        }
                        int i = z * w + x;
                        sum += data[i];
                        count++;
                        if (frozen[i]) {
                            fixedSum += data[i];
                            fixedCount++;
                        }
                    }
                }
                int j = zi * w2 + xi;
                data2[j] = fixedCount > 0 ? fixedSum / fixedCount : count > 0 ? sum / count : 0;
                frozen2[j] = fixedCount > 0;
            }
        }
        return new Level(data2, frozen2, w2, h2);
    }

    /**
     * Coarse to fine (full multigrid without residual correction): the halved problem is solved first and is the starting
     * guess, so the fine sweeps only remove what is left of the high frequencies.
     */
    private static void cascade(double[] data, boolean[] frozen, int w, int h) {
        if (w > CASCADE_MIN && h > CASCADE_MIN) {
            Level c = coarsen(data, frozen, w, h);
            cascade(c.data(), c.frozen(), c.w(), c.h());
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int i = z * w + x;
                    if (!frozen[i]) {
                        data[i] = c.data()[(z >> 1) * c.w() + (x >> 1)];
                    }
                }
            }
        }
        for (int s = 0; s < SWEEPS; s++) {
            laplaceSweep(data, frozen, w, h, s % 2 == 0);
        }
    }

    /** Solves the Laplace equation on the free cells, the held cells' values fixed, in place. */
    private static void solveLaplace(double[] data, boolean[] frozen, int w, int h) {
        cascade(data, frozen, w, h);
        double residual;
        int n = 0;
        do {
            residual = laplaceSweep(data, frozen, w, h, n % 2 == 0);
            n++;
        } while (residual > TOLERANCE && n < MAX_SWEEPS);
    }

    /** Separable box blur with clamped edges; the held cells keep their values. */
    private static void boxBlur(double[] data, int w, int h, int radius, boolean[] frozen) {
        if (radius <= 0) {
            return;
        }
        double[] saved = data.clone();
        double[] tmp = new double[w * h];
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int n = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    sum += data[z * w + Math.min(w - 1, Math.max(0, x + dx))];
                    n++;
                }
                tmp[z * w + x] = sum / n;
            }
        }
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                double sum = 0;
                int n = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    sum += tmp[Math.min(h - 1, Math.max(0, z + dz)) * w + x];
                    n++;
                }
                data[z * w + x] = sum / n;
            }
        }
        for (int i = 0; i < data.length; i++) {
            if (frozen[i]) {
                data[i] = saved[i];
            }
        }
    }

    /**
     * Pulls every free cell into [max(neighbour) - MAX_STEP, min(neighbour) + MAX_STEP], and never under its floor, until
     * the field stops moving. Held cells never move. Sweeps go forward and back in turn.
     *
     * <p>A cell whose neighbours are more than 2 * MAX_STEP apart has no such interval: it takes the midpoint, which splits
     * the difference instead of picking a side and oscillating. Every other update leaves all of the cell's own pairs
     * within the limit and touches no other pair, so where the held columns allow it the sweeps end with none over. Where
     * they don't (a pad 20 above land 8 columns off) the field is handed back as it settled, and the caller is told what
     * the steepest step came to (a cliff forced at the border would hide it).
     */
    private static void clampSteps(double[] data, boolean[] frozen, int[] lows, int w, int h, boolean[] aside) {
        int cap = Math.max(64, Math.min(4096, CLAMP_BUDGET / Math.max(1, w * h)));
        boolean moved = true;
        for (int it = 0; moved && it < cap; it++) {
            moved = false;
            boolean forward = it % 2 == 0;
            int z0 = forward ? 0 : h - 1;
            int zEnd = forward ? h : -1;
            int dz = forward ? 1 : -1;
            int x0 = forward ? 0 : w - 1;
            int xEnd = forward ? w : -1;
            int dx = forward ? 1 : -1;
            for (int z = z0; z != zEnd; z += dz) {
                for (int x = x0; x != xEnd; x += dx) {
                    int i = z * w + x;
                    if (frozen[i]) {
                        continue;
                    }
                    double lo = Double.NEGATIVE_INFINITY;
                    double hi = Double.POSITIVE_INFINITY;
                    for (int k = 0; k < 4; k++) {
                        int j = neighbour(i, x, z, w, h, k);
                        if (j < 0 || (aside != null && aside[j])) {
                            continue;
                        }
                        lo = Math.max(lo, data[j] - MAX_STEP);
                        hi = Math.min(hi, data[j] + MAX_STEP);
                    }
                    if (lo == Double.NEGATIVE_INFINITY) {
                        continue;                            // no neighbour it is held to (a 1x1 grid; one ringed by kept columns)
                    }
                    double current = data[i];
                    double next = current;
                    if (lo > hi) {
                        next = (lo + hi) / 2;
                    } else if (current < lo) {
                        next = lo;
                    } else if (current > hi) {
                        next = hi;
                    }
                    if (next < lows[i]) {
                        next = lows[i];
                    }
                    if (Math.abs(next - current) > 1e-9) {
                        data[i] = next;
                        moved = true;
                    }
                }
            }
        }
    }

    /**
     * Levels the specks the blur, the rounding and the clamp leave (not in field.ts): a free column strictly under, or over,
     * all of its neighbours. The harmonic field has none, but only until it is blurred against a held column and rounded,
     * and the clamp pulls a cell to the edge of its interval, which beside four equal neighbours is 1 off them.
     *
     * <p>Where the steps are within the limit such a column is exactly 1 off four equal neighbours, so it is set level with
     * them. Otherwise it is set to the nearest neighbour's height. Either way none of its pairs gets steeper, no other pair
     * changes, and no neighbour turns into a speck (each now has an equal neighbour), so the passes end. A bump is never
     * cut under its floor: that one is the floor's, not the ground's.
     */
    private static void levelSpecks(int[] a, boolean[] held, int[] lows, int w, int h, boolean[] aside) {
        boolean changed = true;
        for (int pass = 0; changed && pass < w * h; pass++) {
            changed = false;
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int i = z * w + x;
                    if (held[i]) {
                        continue;
                    }
                    int lo = Integer.MAX_VALUE;
                    int hi = Integer.MIN_VALUE;
                    for (int k = 0; k < 4; k++) {
                        int j = neighbour(i, x, z, w, h, k);
                        if (j >= 0 && (aside == null || !aside[j])) {
                            lo = Math.min(lo, a[j]);
                            hi = Math.max(hi, a[j]);
                        }
                    }
                    if (lo == Integer.MAX_VALUE) {
                        continue;
                    }
                    if (a[i] < lo) {
                        a[i] = lo;
                        changed = true;
                    } else if (a[i] > hi && Math.max(hi, lows[i]) < a[i]) {
                        a[i] = Math.max(hi, lows[i]);
                        changed = true;
                    }
                }
            }
        }
    }

    /** The cell of the k-th 4-neighbour of cell i at (x, z) (west, east, north, south), or -1 off the grid. */
    private static int neighbour(int i, int x, int z, int w, int h, int k) {
        return switch (k) {
            case 0 -> x > 0 ? i - 1 : -1;
            case 1 -> x < w - 1 ? i + 1 : -1;
            case 2 -> z > 0 ? i - w : -1;
            default -> z < h - 1 ? i + w : -1;
        };
    }

    // --- the answer --------------------------------------------------------------------------------------------------

    private static Result result(int w, int h, int[] ground, Kind[] kinds, int[] out, int margin, boolean seamed) {
        int steepest = 0;
        int heldSteepest = 0;
        int seamSteepest = 0;
        int spotX = -1;
        int spotZ = -1;
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int i = z * w + x;
                for (int k = 0; k < 2; k++) {
                    int j = k == 0 ? (x < w - 1 ? i + 1 : -1) : (z < h - 1 ? i + w : -1);
                    if (j < 0 || (kinds[i].held() && kinds[j].held())) {
                        continue;
                    }
                    int d = Math.abs(out[i] - out[j]);
                    if (walkedRound(kinds[i]) || walkedRound(kinds[j])) {
                        heldSteepest = Math.max(heldSteepest, d);
                        continue;
                    }
                    if (kinds[i] == Kind.BORDER || kinds[j] == Kind.BORDER) {
                        seamSteepest = Math.max(seamSteepest, d);
                        if (seamed) {
                            continue;                        // a bank where the ramp meets steep land: the walk out decides
                        }
                    }
                    if (d > steepest) {
                        steepest = d;
                        int free = kinds[i].held() ? j : i;
                        spotX = free % w;
                        spotZ = free / w;
                    }
                }
            }
        }
        if (steepest <= MAX_STEP) {
            spotX = -1;                                       // nothing to point at
            spotZ = -1;
        }
        int cutColumns = 0;
        int fillColumns = 0;
        int cutBlocks = 0;
        int fillBlocks = 0;
        for (int i = 0; i < out.length; i++) {
            if (out[i] < ground[i]) {
                cutColumns++;
                cutBlocks += ground[i] - out[i];
            } else if (out[i] > ground[i]) {
                fillColumns++;
                fillBlocks += out[i] - ground[i];
            }
        }
        return new Result(out, margin, steepest, spotX, spotZ, cutColumns, fillColumns, cutBlocks, fillBlocks, heldSteepest,
                seamSteepest, seamed);
    }

    /** A column the ramp goes round, not over: a player's thing, another building, a pond. */
    private static boolean walkedRound(Kind k) {
        return k == Kind.KEPT || k == Kind.FLUID;
    }
}
