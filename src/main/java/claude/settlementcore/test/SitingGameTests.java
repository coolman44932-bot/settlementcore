package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.Reshape;
import claude.settlementcore.room.RoomShoring;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.room.Siting;
import claude.settlementcore.room.Siting.Earthwork;
import claude.settlementcore.room.Siting.Phase;
import claude.settlementcore.room.Siting.Plan;
import claude.settlementcore.room.Siting.Refusal;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;

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
public class SitingGameTests {

    public static final String PLATFORM = "raise_test_platform";
    /** What the test building costs its settlement (Works#raise: a paid raise spends it, a refused one nothing). */
    public static final int PLATFORM_COST = 10;
    static final int UP = 360;
    /** How deep each test's ground goes under the floor. */
    static final int DEEP = 16;

    /**
     * The test building, registered the first time a test asks (never at start-up: nothing but the tests needs it). The one
     * registration for every batch that raises it (the root project's RaiseGameTests too), so they all have the same room.
     */
    public static synchronized Rooms.Room platform() {
        if (Rooms.get(PLATFORM) == null) {
            Rooms.Room r = new Rooms.Room(PLATFORM, "Test Platform", PLATFORM_COST, Rooms.Function.of("RAISE_TEST"),
                    "The engine's stepped test building: a 9x9 apron, 7x7, 5x5 and 3x3 steps and a lantern.", 9, 5, 9, 0, 1, 4,
                    List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(), List.of(), Set.of(), 0, List.of(r)));
        }
        return Rooms.get(PLATFORM);
    }

    // --- the site ----------------------------------------------------------------------------------------------------

    /** The grass's height at a column (absolute y). */
    @FunctionalInterface
    public interface Land {
        int at(int x, int z);
    }

    /**
     * A site in its plot: the platform facing east with its box's north-west column at (bx0, bz0), its door on the west face,
     * its floor (the ground layer) at +360. Public: the crewed tests of the root project (RaiseGameTests) raise the platform on
     * the same plot, and RaiseJobGameTests lays its ground here too.
     */
    public static final class Site {
        public final GameTestHelper h;
        public final ServerLevel level;
        public final int px0;
        public final int pz0;
        public final int px1;
        public final int pz1;
        public final int floor;
        public final int bx0;
        public final int bz0;
        public final PlanPlacement pl;
        public final List<RoomTemplates.Entry> template;

        public Site(GameTestHelper h) {
            this(h, UP);
        }

        /** A site whose floor is at +{@code up} over the plot (lower than +360 where the land must rise far over it). */
        public Site(GameTestHelper h, int up) {
            this(h, up, platform());
        }

        /** A site of another building ({@link #Site(GameTestHelper, int, Rooms.Room)}) at the usual height: the root project's crewed tests. */
        public Site(GameTestHelper h, Rooms.Room room) {
            this(h, UP, room);
        }

        /**
         * A site of another building (a hand-built one, {@link RoomTemplates#put}): 9 wide and deep like the platform, its door
         * at (0,1,4), any height, its template every cell of the box.
         */
        public Site(GameTestHelper h, int up, Rooms.Room room) {
            this.h = h;
            this.level = h.getLevel();
            BlockPos c1 = h.absolutePos(new BlockPos(0, up, 0));
            BlockPos c2 = h.absolutePos(new BlockPos(43, up, 43));
            px0 = Math.min(c1.getX(), c2.getX());
            pz0 = Math.min(c1.getZ(), c2.getZ());
            px1 = Math.max(c1.getX(), c2.getX());
            pz1 = Math.max(c1.getZ(), c2.getZ());
            floor = c1.getY();
            bx0 = px0 + 19;
            bz0 = pz0 + 17;
            h.assertTrue(floor + 17 < (level.getMaxY() + 1) && floor - DEEP - 2 > level.getMinY(),
                    "the site's floor " + floor + " leaves no room for its ground and its hills under the build limit " + (level.getMaxY() + 1));
            BlockPos door = new BlockPos(bx0, floor + 1, bz0 + 4);
            pl = PlanPlacement.onGround(room, door.below(), Direction.EAST);
            BoundingBox b = pl.box();
            h.assertTrue(b.minX() == bx0 && b.minZ() == bz0 && b.maxX() == bx0 + 8 && b.maxZ() == bz0 + 8 && b.minY() == floor
                            && b.maxY() == floor + room.sizeY() - 1,
                    "the " + room.id() + " aimed at " + door.below() + " facing east is placed at " + b + ", not its box from " + bx0 + " " + floor + " " + bz0);
            template = RoomTemplates.get(level, room.id());
            int cells = room.sizeX() * room.sizeY() * room.sizeZ();
            h.assertTrue(template.size() == cells, "the " + room.id() + "'s template (from " + Rooms.namespacesFor(room.id()) + ") has "
                    + template.size() + " cells, not " + cells + ": it didn't load from the engine's namespace");
        }

        /** The highest cell the checks read: 24 over the floor, or the build limit's last (the floor is 301, the limit 319). */
        public int top() {
            return Math.min(floor + 24, (level.getMaxY() + 1) - 1);
        }

        public int bx1() {
            return bx0 + 8;
        }

        public int bz1() {
            return bz0 + 8;
        }

        public boolean inBox(int x, int z) {
            return x >= bx0 && x <= bx1() && z >= bz0 && z <= bz1();
        }

        public void set(int x, int y, int z, BlockState s) {
            level.setBlock(new BlockPos(x, y, z), s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        public BlockState get(int x, int y, int z) {
            return level.getBlockState(new BlockPos(x, y, z));
        }

        /** Every column of the plot, from under the ground to the build limit: air. */
        public void clear() {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int x = px0; x <= px1; x++) {
                for (int z = pz0; z <= pz1; z++) {
                    for (int y = floor - DEEP - 2; y < (level.getMaxY() + 1); y++) {
                        if (!level.getBlockState(p.set(x, y, z)).isAir()) {
                            level.setBlock(p, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
        }

        /** The plot cleared, then its ground laid: stone from DEEP under the floor, 3 of dirt, grass at the land's height. */
        public void lay(Land land) {
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
        public void guard(Runnable r) {
            try {
                r.run();
            } catch (RuntimeException | Error e) {
                clear();
                throw e;
            }
        }

        public Siting.Result plan(boolean reshape) {
            return plan(RoomShoring.Warren.alone(), reshape);
        }

        public Siting.Result plan(RoomShoring.Warren warren, boolean reshape) {
            return Siting.plan(level, pl, warren, reshape);
        }

        public Plan expectPlan(Siting.Result r, String scene) {
            if (r instanceof Refusal no) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(net.minecraft.network.chat.Component.literal(scene + " was refused: " + no.why() + " (at " + no.spot() + ")"
                        + dumpLand(this, no.spot())), 0);
            }
            return (Plan) r;
        }

        public Refusal expectRefusal(Siting.Result r, String scene) {
            if (r instanceof Plan p) {
                throw new net.minecraft.gametest.framework.GameTestAssertException(net.minecraft.network.chat.Component.literal(scene + " was sited, not refused: " + p + dump(this, p, heights(p))), 0);
            }
            return (Refusal) r;
        }

        /** The template put down as its courses would lay it, air and all: the finished building on its plinth. */
        void stamp() {
            for (RoomTemplates.Entry e : template) {
                claude.settlementcore.room.DigJob.place(level, new claude.settlementcore.room.DigJob.Task(pl.world(e.local()),
                        e.state().rotate(pl.rotation()), e.nbt()));
            }
        }

        /** Where a walker's feet go on a column: the top of its highest block with a shape, not a plant or a leaf; water's top. */
        public int surface(int x, int z) {
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
        public int[] heights(Plan p) {
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
    public static String stepProblem(Site s, Plan p, int[] a) {
        return stepProblem(s, p, a, true);
    }

    /**
     * As {@link #stepProblem(Site, Plan, int[])}; with {@code seams} false the steps onto the border are left out too, so it
     * checks the ramp in itself (a free column against a free one or the pad): where the ramp meets steep land with a bank,
     * the bank is no step on it.
     */
    private static String stepProblem(Site s, Plan p, int[] a, boolean seams) {
        int w = p.width();
        int bad = 0;
        String first = null;
        for (int i = 0; i < a.length; i++) {
            int x = i % w;
            int z = i / w;
            for (int j : new int[]{x < w - 1 ? i + 1 : -1, z < p.depth() - 1 ? i + w : -1}) {
                if (j < 0) {
                    continue;
                }
                boolean border = kindAt(p, i) == Reshape.Kind.BORDER || kindAt(p, j) == Reshape.Kind.BORDER;
                if (boxed(s, p, i) || boxed(s, p, j) || !ground(p, i) || !ground(p, j)
                        || kindAt(p, i) == Reshape.Kind.BORDER && kindAt(p, j) == Reshape.Kind.BORDER || !seams && border
                        || Math.abs(a[i] - a[j]) <= 1) {
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
    public static String walkProblem(Site s, Plan p, int[] a) {
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
    public static String heightProblem(Site s, Plan p, int[] a) {
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
    public static String dump(Site s, Plan p, int[] a) {
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

    static void expect(GameTestHelper h, Site s, Plan p, int[] a, String problem) {
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
            s.set(x, land, row, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 0));
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
     * The ramp meets another building level. The site stands 5 over the land, so the pad and the ramp are filled, and a
     * stone-brick hut (2x3, 2 high) stands on the land 3 columns east of the berm, known to the settlement only through a
     * fake Warren. Its ring (its columns and 1 round them) is kept; the fill between it and the pad can't come within 1 of
     * both, so Reshape sets the kept columns aside and would heap the fill 2 or more over the hut's ground beside it. Siting
     * keeps those columns too, at their own height: after the earthworks every column beside the ring is within 1 of it,
     * and the hut and its ring are untouched. The ramp is walkable, and the plan says it goes round another building.
     *
     * <p>The control: Reshape's raw answer for the same area, with the columns the rule kept set free again, leaves a column
     * beside the ring more than 1 off it: the rule is what keeps the hut from being buried.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aRampMeetsAnotherBuildingLevel(GameTestHelper h) {
        Site s = new Site(h);
        int land = s.floor - 5;
        s.lay((x, z) -> land);
        LongOpenHashSet hut = new LongOpenHashSet();
        for (int x = s.bx0 + 12; x <= s.bx0 + 13; x++) {
            for (int z = s.bz0 + 3; z <= s.bz0 + 5; z++) {
                for (int y = land + 1; y <= land + 2; y++) {
                    s.set(x, y, z, Blocks.STONE_BRICKS.defaultBlockState());
                    hut.add(BlockPos.asLong(x, y, z));
                }
            }
        }
        ColumnTest inHut = (x, z) -> x >= s.bx0 + 12 && x <= s.bx0 + 13 && z >= s.bz0 + 3 && z <= s.bz0 + 5;
        ColumnTest inRing = (x, z) -> x >= s.bx0 + 11 && x <= s.bx0 + 14 && z >= s.bz0 + 2 && z <= s.bz0 + 6 && !inHut.test(x, z);
        h.runAfterDelay(20, () -> s.guard(() -> {
            Plan p = s.expectPlan(s.plan(warrenOf(hut), true), "the raised site by a hut");
            int w = p.width();
            int n = w * p.depth();
            // the columns the rule kept: kept, beside the ring, neither the ring nor the hut
            Reshape.Kind[] raw = new Reshape.Kind[n];
            int[] ground = new int[n];
            int ruled = 0;
            for (int i = 0; i < n; i++) {
                int x = p.x0() + i % w;
                int z = p.z0() + i / w;
                raw[i] = p.kind(x, z);
                ground[i] = p.oldGround(x, z);
                if (inRing.test(x, z)) {
                    h.assertTrue(raw[i] == Reshape.Kind.KEPT || raw[i] == Reshape.Kind.BORDER, "the hut's ring column " + x + " " + z + " is " + raw[i]
                            + dump(s, p, planned(p)));
                } else if (raw[i] == Reshape.Kind.KEPT && !inHut.test(x, z) && besideRing(inRing, x, z)) {
                    raw[i] = Reshape.Kind.FREE;
                    ruled++;
                }
            }
            h.assertTrue(ruled > 0, "no column beside the hut's ring was kept: the scene shows nothing of the rule" + dump(s, p, planned(p)));
            int[] loose = Reshape.plan(w, p.depth(), ground, raw, null, p.floor(), p.numbers().margin()).height();
            int worst = 0;
            for (int i = 0; i < n; i++) {
                int x = p.x0() + i % w;
                int z = p.z0() + i / w;
                if (raw[i] != Reshape.Kind.FREE) {
                    continue;
                }
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int rx = x + d.getStepX();
                    int rz = z + d.getStepZ();
                    if (inRing.test(rx, rz)) {
                        worst = Math.max(worst, Math.abs(loose[i] - p.oldGround(rx, rz)));
                    }
                }
            }
            h.assertTrue(worst > 1, "Reshape's raw answer leaves every column beside the hut's ring within 1 of it (" + worst
                    + "): the rule has nothing to keep" + dump(s, p, loose));
            Map<Long, BlockState> kept = s.snapshot((x, z) -> inHut.test(x, z) || inRing.test(x, z));
            p.applyNow(s.level);
            s.stamp();
            int[] a = s.heights(p);
            String moved = s.changed(kept);
            h.assertTrue(moved == null, "the hut or its ring: " + moved + dump(s, p, a));
            for (int x = s.bx0 + 10; x <= s.bx0 + 15; x++) {
                for (int z = s.bz0 + 1; z <= s.bz0 + 7; z++) {
                    if (!inRing.test(x, z)) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int nx = x + d.getStepX();
                        int nz = z + d.getStepZ();
                        if (inRing.test(nx, nz) || inHut.test(nx, nz) || s.inBox(nx, nz) || !p.inArea(nx, nz)) {
                            continue;
                        }
                        int step = s.surface(nx, nz) - s.surface(x, z);
                        h.assertTrue(Math.abs(step) <= 1, "the column " + nx + " " + nz + " beside the hut's ring stands " + step + " off it (at "
                                + x + " " + z + ")" + dump(s, p, a));
                    }
                }
            }
            h.assertTrue(p.goesRound().contains("another building"), "the plan says the ramp goes round \"" + p.goesRound() + "\"");
            expect(h, s, p, a, stepProblem(s, p, a));
            expect(h, s, p, a, heightProblem(s, p, a));
            expect(h, s, p, a, walkProblem(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    private static boolean besideRing(ColumnTest ring, int x, int z) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (ring.test(x + d.getStepX(), z + d.getStepZ())) {
                return true;
            }
        }
        return false;
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
     * Where the land is too steep to meet level, the ramp is cut into it. The land rises 3 a column east of the berm to 16
     * over the floor, 6 from the box, and stays there: no margin up to 12 climbs that at 1 a column, so at the widest the
     * ramp meets the rise with a bank (Reshape's seamed pass), and the site stands because a walk still leads from the
     * landing out to the flat land west, north and south. Asserted: sited, seamed, at the widest margin, with the bank in
     * its numbers and its printout; the ramp walkable in itself (a free column against a free one or the pad, at most 1) in
     * the plan and in the finished world; every free column reached from the landing, and the walk out to the land; every
     * worked column where the plan put it; the bank standing in the world by the rise; nothing outside the area, or on its
     * border, touched. And the level sides are met level ({@link #levelSideProblem}): the bank is only where the land is
     * steep, and the ramp doesn't rise over the flat land beside the hill (the review of 81f1f2a: with the whole ring set
     * aside, it stood 5 over the flat land by the level ring).
     *
     * <p>(Not a sheer cliff with flat land between: a site 6 flat columns from a cliff needs no ramp at all, and is sited
     * level. The margin starts from the land just outside the pad, section 9.)
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aSiteOnASteepRiseIsCutIntoIt(GameTestHelper h) {
        Site s = new Site(h);
        s.lay(rise(s, 16));
        h.runAfterDelay(20, () -> s.guard(() -> {
            Plan p = s.expectPlan(s.plan(true), "the land rising 3 a column to 16 east of the berm");
            Siting.Numbers n = p.numbers();
            h.assertTrue(n.seamed() && n.margin() == Reshape.MARGIN_MAX && n.bank() > Reshape.MAX_STEP,
                    "the rise isn't met with a bank at the widest margin: " + p + dump(s, p, planned(p)));
            h.assertTrue(p.describe().contains("banked up to " + n.bank() + " where the land is too steep"),
                    "the printout doesn't report the bank: " + p.describe());
            h.assertTrue(n.steepest() <= Reshape.MAX_STEP, "the plan's ramp has a step of " + n.steepest() + " on it" + dump(s, p, planned(p)));
            int[] q = planned(p);
            expect(h, s, p, q, stepProblem(s, p, q, false));
            expect(h, s, p, q, levelSideProblem(p, q));
            int w = p.width();
            Map<Long, BlockState> outside = s.snapshot((x, z) -> x <= p.x0() || z <= p.z0() || x >= p.x0() + w - 1 || z >= p.z0() + p.depth() - 1);
            p.applyNow(s.level);
            s.stamp();
            int[] a = s.heights(p);
            expect(h, s, p, a, stepProblem(s, p, a, false));
            expect(h, s, p, a, levelSideProblem(p, a));
            expect(h, s, p, a, heightProblem(s, p, a));
            expect(h, s, p, a, walkProblem(s, p, a));
            String moved = s.changed(outside);
            h.assertTrue(moved == null, "outside the area, or on its border: " + moved + dump(s, p, a));
            int bank = 0;
            for (int i = 0; i < a.length; i++) {
                int x = i % w;
                int z = i / w;
                if (kindAt(p, i) != Reshape.Kind.FREE) {
                    continue;
                }
                for (int j : new int[]{x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, z > 0 ? i - w : -1, z < p.depth() - 1 ? i + w : -1}) {
                    if (j >= 0 && kindAt(p, j) == Reshape.Kind.BORDER) {
                        bank = Math.max(bank, Math.abs(a[j] - a[i]));
                    }
                }
            }
            h.assertTrue(bank == n.bank(), "the bank cut into the rise stands " + bank + " in the world; the plan reported " + n.bank() + dump(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Section 9's test 4: a bowl, the land rising 3 a column on every side of the pad to 30 over the floor (10 out) and flat
     * from there. At the widest margin the ramp can be cut into it, but it rises only 1 a column from the pad: at most about
     * 24 at the area's corners, the furthest a walker gets in steps of 1 (a rim of 16 is climbed out of there, and sited). No
     * walk leads from the landing out to the land: refused as too steep, naming a spot at the ramp's edge, by the border
     * (the one with the smallest step out), and how high the land rises there; and nothing in the world is changed. The site
     * sits at +330, so the rim is under the build limit.
     *
     * <p>The control: a hill rising 2 a column east of the berm to 6, tapering 1 a column to the north and south, is too
     * steep at its first margin and is ramped level at a wider one, with no bank: the refusal is the land's steepness, and
     * the widening works.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aSiteIsRefusedWhereTheLandIsTooSteep(GameTestHelper h) {
        Site s = new Site(h, UP - 30);
        h.assertTrue(s.floor + BOWL_RIM < (s.level.getMaxY() + 1), "the bowl's rim " + (s.floor + BOWL_RIM) + " is over the build limit");
        s.lay(bowl(s));
        h.runAfterDelay(20, () -> s.guard(() -> {
            Map<Long, BlockState> before = s.snapshot((x, z) -> true);
            Refusal no = s.expectRefusal(s.plan(true), "the bowl, the land rising 3 a column to " + BOWL_RIM + " all round");
            h.assertTrue(no.why().contains("too steep to ramp out of") && no.why().contains(" at " + no.spot().getX() + " " + no.spot().getZ()),
                    "the refusal doesn't say it is too steep, or where: \"" + no.why() + "\"" + dumpLand(s, no.spot()));
            h.assertTrue(no.why().contains("rises " + BOWL_RIM), "the refusal doesn't say how high the land rises: \"" + no.why() + "\"");
            // the widest area: the landing 2 out west, and a margin of 12 (and 2 more on the door's side)
            int wx0 = s.bx0 - 2 - Reshape.MARGIN_MAX - Reshape.DOOR_EXTRA;
            int wx1 = s.bx1() + 1 + Reshape.MARGIN_MAX;
            int wz0 = s.bz0 - 1 - Reshape.MARGIN_MAX;
            int wz1 = s.bz1() + 1 + Reshape.MARGIN_MAX;
            int sx = no.spot().getX();
            int sz = no.spot().getZ();
            h.assertTrue(sx > wx0 && sx < wx1 && sz > wz0 && sz < wz1 && (sx == wx0 + 1 || sx == wx1 - 1 || sz == wz0 + 1 || sz == wz1 - 1),
                    "the refusal's spot " + no.spot() + " isn't at the ramp's edge, beside the border of " + wx0 + " " + wz0 + " to " + wx1 + " " + wz1
                            + dumpLand(s, no.spot()));
            String moved = s.changed(before);
            h.assertTrue(moved == null, "siting changed the world: " + moved);
            s.lay(hill(s));
            Plan p = s.expectPlan(s.plan(true), "the hill rising 2 a column to 6 east of the berm");
            h.assertTrue(p.numbers().margin() > Reshape.firstMargin(2), "the hill was ramped at its first margin " + p.numbers().margin()
                    + ": the widening shows nothing" + dump(s, p, s.heights(p)));
            h.assertTrue(!p.numbers().seamed() && p.numbers().bank() == 0, "the hill is met with a bank: " + p + dump(s, p, planned(p)));
            p.applyNow(s.level);
            int[] a = s.heights(p);
            expect(h, s, p, a, stepProblem(s, p, a));
            expect(h, s, p, a, walkProblem(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Null when the ramp meets the level land at the floor level: (a) every free column beside a border cell whose land is
     * within 1 of both its ring neighbours' and of the floor steps at most 1 onto it; (b) no free column whose land was at
     * the floor, within 3 columns of such a border cell, is raised more than 1. A level ring high over the floor (the top of
     * the steep rise, 16 up and 11 walked columns from the pad) is left out: no ramp of 1 a column reaches it, and it is
     * banked.
     */
    private static String levelSideProblem(Plan p, int[] a) {
        int w = p.width();
        int d = p.depth();
        List<Integer> level = new ArrayList<>();
        for (int j = 0; j < a.length; j++) {
            int x = j % w;
            int z = j / w;
            if (!(x == 0 || z == 0 || x == w - 1 || z == d - 1)) {
                continue;
            }
            int land = p.oldGround(p.x0() + x, p.z0() + z);
            if (Math.abs(land - p.floor()) > 1) {
                continue;
            }
            int ring = 0;
            boolean flat = true;
            for (int k : new int[]{x > 0 ? j - 1 : -1, x < w - 1 ? j + 1 : -1, z > 0 ? j - w : -1, z < d - 1 ? j + w : -1}) {
                if (k < 0) {
                    continue;
                }
                int kx = k % w;
                int kz = k / w;
                if (kx == 0 || kz == 0 || kx == w - 1 || kz == d - 1) {
                    ring++;
                    flat &= Math.abs(p.oldGround(p.x0() + kx, p.z0() + kz) - land) <= 1;
                }
            }
            if (ring < 2 || !flat) {
                continue;
            }
            level.add(j);
            for (int i : new int[]{x > 0 ? j - 1 : -1, x < w - 1 ? j + 1 : -1, z > 0 ? j - w : -1, z < d - 1 ? j + w : -1}) {
                if (i >= 0 && kindAt(p, i) == Reshape.Kind.FREE && Math.abs(a[i] - a[j]) > 1) {
                    return "(a) the free column " + where(p, i) + " stands " + (a[i] - p.floor()) + ", beside the level border " + where(p, j) + " at "
                            + (a[j] - p.floor());
                }
            }
        }
        for (int i = 0; i < a.length; i++) {
            int x = p.x0() + i % w;
            int z = p.z0() + i / w;
            if (kindAt(p, i) != Reshape.Kind.FREE || p.oldGround(x, z) != p.floor() || a[i] - p.oldGround(x, z) <= 1) {
                continue;
            }
            for (int j : level) {
                if (Math.max(Math.abs(i % w - j % w), Math.abs(i / w - j / w)) <= 3) {
                    return "(b) the free column " + where(p, i) + ", level land at the floor, is raised " + (a[i] - p.oldGround(x, z))
                            + " within 3 of the level border " + where(p, j);
                }
            }
        }
        return level.isEmpty() ? "no border cell is level at the floor: the scene shows nothing of the level sides" : null;
    }

    /** A plan's own heights over its area, row by row, as {@link Site#heights} reads the world's. */
    private static int[] planned(Plan p) {
        int[] a = new int[p.width() * p.depth()];
        for (int i = 0; i < a.length; i++) {
            a[i] = p.newHeight(p.x0() + i % p.width(), p.z0() + i / p.width());
        }
        return a;
    }

    /** The bowl's rim over its floor: higher than a ramp of 1 a column climbs to the widest area's corners. */
    private static final int BOWL_RIM = 30;

    /** The land rising 3 a column to {@link #BOWL_RIM} on every side of the pad, from the berm out. */
    private static Land bowl(Site s) {
        return (x, z) -> {
            int dx = Math.max(0, Math.max(s.bx0 - 1 - x, x - (s.bx1() + 1)));
            int dz = Math.max(0, Math.max(s.bz0 - 1 - z, z - (s.bz1() + 1)));
            return s.floor + Math.min(BOWL_RIM, 3 * Math.max(dx, dz));
        };
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
     * no level at all). A look whose block is gone (a removed mod's: it reads back as air) is loaded as the plain one, so no
     * earthwork that laid a block lays air instead.
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
            CompoundTag gone = saved.copy();
            CompoundTag rock = new CompoundTag();
            rock.putString("Name", "removedmod:rock");
            gone.getListOrEmpty("states").set(0, rock);   // the first look's top block
            Plan plain = Plan.load(gone, s.pl, s.template);
            h.assertTrue(plain != null && plain.earthworks().size() == want.size(), "the plan with a block gone didn't load as it was: " + plain);
            for (int k = 0; k < want.size(); k++) {
                Earthwork e = plain.earthworks().get(k);
                h.assertTrue(want.get(k).state().isAir() || !e.state().isAir(), "with its block gone, task " + k + " lays air: " + e + " (was "
                        + want.get(k).state() + ")");
            }
            h.succeed();
        }));
    }

    /** How many bytes a tag takes in a save file: as written, or compressed as a chunk's or a level's data is. */
    private static int written(CompoundTag t, boolean compressed) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (compressed) {
                NbtIo.writeCompressed(t, out);
            } else {
                NbtIo.write(t, new java.io.DataOutputStream(out));
            }
            return out.size();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int written(CompoundTag t) {
        return written(t, false);
    }

    public static CompoundTag roundTrip(CompoundTag t) {
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
     * clearing task setting air, no later phase touches them, and once put down none is left. A log set by hand in the
     * box's air after the plan is no tree of the plan's, and is left standing. A pile of 5 logs with no leaves on the pad is
     * someone's, not a tree: the site is refused, naming it; in the margin such a pile keeps its column, as the player's
     * blocks it is. A tree of more than 256 blocks (a trunk of 8 in a 7x7 crown 6 high) refuses the site, naming it.
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
            BlockPos hand = new BlockPos(s.bx0 + 1, s.floor + 3, s.bz0 + 1);
            h.assertTrue(clearing.containsKey(hand.asLong()) && !small.contains(hand), "the cell " + hand + " isn't a cell of the box's air the clearing sets");
            s.set(hand.getX(), hand.getY(), hand.getZ(), Blocks.OAK_LOG.defaultBlockState());
            p.applyNow(s.level);
            for (BlockPos c : small) {
                BlockState now = s.level.getBlockState(c);
                h.assertTrue(!now.is(net.minecraft.tags.BlockTags.LOGS) && !(now.getBlock() instanceof LeavesBlock), "the tree is still at " + c + ": " + now);
            }
            h.assertTrue(s.level.getBlockState(hand).is(Blocks.OAK_LOG), "the log set by hand at " + hand + " after the plan was cleared: "
                    + s.level.getBlockState(hand));
            // a pile of logs with no leaves, on the pad: someone's
            s.lay((x, z) -> s.floor);
            List<BlockPos> pile = List.of(new BlockPos(s.bx0 + 2, s.floor + 1, s.bz0 + 6), new BlockPos(s.bx0 + 3, s.floor + 1, s.bz0 + 6),
                    new BlockPos(s.bx0 + 4, s.floor + 1, s.bz0 + 6), new BlockPos(s.bx0 + 3, s.floor + 2, s.bz0 + 6), new BlockPos(s.bx0 + 3, s.floor + 1, s.bz0 + 7));
            for (BlockPos c : pile) {
                s.set(c.getX(), c.getY(), c.getZ(), Blocks.OAK_LOG.defaultBlockState());
            }
            Refusal logs = s.expectRefusal(s.plan(true), "the site with a pile of 5 logs on it");
            h.assertTrue(logs.why().contains("no leaves") && pile.contains(logs.spot())
                            && logs.why().contains(logs.spot().getX() + " " + logs.spot().getY() + " " + logs.spot().getZ()),
                    "the refusal doesn't name the pile: \"" + logs.why() + "\" at " + logs.spot());
            // the same kind of pile in the margin keeps its columns, and is the player's blocks, not a tree
            s.lay((x, z) -> s.floor);
            List<BlockPos> heap = List.of(new BlockPos(s.bx1() + 2, s.floor + 1, s.bz0 + 4), new BlockPos(s.bx1() + 3, s.floor + 1, s.bz0 + 4),
                    new BlockPos(s.bx1() + 2, s.floor + 2, s.bz0 + 4));
            for (BlockPos c : heap) {
                s.set(c.getX(), c.getY(), c.getZ(), Blocks.OAK_LOG.defaultBlockState());
            }
            Plan m = s.expectPlan(s.plan(true), "the site with a pile of logs in the margin");
            for (BlockPos c : heap) {
                h.assertTrue(m.kind(c.getX(), c.getZ()) == Reshape.Kind.KEPT, "the pile's column " + c.getX() + " " + c.getZ() + " is " + m.kind(c.getX(), c.getZ()));
            }
            h.assertTrue(m.goesRound().contains("of your blocks") && !m.goesRound().contains("tree"),
                    "the ramp goes round \"" + m.goesRound() + "\": a pile of logs is the player's blocks");
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
     * A tree whose crown reaches over the pad's edge is felled whole, and the crown of a neighbour that touches it stays (the film of 2026-10-01: an oak
     * felled in the box, and a clump of its leaves left hanging in the air beside the terraces). An oak with its trunk on the box's east edge and a crown 3
     * each way (about 100 cells, the east 2 columns of it over the margin, past the berm), and a neighbour's oak rooted outside the pad whose crown touches
     * its crown. Twice: with every leaf's distance as the world keeps it, and with every leaf's distance 1, as a world that was frozen before its leaves
     * ticked, a command and a structure's template leave it (the film's oak: the flood believed a leaf's distance, which said "nearer another tree's
     * logs", and took only the leaves a step from a log). The plan fells every cell of the first and none of the second's, both times.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aTreeOverThePadsEdgeIsFelledWholeAndANeighboursCrownStays(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            for (int stale = 0; stale <= 1; stale++) {
                s.lay((x, z) -> s.floor);
                List<BlockPos> felled = tree(s, s.bx1(), s.floor + 1, s.bz0 + 4, 4, 3, 2);
                List<BlockPos> neighbour = tree(s, s.bx1() + 6, s.floor + 1, s.bz0 + 4, 4, 2, 2);
                String which = stale == 0 ? "with every leaf's distance right" : "with every leaf's distance 1";
                if (stale == 1) {
                    for (List<BlockPos> cells : List.of(felled, neighbour)) {
                        for (BlockPos c : cells) {
                            BlockState b = s.level.getBlockState(c);
                            if (b.getBlock() instanceof LeavesBlock) {
                                s.set(c.getX(), c.getY(), c.getZ(), b.setValue(LeavesBlock.DISTANCE, 1));
                            }
                        }
                    }
                }
                int edge = s.bx1() + 1;                          // the pad's east edge: the box and its berm
                h.assertTrue(felled.stream().anyMatch(c -> c.getX() > edge), "the crown doesn't reach over the pad's edge at x " + edge);
                Plan p = s.expectPlan(s.plan(true), "the site with a tree over its edge, " + which);
                java.util.Set<Long> got = new java.util.HashSet<>();
                for (long t : p.trees()) {
                    got.add(t);
                }
                List<BlockPos> missed = felled.stream().filter(c -> !got.contains(c.asLong())).toList();
                List<BlockPos> taken = neighbour.stream().filter(c -> got.contains(c.asLong())).toList();
                h.assertTrue(missed.isEmpty(), "the plan " + which + " fells " + (felled.size() - missed.size()) + " of the tree's " + felled.size() + " cells and leaves "
                        + missed.size() + " of its crown standing, the first at " + (missed.isEmpty() ? "" : missed.get(0).toShortString() + " (" + s.level.getBlockState(missed.get(0)) + ")")
                        + ": a crown hanging in the air with no trunk");
                h.assertTrue(taken.isEmpty(), "the plan " + which + " fells " + taken.size() + " cells of the neighbour's tree, the first at " + (taken.isEmpty() ? "" : taken.get(0).toShortString())
                        + ": its crown stays");
                h.assertTrue(got.size() == felled.size(), "the plan " + which + " fells " + got.size() + " cells, not the tree's " + felled.size());
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Water in the wall beside a cut berm is walled before the cut opens it (section 9). The site sits 3 under the land, so
     * the berm is cut 3; in the wall east of it a pocket of 2 water sources runs 2 into the ground, 2 over the floor. The
     * plan walls the cell facing the cut (one seal, of the wall's own dirt, in the plinth phase, before any of the berm's
     * cut), and a saved plan rebuilds it. After the earthworks, and 40 ticks with the pocket made to flow, there is no water
     * but the pocket's back cell, so the floor is dry.
     *
     * <p>Two controls: the seal taken out by hand lets the water pour down the cut onto the floor, and the check sees it;
     * and lava in the same place refuses the site, naming it.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aWaterPocketBesideACutBermIsWalled(GameTestHelper h) {
        Site s = new Site(h);
        int land = s.floor + 3;
        BlockPos face = new BlockPos(s.bx1() + 2, s.floor + 2, s.bz0 + 4);     // in the wall, beside the berm at bx1 + 1
        BlockPos back = face.east();
        s.lay((x, z) -> land);
        s.set(face.getX(), face.getY(), face.getZ(), Blocks.WATER.defaultBlockState());
        s.set(back.getX(), back.getY(), back.getZ(), Blocks.WATER.defaultBlockState());
        LongOpenHashSet pocket = new LongOpenHashSet(new long[]{back.asLong()});
        h.startSequence()
                .thenExecuteAfter(20, () -> s.guard(() -> {
                    Plan p = s.expectPlan(s.plan(true), "the sunken site with water in the wall beside its berm");
                    h.assertTrue(p.seals().length == 1 && p.seals()[0] == face.asLong(), "the plan walls " + Arrays.toString(Arrays.stream(p.seals())
                            .mapToObj(BlockPos::of).toArray()) + ", not the pocket's cell facing the cut, " + face);
                    int seal = -1;
                    int firstCut = Integer.MAX_VALUE;
                    List<Earthwork> works = p.earthworks();
                    for (int k = 0; k < works.size(); k++) {
                        Earthwork e = works.get(k);
                        if (e.pos().equals(face)) {
                            h.assertTrue(e.phase() == Phase.PLINTH && e.state().is(Blocks.DIRT), "the seal is " + e);
                            seal = k;
                        }
                        if (e.phase() == Phase.PAD) {
                            firstCut = Math.min(firstCut, k);
                        }
                    }
                    h.assertTrue(seal >= 0 && seal < firstCut, "the seal (task " + seal + ") isn't laid before the berm's cut (task " + firstCut + ")");
                    Plan again = Plan.load(roundTrip(p.save()), s.pl, s.template);
                    h.assertTrue(again != null && again.earthworks().equals(works), "the saved plan rebuilds other earthworks: "
                            + (again == null ? "it didn't load" : firstDifference(works, again.earthworks())));
                    p.applyNow(s.level);
                    s.stamp();
                    int[] a = s.heights(p);
                    expect(h, s, p, a, heightProblem(s, p, a));
                    expect(h, s, p, a, walkProblem(s, p, a));
                    h.assertTrue(s.level.getBlockState(face).is(Blocks.DIRT), "the pocket's face is " + s.level.getBlockState(face));
                    s.level.scheduleTick(back, Fluids.WATER, 1);
                }))
                .thenExecuteAfter(40, () -> s.guard(() -> {
                    String wet = waterProblem(s, pocket, s.bx0 - 16, s.bz0 - 13, s.bx1() + 13, s.bz1() + 13);
                    h.assertTrue(wet == null, wet + dumpLand(s, face));
                    // the control: the wall taken out by hand
                    s.set(face.getX(), face.getY(), face.getZ(), Blocks.AIR.defaultBlockState());
                    s.level.scheduleTick(back, Fluids.WATER, 1);
                }))
                .thenExecuteAfter(40, () -> s.guard(() -> {
                    boolean floorWet = false;
                    for (int x = s.bx1() - 1; x <= s.bx1() + 1; x++) {
                        for (int z = face.getZ() - 1; z <= face.getZ() + 1; z++) {
                            floorWet |= !s.level.getFluidState(new BlockPos(x, s.floor + 1, z)).isEmpty();
                        }
                    }
                    h.assertTrue(floorWet && waterProblem(s, pocket, s.bx0 - 16, s.bz0 - 13, s.bx1() + 13, s.bz1() + 13) != null,
                            "with the seal taken out, no water reached the floor: the check shows nothing" + dumpLand(s, face));
                    // lava in the wall refuses the site
                    s.lay((x, z) -> land);
                    s.set(face.getX(), face.getY(), face.getZ(), Blocks.LAVA.defaultBlockState());
                    Refusal no = s.expectRefusal(s.plan(true), "the sunken site with lava in the wall beside its berm");
                    h.assertTrue(no.spot().equals(face) && no.why().startsWith("Lava at " + face.getX() + " " + face.getY() + " " + face.getZ()),
                            "the refusal doesn't name the lava at " + face + ": \"" + no.why() + "\" at " + no.spot());
                    s.clear();
                }))
                .thenSucceed();
    }

    /**
     * The pad's own refusals, each naming its spot: water 3 deep in the box (WATER_MAX is 2); lava on the ground in the box;
     * a pond standing 2 over the floor beside the berm (its bank would be cut); a cave 1 and 2 under the box's ground; and
     * another building (a fake settlement's, the ground's own cells) beside the berm.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void thePadsRefusalsNameTheirSpots(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockState water = Blocks.WATER.defaultBlockState();
            // water 3 deep
            s.lay((x, z) -> s.floor);
            for (int x = s.bx0 + 3; x <= s.bx0 + 5; x++) {
                for (int z = s.bz0 + 3; z <= s.bz0 + 5; z++) {
                    for (int y = s.floor - 2; y <= s.floor; y++) {
                        s.set(x, y, z, water);
                    }
                }
            }
            refusedAt(h, s, s.plan(true), "a pool 3 deep in the box", "3 deep", new BlockPos(s.bx0 + 3, s.floor, s.bz0 + 3));
            // lava
            s.lay((x, z) -> s.floor);
            s.set(s.bx0 + 4, s.floor, s.bz0 + 4, Blocks.LAVA.defaultBlockState());
            refusedAt(h, s, s.plan(true), "lava in the box", "Lava at", new BlockPos(s.bx0 + 4, s.floor, s.bz0 + 4));
            // a pond over the floor beside the berm
            s.lay((x, z) -> s.floor + 2);
            for (int x = s.bx1() + 2; x <= s.bx1() + 3; x++) {
                for (int z = s.bz0 + 4; z <= s.bz0 + 5; z++) {
                    s.set(x, s.floor + 2, z, water);
                }
            }
            refusedAt(h, s, s.plan(true), "a pond 2 over the floor beside the berm", "stands 2 above", new BlockPos(s.bx1() + 2, s.floor + 2, s.bz0 + 4));
            // a cave under the box's ground
            s.lay((x, z) -> s.floor);
            for (int x = s.bx0 + 3; x <= s.bx0 + 5; x++) {
                for (int z = s.bz0 + 3; z <= s.bz0 + 5; z++) {
                    s.set(x, s.floor - 2, z, Blocks.AIR.defaultBlockState());
                    s.set(x, s.floor - 1, z, Blocks.AIR.defaultBlockState());
                }
            }
            refusedAt(h, s, s.plan(true), "a cave under the box", "hollow", new BlockPos(s.bx0 + 3, s.floor - 2, s.bz0 + 3));
            // another building beside the berm
            s.lay((x, z) -> s.floor);
            LongOpenHashSet room = new LongOpenHashSet();
            for (int x = s.bx1() + 2; x <= s.bx1() + 3; x++) {
                for (int z = s.bz0 + 4; z <= s.bz0 + 5; z++) {
                    room.add(BlockPos.asLong(x, s.floor - 1, z));
                    room.add(BlockPos.asLong(x, s.floor, z));
                }
            }
            Refusal no = s.expectRefusal(s.plan(warrenOf(room), true), "another building beside the berm");
            BlockPos q = no.spot();
            boolean near = false;
            for (long c : room) {
                BlockPos r = BlockPos.of(c);
                near |= Math.abs(r.getX() - q.getX()) <= 1 && Math.abs(r.getY() - q.getY()) <= 1 && Math.abs(r.getZ() - q.getZ()) <= 1;
            }
            h.assertTrue(no.why().startsWith("Another building is too near at " + q.getX() + " " + q.getY() + " " + q.getZ()) && near
                    && q.getX() == s.bx1() + 1, "the refusal doesn't name the berm's cell beside the building: \"" + no.why() + "\" at " + q);
            s.clear();
            h.succeed();
        }));
    }

    /**
     * What a held plan shows (Siting#preview, B9 of the Great Plan's plan) is the plan's own reading of the same ground, on four of them: flat
     * ground (the floor reaches the ground: no plinth), a slope under the box (a plinth 0 to 4 deep), a pit 12 deep under the box's corner
     * (refused: the ground falls away, and its columns are over SKIRT_MAX) and lava in the box (refused, naming the lava). Where the plan
     * refuses, the preview's refusal is the same words at the same spot; where it sites, there is none; the plinth's columns are the
     * 81 under the box, each with the ground's height and the plinth's depth that the land has (the lava's column read down to the ground
     * under it); the preview with the land not reshaped agrees with the plan not reshaped; and neither preview changes a block.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "siting")
    public static void aPreviewAgreesWithThePlanForTheSameGround(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            Land[] grounds = {
                    (x, z) -> s.floor,
                    (x, z) -> s.floor - 1 - Math.min(4, Math.max(0, x - s.bx0)),
                    pit(s, 12),
                    (x, z) -> s.floor,
            };
            String[] names = {"flat ground", "a slope under the box", "a pit 12 deep under the corner", "lava in the box"};
            BlockPos lava = new BlockPos(s.bx0 + 4, s.floor, s.bz0 + 4);
            int sited = 0, refused = 0;
            for (int g = 0; g < grounds.length; g++) {
                s.lay(grounds[g]);
                if (g == 3) {
                    s.set(lava.getX(), lava.getY(), lava.getZ(), Blocks.LAVA.defaultBlockState());
                }
                Land land = grounds[g];
                Map<Long, BlockState> before = s.snapshot((x, z) -> x >= s.bx0 - 6 && x <= s.bx1() + 6 && z >= s.bz0 - 6 && z <= s.bz1() + 6);
                for (boolean reshape : new boolean[]{true, false}) {
                    Siting.Result plan = s.plan(reshape);
                    Siting.Preview preview = Siting.preview(s.level, s.pl, reshape);
                    if (plan instanceof Refusal no) {
                        h.assertTrue(preview.refusal() != null && preview.refusal().why().equals(no.why()) && preview.refusal().spot().equals(no.spot()),
                                names[g] + " (reshape " + reshape + "): the plan was refused \"" + no.why() + "\" at " + no.spot() + ", the preview's refusal is " + preview.refusal()
                                        + (preview.refusal() == null ? "" : " \"" + preview.refusal().why() + "\" at " + preview.refusal().spot()));
                        if (reshape) {
                            refused++;
                        }
                    } else {
                        h.assertTrue(preview.refusal() == null, names[g] + " (reshape " + reshape + "): the plan was sited and the preview is refused: \"" + preview.refusal() + "\"");
                        if (reshape) {
                            sited++;
                        }
                    }
                    h.assertTrue(preview.box().equals(s.pl.box()) && preview.door().equals(s.pl.door()) && preview.floor() == s.floor,
                            names[g] + ": the preview's box " + preview.box() + ", door " + preview.door() + " or floor " + preview.floor() + " is not the placement's");
                    h.assertTrue(preview.columns().size() == 81, names[g] + ": the preview has " + preview.columns().size() + " plinth columns, not the 81 under the box");
                    java.util.Set<Long> seen = new java.util.HashSet<>();
                    for (Siting.Column c : preview.columns()) {
                        h.assertTrue(s.inBox(c.x(), c.z()) && seen.add(BlockPos.asLong(c.x(), 0, c.z())), names[g] + ": the column " + c + " is not one of the box's, or twice");
                        h.assertTrue(c.tooDeep() == (c.depth() > Siting.SKIRT_MAX) && c.depth() == s.floor - 1 - c.ground(),
                                names[g] + ": the column " + c + " has a depth that is not the floor less the ground");
                        int expected = g == 3 && c.x() == lava.getX() && c.z() == lava.getZ() ? s.floor - 1 : land.at(c.x(), c.z());
                        h.assertTrue(c.ground() == expected, names[g] + ": the column " + c + " reads the ground at " + c.ground() + ", the land is at " + expected);
                    }
                }
                h.assertTrue(g != 2 || Siting.preview(s.level, s.pl).columns().stream().filter(Siting.Column::tooDeep).count() == 9,
                        names[g] + ": not the pit's 9 columns are over the most the plinth may be");
                String moved = s.changed(before);
                h.assertTrue(moved == null, names[g] + ": the previews changed the world: " + moved);
            }
            h.assertTrue(sited >= 1 && refused >= 2, "the four grounds were sited " + sited + " times and refused " + refused + ": the test does not reach both");
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A plinth column's colour (core/client/OutlineShade, which the plan outline draws): none where there is no plinth, shading from blue
     * for a thin one to yellow at the deepest the plan allows, and red past it; the shades never go back as the plinth deepens.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "siting")
    public static void thePlinthsColumnsAreShadedByDepthAndRedPastTheMost(GameTestHelper h) {
        h.assertTrue(claude.settlementcore.client.OutlineShade.of(0) == null && claude.settlementcore.client.OutlineShade.of(-3) == null,
                "a column with no plinth has a colour");
        float[] thin = claude.settlementcore.client.OutlineShade.of(1);
        float[] deepest = claude.settlementcore.client.OutlineShade.of(Siting.SKIRT_MAX);
        float[] over = claude.settlementcore.client.OutlineShade.of(Siting.SKIRT_MAX + 1);
        h.assertTrue(thin[2] > thin[0] && deepest[0] > deepest[2] && deepest[1] > 0.5f, "a thin plinth is not blue, or the deepest allowed is not yellow: " + java.util.Arrays.toString(thin) + ", "
                + java.util.Arrays.toString(deepest));
        h.assertTrue(over[0] == 1f && over[1] < 0.3f && over[2] < 0.3f, "a plinth past the most is not red: " + java.util.Arrays.toString(over));
        for (int d = 1; d < Siting.SKIRT_MAX; d++) {
            float[] a = claude.settlementcore.client.OutlineShade.of(d), b = claude.settlementcore.client.OutlineShade.of(d + 1);
            h.assertTrue(b[0] >= a[0] && b[2] <= a[2], "the shade went back between a plinth of " + d + " and " + (d + 1));
        }
        h.succeed();
    }

    /**
     * A client has no server's resources, so a template for a preview is read from the data folder of the jar of the mod that registered the
     * room (RoomTemplates#readFromMods): the engine's own test building read that way is the very cells the server reads, and the lookup on a
     * server's level is the server's.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "siting")
    public static void aTemplateReadFromTheModsJarIsTheOneTheServerReads(GameTestHelper h) {
        platform();
        List<RoomTemplates.Entry> server = RoomTemplates.get(h.getLevel(), PLATFORM);
        List<RoomTemplates.Entry> jar = RoomTemplates.readFromMods(PLATFORM);
        h.assertTrue(server.size() == 9 * 5 * 9, "the server read " + server.size() + " cells of the test building, not " + 9 * 5 * 9);
        h.assertTrue(jar.equals(server), "the mod's jar gave " + jar.size() + " cells and not the server's " + server.size() + " (or not the same ones)");
        h.assertTrue(RoomTemplates.lookup(h.getLevel(), PLATFORM).equals(server), "the lookup on a server's level is not the server's template");
        h.succeed();
    }

    /**
     * A room with no template anywhere is looked for once on a client, not every tick (the review of 20da67f: {@code lookup} remembered only a read
     * that found something, so a plan held on a client for such a room went through ModList and the mods' jars every tick): the first lookup of an
     * id nobody registered reads the jars once and is empty, and the next three read nothing and are the same empty answer; once the id is
     * forgotten it is looked for again.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "siting")
    public static void aTemplateThatIsNotThereIsLookedForOnceOnAClient(GameTestHelper h) {
        String missing = "no_such_room_for_a_client_lookup";
        RoomTemplates.forget(missing);
        int before = RoomTemplates.modReads(missing);
        List<RoomTemplates.Entry> first = RoomTemplates.lookupFromMods(missing);
        h.assertTrue(first.isEmpty() && RoomTemplates.modReads(missing) == before + 1, "the first look for a missing template made " + (RoomTemplates.modReads(missing) - before)
                + " reads and found " + first.size() + " cells, not 1 read and none");
        for (int i = 0; i < 3; i++) {
            List<RoomTemplates.Entry> again = RoomTemplates.lookupFromMods(missing);
            h.assertTrue(again.isEmpty() && RoomTemplates.modReads(missing) == before + 1, "look " + (i + 2) + " for a missing template read the mods' jars again: " + (RoomTemplates.modReads(missing) - before)
                    + " reads in all, not 1");
        }
        RoomTemplates.forget(missing);
        RoomTemplates.lookupFromMods(missing);
        h.assertTrue(RoomTemplates.modReads(missing) == before + 2, "a forgotten missing template was not looked for again: " + (RoomTemplates.modReads(missing) - before) + " reads, not 2");
        RoomTemplates.forget(missing);
        h.succeed();
    }


    /** The site was refused, saying {@code words}, at {@code spot}, and naming it. */
    private static void refusedAt(GameTestHelper h, Site s, Siting.Result r, String scene, String words, BlockPos spot) {
        Refusal no = s.expectRefusal(r, scene);
        h.assertTrue(no.why().contains(words) && no.spot().equals(spot) && no.why().contains(spot.getX() + " ") && no.why().contains(" " + spot.getZ()),
                scene + ": the refusal is \"" + no.why() + "\" at " + no.spot() + ", not \"" + words + "\" at " + spot + dumpLand(s, no.spot()));
    }

    /**
     * A site on land that isn't loaded (4096 blocks east of the plot) is refused as not loaded, naming a column of it, and
     * siting loads nothing: the chunk is still not there afterwards.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "siting")
    public static void aSiteOnLandNotLoadedIsRefused(GameTestHelper h) {
        Site s = new Site(h);
        PlanPlacement far = PlanPlacement.onGround(platform(), new BlockPos(s.bx0 + 4096, s.floor, s.bz0 + 4), Direction.EAST);
        int cx = SectionPos.blockToSectionCoord(far.box().minX());
        int cz = SectionPos.blockToSectionCoord(far.box().minZ());
        h.assertTrue(!s.level.hasChunk(cx, cz), "the land 4096 east of the plot is loaded: the test shows nothing");
        Refusal no = s.expectRefusal(Siting.plan(s.level, far, RoomShoring.Warren.alone(), true), "a site 4096 east of the plot");
        int sx = SectionPos.blockToSectionCoord(no.spot().getX());
        int sz = SectionPos.blockToSectionCoord(no.spot().getZ());
        h.assertTrue(no.why().contains("not loaded") && no.why().contains(no.spot().getX() + " " + no.spot().getZ()) && far.box().intersects(
                no.spot().getX(), no.spot().getZ(), no.spot().getX(), no.spot().getZ()), "the refusal is \"" + no.why() + "\" at " + no.spot());
        h.assertTrue(!s.level.hasChunk(cx, cz) && !s.level.hasChunk(sx, sz), "siting loaded the chunk it was refused for");
        h.succeed();
    }

    /**
     * The biggest plan saved (the widest area, 64 by 64 columns, every column's heights and looks different, the most looks
     * a plan keeps, 256 blocks of trees and 64 seals) stays under 64 KB, and loads back the same. Made from a tag (Plan.load),
     * since the test building's widest real area is 38 by 35.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "siting")
    public static void theWidestSavedPlanStaysSmall(GameTestHelper h) {
        Site s = new Site(h);
        int w = Siting.AREA_MAX;
        int n = w * w;
        byte[] kinds = new byte[n];
        byte[] now = new byte[n];
        byte[] ground = new byte[n];
        byte[] top = new byte[n];
        byte[] look = new byte[n];
        for (int i = 0; i < n; i++) {
            kinds[i] = (byte) (i % Reshape.Kind.values().length);
            now[i] = (byte) (i % 201 - 100);
            ground[i] = (byte) (i * 7 % 201 - 100);
            top[i] = (byte) (i * 13 % 201 - 100);
            look[i] = (byte) ((i % 2 == 0 ? 1 : -1) * (i % 126 + 1));
        }
        ListTag states = new ListTag();
        for (net.minecraft.world.level.block.Block b : BuiltInRegistries.BLOCK) {
            if (states.size() >= 126 * 3) {
                break;
            }
            if (!b.defaultBlockState().isAir()) {
                states.add(NbtUtils.writeBlockState(b.defaultBlockState()));
            }
        }
        long[] trees = new long[Siting.TREE_MAX];
        for (int k = 0; k < trees.length; k++) {
            trees[k] = BlockPos.asLong(s.bx0 + k % 9, s.floor + 1 + k / 81, s.bz0 + k / 9 % 9);
        }
        long[] seals = new long[64];
        byte[] sealLooks = new byte[64];
        for (int k = 0; k < seals.length; k++) {
            seals[k] = BlockPos.asLong(s.bx0 - 3 - k, s.floor + 2, s.bz0);
            sealLooks[k] = (byte) (k + 1);
        }
        CompoundTag t = new CompoundTag();
        t.putInt("x0", s.bx0 - 27);
        t.putInt("z0", s.bz0 - 27);
        t.putInt("w", w);
        t.putInt("h", w);
        t.putInt("floor", s.floor);
        t.putBoolean("reshaped", true);
        t.putByteArray("kind", kinds);
        t.putByteArray("new", now);
        t.putByteArray("ground", ground);
        t.putByteArray("top", top);
        t.putByteArray("look", look);
        t.put("states", states);
        t.putLongArray("trees", trees);
        t.putLongArray("seals", seals);
        t.putByteArray("seal_looks", sealLooks);
        t.putIntArray("numbers", new int[]{12, 1000, 1000, 5000, 5000, 200, 1, 5, 1});
        t.putString("goes_round", "126 of your blocks, 12 trees, 3 other buildings, 2 caves and 9 ponds");
        Plan p = Plan.load(t, s.pl, s.template);
        h.assertTrue(p != null && p.width() == w && p.depth() == w, "the widest plan didn't load: " + p);
        CompoundTag saved = p.save();
        int bytes = written(saved);
        h.assertTrue(bytes < 64 * 1024, "the widest plan saves as " + bytes + " bytes (" + written(saved, true) + " compressed)");
        Plan back = Plan.load(roundTrip(saved), s.pl, s.template);
        h.assertTrue(back != null && back.numbers().equals(p.numbers()) && Arrays.equals(back.trees(), p.trees()) && Arrays.equals(back.seals(), p.seals())
                        && back.newHeight(p.x0() + 63, p.z0() + 63) == p.newHeight(p.x0() + 63, p.z0() + 63)
                        && back.look(p.x0() + 5, p.z0() + 7).equals(p.look(p.x0() + 5, p.z0() + 7)),
                "the widest plan doesn't load back the same: " + back);
        h.succeed();
    }

    /**
     * An oak: a trunk of {@code height} logs from {@code (x, y, z)}, and a crown {@code reach} out each way over the top
     * {@code layers} logs and one layer over them (a plus, for a small crown; a full square for a big one). Each leaf's
     * distance is its real one to the trunk, so none decays. Returns every cell, trunk first.
     */
    public static List<BlockPos> tree(Site s, int x, int y, int z, int height, int reach, int layers) {
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
