package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.CourtRing;
import claude.underempire.core.room.PlanPlacement;
import claude.underempire.core.room.RaiseJob;
import claude.underempire.core.room.RoomTemplates;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.underempire.core.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The open court (docs/plans/greatplan-first-spawning.md, J P4: the ring round the pool the user chose): a raised building made in code
 * round a court of any size ({@link CourtRing}, a {@link Rooms#family}), whose court the raise never touches. Each test lays a flat
 * plot of grass at +360 (its own 44x44, as the siting tests do), digs a pool two deep in it, and raises the ring round the pool's
 * bounding box plus 1 for a settlement of the tests' own; the pool, its rim and the air over them are read before and after, cell
 * by cell.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class CourtGameTests {

    static final int UP = 360;
    static final int DEEP = 8;

    /** The engine's test ring, in vanilla blocks: two terraces. */
    public static final CourtRing.Kind RING = new CourtRing.Kind("court_test_ring", "Test Ring", 12, Rooms.Function.of("RAISE_TEST"),
            "The engine's test ring of two terraces.", 2, new CourtRing.Style(Blocks.STONE_BRICKS.defaultBlockState(),
            Blocks.POLISHED_ANDESITE.defaultBlockState(), List.of(Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState()),
            Blocks.STONE_BRICK_STAIRS.defaultBlockState(), Blocks.SEA_LANTERN.defaultBlockState()));

    private static boolean registered;

    static synchronized void register() {
        if (!registered) {
            CourtRing.register(Core.NAMESPACE, RING);
            registered = true;
        }
    }

    /** A plot of flat grass with a pool dug in it, and the ring placed round the pool's bounding box plus 1. */
    public static final class Pool {
        public final GameTestHelper h;
        public final ServerLevel level;
        public final int px0, pz0, px1, pz1, floor;
        public final int wx0, wz0, wx1, wz1;          // the water
        public final PlanPlacement pl;
        public final BoundingBox court;               // world columns
        final Map<BlockPos, BlockState> before = new HashMap<>();

        public Pool(GameTestHelper h, int poolX, int poolZ) {
            register();
            this.h = h;
            this.level = h.getLevel();
            BlockPos c1 = h.absolutePos(new BlockPos(0, UP, 0));
            BlockPos c2 = h.absolutePos(new BlockPos(43, UP, 43));
            px0 = Math.min(c1.getX(), c2.getX());
            pz0 = Math.min(c1.getZ(), c2.getZ());
            px1 = Math.max(c1.getX(), c2.getX());
            pz1 = Math.max(c1.getZ(), c2.getZ());
            floor = c1.getY();
            wx0 = px0 + 16;
            wz0 = pz0 + 16;
            wx1 = wx0 + poolX - 1;
            wz1 = wz0 + poolZ - 1;
            String id = CourtRing.id(RING, poolX + 2, poolZ + 2);
            Rooms.Room room = Rooms.get(id);
            h.assertTrue(room != null && Rooms.isRaised(id), "the family made no raised room " + id);
            int band = room.doorZ() - (poolZ + 2) / 2;      // the ring's width round the court
            BlockPos door = new BlockPos(wx0 - 1 - band, floor + 1, wz0 - 1 - band + room.doorZ());
            pl = PlanPlacement.onGround(room, door.below(), Direction.EAST);
            court = new BoundingBox(wx0 - 1, floor - DEEP, wz0 - 1, wx1 + 1, floor + room.sizeY() + 2, wz1 + 1);
            BoundingBox local = Rooms.court(id);
            h.assertTrue(local != null && pl.world(new BlockPos(local.minX(), 0, local.minZ())).getX() == court.minX()
                            && pl.world(new BlockPos(local.maxX(), 0, local.maxZ())).getZ() == court.maxZ(),
                    "the ring's court " + local + " is not the pool and its rim, " + court);
        }

        public void set(int x, int y, int z, BlockState s) {
            level.setBlock(new BlockPos(x, y, z), s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        /** The plot cleared to air, then grass at the floor on dirt on stone, the pool dug two deep and filled. */
        public void lay() {
            lay((x, z) -> floor);
        }

        /** {@link #lay()} on land of its own: the grass at {@code land}'s height in each column (the pool and its rim at the floor). */
        public void lay(SitingGameTests.Land land) {
            clear();
            for (int x = px0; x <= px1; x++) {
                for (int z = pz0; z <= pz1; z++) {
                    int g = court.minX() <= x && x <= court.maxX() && court.minZ() <= z && z <= court.maxZ() ? floor : land.at(x, z);
                    for (int y = floor - DEEP; y <= g; y++) {
                        set(x, y, z, (y == g ? Blocks.GRASS_BLOCK : y >= g - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState());
                    }
                }
            }
            for (int x = wx0; x <= wx1; x++) {
                for (int z = wz0; z <= wz1; z++) {
                    set(x, floor - 1, z, Blocks.WATER.defaultBlockState());
                    set(x, floor, z, Blocks.WATER.defaultBlockState());
                }
            }
            before.clear();
            for (BlockPos p : BlockPos.betweenClosed(court.minX(), court.minY(), court.minZ(), court.maxX(), court.maxY(), court.maxZ())) {
                before.put(p.immutable(), level.getBlockState(p));
            }
        }

        public void clear() {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = px0; x <= px1; x++) {
                for (int z = pz0; z <= pz1; z++) {
                    for (int y = floor - DEEP - 2; y <= floor + 24; y++) {
                        if (!level.getBlockState(p.set(x, y, z)).isAir()) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        }
                    }
                }
            }
        }

        /** Every cell of the pool, its rim and the air over them as it was before the raise; the first that is not, or null. */
        public String courtChanged() {
            for (Map.Entry<BlockPos, BlockState> e : before.entrySet()) {
                BlockState now = level.getBlockState(e.getKey());
                if (now != e.getValue()) {
                    return e.getKey().toShortString() + " was " + e.getValue() + " and is " + now;
                }
            }
            return null;
        }

        /** Every cell of the ring as its template has it; the first that is not, or null. */
        public String ringWrong() {
            for (RoomTemplates.Entry e : RoomTemplates.get(level, pl.room().id())) {
                BlockPos w = pl.world(e.local());
                BlockState want = e.state().rotate(pl.rotation());
                if (level.getBlockState(w) != want) {
                    return w.toShortString() + " is " + level.getBlockState(w) + ", not " + want;
                }
            }
            return null;
        }

        void guard(Runnable r) {
            try {
                r.run();
            } catch (RuntimeException | Error e) {
                clear();
                throw e;
            }
        }
    }

    private static TestSettlement settlement(GameTestHelper h) {
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        t.holds = true;
        return t;
    }

    /** The ring round a pool of {@code x} by {@code z} raised whole: equal to its template, the pool and its rim untouched. */
    private static void raisedWhole(GameTestHelper h, int x, int z) {
        Pool p = new Pool(h, x, z);
        p.lay();
        h.runAfterDelay(20, () -> p.guard(() -> {
            TestSettlement t = settlement(h);
            Works works = t.works();
            String refused = works.raise(p.level, p.pl, true, true);
            h.assertTrue(refused == null, "the ring round a pool " + x + " by " + z + " was refused: " + refused);
            RaiseJob job = works.sites().get(0).job();
            job.finishAll(p.level);
            works.second(p.level, 100);
            String wrong = p.ringWrong();
            h.assertTrue(wrong == null, "the ring round a pool " + x + " by " + z + " is not its template: " + wrong);
            String changed = p.courtChanged();
            h.assertTrue(changed == null, "the raise touched the pool " + x + " by " + z + " or its rim: " + changed);
            h.assertTrue(works.sites().isEmpty() && t.heard.stream().anyMatch(l -> l.contains("is raised")), "the ring was not handed over: " + t.heard);
            t.setRemoved();
            p.clear();
            h.succeed();
        }));
    }

    @GameTest(template = "digsite", timeoutTicks = 100, batch = "court")
    public static void aRingIsRaisedRoundAPoolThreeByThree(GameTestHelper h) {
        raisedWhole(h, 3, 3);
    }

    @GameTest(template = "digsite", timeoutTicks = 100, batch = "court")
    public static void aRingIsRaisedRoundAPoolFiveBySeven(GameTestHelper h) {
        raisedWhole(h, 5, 7);
    }

    @GameTest(template = "digsite", timeoutTicks = 100, batch = "court")
    public static void aRingIsRaisedRoundAPoolNineByFour(GameTestHelper h) {
        raisedWhole(h, 9, 4);
    }

    /**
     * A ring called off at 40% of its courses: the builders' teardown takes the ring down to level ground, and the pool, its rim and the air
     * over them are as they were, cell for cell, through the call-off, the teardown and the levelling.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "court")
    public static void aRingCalledOffAtFortyPercentLeavesThePoolAsItWas(GameTestHelper h) {
        Pool p = new Pool(h, 5, 7);
        p.lay();
        h.runAfterDelay(20, () -> p.guard(() -> {
            TestSettlement t = settlement(h);
            Works works = t.works();
            h.assertTrue(works.raise(p.level, p.pl, true, true) == null, "the ring was refused");
            RaiseJob job = works.sites().get(0).job();
            job.ensureLoaded(p.level);
            int firstCourse = 0;
            while (firstCourse < job.taskList().size() && job.phaseOf(firstCourse) != RaiseJob.COURSE) {
                firstCourse++;
            }
            int courses = job.taskList().size() - firstCourse;
            job.gnawInOrder(p.level, firstCourse + courses * 2 / 5, new ArrayList<>());
            h.assertTrue(job.laidCount() > 0 && job.laidCount() < job.buildingCells(), "the ring is not part laid: " + job.laidCount() + " of " + job.buildingCells());
            String changed = p.courtChanged();
            h.assertTrue(changed == null, "the ring's first 40% touched the pool or its rim: " + changed);
            Works.Cancelled off = works.cancel(p.level, p.pl.door());
            h.assertTrue(off != null && !off.atOnce(), "the part-laid ring was not called off for the builders to take down: " + off);
            job.tearSelf(p.level, 100_000);
            works.second(p.level, 100);
            h.assertTrue(works.sites().isEmpty() && t.heard.stream().anyMatch(l -> l.contains("is down")), "the ring is not down: " + t.heard);
            changed = p.courtChanged();
            h.assertTrue(changed == null, "the teardown touched the pool or its rim: " + changed);
            for (RoomTemplates.Entry e : RoomTemplates.get(p.level, p.pl.room().id())) {
                BlockPos w = p.pl.world(e.local());
                BlockState now = p.level.getBlockState(w);
                boolean ok = w.getY() == p.floor ? now.is(Blocks.GRASS_BLOCK) || now.is(Blocks.DIRT) : now.isAir();
                h.assertTrue(ok, "the torn-down ring left " + now + " at " + w.toShortString());
            }
            t.setRemoved();
            p.clear();
            h.succeed();
        }));
    }

    /**
     * The family: one room per court size, made the first time it is asked for and the same after; refused sizes make none; the court is
     * no cell of the template at any height, and every other cell of the box is (air too); the ground layer is solid round it; the door's
     * cell is open, at the middle of the x=0 face.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "court")
    public static void aRingIsMadeForEveryCourtSizeAndNoOther(GameTestHelper h) {
        register();
        String id = CourtRing.id(RING, 7, 9);
        Rooms.Room r = Rooms.get(id);
        h.assertTrue(r != null && r == Rooms.get(id) && Rooms.isRaised(id), "the ring " + id + " was not made once, as a raised building");
        for (String bad : new String[]{CourtRing.id(RING, 2, 5), CourtRing.id(RING, 5, 25), RING.prefix() + "_05x7", RING.prefix() + "_5x", RING.prefix() + "_x"}) {
            h.assertTrue(Rooms.get(bad) == null, "the family made a room for " + bad);
        }
        BoundingBox c = Rooms.court(id);
        List<RoomTemplates.Entry> cells = RoomTemplates.get(h.getLevel(), id);
        int courtCells = 7 * 9 * r.sizeY();
        h.assertTrue(cells.size() == r.sizeX() * r.sizeY() * r.sizeZ() - courtCells, "the ring's template has " + cells.size() + " cells, not its box less its court's");
        for (RoomTemplates.Entry e : cells) {
            h.assertTrue(!c.isInside(e.local().getX(), c.minY(), e.local().getZ()), "a cell of the template is in the court: " + e.local());
            if (e.local().getY() == 0) {
                h.assertTrue(!e.state().isAir(), "the ground layer is open at " + e.local());
            }
        }
        h.assertTrue(r.doorX() == 0 && r.doorY() == 1 && cells.stream().anyMatch(e -> e.local().equals(new BlockPos(0, 1, r.doorZ())) && e.state().isAir()),
                "the door's cell is not open at (0, 1, " + r.doorZ() + ")");
        h.succeed();
    }
}
