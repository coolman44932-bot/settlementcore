package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.PlanPlacement;
import claude.underempire.core.room.Reshape;
import claude.underempire.core.room.RoomShoring;
import claude.underempire.core.room.RoomTemplates;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.room.Siting;
import claude.underempire.core.room.Siting.Earthwork;
import claude.underempire.core.room.Siting.Phase;
import claude.underempire.core.room.Siting.Plan;
import claude.underempire.core.room.Siting.Refusal;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Siting, the surface builder's reading of the world (docs/plans/surface-builder.md, sections 1 and 9): a plan for the land
 * round a raised building, or a refusal, checked on ground each test lays itself. No crew and no settlement: the plans are put
 * down at once ({@link Plan#applyNow}), then the template is stamped as its courses would lay it, and the finished world is
 * read by the numbers.
 *
 * <p>Batch "siting", at +360 over the plot (the floor at y 301, the platform's top at 305, the build limit's last cell 319:
 * 18 over the floor, so the steepest scene rises 16; "works" builds at 355 and 370 and "muzzle" at 376
 * nearby, so every test clears its plot's columns to air up to the build limit before it lays its ground, and after). Each
 * site is centred in its 44x44 plot with its widest area (a margin of 12) inside it. The ground is grass on 3 of dirt on
 * stone, 17 deep, so no cut comes within 2 of air under it.
 *
 * <p>The test building is the engine's own {@code settlementcore:room/raise_test_platform} (9x5x9, its door at (0,1,4)),
 * registered here once under {@link Core#NAMESPACE} with the function RAISE_TEST ({@link #platform}): it proves a raised
 * building's template loads from the engine's namespace. The registration stays for the rest of the run; nothing lists
 * the raised buildings yet, and the id is the engine's own.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class SitingGameTests {

    private static final String PLATFORM = "raise_test_platform";
    private static final int UP = 360;
    /** How deep each test's ground goes under the floor. */
    private static final int DEEP = 16;

    /** The test building, registered the first time a test asks (never at start-up: nothing but the tests needs it). */
    static synchronized Rooms.Room platform() {
        if (Rooms.get(PLATFORM) == null) {
            Rooms.Room r = new Rooms.Room(PLATFORM, "Test Platform", 0, Rooms.Function.of("RAISE_TEST"),
                    "The engine's stepped test building: a 9x9 apron, 7x7, 5x5 and 3x3 steps and a lantern.", 9, 5, 9, 0, 1, 4,
                    List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(), List.of(), Set.of(), 0, List.of(r)));
        }
        return Rooms.get(PLATFORM);
    }

    // --- the site ----------------------------------------------------------------------------------------------------

    /** The grass's height at a column (absolute y). */
    @FunctionalInterface
    private interface Land {
        int at(int x, int z);
    }

    /**
     * A site in its plot: the platform facing east with its box's north-west column at (bx0, bz0), its door on the west face,
     * its floor (the ground layer) at +360.
     */
    private static final class Site {
        final GameTestHelper h;
        final ServerLevel level;
        final int px0;
        final int pz0;
        final int px1;
        final int pz1;
        final int floor;
        final int bx0;
        final int bz0;
        final PlanPlacement pl;
        final List<RoomTemplates.Entry> template;

        Site(GameTestHelper h) {
            this.h = h;
            this.level = h.getLevel();
            BlockPos c1 = h.absolutePos(new BlockPos(0, UP, 0));
            BlockPos c2 = h.absolutePos(new BlockPos(43, UP, 43));
            px0 = Math.min(c1.getX(), c2.getX());
            pz0 = Math.min(c1.getZ(), c2.getZ());
            px1 = Math.max(c1.getX(), c2.getX());
            pz1 = Math.max(c1.getZ(), c2.getZ());
            floor = c1.getY();
            bx0 = px0 + 19;
            bz0 = pz0 + 17;
            h.assertTrue(floor + 17 < level.getMaxBuildHeight() && floor - DEEP - 2 > level.getMinBuildHeight(),
                    "the site's floor " + floor + " leaves no room for its ground and its hills under the build limit " + level.getMaxBuildHeight());
            BlockPos door = new BlockPos(bx0, floor + 1, bz0 + 4);
            pl = PlanPlacement.onGround(platform(), door.below(), Direction.EAST);
            BoundingBox b = pl.box();
            h.assertTrue(b.minX() == bx0 && b.minZ() == bz0 && b.maxX() == bx0 + 8 && b.maxZ() == bz0 + 8 && b.minY() == floor && b.maxY() == floor + 4,
                    "the platform aimed at " + door.below() + " facing east is placed at " + b + ", not its box from " + bx0 + " " + floor + " " + bz0);
            template = RoomTemplates.get(level, PLATFORM);
            h.assertTrue(template.size() == 9 * 5 * 9, "the platform's template (from " + Rooms.namespacesFor(PLATFORM) + ") has "
                    + template.size() + " cells, not 405: it didn't load from the engine's namespace");
        }

        /** The highest cell the checks read: 24 over the floor, or the build limit's last (the floor is 301, the limit 319). */
        int top() {
            return Math.min(floor + 24, level.getMaxBuildHeight() - 1);
        }

        int bx1() {
            return bx0 + 8;
        }

        int bz1() {
            return bz0 + 8;
        }

        boolean inBox(int x, int z) {
            return x >= bx0 && x <= bx1() && z >= bz0 && z <= bz1();
        }

        void set(int x, int y, int z, BlockState s) {
            level.setBlock(new BlockPos(x, y, z), s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        BlockState get(int x, int y, int z) {
            return level.getBlockState(new BlockPos(x, y, z));
        }

        /** Every column of the plot, from under the ground to the build limit: air. */
        void clear() {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int x = px0; x <= px1; x++) {
                for (int z = pz0; z <= pz1; z++) {
                    for (int y = floor - DEEP - 2; y < level.getMaxBuildHeight(); y++) {
                        if (!level.getBlockState(p.set(x, y, z)).isAir()) {
                            level.setBlock(p, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
        }

        /** The plot cleared, then its ground laid: stone from DEEP under the floor, 3 of dirt, grass at the land's height. */
        void lay(Land land) {
            clear();
            for (int x = px0; x <= px1; x++) {
                for (int z = pz0; z <= pz1; z++) {
                    int g = land.at(x, z);
                    for (int y = floor - DEEP; y <= g; y++) {
                        set(x, y, z, (y == g ? Blocks.GRASS_BLOCK : y >= g - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
                    }
                }
            }
        }

        /** Fails the test after clearing the plot, so nothing is left over the next batches' plots. */
        void guard(Runnable r) {
            try {
                r.run();
            } catch (RuntimeException | Error e) {
                clear();
                throw e;
            }
        }

        Siting.Result plan(boolean reshape) {
            return plan(RoomShoring.Warren.alone(), reshape);
        }

        Siting.Result plan(RoomShoring.Warren warren, boolean reshape) {
            return Siting.plan(level, pl, warren, reshape);
        }

        Plan expectPlan(Siting.Result r, String scene) {
            if (r instanceof Refusal no) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(scene + " was refused: " + no.why() + " (at " + no.spot() + ")"
                        + dumpLand(this, no.spot()));
            }
            return (Plan) r;
        }

        Refusal expectRefusal(Siting.Result r, String scene) {
            if (r instanceof Plan p) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(scene + " was sited, not refused: " + p + dump(this, p, heights(p)));
            }
            return (Refusal) r;
        }

        /** The template put down as its courses would lay it, air and all: the finished building on its plinth. */
        void stamp() {
            for (RoomTemplates.Entry e : template) {
                claude.underempire.core.room.DigJob.place(level, new claude.underempire.core.room.DigJob.Task(pl.world(e.local()),
                        e.state().rotate(pl.rotation()), e.nbt()));
            }
        }

        /** Where a walker's feet go on a column: the top of its highest block with a shape, not a plant or a leaf; water's top. */
        int surface(int x, int z) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int y = top(); y > floor - DEEP - 2; y--) {
                BlockState s = level.getBlockState(p.set(x, y, z));
                if (!s.getFluidState().isEmpty()) {
                    return y;
                }
                if (s.isAir() || Siting.cover(s) || s.getBlock() instanceof LeavesBlock || s.getCollisionShape(level, p).isEmpty()) {
                    continue;
                }
                return y;
            }
            return floor - DEEP - 2;
        }

        /** The world's surface over a plan's area, row by row (z outer), as the plan's arrays are. */
        int[] heights(Plan p) {
            int[] a = new int[p.width() * p.depth()];
            for (int i = 0; i < a.length; i++) {
                a[i] = surface(p.x0() + i % p.width(), p.z0() + i / p.width());
            }
            return a;
        }

        /** The cells of the plot's columns that pass {@code which}, from under the ground to {@link #top}. */
        Map<Long, BlockState> snapshot(ColumnTest which) {
            Map<Long, BlockState> out = new HashMap<>();
            for (int x = px0; x <= px1; x++) {
                for (int z = pz0; z <= pz1; z++) {
                    if (!which.test(x, z)) {
                        continue;
                    }
                    for (int y = floor - DEEP - 1; y <= top(); y++) {
                        out.put(BlockPos.asLong(x, y, z), get(x, y, z));
                    }
                }
            }
            return out;
        }

        /** Null when every cell of the snapshot is as it was; else how many changed, and the first. */
        String changed(Map<Long, BlockState> before) {
            int n = 0;
            String first = null;
            List<Long> keys = new ArrayList<>(before.keySet());
            keys.sort(Long::compare);
            for (long k : keys) {
                BlockPos p = BlockPos.of(k);
                BlockState now = level.getBlockState(p);
                if (now != before.get(k)) {
                    n++;
                    if (first == null) {
                        first = p.getX() + " " + p.getY() + " " + p.getZ() + " (" + (p.getY() - floor) + " over the floor) was " + before.get(k) + ", is " + now;
                    }
                }
            }
            return n == 0 ? null : n + " cells changed, the first at " + first;
        }
    }

    @FunctionalInterface
    private interface ColumnTest {
        boolean test(int x, int z);
    }

    // --- the checks, on the finished world ---------------------------------------------------------------------------

    private static boolean ground(Plan p, int i) {
        Reshape.Kind k = kindAt(p, i);
        return k != Reshape.Kind.KEPT && k != Reshape.Kind.FLUID;
    }

    private static Reshape.Kind kindAt(Plan p, int i) {
        return p.kind(p.x0() + i % p.width(), p.z0() + i / p.width());
    }

    private static boolean boxed(Site s, Plan p, int i) {
        return s.inBox(p.x0() + i % p.width(), p.z0() + i / p.width());
    }

    private static String where(Plan p, int i) {
        return "(" + (p.x0() + i % p.width()) + "," + (p.z0() + i / p.width()) + ")";
    }

    /**
     * Null when no step on the ground walked is over 1: between neighbouring columns of the area, neither in the box, kept or
     * a pond, and not both on the border (that is the land as it was). Else how many, and the first.
     */
    private static String stepProblem(Site s, Plan p, int[] a) {
        int w = p.width();
        int bad = 0;
        String first = null;
        for (int i = 0; i < a.length; i++) {
            int x = i % w;
            int z = i / w;
            for (int j : new int[]{x < w - 1 ? i + 1 : -1, z < p.depth() - 1 ? i + w : -1}) {
                if (j < 0 || boxed(s, p, i) || boxed(s, p, j) || !ground(p, i) || !ground(p, j)
                        || kindAt(p, i) == Reshape.Kind.BORDER && kindAt(p, j) == Reshape.Kind.BORDER || Math.abs(a[i] - a[j]) <= 1) {
                    continue;
                }
                bad++;
                if (first == null) {
                    first = (a[i] - p.floor()) + " to " + (a[j] - p.floor()) + " between " + where(p, i) + " and " + where(p, j);
                }
            }
        }
        return bad == 0 ? null : bad + " steps over 1 on the ground walked, the first " + first;
    }

    /** Null when no free column is strictly under, or over, all four of its neighbours in the world. */
    private static String speckProblem(Plan p, int[] a) {
        int w = p.width();
        for (int i = 0; i < a.length; i++) {
            int x = i % w;
            int z = i / w;
            if (kindAt(p, i) != Reshape.Kind.FREE || x == 0 || z == 0 || x == w - 1 || z == p.depth() - 1) {
                continue;
            }
            int[] nb = {a[i - 1], a[i + 1], a[i - w], a[i + w]};
            boolean under = true;
            boolean over = true;
            for (int n : nb) {
                under &= a[i] < n;
                over &= a[i] > n;
            }
            if (under || over) {
                return "the free column " + where(p, i) + " is " + (a[i] - p.floor()) + ", " + (under ? "a pit under" : "a bump over") + " all of "
                        + Arrays.toString(Arrays.stream(nb).map(v -> v - p.floor()).toArray());
            }
        }
        return null;
    }

    /**
     * Null when a walker on the landing gets to every free column and to the border, stepping at most 1 and never onto a
     * kept column, a pond or the box.
     */
    private static String walkProblem(Site s, Plan p, int[] a) {
        int w = p.width();
        boolean[] seen = new boolean[a.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        Reshape.Rect land = p.landing();
        for (int i = 0; i < a.length; i++) {
            int x = p.x0() + i % w;
            int z = p.z0() + i / w;
            if (x >= land.x0() && x <= land.x1() && z >= land.z0() && z <= land.z1()) {
                seen[i] = true;
                q.add(i);
            }
        }
        boolean border = false;
        while (!q.isEmpty()) {
            int i = q.poll();
            int x = i % w;
            int z = i / w;
            for (int j : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, z > 0 ? i - w : -1, z < p.depth() - 1 ? i + w : -1}) {
                if (j < 0 || seen[j] || boxed(s, p, j) || !ground(p, j) || Math.abs(a[i] - a[j]) > 1) {
                    continue;
                }
                seen[j] = true;
                border |= kindAt(p, j) == Reshape.Kind.BORDER;
                q.add(j);
            }
        }
        for (int i = 0; i < a.length; i++) {
            if (kindAt(p, i) == Reshape.Kind.FREE && !seen[i]) {
                return "no walk from the landing to the free column " + where(p, i);
            }
        }
        return border ? null : "no walk from the landing reaches the border";
    }

    /** Null when every worked column (the berm, the landing, the ramp) stands at the height the plan gave it. */
    private static String heightProblem(Site s, Plan p, int[] a) {
        for (int i = 0; i < a.length; i++) {
            Reshape.Kind k = kindAt(p, i);
            if ((k == Reshape.Kind.FREE || k == Reshape.Kind.PAD) && !boxed(s, p, i)) {
                int x = p.x0() + i % p.width();
                int z = p.z0() + i / p.width();
                if (a[i] != p.newHeight(x, z)) {
                    return "the " + k + " column " + where(p, i) + " stands at " + (a[i] - p.floor()) + ", the plan said " + (p.newHeight(x, z) - p.floor());
                }
            }
        }
        return null;
    }

    /** The area's heights over the floor: # the box, P the pad, b the border, K kept, ~ a pond. */
    private static String dump(Site s, Plan p, int[] a) {
        StringBuilder sb = new StringBuilder("\n(x " + p.x0() + ", z " + p.z0() + ", over the floor " + p.floor() + "; # box, P pad, b border, K kept, ~ pond; "
                + p.describe() + ")");
        for (int z = 0; z < p.depth(); z++) {
            sb.append('\n');
            for (int x = 0; x < p.width(); x++) {
                int i = z * p.width() + x;
                char mark = boxed(s, p, i) ? '#' : switch (kindAt(p, i)) {
                    case PAD -> 'P';
                    case BORDER -> 'b';
                    case KEPT -> 'K';
                    case FLUID -> '~';
                    default -> ' ';
                };
                sb.append(String.format(Locale.ROOT, "%3d%c", a[i] - p.floor(), mark));
            }
        }
        return sb.toString();
    }

    /** The land's heights over the floor round a spot, for a refusal. */
    private static String dumpLand(Site s, BlockPos spot) {
        StringBuilder sb = new StringBuilder("\n(the land over the floor " + s.floor + " from x " + (s.bx0 - 16) + ", z " + (s.bz0 - 13)
                + "; the spot is *, the box #)");
        for (int z = s.bz0 - 13; z <= s.bz1() + 13; z++) {
            sb.append('\n');
            for (int x = s.bx0 - 16; x <= s.bx1() + 13; x++) {
                char mark = x == spot.getX() && z == spot.getZ() ? '*' : s.inBox(x, z) ? '#' : ' ';
                sb.append(String.format(Locale.ROOT, "%3d%c", s.surface(x, z) - s.floor, mark));
            }
        }
        return sb.toString();
    }

    private static void expect(GameTestHelper h, Site s, Plan p, int[] a, String problem) {
        h.assertTrue(problem == null, problem == null ? "" : problem + dump(s, p, a));
    }

    // --- the scenes --------------------------------------------------------------------------------------------------

    /**
     * Section 6's patch with a dip and a hump in the box and in the margin: flat at the floor, a 2x2 hump of 2 in the box, a
     * 4x4 dip of 2 across the box's south-east corner (box, berm and margin), a 3x3 dip of 3 in the west margin and a 3x3
     * hump of 3 in the north margin, and the land rising 3 to the east from 3 past the berm.
     */
    private static Land hillock(Site s) {
        return (x, z) -> {
            int g = s.floor;
            if (x >= s.bx0 + 3 && x <= s.bx0 + 4 && z >= s.bz0 + 3 && z <= s.bz0 + 4) {
                g += 2;
            }
            if (x >= s.bx0 + 7 && x <= s.bx0 + 10 && z >= s.bz0 + 7 && z <= s.bz0 + 10) {
                g -= 2;
            }
            if (x >= s.bx0 - 6 && x <= s.bx0 - 4 && z >= s.bz0 + 6 && z <= s.bz0 + 8) {
                g -= 3;
            }
            if (x >= s.bx0 + 3 && x <= s.bx0 + 5 && z >= s.bz0 - 4 && z <= s.bz0 - 2) {
                g += 3;
            }
            if (x >= s.bx0 + 12) {
                g += Math.min(3, x - (s.bx0 + 11));
            }
            return g;
        };
    }

    // --- the tests ---------------------------------------------------------------------------------------------------

    /**
     * Section 9's test 1: on the hillock the reshaped site, put down and built, is walkable all round. No step on the ground
     * walked is over 1, no free column is a pit or a bump, every worked column stands where the plan put it, a walk leads
     * from the landing to every free column and the border, and the border ring and everything outside the area are as they
     * were. The dip is filled and the hump cut. Two plans of the same ground are the same.
     *
     * <p>The control, first: the same ground with the plinth, berm and landing only leaves the dip at the box's corner 2
     * under the berm. The step check sees it, so it can fail, and it is the ramp that makes it pass.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aReshapeIsWalkableAllRound(GameTestHelper h) {
        Site s = new Site(h);
        s.lay(hillock(s));
        h.runAfterDelay(20, () -> s.guard(() -> {
            Plan shaped = s.expectPlan(s.plan(true), "the hillock");
            // the control: the pad alone
            Plan bare = s.expectPlan(s.plan(false), "the hillock, the pad only");
            bare.applyNow(s.level);
            s.stamp();
            int[] b = s.heights(shaped);
            String control = stepProblem(s, shaped, b);
            int berm = (s.bz0 + 8 - shaped.z0()) * shaped.width() + (s.bx0 + 9 - shaped.x0());
            h.assertTrue(control != null && b[berm] - b[berm + 1] >= 2, "with the pad only, the dip at the box's corner is no step under the berm ("
                    + (b[berm] - s.floor) + " to " + (b[berm + 1] - s.floor) + "): the scene shows nothing of the ramp" + dump(s, shaped, b));
            // the same ground, reshaped
            s.lay(hillock(s));
            Plan p = s.expectPlan(s.plan(true), "the hillock again");
            h.assertTrue(p.earthworks().equals(shaped.earthworks()), "two plans of the same ground differ: " + p + " and " + shaped);
            h.assertTrue(p.reshaped() && p.numbers().margin() >= Reshape.MARGIN_MIN && p.goesRound().isEmpty(),
                    "the hillock's plan: " + p);
            int area = p.width();
            Map<Long, BlockState> outside = s.snapshot((x, z) -> x <= p.x0() || z <= p.z0() || x >= p.x0() + area - 1 || z >= p.z0() + p.depth() - 1);
            int set = p.applyNow(s.level);
            s.stamp();
            int[] a = s.heights(p);
            h.assertTrue(set > 0, "nothing was set" + dump(s, p, a));
            expect(h, s, p, a, stepProblem(s, p, a));
            expect(h, s, p, a, speckProblem(p, a));
            expect(h, s, p, a, heightProblem(s, p, a));
            expect(h, s, p, a, walkProblem(s, p, a));
            String moved = s.changed(outside);
            h.assertTrue(moved == null, "outside the area, or on its border: " + moved + dump(s, p, a));
            int dip = p.newHeight(s.bx0 - 5, s.bz0 + 7) - p.oldGround(s.bx0 - 5, s.bz0 + 7);
            int hump = p.newHeight(s.bx0 + 4, s.bz0 - 3) - p.oldGround(s.bx0 + 4, s.bz0 - 3);
            h.assertTrue(dip > 0 && hump < 0 && p.numbers().cutColumns() > 0 && p.numbers().fillColumns() > 0,
                    "the margin's dip was raised " + dip + " and its hump " + hump + ": they should be filled and cut" + dump(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Section 9's test 2: the site sunk 2 under the land, so the ramp cuts the margin, with a player's things in the cut: a
     * cobblestone post, a chest, a torch on a fence, a farmland strip with ripe wheat, and a tree. Each is untouched with the
     * cells over and under it, a neighbour of each was cut round it, and the ramp is walkable round them all.
     *
     * <p>Another building's keep-out, too: a fake settlement's building whose cells are only the land's top two layers in
     * the east margin. It is kept (and 1 round it); with a settlement that has no buildings the same plan cuts into it,
     * so it is the keep-out, not the ground, that keeps it.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aPlayersBlockInTheMarginIsNeverTouched(GameTestHelper h) {
        Site s = new Site(h);
        int land = s.floor + 2;
        s.lay((x, z) -> land);
        int row = s.bz0 + 11;                                    // 2 out from the berm, in the cut
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        for (int y = land + 1; y <= land + 3; y++) {
            s.set(s.bx0 + 1, y, row, cobble);
        }
        s.set(s.bx0 + 3, land + 1, row, Blocks.CHEST.defaultBlockState());
        s.set(s.bx0 + 5, land + 1, row, Blocks.OAK_FENCE.defaultBlockState());
        s.set(s.bx0 + 5, land + 2, row, Blocks.TORCH.defaultBlockState());
        for (int x = s.bx0 + 9; x <= s.bx0 + 11; x++) {
            s.set(x, land, row, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
            s.set(x, land + 1, row, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        }
        tree(s, s.bx0 + 7, land + 1, row, 4, 1, 2);
        LongOpenHashSet hut = new LongOpenHashSet();
        for (int x = s.bx0 + 11; x <= s.bx0 + 12; x++) {
            for (int z = s.bz0 + 3; z <= s.bz0 + 5; z++) {
                hut.add(BlockPos.asLong(x, land - 1, z));
                hut.add(BlockPos.asLong(x, land, z));
            }
        }
        int[][] things = {{s.bx0 + 1, row}, {s.bx0 + 3, row}, {s.bx0 + 5, row}, {s.bx0 + 7, row}, {s.bx0 + 9, row}, {s.bx0 + 10, row}, {s.bx0 + 11, row}};
        h.runAfterDelay(20, () -> s.guard(() -> {
            Plan blind = s.expectPlan(s.plan(true), "the sunken site with no buildings round it");
            h.assertTrue(blind.earthworks().stream().anyMatch(e -> hut.contains(e.pos().asLong())),
                    "with no buildings round it, the ramp doesn't cut where the building is: the keep-out has nothing to keep" + dump(s, blind, s.heights(blind)));
            Plan p = s.expectPlan(s.plan(warrenOf(hut), true), "the sunken site");
            for (Earthwork e : p.earthworks()) {
                BlockPos q = e.pos();
                boolean nearHut = q.getX() >= s.bx0 + 10 && q.getX() <= s.bx0 + 13 && q.getZ() >= s.bz0 + 2 && q.getZ() <= s.bz0 + 6
                        && q.getY() >= land - 2 && q.getY() <= land + 1;
                h.assertTrue(!nearHut, "an earthwork at " + q + " (" + e.phase() + ") is within 1 of the other building" + dump(s, p, s.heights(p)));
            }
            for (int[] t : things) {
                h.assertTrue(p.kind(t[0], t[1]) == Reshape.Kind.KEPT, "the column " + t[0] + " " + t[1] + " isn't kept: " + p.kind(t[0], t[1])
                        + dump(s, p, s.heights(p)));
                boolean cutBeside = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int x = t[0] + d.getStepX();
                    int z = t[1] + d.getStepZ();
                    cutBeside |= p.kind(x, z) == Reshape.Kind.FREE && p.newHeight(x, z) < p.oldGround(x, z);
                }
                h.assertTrue(cutBeside || t[0] > s.bx0 + 9, "nothing was cut beside " + t[0] + " " + t[1] + ": the scene doesn't show the ramp going round it"
                        + dump(s, p, s.heights(p)));
            }
            String round = p.goesRound();
            h.assertTrue(round.contains("of your blocks") && round.contains("a tree") && round.contains("another building"),
                    "the plan says the ramp goes round \"" + round + "\"");
            Map<Long, BlockState> kept = s.snapshot((x, z) -> {
                for (int[] t : things) {
                    if (x == t[0] && z == t[1]) {
                        return true;
                    }
                }
                return x >= s.bx0 + 10 && x <= s.bx0 + 13 && z >= s.bz0 + 2 && z <= s.bz0 + 6;
            });
            Map<Long, BlockState> crown = new HashMap<>();
            for (int x = s.bx0 + 6; x <= s.bx0 + 8; x++) {
                for (int z = row - 1; z <= row + 1; z++) {
                    for (int y = land + 3; y <= land + 6; y++) {
                        crown.put(BlockPos.asLong(x, y, z), s.get(x, y, z));
                    }
                }
            }
            p.applyNow(s.level);
            s.stamp();
            int[] a = s.heights(p);
            String moved = s.changed(kept);
            h.assertTrue(moved == null, "a player's thing, the cells over or under it, or the building: " + moved + dump(s, p, a));
            String leaves = s.changed(crown);
            h.assertTrue(leaves == null, "the tree's crown: " + leaves + dump(s, p, a));
            expect(h, s, p, a, stepProblem(s, p, a));
            expect(h, s, p, a, heightProblem(s, p, a));
            expect(h, s, p, a, walkProblem(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Section 9's test 3: a 3x3 pond, 1 deep, in the west margin of the sunken site, where the ramp from the landing cuts.
     * Its banks are never cut under its water: after the earthworks, and 40 ticks with every water block made to flow, each
     * is still a source and there is no water anywhere else in the area.
     *
     * <p>Two controls. The same ground with no pond is cut there, 1 under where the water will stand: the banks have
     * something to hold. And when one bank block is taken out by hand, water is found outside the pond: the check can fail.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aPondKeepsItsBanks(GameTestHelper h) {
        Site s = new Site(h);
        int land = s.floor + 2;
        int px = s.bx0 - 6;                                      // the pond's north-west column
        int pz = s.bz0 + 6;
        s.lay((x, z) -> land);
        LongOpenHashSet pond = new LongOpenHashSet();
        for (int x = px; x <= px + 2; x++) {
            for (int z = pz; z <= pz + 2; z++) {
                pond.add(BlockPos.asLong(x, land, z));
            }
        }
        h.startSequence()
                .thenExecuteAfter(20, () -> s.guard(() -> {
                    Plan dry = s.expectPlan(s.plan(true), "the sunken site with no pond");
                    int lowest = Integer.MAX_VALUE;
                    for (int x = px - 1; x <= px + 3; x++) {
                        for (int z = pz - 1; z <= pz + 3; z++) {
                            if (dry.kind(x, z) == Reshape.Kind.FREE) {
                                lowest = Math.min(lowest, dry.newHeight(x, z));
                            }
                        }
                    }
                    h.assertTrue(lowest < land, "with no pond the ramp doesn't cut under the land where the pond goes (lowest "
                            + (lowest - s.floor) + "): its banks have nothing to hold" + dump(s, dry, s.heights(dry)));
                    for (long k : pond) {
                        s.level.setBlock(BlockPos.of(k), Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    }
                    Plan p = s.expectPlan(s.plan(true), "the sunken site by a pond");
                    for (long k : pond) {
                        BlockPos q = BlockPos.of(k);
                        h.assertTrue(p.kind(q.getX(), q.getZ()) == Reshape.Kind.FLUID, "the pond's column " + q + " is " + p.kind(q.getX(), q.getZ()));
                    }
                    for (long k : pond) {
                        BlockPos q = BlockPos.of(k);
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            int x = q.getX() + d.getStepX();
                            int z = q.getZ() + d.getStepZ();
                            h.assertTrue(p.newHeight(x, z) >= land, "the bank " + x + " " + z + " is cut to " + (p.newHeight(x, z) - s.floor)
                                    + ", under the pond's water at " + (land - s.floor) + dump(s, p, s.heights(p)));
                        }
                    }
                    h.assertTrue(p.goesRound().contains("a pond"), "the plan says the ramp goes round \"" + p.goesRound() + "\"");
                    p.applyNow(s.level);
                    s.stamp();
                    int[] a = s.heights(p);
                    expect(h, s, p, a, heightProblem(s, p, a));
                    expect(h, s, p, a, walkProblem(s, p, a));
                    stir(s, pond);
                }))
                .thenExecuteAfter(40, () -> s.guard(() -> {
                    String wet = waterProblem(s, pond, s.bx0 - 16, s.bz0 - 13, s.bx1() + 13, s.bz1() + 13);
                    h.assertTrue(wet == null, wet + dumpLand(s, new BlockPos(px, land, pz)));
                    // the detector's control: a bank taken out by hand lets the water out, and it is seen
                    s.set(px + 3, land, pz + 1, Blocks.AIR.defaultBlockState());
                    stir(s, pond);
                }))
                .thenExecuteAfter(40, () -> s.guard(() -> {
                    String wet = waterProblem(s, pond, s.bx0 - 16, s.bz0 - 13, s.bx1() + 13, s.bz1() + 13);
                    h.assertTrue(wet != null, "a bank taken out by hand let no water out that the check sees: it can't fail");
                    s.clear();
                }))
                .thenSucceed();
    }

    /** Every pond block made to flow now, as a neighbour's change would (the earthworks set blocks without telling it). */
    private static void stir(Site s, LongOpenHashSet pond) {
        for (long k : pond) {
            s.level.scheduleTick(BlockPos.of(k), Fluids.WATER, 1);
        }
    }

    /** Null when every pond block is still a water source and no water is anywhere else in the columns given. */
    private static String waterProblem(Site s, LongOpenHashSet pond, int x0, int z0, int x1, int z1) {
        for (long k : pond) {
            BlockPos q = BlockPos.of(k);
            if (!s.level.getFluidState(q).isSource() || !s.level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) {
                return "the pond's block " + q + " is no longer a water source: " + s.level.getBlockState(q);
            }
        }
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = s.floor - 4; y <= s.floor + 8; y++) {
                    if (!pond.contains(BlockPos.asLong(x, y, z)) && !s.level.getFluidState(new BlockPos(x, y, z)).isEmpty()) {
                        return "water at " + x + " " + y + " " + z + ", outside the pond";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Section 9's test 4: the land rises 3 a column east of the berm to a cliff top 16 over the floor, 6 from the box, and
     * stays there: no margin up to 12 ramps it. The refusal names a spot east of the box's middle, says the land rises 16
     * and is too steep, and nothing in the world is changed.
     *
     * <p>(Not a sheer cliff with flat land between: a site 6 flat columns from a cliff needs no ramp at all, and is sited.
     * The margin starts from the land just outside the pad, section 9.)
     *
     * <p>The control: a hill rising 2 a column east of the berm to 6, tapering 1 a column to the north and south, is too
     * steep at its first margin and is ramped at a wider one: the refusal is the land's steepness, and the widening works.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aSiteIsRefusedWhereTheLandIsTooSteep(GameTestHelper h) {
        Site s = new Site(h);
        s.lay(rise(s, 16));
        h.runAfterDelay(20, () -> s.guard(() -> {
            Map<Long, BlockState> before = s.snapshot((x, z) -> true);
            Refusal no = s.expectRefusal(s.plan(true), "the land rising to 16 from the berm");
            h.assertTrue(no.why().contains("too steep") && no.why().contains(" at " + no.spot().getX() + " " + no.spot().getZ()),
                    "the refusal doesn't say it is too steep, or where: \"" + no.why() + "\"" + dumpLand(s, no.spot()));
            h.assertTrue(no.why().contains("rises 16"), "the refusal doesn't say how high the land rises: \"" + no.why() + "\"");
            h.assertTrue(no.spot().getX() > s.bx0 + 4 && no.spot().getX() <= s.bx1() + 1 + Reshape.MARGIN_MAX
                            && no.spot().getZ() >= s.bz0 - 13 && no.spot().getZ() <= s.bz1() + 13,
                    "the refusal's spot " + no.spot() + " isn't on the rising side, east of the box's middle at x " + (s.bx0 + 4) + dumpLand(s, no.spot()));
            String moved = s.changed(before);
            h.assertTrue(moved == null, "siting changed the world: " + moved);
            s.lay(hill(s));
            Plan p = s.expectPlan(s.plan(true), "the hill rising 2 a column to 6 east of the berm");
            h.assertTrue(p.numbers().margin() > Reshape.firstMargin(2), "the hill was ramped at its first margin " + p.numbers().margin()
                    + ": the widening shows nothing" + dump(s, p, s.heights(p)));
            p.applyNow(s.level);
            int[] a = s.heights(p);
            expect(h, s, p, a, stepProblem(s, p, a));
            expect(h, s, p, a, walkProblem(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    private static Land rise(Site s, int top) {
        return (x, z) -> s.floor + Math.max(0, Math.min(top, 3 * (x - (s.bx1() + 1))));
    }

    /** 2 a column up east of the berm to 6, and 1 a column down to the north and south of the pad's rows. */
    private static Land hill(Site s) {
        return (x, z) -> s.floor + Math.max(0, Math.min(Math.min(6, 2 * (x - (s.bx1() + 1))), Math.min(z - (s.bz0 - 9), s.bz1() + 9 - z)));
    }

    /**
     * Section 6's test 3: a pit 12 deep under the box's north-west corner (3x3). The plinth there would be 11 deep, over
     * SKIRT_MAX: refused, naming the corner and how deep. The control: the same pit 9 deep (a plinth of 8) is sited, with
     * 8 blocks of plinth in each of its columns, laid bottom-up in the template's ground-layer block.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aSiteIsRefusedWhereTheGroundFallsAway(GameTestHelper h) {
        Site s = new Site(h);
        s.lay(pit(s, 12));
        h.runAfterDelay(20, () -> s.guard(() -> {
            Refusal no = s.expectRefusal(s.plan(true), "a pit 12 deep under the box");
            h.assertTrue(no.spot().getX() == s.bx0 && no.spot().getZ() == s.bz0 && no.spot().getY() == s.floor - 12,
                    "the refusal's spot " + no.spot() + " isn't the pit's first column " + s.bx0 + " " + (s.floor - 12) + " " + s.bz0 + ": \"" + no.why() + "\"");
            h.assertTrue(no.why().contains("12") && no.why().contains("11 deep") && no.why().contains(s.bx0 + " " + s.bz0),
                    "the refusal doesn't name the spot and the depth: \"" + no.why() + "\"");
            s.lay(pit(s, 9));
            Plan p = s.expectPlan(s.plan(true), "a pit 9 deep under the box");
            int lift = -1;
            for (int x = s.bx0; x <= s.bx0 + 2; x++) {
                for (int z = s.bz0; z <= s.bz0 + 2; z++) {
                    int n = 0;
                    for (Earthwork e : p.earthworks()) {
                        if (e.phase() == Phase.PLINTH && e.pos().getX() == x && e.pos().getZ() == z) {
                            h.assertTrue(e.state().is(Blocks.STONE_BRICKS), "the plinth at " + e.pos() + " is " + e.state());
                            n++;
                        }
                    }
                    h.assertTrue(n == 8, "the plinth under " + x + " " + z + " is " + n + " blocks, not 8");
                }
            }
            for (Earthwork e : p.earthworks()) {
                if (e.phase() == Phase.PLINTH) {
                    h.assertTrue(e.lift() >= lift, "the plinth isn't laid bottom-up: " + e + " after lift " + lift);
                    lift = e.lift();
                }
            }
            s.clear();
            h.succeed();
        }));
    }

    private static Land pit(Site s, int deep) {
        return (x, z) -> x >= s.bx0 && x <= s.bx0 + 2 && z >= s.bz0 && z <= s.bz0 + 2 ? s.floor - deep : s.floor;
    }

    /**
     * A plan saved and loaded back (through the bytes a save file holds) gives the same earthworks, in the same order, and
     * the same numbers; and again after the ground is gone, since a loaded plan reads nothing from the world (its load takes
     * no level at all).
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aSavedPlanRebuildsTheSameEarthworks(GameTestHelper h) {
        Site s = new Site(h);
        s.lay(hillock(s));
        h.runAfterDelay(20, () -> s.guard(() -> {
            Plan p = s.expectPlan(s.plan(true), "the hillock");
            List<Earthwork> want = p.earthworks();
            h.assertTrue(want.size() > 100 && want.stream().anyMatch(e -> e.phase() == Phase.RAMP) && want.stream().anyMatch(e -> e.phase() == Phase.PLINTH),
                    "the hillock's earthworks are too few to show anything: " + p);
            CompoundTag saved = roundTrip(p.save());
            Plan back = Plan.load(saved, s.pl, s.template);
            h.assertTrue(back != null, "the saved plan didn't load");
            h.assertTrue(back.earthworks().equals(want), "the loaded plan's earthworks differ: " + firstDifference(want, back.earthworks()));
            h.assertTrue(back.numbers().equals(p.numbers()) && back.goesRound().equals(p.goesRound()) && back.describe().equals(p.describe()),
                    "the loaded plan's numbers differ: " + back + " against " + p);
            s.clear();
            Plan again = Plan.load(saved, s.pl, s.template);
            h.assertTrue(again != null && again.earthworks().equals(want), "with the ground gone, the loaded plan's earthworks differ: "
                    + (again == null ? "it didn't load" : firstDifference(want, again.earthworks())));
            h.assertTrue(saved.sizeInBytes() < 64 * 1024, "the saved plan is " + saved.sizeInBytes() + " bytes");
            h.succeed();
        }));
    }

    private static CompoundTag roundTrip(CompoundTag t) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(t, out);
            return NbtIo.readCompressed(new ByteArrayInputStream(out.toByteArray()), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstDifference(List<Earthwork> a, List<Earthwork> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return "task " + i + " was " + a.get(i) + ", is " + b.get(i);
            }
        }
        return a.size() + " tasks against " + b.size();
    }

    /**
     * A small tree on the pad (a trunk of 4 in the box, 21 leaves) is felled in the clearing: every one of its cells is a
     * clearing task setting air, no later phase touches them, and once put down none is left. A tree of more than 256
     * blocks (a trunk of 8 in a 7x7 crown 6 high) refuses the site, naming it.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aTreeOnThePadIsFelledOrRefused(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        List<BlockPos> small = tree(s, s.bx0 + 6, s.floor + 1, s.bz0 + 6, 4, 1, 2);
        h.runAfterDelay(20, () -> s.guard(() -> {
            Plan p = s.expectPlan(s.plan(true), "the site with a small tree");
            Map<Long, Earthwork> clearing = new HashMap<>();
            for (Earthwork e : p.earthworks()) {
                if (e.phase() == Phase.CLEARING) {
                    clearing.put(e.pos().asLong(), e);
                }
            }
            for (BlockPos c : small) {
                Earthwork e = clearing.get(c.asLong());
                h.assertTrue(e != null && e.state().isAir(), "the tree's " + s.level.getBlockState(c) + " at " + c + " isn't cleared: " + e);
            }
            for (Earthwork e : p.earthworks()) {
                h.assertTrue(e.phase() == Phase.CLEARING || !small.contains(e.pos()), "the tree's cell " + e.pos() + " is worked again, in " + e.phase());
            }
            h.assertTrue(p.trees().length == small.size(), "the plan fells " + p.trees().length + " blocks of tree, not the " + small.size() + " there are");
            p.applyNow(s.level);
            for (BlockPos c : small) {
                BlockState now = s.level.getBlockState(c);
                h.assertTrue(!now.is(net.minecraft.tags.BlockTags.LOGS) && !(now.getBlock() instanceof LeavesBlock), "the tree is still at " + c + ": " + now);
            }
            s.lay((x, z) -> s.floor);
            List<BlockPos> big = tree(s, s.bx0 + 4, s.floor + 1, s.bz0 + 4, 8, 3, 6);
            h.assertTrue(big.size() > Siting.TREE_MAX, "the big tree is only " + big.size() + " blocks");
            Refusal no = s.expectRefusal(s.plan(true), "the site with a tree of " + big.size() + " blocks");
            h.assertTrue(no.why().contains("too big") && big.contains(no.spot()), "the refusal doesn't name the tree: \"" + no.why() + "\" at " + no.spot());
            s.clear();
            h.succeed();
        }));
    }

    /**
     * An oak: a trunk of {@code height} logs from {@code (x, y, z)}, and a crown {@code reach} out each way over the top
     * {@code layers} logs and one layer over them (a plus, for a small crown; a full square for a big one). Each leaf's
     * distance is its real one to the trunk, so none decays. Returns every cell, trunk first.
     */
    private static List<BlockPos> tree(Site s, int x, int y, int z, int height, int reach, int layers) {
        List<BlockPos> cells = new ArrayList<>();
        for (int k = 0; k < height; k++) {
            BlockPos p = new BlockPos(x, y + k, z);
            s.set(p.getX(), p.getY(), p.getZ(), Blocks.OAK_LOG.defaultBlockState());
            cells.add(p);
        }
        int topLog = y + height - 1;
        boolean big = reach > 1;
        int bottom = topLog - layers + 1;
        int crownTop = big ? topLog : topLog + 1;
        for (int cy = bottom; cy <= crownTop; cy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx == 0 && dz == 0 && cy <= topLog) {
                        continue;
                    }
                    if (!big && cy > topLog && Math.abs(dx) + Math.abs(dz) > 1) {
                        continue;                                // the small crown's cap is a plus
                    }
                    int trunkY = Math.min(Math.max(cy, y), topLog);
                    int distance = Math.min(LeavesBlock.DECAY_DISTANCE, Math.abs(dx) + Math.abs(dz) + Math.abs(cy - trunkY));
                    s.set(x + dx, cy, z + dz, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, Math.max(1, distance))
                            .setValue(LeavesBlock.PERSISTENT, false));
                    cells.add(new BlockPos(x + dx, cy, z + dz));
                }
            }
        }
        return cells;
    }

    /** A settlement whose only building is {@code cells}. */
    private static RoomShoring.Warren warrenOf(LongOpenHashSet cells) {
        RoomShoring.Warren walls = RoomShoring.Warren.alone();
        return new RoomShoring.Warren() {
            @Override
            public LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except) {
                LongOpenHashSet out = new LongOpenHashSet();
                for (long c : cells) {
                    BlockPos p = BlockPos.of(c);
                    if (area.isInside(p) && !except.isInside(p)) {
                        out.add(c);
                    }
                }
                return out;
            }

            @Override
            public boolean shored(long pos) {
                return walls.shored(pos);
            }

            @Override
            public void shore(long pos) {
                walls.shore(pos);
            }

            @Override
            public void unshore(long pos) {
                walls.unshore(pos);
            }
        };
    }
}
