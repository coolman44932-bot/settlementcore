package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.DigJob;
import claude.underempire.core.room.RaiseJob;
import claude.underempire.core.room.RoomTemplates;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.room.Siting;
import claude.underempire.core.room.Siting.Earthwork;
import claude.underempire.core.room.Siting.Phase;
import claude.underempire.core.room.Siting.Plan;
import claude.underempire.core.test.SitingGameTests.Site;
import claude.underempire.core.work.SiteWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RaiseJob with no crew (docs/plans/surface-builder.md, section 6's test 1): the surface builder's task list driven by hand,
 * the claims made and swung at as a builder would, a cell at a time, and every block checked as it goes in.
 *
 * <p>Batch "raise", with the root project's crewed test (RaiseGameTests), at +360 on the engine's own plot
 * (settlementcore:digsite), laid and cleared by {@link Site} as the siting tests are (section 7's rules: the plot's columns
 * cleared to the build limit before and after, so leftovers of "works" at 355/370 and "muzzle" at 376 never cover it).
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class RaiseJobGameTests {

    /**
     * Section 6's patch round a site whose box's north-west column is (bx0, bz0), its floor at {@code floor}: grass at the
     * floor; a 4x4 dip 2 deep across the box's south-east corner (box, berm and margin: a plinth, a berm filled up and a ramp
     * banked up to it); a hump of 1 on one column in the box, under the first step (its earth is laid over by the courses);
     * and one in the north margin (a speck the ramp cuts). The crewed test (the root project's RaiseGameTests) lays it too.
     */
    public static int patch(int floor, int bx0, int bz0, int x, int z) {
        int g = floor;
        if (x >= bx0 + 7 && x <= bx0 + 10 && z >= bz0 + 7 && z <= bz0 + 10) {
            g -= 2;
        }
        if (x == bx0 + 3 && z == bz0 + 2 || x == bx0 + 4 && z == bz0 - 3) {
            g += 1;
        }
        return g;
    }

    /**
     * Section 6's test 1, on section 6's patch: the site clears its land, then a made-up builder is handed batch after batch
     * and lays each a cell at a time ({@code gnawSome}), as a builder's swings do. As each block goes in:
     * <ul>
     *   <li>it is ready: a block has something under it; a cut is the top of its column, with a way out beside it;</li>
     *   <li>the earthworks go first: when the first course's block goes in, every earthwork is in the world, and none after;</li>
     *   <li>no block of a course is laid more than one course over the lowest open course (read off the world).</li>
     * </ul>
     * Half-way through the earthworks, and again half-way through the courses, the site is saved and loaded back (through a
     * save file's bytes): the same task list, the same cells done, and the loaded one goes on (and finishes the building). At the end the world is the template, the dip is filled by the plinth, the hump in
     * the margin is cut, the ramp stands where the plan put it and is walkable, and nothing was heaved or spared.
     *
     * <p>The control: the dig's own order (outward from the door, top-down within a distance: DigJob#order) lays a course
     * two over an open one at once, so the course check can fail.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aRaiseJobLaysCourseByCourse(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> patch(s.floor, s.bx0, s.bz0, x, z));
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "section 6's patch");
            List<Earthwork> works = new ArrayList<>();
            int cuts = 0;
            int[] byPhase = new int[Phase.values().length];
            for (Earthwork e : p.earthworks()) {
                byPhase[e.phase().ordinal()]++;
                if (e.phase() != Phase.CLEARING) {
                    works.add(e);
                    cuts += e.state().isAir() ? 1 : 0;
                }
            }
            h.assertTrue(byPhase[Phase.PLINTH.ordinal()] > 0 && byPhase[Phase.PAD.ordinal()] > 0 && byPhase[Phase.RAMP.ordinal()] > 0 && cuts > 0,
                    "the patch doesn't give every phase work (clearing, plinth, pad, ramp: " + java.util.Arrays.toString(byPhase) + ", " + cuts
                            + " cuts): " + p + SitingGameTests.dump(s, p, s.heights(p)));
            String control = digOrderProblem(s);
            h.assertTrue(control != null, "the dig's own order never lays a course over an open one: the course check can't fail");
            // the template's solid cells, where they go: the courses
            Map<Long, Integer> courseOf = new HashMap<>();
            Map<Long, BlockState> want = new HashMap<>();
            for (RoomTemplates.Entry e : s.template) {
                if (!e.state().isAir()) {
                    long k = s.pl.world(e.local()).asLong();
                    courseOf.put(k, e.local().getY());
                    want.put(k, e.state().rotate(s.pl.rotation()));
                }
            }
            RaiseJob job = new RaiseJob(SitingGameTests.PLATFORM, s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            UUID builder = new UUID(0x5a17eL, 1L);
            BlockPos near = s.pl.door();
            int courseLays = 0;
            int earthLays = 0;
            boolean begun = false;
            int reloads = 0;
            for (int round = 0; !job.isDone(); round++) {
                h.assertTrue(round < 4000, "no end to the claims: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size() + " done");
                BlockPos anchor = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                h.assertTrue(anchor != null, "nothing could be handed out, " + (job.taskList().size() - job.finishedCount()) + " cells left, "
                        + job.state() + " (" + job.openNow() + " open now)" + SitingGameTests.dump(s, p, s.heights(p)));
                List<DigJob.Task> batch = job.batch(builder);
                for (int k = 0; k < batch.size(); k++) {
                    int lowest = lowestOpenCourse(level, want, courseOf);
                    Map<Long, String> unready = new HashMap<>();
                    for (DigJob.Task t : batch) {
                        String why = unready(level, t);
                        if (why != null) {
                            unready.put(t.pos().asLong(), why);
                        }
                    }
                    for (DigJob.Crumb c : job.gnawSome(level, builder, (k + 0.5f) / batch.size())) {
                        long key = c.pos().asLong();
                        String why = unready.get(key);
                        h.assertTrue(why == null, "the cell " + c.pos().toShortString() + " (" + level.getBlockState(c.pos()) + ", " + job.state()
                                + ") went in " + why);
                        Integer course = courseOf.get(key);
                        if (course == null) {
                            h.assertTrue(!begun, "an earthwork at " + c.pos().toShortString() + " went in after the courses were begun");
                            earthLays++;
                            continue;
                        }
                        if (!begun) {
                            begun = true;
                            String left = earthworkLeft(level, works);
                            h.assertTrue(left == null, "the first course's block went in at " + c.pos().toShortString() + " with the earthworks unfinished: "
                                    + left);
                        }
                        h.assertTrue(course <= lowest + 1, "a block of the course " + course + " went in at " + c.pos().toShortString()
                                + " with the course " + lowest + " still open");
                        courseLays++;
                    }
                }
                job.complete(level, builder);
                near = anchor;
                // saved and loaded back half-way through the earthworks, and again half-way through the courses
                if (reloads == 0 && earthLays >= works.size() / 2 || reloads == 1 && courseLays >= want.size() / 2) {
                    reloads++;
                    CompoundTag saved = SitingGameTests.roundTrip(job.save());
                    h.assertTrue(saved.getBoolean("raise") && saved.contains("reshape") && saved.contains("paid") && saved.contains("heaved"),
                            "the half-raised site saves as " + saved.getAllKeys());
                    RaiseJob back = RaiseJob.load(saved);
                    h.assertTrue(back != null, "the half-raised site didn't load back");
                    back.ensureLoaded(level);
                    h.assertTrue(back.taskList().equals(job.taskList()), "loaded back, its task list differs: " + firstDifference(job.taskList(), back.taskList()));
                    h.assertTrue(back.finishedCount() == job.finishedCount() && back.state().equals(job.state()),
                            "loaded back it has " + back.finishedCount() + " done (" + back.state() + "), not " + job.finishedCount() + " (" + job.state() + ")");
                    job = back;                                  // the loaded one raises the rest
                }
            }
            h.assertTrue(reloads == 2, "the site was saved half-way " + reloads + " times, not twice");
            h.assertTrue(earthLays == works.size() && courseLays == want.size(), "laid by hand: " + earthLays + " of " + works.size()
                    + " earthworks and " + courseLays + " of " + want.size() + " course cells (the rest done for free)");
            h.assertTrue(job.heaved() == 0 && job.spared() == 0, "heaved " + job.heaved() + ", spared " + job.spared());
            // the world is the template, on its plinth, in its reshaped land
            for (RoomTemplates.Entry e : s.template) {
                BlockPos w = s.pl.world(e.local());
                BlockState now = level.getBlockState(w);
                h.assertTrue(now == e.state().rotate(s.pl.rotation()), "the platform's cell " + e.local() + " at " + w.toShortString() + " is " + now
                        + ", not " + e.state());
            }
            for (int x = s.bx0 + 7; x <= s.bx0 + 8; x++) {
                for (int z = s.bz0 + 7; z <= s.bz0 + 8; z++) {
                    h.assertTrue(s.get(x, s.floor - 1, z).is(Blocks.STONE_BRICKS), "the dip under the box at " + x + " " + z + " isn't filled: "
                            + s.get(x, s.floor - 1, z));
                }
            }
            int hx = s.bx0 + 4;
            int hz = s.bz0 - 3;
            h.assertTrue(p.oldGround(hx, hz) == s.floor + 1 && p.newHeight(hx, hz) == s.floor && s.surface(hx, hz) == s.floor,
                    "the margin's hump at " + hx + " " + hz + ": was " + (p.oldGround(hx, hz) - s.floor) + ", planned " + (p.newHeight(hx, hz) - s.floor)
                            + ", stands " + (s.surface(hx, hz) - s.floor));
            int[] a = s.heights(p);
            SitingGameTests.expect(h, s, p, a, SitingGameTests.heightProblem(s, p, a));
            SitingGameTests.expect(h, s, p, a, SitingGameTests.stepProblem(s, p, a));
            SitingGameTests.expect(h, s, p, a, SitingGameTests.walkProblem(s, p, a));
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Nobody is built into a raised building's courses, a player least of all (the review of 51b59f8: Clearance moved only
     * mobs, and a player standing on a half-built step, in the next course's cell, was built into, feet then head). On flat
     * ground the earthworks and the first course are laid by hand; then a mock player stands in a cell of the 2nd course (on
     * the ground layer, in the first step's cell by the door) and the rest of the building is laid a cell at a time as a
     * builder's swings lay it. After every block the player is not inside one; its cell is laid; and it was set down clear of
     * it, on a floor it fits on.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aPlayerInTheNextCourseIsSetDownClear(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "flat ground");
            RaiseJob job = new RaiseJob(SitingGameTests.PLATFORM, s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            UUID builder = new UUID(0x5a17eL, 2L);
            BlockPos near = s.pl.door();
            for (int round = 0; job.courseNow() < 1; round++) {
                h.assertTrue(round < 2000 && !job.isDone(), "the first course never finished: " + job.state());
                BlockPos anchor = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                h.assertTrue(anchor != null, "nothing could be handed out before the second course: " + job.openReport(level, 8));
                job.complete(level, builder);
                near = anchor;
            }
            BlockPos cell = s.pl.world(new BlockPos(3, 1, 4));
            BlockState want = null;
            for (RoomTemplates.Entry e : s.template) {
                if (e.local().equals(new BlockPos(3, 1, 4))) {
                    want = e.state().rotate(s.pl.rotation());
                }
            }
            h.assertTrue(want != null && !want.isAir() && job.fillsOpen(cell) && level.getBlockState(cell).isAir(),
                    "the 2nd course's cell " + cell.toShortString() + " isn't an open cell of the site: " + level.getBlockState(cell));
            ServerPlayer player = h.makeMockServerPlayerInLevel();
            try {
                player.teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                h.assertTrue(player.blockPosition().equals(cell), "the player stands at " + player.blockPosition().toShortString() + ", not in the cell "
                        + cell.toShortString());
                boolean setDown = false;
                for (int round = 0; !job.isDone(); round++) {
                    h.assertTrue(round < 2000, "the building never finished: " + job.state());
                    BlockPos anchor = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                    h.assertTrue(anchor != null, "nothing could be handed out: " + job.openReport(level, 8));
                    int n = job.batch(builder).size();
                    for (int k = 0; k < n; k++) {
                        for (DigJob.Crumb c : job.gnawSome(level, builder, (k + 0.5f) / n)) {
                            h.assertTrue(!claude.underempire.core.room.Clearance.walledIn(level, player), "the player at "
                                    + player.blockPosition().toShortString() + " is inside a block (" + level.getBlockState(player.blockPosition())
                                    + ") once " + level.getBlockState(c.pos()) + " went in at " + c.pos().toShortString());
                            setDown |= !player.blockPosition().equals(cell);
                        }
                    }
                    job.complete(level, builder);
                    near = anchor;
                }
                BlockPos at = player.blockPosition();
                h.assertTrue(level.getBlockState(cell) == want, "the cell " + cell.toShortString() + " is " + level.getBlockState(cell) + ", not " + want);
                h.assertTrue(setDown && !at.equals(cell) && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                                && level.noCollision(player), "the player wasn't set down clear of the cell, on a floor it fits on: it is at "
                        + at.toShortString() + " over " + level.getBlockState(at.below()));
            } finally {
                level.getServer().getPlayerList().remove(player);
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Hanging things are held by what they hang from (the review of 51b59f8: a hanging lantern, a chain or vines under a
     * ceiling waited out the whole stall, since a block was held only from under it or beside it). In the air over the plot,
     * under a stone ceiling: a hanging lantern, a chain, vines and a cobweb are held; a standing lantern and a stone block are
     * not (nothing under them, nothing beside); the earthworks hang nothing; with the ceiling gone the lantern isn't held;
     * and a block with a sturdy face beside it is held as eaves, unless the site still has a block to put under it.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "raise")
    public static void aHangingThingIsHeldFromAbove(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            BlockPos p = new BlockPos(s.px0 + 4, s.floor + 6, s.pz0 + 4);
            s.set(p.getX(), p.getY() + 1, p.getZ(), Blocks.STONE.defaultBlockState());
            BlockState hanging = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
            BlockState standing = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false);
            BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, true);
            held(h, level, p, hanging, true, false, true, "a hanging lantern under stone");
            held(h, level, p, Blocks.CHAIN.defaultBlockState(), true, false, true, "a chain under stone");
            held(h, level, p, vine, true, false, true, "vines under stone");
            held(h, level, p, Blocks.COBWEB.defaultBlockState(), true, false, true, "a cobweb under stone");
            held(h, level, p, standing, true, false, false, "a standing lantern over air");
            held(h, level, p, Blocks.STONE_BRICKS.defaultBlockState(), true, false, false, "stone bricks over air, nothing beside");
            held(h, level, p, hanging, false, false, false, "a hanging lantern in the earthworks");
            s.set(p.getX(), p.getY() + 1, p.getZ(), Blocks.AIR.defaultBlockState());
            held(h, level, p, hanging, true, false, false, "a hanging lantern with its ceiling gone");
            s.set(p.getX() + 1, p.getY(), p.getZ(), Blocks.STONE.defaultBlockState());
            held(h, level, p, Blocks.STONE_BRICKS.defaultBlockState(), true, false, true, "stone bricks beside stone (eaves)");
            held(h, level, p, Blocks.STONE_BRICKS.defaultBlockState(), true, true, false, "stone bricks beside stone, with a block still to go under");
            s.clear();
            h.succeed();
        }));
    }

    private static void held(GameTestHelper h, ServerLevel level, BlockPos p, BlockState s, boolean course, boolean underToCome, boolean want,
                             String scene) {
        h.assertTrue(RaiseJob.held(level, p, s, course, underToCome) == want, scene + ": " + (want ? "not held" : "held") + " (" + s + " at "
                + p.toShortString() + ", over " + level.getBlockState(p.below()) + ", under " + level.getBlockState(p.above()) + ")");
    }

    // --- hand-built buildings: hanging pieces -----------------------------------------------------------------------------

    /** A hanging lantern under a roof cell, and a lantern on a chain under another: every hanging piece under a roof still to be laid. */
    public static final String HANGERS = "raise_test_hangers";
    /** Wall vines, a chain between two pillars and a cobweb in a corner (under open sky), and vine columns hanging from beams. */
    public static final String VINES = "raise_test_vines";

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BRICK = Blocks.STONE_BRICKS.defaultBlockState();

    /** The cells of a hand-built building, every cell of its box (air until set). */
    private static final class Cells {
        final Map<BlockPos, BlockState> at = new java.util.LinkedHashMap<>();

        Cells(int sizeX, int sizeY, int sizeZ) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        at.put(new BlockPos(x, y, z), AIR);
                    }
                }
            }
        }

        void set(int x, int y, int z, BlockState s) {
            at.put(new BlockPos(x, y, z), s);
        }

        void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        set(x, y, z, s);
                    }
                }
            }
        }

        /** The apron (the ground layer, whole in every column), the walls up to y 4, and the door gap at (0,1,4) and (0,2,4). */
        void room() {
            box(0, 0, 0, 8, 0, 8, BRICK);
            box(0, 1, 0, 8, 4, 0, BRICK);
            box(0, 1, 8, 8, 4, 8, BRICK);
            box(0, 1, 0, 0, 4, 8, BRICK);
            box(8, 1, 0, 8, 4, 8, BRICK);
            box(0, 1, 4, 0, 2, 4, AIR);
        }
    }

    /** A 9x{@code sizeY}x9 building of {@code cells}, door (0,1,4), registered under the engine's namespace once, its template handed in. */
    private static synchronized Rooms.Room handBuilt(String id, String name, int sizeY, java.util.function.Consumer<Cells> furnish) {
        if (Rooms.get(id) == null) {
            Cells c = new Cells(9, sizeY, 9);
            furnish.accept(c);
            List<RoomTemplates.Entry> entries = new ArrayList<>();
            for (var e : c.at.entrySet()) {
                entries.add(new RoomTemplates.Entry(e.getKey(), e.getValue(), null));
            }
            RoomTemplates.put(id, entries);
            Rooms.Room r = new Rooms.Room(id, name, 0, Rooms.Function.of("RAISE_TEST"), "A hand-built test building: " + name, 9, sizeY, 9, 0, 1, 4,
                    List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(), List.of(), java.util.Set.of(), 0, List.of(r)));
        }
        return Rooms.get(id);
    }

    private static BlockState hangingLantern() {
        return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
    }

    private static BlockState wallVine(net.minecraft.world.level.block.state.properties.BooleanProperty face) {
        return Blocks.VINE.defaultBlockState().setValue(face, true);
    }

    /** Stone pillars, 1 to {@code top} high, at (x, z). */
    private static void pillar(Cells c, int x, int z, int top) {
        c.box(x, 1, z, x, top, z, BRICK);
    }

    /** Under a roof of stone bricks over the whole box (y 5): a hanging lantern under one roof cell, a lantern on a chain under another. */
    public static synchronized Rooms.Room hangers() {
        return handBuilt(HANGERS, "Test Hanging Lamps", 7, c -> {
            c.room();
            c.box(0, 5, 0, 8, 5, 8, BRICK);                      // the roof
            c.set(6, 4, 2, hangingLantern());                    // under the roof cell (6,5,2)
            c.set(3, 4, 6, Blocks.CHAIN.defaultBlockState());    // a chain under (3,5,6), and a lantern on it
            c.set(3, 3, 6, hangingLantern());
        });
    }

    /**
     * Under open sky: three wall vines up the south wall; a cobweb in the north-west corner; a chain between two pillars' tops; and
     * two beams on pillars, one with a column of cave vines hanging from it (two plants and their head), one with weeping vines.
     */
    public static synchronized Rooms.Room vines() {
        return handBuilt(VINES, "Test Vines", 7, c -> {
            c.room();
            for (int y = 2; y <= 4; y++) {
                c.set(2, y, 7, wallVine(VineBlock.SOUTH));       // against the south wall (z 8)
            }
            c.set(1, 4, 1, Blocks.COBWEB.defaultBlockState());   // in the corner, the walls beside it
            pillar(c, 3, 4, 3);
            pillar(c, 5, 4, 3);
            c.set(4, 3, 4, Blocks.CHAIN.defaultBlockState().setValue(net.minecraft.world.level.block.ChainBlock.AXIS, net.minecraft.core.Direction.Axis.X));
            for (int z : new int[]{6, 2}) {                      // a beam over two pillars, and a column hanging from its middle, free of both
                pillar(c, 3, z, 4);
                pillar(c, 7, z, 4);
                c.box(3, 5, z, 7, 5, z, BRICK);
                boolean cave = z == 6;
                c.set(5, 4, z, (cave ? Blocks.CAVE_VINES_PLANT : Blocks.WEEPING_VINES_PLANT).defaultBlockState());
                c.set(5, 3, z, (cave ? Blocks.CAVE_VINES_PLANT : Blocks.WEEPING_VINES_PLANT).defaultBlockState());
                c.set(5, 2, z, (cave ? Blocks.CAVE_VINES : Blocks.WEEPING_VINES).defaultBlockState());
            }
        });
    }

    /** The first cell of the job's list the world doesn't have as the job wants it yet: where its lowest open cell is, in words; or "none". */
    private static String firstOpen(ServerLevel level, RaiseJob job) {
        List<DigJob.Task> tasks = job.taskList();
        for (int i = 0; i < tasks.size(); i++) {
            DigJob.Task t = tasks.get(i);
            if (level.getBlockState(t.pos()) != t.state()) {
                return t.pos().toShortString() + " " + t.state() + " (course " + job.liftOf(i) + ", phase " + job.phaseOf(i) + ")";
            }
        }
        return "none";
    }

    /**
     * A hand-built building raised as a site's builders raise it (the claim loop of aRaiseJobLaysCourseByCourse: batch after batch,
     * a cell at a time), on flat ground, with the site's own stall rule as SiteWork#second has it: with nothing to hand out and nobody
     * at work for {@link SiteWork#DRY_SECONDS} seconds (60 ticks, the builders standing idle and the courses over held back) the lowest
     * open cell is heaved into place. The check is that none ever had to be: every stall is listed, with the cell it was stuck on,
     * and the world is the template at the end.
     */
    private static void raisedWithoutAStall(GameTestHelper h, Rooms.Room room, String scene) {
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), scene);
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            UUID builder = new UUID(0x5a17eL, 3L);
            BlockPos near = s.pl.door();
            List<String> stalls = new ArrayList<>();
            int rounds = 0;
            for (; !job.isDone(); rounds++) {
                h.assertTrue(rounds < 4000, "no end to the claims: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size()
                        + " done; open: " + job.openReport(level, 8));
                BlockPos anchor = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                if (anchor == null) {
                    h.assertTrue(!job.anyClaimable(level) && !job.anyOut(level.getGameTime()), "claim handed out nothing, but the site could: "
                            + job.openReport(level, 8));
                    stalls.add(firstOpen(level, job) + " [" + job.openReport(level, 4) + "]");
                    h.assertTrue(job.heaveFront(level), "the stalled site's lowest open cell can't even be heaved: " + job.openReport(level, 8));
                    continue;
                }
                job.gnawSome(level, builder, 1f);
                job.complete(level, builder);
                near = anchor;
            }
            h.assertTrue(job.heaved() == 0 && stalls.isEmpty(), scene + ": the site stalled " + stalls.size() + " times (" + job.heaved()
                    + " heaved; each a " + SiteWork.DRY_SECONDS + " s wait, " + SiteWork.DRY_SECONDS * 20 * stalls.size() + " ticks, with the builders idle): "
                    + String.join(" | ", stalls));
            for (RoomTemplates.Entry e : s.template) {
                BlockPos w = s.pl.world(e.local());
                BlockState now = level.getBlockState(w);
                h.assertTrue(now == e.state().rotate(s.pl.rotation()), "the " + room.id() + "'s cell " + e.local() + " at " + w.toShortString()
                        + " is " + now + ", not " + e.state());
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A hanging cell and the cell it hangs from wait for each other (the review of 51b59f8's second pass): a hanging lantern L under a
     * roof cell C, both the site's to lay. L waits for C (a hanging thing is held by what is over it); C waited for L (a course's cell
     * over one still to be laid waits for it), so neither could ever go in and only the stall rule's heave broke it, a pair at a time.
     * A lantern on a chain under the roof is the same over three courses, and worse: the lantern (course 3) held the window down, so the
     * roof (course 5) was never handed out, however the chain was. In a 9x9 hall with a roof over it: a hanging lantern under one roof
     * cell, and a lantern on a chain under another; no cell may be heaved.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aHangingPieceAndItsCeilingAreLaidWithoutAHeave(GameTestHelper h) {
        raisedWithoutAStall(h, hangers(), "a hanging lantern under its roof cell, and one on a chain");
    }

    /**
     * Only what hangs is held from above (the review of 51b59f8's second pass: the hold for hanging cells was too broad and had no
     * eaves to fall back on: a vine is {@code noCollission}, so "something solid over it" failed for every vine but the top one of a
     * column, for wall vines whose faces are the sides, for a chain laid between two walls, and for a cobweb in a corner; the eaves
     * rule held them all before). Under open sky: three wall vines up a wall, a cobweb in a corner, a chain between two pillars,
     * and a column of cave vines and one of weeping vines hanging from beams; no cell may be heaved.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aWallVineAChainBetweenWallsAndAVineColumnAreLaidWithoutAHeave(GameTestHelper h) {
        raisedWithoutAStall(h, vines(), "wall vines, a cobweb in a corner, a chain between pillars, and vine columns hanging from beams");
    }

    /**
     * What is a hanging piece and what holds it, cell by cell in the air (the two tests above raise whole buildings): a piece hangs only
     * where it depends on the cell over it (a vine's top face, a vertical chain: not a wall vine, not a chain laid sideways); a hanging
     * piece is held by something solid over it, or by its own column over it already laid (vines under vines, a cave vine under its
     * plant, weeping vines), and else, like any block, by a sturdy face beside it where nothing is still to go under it.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "raise")
    public static void aHangingPieceIsHeldFromAboveFromItsColumnOrAsEaves(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            BlockState vineUp = Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, true);
            BlockState vineSouth = wallVine(VineBlock.SOUTH);
            BlockState chainX = Blocks.CHAIN.defaultBlockState().setValue(net.minecraft.world.level.block.ChainBlock.AXIS, Direction.Axis.X);
            for (var e : new Object[][]{{hangingLantern(), true}, {Blocks.LANTERN.defaultBlockState(), false}, {Blocks.CHAIN.defaultBlockState(), true},
                    {chainX, false}, {vineUp, true}, {vineSouth, false}, {Blocks.COBWEB.defaultBlockState(), true}, {Blocks.CAVE_VINES_PLANT.defaultBlockState(), true},
                    {Blocks.WEEPING_VINES.defaultBlockState(), true}, {Blocks.HANGING_ROOTS.defaultBlockState(), true}, {BRICK, false}}) {
                BlockState st = (BlockState) e[0];
                h.assertTrue(RaiseJob.hangs(st) == (Boolean) e[1], st + (((Boolean) e[1]) ? " doesn't hang from the cell over it" : " is taken for a piece that hangs from the cell over it"));
            }
            BlockPos p = new BlockPos(s.px0 + 4, s.floor + 6, s.pz0 + 4);
            BlockState stone = Blocks.STONE.defaultBlockState();
            // a wall vine: held by the wall beside it (eaves), not by anything over it, and not with a block still to go under it
            s.set(p.getX(), p.getY(), p.getZ() + 1, stone);
            held(h, level, p, vineSouth, true, false, true, "a wall vine against stone");
            held(h, level, p, vineSouth, true, true, false, "a wall vine against stone, with a block still to go under it");
            s.set(p.getX(), p.getY(), p.getZ() + 1, AIR);
            held(h, level, p, vineSouth, true, false, false, "a wall vine against nothing");
            // a chain laid sideways between two walls; a chain hanging, with only a wall beside it (hangs, but the eaves hold it too)
            s.set(p.getX() - 1, p.getY(), p.getZ(), stone);
            held(h, level, p, Blocks.CHAIN.defaultBlockState(), true, false, true, "a hanging chain with a wall beside it and nothing over it");
            s.set(p.getX() + 1, p.getY(), p.getZ(), stone);
            held(h, level, p, chainX, true, false, true, "a chain between two walls");
            s.set(p.getX() - 1, p.getY(), p.getZ(), AIR);
            s.set(p.getX() + 1, p.getY(), p.getZ(), AIR);
            held(h, level, p, Blocks.CHAIN.defaultBlockState(), true, false, false, "a chain hanging from nothing");
            // a cobweb in a corner
            s.set(p.getX() - 1, p.getY(), p.getZ(), stone);
            s.set(p.getX(), p.getY(), p.getZ() - 1, stone);
            held(h, level, p, Blocks.COBWEB.defaultBlockState(), true, false, true, "a cobweb in a corner");
            s.set(p.getX() - 1, p.getY(), p.getZ(), AIR);
            s.set(p.getX(), p.getY(), p.getZ() - 1, AIR);
            held(h, level, p, Blocks.COBWEB.defaultBlockState(), true, false, false, "a cobweb in the open");
            // a column hangs from its own top piece, already laid, where nothing solid is over it
            BlockPos over = p.above();
            s.set(over.getX(), over.getY(), over.getZ(), Blocks.CAVE_VINES_PLANT.defaultBlockState());
            held(h, level, p, Blocks.CAVE_VINES_PLANT.defaultBlockState(), true, false, true, "a cave vine plant under its plant");
            held(h, level, p, Blocks.CAVE_VINES.defaultBlockState(), true, false, true, "a cave vine's head under its plant");
            held(h, level, p, Blocks.WEEPING_VINES_PLANT.defaultBlockState(), true, false, false, "weeping vines under cave vines");
            s.set(over.getX(), over.getY(), over.getZ(), Blocks.WEEPING_VINES_PLANT.defaultBlockState());
            held(h, level, p, Blocks.WEEPING_VINES_PLANT.defaultBlockState(), true, false, true, "a weeping vines plant under its plant");
            held(h, level, p, Blocks.WEEPING_VINES.defaultBlockState(), true, false, true, "weeping vines' head under its plant");
            held(h, level, p, BRICK, true, false, false, "stone bricks under a plant (nothing holds them)");
            s.set(over.getX(), over.getY(), over.getZ(), vineSouth);
            held(h, level, p, vineSouth, true, false, true, "a vine under a vine (its face held by the vine over it, below a wall's end)");
            s.set(over.getX(), over.getY(), over.getZ(), AIR);
            held(h, level, p, Blocks.CAVE_VINES_PLANT.defaultBlockState(), true, false, false, "a cave vine plant under air");
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Why task {@code t} could not go in now, or null: a block with nothing under it; a cut with something solid over it (it
     * would leave that floating), or with no neighbour at most a block over the new ground (a pit).
     */
    static String unready(ServerLevel level, DigJob.Task t) {
        BlockPos p = t.pos();
        if (!t.state().isAir()) {
            BlockPos b = p.below();
            return level.getBlockState(b).getCollisionShape(level, b).isEmpty() ? "with nothing under it (" + level.getBlockState(b) + ")" : null;
        }
        if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
            return "cut from under " + level.getBlockState(p.above());
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = p.relative(d).above();
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) {
                return null;
            }
        }
        return "cut with every neighbour 2 or more over it: a pit";
    }

    /** The lowest course with a cell not yet its template's block in the world; a course past the top when all are. */
    private static int lowestOpenCourse(ServerLevel level, Map<Long, BlockState> want, Map<Long, Integer> courseOf) {
        int lowest = Integer.MAX_VALUE;
        for (var e : want.entrySet()) {
            int c = courseOf.get(e.getKey());
            if (c < lowest && level.getBlockState(BlockPos.of(e.getKey())) != e.getValue()) {
                lowest = c;
            }
        }
        return lowest;
    }

    /** The first earthwork not in the world as the plan has it, or null. */
    private static String earthworkLeft(ServerLevel level, List<Earthwork> works) {
        for (Earthwork e : works) {
            BlockState now = level.getBlockState(e.pos());
            if (now != e.state()) {
                return e + " is " + now;
            }
        }
        return null;
    }

    /** Where the dig's own order (DigJob#order: outward from the door, top-down within a distance) lays a course over an open one. */
    static String digOrderProblem(Site s) {
        BlockPos door = new BlockPos(s.pl.room().doorX(), s.pl.room().doorY(), s.pl.room().doorZ());
        List<RoomTemplates.Entry> dig = new ArrayList<>(s.template);
        dig.sort(Comparator.<RoomTemplates.Entry>comparingInt(e -> e.local().distManhattan(door)).thenComparingInt(e -> -e.local().getY()));
        int[] open = new int[s.pl.room().sizeY()];
        for (RoomTemplates.Entry e : s.template) {
            if (!e.state().isAir()) {
                open[e.local().getY()]++;
            }
        }
        for (RoomTemplates.Entry e : dig) {
            if (e.state().isAir()) {
                continue;
            }
            int lowest = 0;
            while (lowest < open.length && open[lowest] == 0) {
                lowest++;
            }
            if (e.local().getY() > lowest + 1) {
                return "the course " + e.local().getY() + " at " + e.local() + " with the course " + lowest + " open";
            }
            open[e.local().getY()]--;
        }
        return null;
    }

    private static String firstDifference(List<DigJob.Task> a, List<DigJob.Task> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return "task " + i + " was " + a.get(i) + ", is " + b.get(i);
            }
        }
        return a.size() + " tasks against " + b.size();
    }
}
