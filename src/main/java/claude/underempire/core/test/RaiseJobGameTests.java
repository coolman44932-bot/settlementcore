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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
     * {@link #patch} with a hump of 2 over four columns in the box (cells of the 1st and 2nd steps, which the courses lay over) and a dip of 1 under four
     * others (floor cells that are air over solid ground): what a site that did nothing must neither carve into a hillside nor fill (surface-builder.md,
     * item 20). The crewed test of the call-off (the root project's RaiseGameTests) lays it too.
     */
    public static int humpy(int floor, int bx0, int bz0, int x, int z) {
        int g = patch(floor, bx0, bz0, x, z);
        if (x >= bx0 + 3 && x <= bx0 + 4 && z >= bz0 + 5 && z <= bz0 + 6) {
            g = floor + 2;
        }
        if (x >= bx0 + 1 && x <= bx0 + 2 && z >= bz0 + 7 && z <= bz0 + 8) {
            g = floor - 1;
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
     * it, on a floor it fits on, and not in a cell the building is still to fill.
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
                BlockPos[] last = {cell};
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
                            BlockPos nowAt = player.blockPosition();
                            if (!nowAt.equals(last[0])) {        // set down by the block that just went in: not in a cell the building is still to fill
                                h.assertTrue(!job.fillsOpen(nowAt) && !job.fillsOpen(nowAt.above()), "the player was set down at " + nowAt.toShortString()
                                        + " by the block that went into " + c.pos().toShortString() + ", a cell the building is still to fill (a hole that the courses "
                                        + "close round it)");
                                last[0] = nowAt;
                            }
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
     * Nobody is set down in a pit when there is anywhere else (the crewed step platform's flake, about 1 copy in 500: a builder standing in the cell
     * a block goes into was set down in the open cell beside it, a hole in the layer under with a block on every side once that block was in, and
     * could not climb out). On flat ground: a layer of stone bricks 1 over the ground with a hole in it, and over that a layer with the cell over
     * the hole and the cell beside it (F) still open and every other cell laid. A pig stands in both cells, as a builder does laying its batch, and
     * the block is put in F ({@code Clearance#before}): the pig is set down clear of F, and not in the hole, which is the nearest dry floor to it and
     * a pit once F is laid (the control: the old rule sets it down there).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "raise")
    public static void aMobSetDownByABlockGoingInIsNotSetDownInAPit(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            int x0 = s.px0 + 8;
            int z0 = s.pz0 + 8;
            int g = s.floor;
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    s.set(x0 + x, g + 1, z0 + z, BRICK);         // the layer over the ground
                }
            }
            BlockPos hole = new BlockPos(x0 + 3, g + 1, z0 + 3);
            s.set(hole.getX(), hole.getY(), hole.getZ(), AIR);   // with a hole in it
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    s.set(x0 + x, g + 2, z0 + z, BRICK);         // the layer over that
                }
            }
            BlockPos over = hole.above();
            BlockPos f = over.west();                            // the cell the block goes into, beside the cell over the hole
            s.set(over.getX(), over.getY(), over.getZ(), AIR);
            s.set(f.getX(), f.getY(), f.getZ(), AIR);
            net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
            h.assertTrue(pig != null, "no pig to set down");
            try {
                pig.setNoAi(true);
                pig.moveTo(x0 + 3.12, g + 2, z0 + 3.5, 0f, 0f);  // over the layer's edge: its body in both cells, as a builder's at its work
                level.addFreshEntity(pig);
                h.assertTrue(pig.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(f)) && level.noCollision(pig),
                        "the pig at " + pig.position() + " isn't standing free in the cell " + f.toShortString() + " and the cell over the hole");
                int moved = claude.underempire.core.room.Clearance.before(level, f, BRICK);
                level.setBlock(f, BRICK, 3);
                h.assertTrue(moved == 1, "the block going into " + f.toShortString() + " moved " + moved + " mobs, not the pig");
                h.assertTrue(RaiseJob.pit(level, hole), "the control's hole at " + hole.toShortString() + " isn't a pit once " + f.toShortString() + " is laid");
                h.assertTrue(!pig.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(f)) && level.noCollision(pig),
                        "the pig at " + pig.position() + " wasn't set down clear of the cell " + f.toShortString());
                h.assertTrue(!RaiseJob.pit(level, pig.blockPosition()), "the pig was set down at " + pig.blockPosition().toShortString() + " (" + pig.position()
                        + "), a pit once " + f.toShortString() + " was laid: every cell round it 2 over its feet is a block, and it can't climb out");
            } finally {
                pig.discard();
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Nobody is set down in a cell the job is still to fill when another spot will do (the crewed step platform's flake again, once the pit rule
     * was in: a builder was set down in the last open cell of the course it was laying, and the blocks of its own batch walled it in). On flat
     * ground, a layer of stone bricks 1 over the ground, and over it a layer with two open cells side by side, F (the block goes into it) and A, the
     * nearest dry floor to the pig that stands in both. With A one of the job's cells still to fill ({@code Clearance#before}'s {@code taken}) the pig
     * is set down clear of F on the finished layer, not in A. The control, so the test can fail: with no cell taken it is set down in A.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "raise")
    public static void aMobSetDownByABlockGoingInIsNotSetDownInACellTheJobStillFills(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            int x0 = s.px0 + 8;
            int z0 = s.pz0 + 8;
            int g = s.floor;
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    s.set(x0 + x, g + 1, z0 + z, BRICK);         // the layer over the ground
                }
            }
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    s.set(x0 + x, g + 2, z0 + z, BRICK);         // the layer over that
                }
            }
            BlockPos f = new BlockPos(x0 + 3, g + 2, z0 + 3);    // the cell the block goes into
            BlockPos a = f.east();                               // and the open cell beside it
            s.set(f.getX(), f.getY(), f.getZ(), AIR);
            s.set(a.getX(), a.getY(), a.getZ(), AIR);
            net.minecraft.world.entity.animal.Pig pig = net.minecraft.world.entity.EntityType.PIG.create(level);
            h.assertTrue(pig != null, "no pig to set down");
            try {
                pig.setNoAi(true);
                for (int round = 0; round < 2; round++) {
                    boolean taken = round == 1;
                    s.set(f.getX(), f.getY(), f.getZ(), AIR);
                    pig.moveTo(x0 + 3.9, g + 2, z0 + 3.5, 0f, 0f);   // over the edge of F, in A
                    if (round == 0) {
                        level.addFreshEntity(pig);
                    }
                    h.assertTrue(pig.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(f)) && level.noCollision(pig),
                            "the pig at " + pig.position() + " isn't standing free in the cells " + f.toShortString() + " and " + a.toShortString());
                    int moved = claude.underempire.core.room.Clearance.before(level, f, BRICK, taken ? p -> p.equals(a) : null);
                    level.setBlock(f, BRICK, 3);
                    BlockPos at = pig.blockPosition();
                    h.assertTrue(moved == 1 && !pig.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(f)) && level.noCollision(pig),
                            "the block going into " + f.toShortString() + " moved " + moved + " mobs, and left the pig at " + pig.position() + (taken ? "" : " (the control)"));
                    if (taken) {
                        h.assertTrue(!at.equals(a), "the pig was set down at " + at.toShortString() + ", the cell " + a.toShortString() + " the job is still to fill");
                    } else {
                        h.assertTrue(at.equals(a), "the control: with no cell taken the pig is set down at " + at.toShortString() + ", not in the nearest dry floor, the cell "
                                + a.toShortString());
                        s.set(a.getX(), a.getY(), a.getZ(), AIR);
                        s.set(f.getX(), f.getY(), f.getZ(), AIR);
                    }
                }
            } finally {
                pig.discard();
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
    /** A hall under a roof with a lantern on chains under one roof cell, and a block hanging in the air in the way. */
    public static final String BLOCKED = "raise_test_blocked";
    /** A closed hall whose roof is 4 over its floor, with hanging lanterns under it: every cell in a builder's reach from the floor. */
    public static final String LAMPS = "raise_test_lamps";
    /** A flat apron, for the template tests: given by hand, dropped and given again. */
    public static final String RESTORED = "raise_test_restored";
    /** A flat apron of dirt, 2 high: a ground layer that is the land's own kind of block (a plinth of it is {@code Siting.ground}, no part of the building). */
    public static final String GROUND_APRON = "raise_test_groundapron";
    /** A closed hall with one door, its walls at the box's edge and its roof 4 over its floor: the way in is a long walk from outside its back wall. */
    public static final String ONE_DOOR = "raise_test_onedoor";

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
            room(4);
        }

        /** The apron, the walls up to y {@code top}, and the door gap at (0,1,4) and (0,2,4). */
        void room(int top) {
            box(0, 0, 0, 8, 0, 8, BRICK);
            box(0, 1, 0, 8, top, 0, BRICK);
            box(0, 1, 8, 8, top, 8, BRICK);
            box(0, 1, 0, 0, top, 8, BRICK);
            box(8, 1, 0, 8, top, 8, BRICK);
            box(0, 1, 4, 0, 2, 4, AIR);
        }
    }

    /**
     * A 9x{@code sizeY}x9 building of {@code cells}, door (0,1,4), registered under the engine's namespace once, its template handed in
     * whenever the cache lacks it: the first time, and again after a server stop in the dev client (it drops every template,
     * UnderEmpire#onServerStopped, and keeps the rooms registered: the building would have come back with no cells).
     */
    private static synchronized Rooms.Room handBuilt(String id, String name, int sizeY, java.util.function.Consumer<Cells> furnish) {
        if (!RoomTemplates.has(id)) {
            Cells c = new Cells(9, sizeY, 9);
            furnish.accept(c);
            List<RoomTemplates.Entry> entries = new ArrayList<>();
            for (var e : c.at.entrySet()) {
                entries.add(new RoomTemplates.Entry(e.getKey(), e.getValue(), null));
            }
            RoomTemplates.put(id, entries);
        }
        if (Rooms.get(id) == null) {
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

    /** A vine of a column that hangs free: a face, and nothing beside it but the vine over it. */
    private static BlockState plainVine() {
        return wallVine(VineBlock.NORTH);
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
     * A hall under a roof (y 5) with a lantern on two chains under one roof cell (the lantern at y 2, the chains at y 3 and 4), and a
     * block hanging in the air at y 3 with nothing under it or beside it: never held, so it keeps the course window at its course for
     * good, and the roof two courses over it is never handed out.
     */
    public static synchronized Rooms.Room blocked() {
        return handBuilt(BLOCKED, "Test Blocked Lamp", 7, c -> {
            c.room();
            c.box(0, 5, 0, 8, 5, 8, BRICK);                      // the roof
            c.set(3, 4, 6, Blocks.CHAIN.defaultBlockState());    // two chains under the roof cell (3,5,6), and a lantern on them
            c.set(3, 3, 6, Blocks.CHAIN.defaultBlockState());
            c.set(3, 2, 6, hangingLantern());
            c.set(5, 3, 3, BRICK);                               // the blocker
        });
    }

    /**
     * A closed hall, 7 by 7 with its walls (x and z 1 to 7) in a courtyard of apron, its roof 4 over its floor (walls 3 high, the roof the
     * course over them): every cell is in a builder's reach from the floor ({@link RaiseJob#STAND_UP} is 3). A door gap 2 high in the middle
     * of each wall; a hanging lantern under one roof cell, and a lantern on a chain under another, both clear of the walls.
     *
     * <p>Built for the walking of its builders: a stand is chosen only within {@value RaiseJob#WALK_STEPS} steps of a builder
     * ({@link RaiseJob#standFor}), and a vanilla path gives out round walls, so a builder outside a hall with one door cannot be sent to a stand
     * deep inside it (a hall of 9 by 9 with one door stalled 1 raise in 20, on its last roof cells). With a door in each wall every stand is
     * a short walk from anywhere round the hall. The lanterns hang clear of the walls because the eaves hold a hanging lantern beside a wall
     * (RaiseJob#held) before its chain and roof are laid: it hangs from nothing for a while.
     */
    public static synchronized Rooms.Room lamps() {
        return handBuilt(LAMPS, "Test Lamp Hall", 5, c -> {
            c.box(0, 0, 0, 8, 0, 8, BRICK);                      // the apron, the courtyard round the hall
            c.box(1, 1, 1, 7, 3, 1, BRICK);                      // the walls
            c.box(1, 1, 7, 7, 3, 7, BRICK);
            c.box(1, 1, 1, 1, 3, 7, BRICK);
            c.box(7, 1, 1, 7, 3, 7, BRICK);
            c.box(1, 1, 4, 1, 2, 4, AIR);                        // a door gap in each wall
            c.box(7, 1, 4, 7, 2, 4, AIR);
            c.box(4, 1, 1, 4, 2, 1, AIR);
            c.box(4, 1, 7, 4, 2, 7, AIR);
            c.box(1, 4, 1, 7, 4, 7, BRICK);                      // the roof
            c.set(3, 3, 3, hangingLantern());                    // under the roof cell (3,4,3)
            c.set(5, 3, 5, Blocks.CHAIN.defaultBlockState());    // a chain under (5,4,5), and a lantern on it
            c.set(5, 2, 5, hangingLantern());
        });
    }

    /**
     * A closed hall, 9 by 9, its walls (3 high) on the box's edge and its roof the course over them (4 over the floor, so every cell is in reach of a
     * builder on the floor), with one door gap 2 high in the middle of the west wall, and nothing hanging. The way a builder walks in is round the hall:
     * from outside the back wall it is more than {@value RaiseJob#WALK_STEPS} steps, and the roof's middle cells are in reach of no stand outside
     * ({@link RaiseJob#STAND_ACROSS} is 4, the walls are on the edge): they can be laid from inside only (the finding 2 of f12928d's review, and the
     * old lamp hall of 9 by 9 with one door, which stalled 1 raise in 20 on its last roof cells).
     */
    public static synchronized Rooms.Room oneDoor() {
        return handBuilt(ONE_DOOR, "Test One-Door Hall", 5, c -> {
            c.room(3);                                           // the apron, the walls to y 3 on the edge, the door gap (0,1,4) and (0,2,4)
            c.box(0, 4, 0, 8, 4, 8, BRICK);                      // the roof
        });
    }

    /** A flat apron and nothing else, 2 high: a template to give by hand, drop and give again. */
    private static synchronized Rooms.Room restorable() {
        return handBuilt(RESTORED, "Test Restored Apron", 2, c -> c.box(0, 0, 0, 8, 0, 8, BRICK));
    }

    /** A flat apron of dirt and nothing else, 2 high: its plinth is dirt, the land's own kind of block. */
    private static synchronized Rooms.Room groundApron() {
        return handBuilt(GROUND_APRON, "Test Ground Apron", 2, c -> c.box(0, 0, 0, 8, 0, 8, Blocks.DIRT.defaultBlockState()));
    }

    /**
     * Under open sky: three wall vines up the south wall; a cobweb in the north-west corner; a chain between two pillars' tops; two
     * beams on pillars, one with a column of cave vines hanging from it (two plants and their head), one with weeping vines; and, off
     * the west wall's top, a strip of two bricks with a column of three plain vines hanging free under its end: the top one by its top
     * face, the two under it only by the vine over each, with nothing beside them (the review of 30f4859: a column of three or more
     * pinned the course window, its lowest vine the lowest open course and the top one three courses over it, out of the window).
     */
    public static synchronized Rooms.Room vines() {
        return handBuilt(VINES, "Test Vines", 7, c -> {
            c.room();
            c.box(1, 4, 3, 2, 4, 3, BRICK);                      // a strip off the west wall's top (held as eaves)
            c.set(2, 3, 3, Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, true));
            c.set(2, 2, 3, plainVine());                         // plain vines: a face, and the vine over it to hold it
            c.set(2, 1, 3, plainVine());
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
     * The stall rule heaves the cell that holds the window, not a hanging cell that is waiting correctly (the review of 30f4859:
     * heaveFront heaved the lowest open cell, which can be a lantern waiting for its roof, and left the real blocker where it was).
     * In the hall of {@link #blocked()} (a lantern on two chains under the roof, a block hanging in the air a course over the
     * lantern): everything else is laid, and then the site stalls: the blocker is never held, so the course window stays at its
     * course, the roof two courses over it is never handed out, and the lantern, the lowest open cell, waits for the roof. At the
     * stall: the lantern is the lowest open cell, and the site's words (courseNow, state, phaseNow: the Ledger's course line) are the
     * window's, the blocker's course, not the lantern's. The first heave must go to the blocker, with the lantern's cell still open;
     * then the roof is laid, the chains and the lantern under it, and nothing more is heaved.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aStalledSiteHeavesTheBlockerNotALanternWaitingForItsRoof(GameTestHelper h) {
        Rooms.Room room = blocked();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "a hall with a lantern waiting for its roof and a blocker");
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            BlockPos lantern = s.pl.world(new BlockPos(3, 2, 6));
            BlockPos blocker = s.pl.world(new BlockPos(5, 3, 3));
            UUID builder = new UUID(0x5a17eL, 4L);
            BlockPos near = s.pl.door();
            boolean stalled = false;
            for (int rounds = 0; !job.isDone(); rounds++) {
                h.assertTrue(rounds < 4000, "no end to the claims: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size()
                        + " done; open: " + job.openReport(level, 8));
                BlockPos anchor = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                if (anchor != null) {
                    job.gnawSome(level, builder, 1f);
                    job.complete(level, builder);
                    near = anchor;
                    continue;
                }
                h.assertTrue(!stalled, "the site stalled a second time, after the blocker was heaved: " + firstOpen(level, job) + " [" + job.openReport(level, 6) + "]");
                h.assertTrue(!job.anyClaimable(level) && !job.anyOut(level.getGameTime()), "claim handed out nothing, but the site could: " + job.openReport(level, 8));
                stalled = true;
                String first = firstOpen(level, job);
                h.assertTrue(first.startsWith(lantern.toShortString() + " "), "the site's lowest open cell isn't the lantern waiting for its roof, at "
                        + lantern.toShortString() + ": " + first);
                h.assertTrue(job.phaseNow() == RaiseJob.COURSE && job.courseNow() == 3 && job.state().equals("laying the 4th course"),
                        "the site's words follow its lowest open cell (the lantern's course 2), not the course window (the blocker's course 3): phase "
                                + job.phaseNow() + ", course " + job.courseNow() + ", \"" + job.state() + "\"");
                h.assertTrue(job.heaveFront(level) && job.heaved() == 1, "the stalled site heaved nothing: " + job.openReport(level, 8));
                h.assertTrue(level.getBlockState(blocker) == BRICK && level.getBlockState(lantern).isAir(),
                        "the first heave went to the lantern waiting for its roof, not to the blocker: the blocker at " + blocker.toShortString() + " is "
                                + level.getBlockState(blocker) + ", the lantern at " + lantern.toShortString() + " is " + level.getBlockState(lantern));
            }
            h.assertTrue(stalled && job.heaved() == 1, "the hall was raised with " + job.heaved() + " cells heaved and " + (stalled ? "a stall" : "no stall at all")
                    + " (one stall, the blocker's, is the scene)");
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
     * A template given by hand is refused for a registered room (the review of 30f4859: RoomTemplates.put was unguarded, and a test
     * giving a real room's id swapped its template under every dig and raise of it), and a building of the tests' own is given again
     * once the cache has lost it: the dev client drops every template at a server stop (UnderEmpire#onServerStopped) and keeps the
     * rooms registered, and handBuilt gave the template only when the room wasn't registered, so the building came back with no cells.
     * The engine's test platform (a registered room with a file of its own) is refused and keeps its template; a hand-built apron is
     * dropped, as a server stop drops it, and given again by the same call that gave it first.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "raise")
    public static void aHandGivenTemplateIsRefusedForARoomAndGivenAgainForABuildingOfTheTests(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room platform = SitingGameTests.platform();
        List<RoomTemplates.Entry> real = RoomTemplates.get(level, platform.id());
        boolean refused = false;
        try {
            RoomTemplates.put(platform.id(), List.of());
        } catch (IllegalStateException e) {
            refused = true;
        }
        if (!refused) {
            RoomTemplates.forget(platform.id());                 // put it back from its file: a failure leaves the other tests their platform
        }
        h.assertTrue(refused, "put took a template for the registered room " + platform.id() + ": every dig and raise of it would read the one given by hand");
        h.assertTrue(RoomTemplates.get(level, platform.id()) == real && real.size() == 9 * 5 * 9, "the refused put changed the platform's template: "
                + RoomTemplates.get(level, platform.id()).size() + " cells, not " + real.size());
        Rooms.Room apron = restorable();
        int cells = RoomTemplates.get(level, apron.id()).size();
        h.assertTrue(cells == 9 * 2 * 9 && RoomTemplates.has(apron.id()), "the hand-built apron has " + cells + " cells, not " + 9 * 2 * 9);
        RoomTemplates.forget(apron.id());                        // a server stop drops it, and the room stays registered
        h.assertTrue(!RoomTemplates.has(apron.id()) && Rooms.get(apron.id()) == apron, "the apron's template wasn't dropped, or its room went with it");
        Rooms.Room again = restorable();
        h.assertTrue(again == apron && RoomTemplates.has(apron.id()) && RoomTemplates.get(level, apron.id()).size() == cells,
                "after a server stop the hand-built apron's template wasn't given again: " + RoomTemplates.get(level, apron.id()).size() + " cells, not " + cells);
        h.succeed();
    }

    /**
     * A builder is sent to stand where it can walk to (the crewed test of the hanging pieces, aLampHallHasItsLanternsHungByBuilders, failed 1 run
     * in 4: the finished roof of a closed hall was the best cell to lay its last roof cells from, with no way up to it, and its builder stood out
     * its 400 ticks at the foot of a wall on a path to nowhere, the cell shelved and then heaved). In the hall of {@link #lamps()} (a roof 4 over the
     * floor, the walls 3 high: the roof is in reach from the floor, never from a step): the claims lay the hall up to its last few cells, a roof's;
     * then for the next cell of the roof a builder standing in the doorway is given a cell on the floor under the roof, never one on the roof.
     * The control, so the test can fail: for a builder who stands on the roof the best cell is on the roof (so it is the best by the old rule).
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aBuilderIsSentToStandWhereItCanWalkTo(GameTestHelper h) {
        Rooms.Room room = lamps();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "the lamp hall");
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            UUID builder = new UUID(0x5a17eL, 5L);
            BlockPos near = s.pl.door();
            for (int rounds = 0; job.taskList().size() - job.finishedCount() > 12; rounds++) {
                h.assertTrue(rounds < 4000, "no end to the claims: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size()
                        + " done; open: " + job.openReport(level, 8));
                BlockPos anchor = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                h.assertTrue(anchor != null, "nothing could be handed out: " + job.openReport(level, 8));
                job.gnawSome(level, builder, 1f);
                job.complete(level, builder);
            }
            BlockPos anchor = null;                              // a roof cell still to be laid (the lanterns, the last cells of all, are not)
            for (DigJob.Task t : job.taskList()) {
                if (anchor == null && t.pos().getY() == s.floor + 4 && job.fillsOpen(t.pos())) {
                    anchor = t.pos();
                }
            }
            h.assertTrue(anchor != null, "no roof cell is left to be laid (4 over the floor): " + job.openReport(level, 12));
            List<BlockPos> cells = List.of(anchor);
            BlockPos roofTop = null;
            for (int dx = -3; dx <= 3 && roofTop == null; dx++) {
                for (int dz = -3; dz <= 3 && roofTop == null; dz++) {
                    BlockPos c = anchor.offset(dx, 1, dz);
                    if ((dx != 0 || dz != 0) && claude.underempire.core.unit.SettlerMob.standable(level, c)) {
                        roofTop = c;
                    }
                }
            }
            h.assertTrue(roofTop != null, "no finished roof by the cell " + anchor.toShortString() + " to stand on: the scene is wrong");
            BlockPos onRoof = job.standFor(level, anchor, cells, roofTop);
            h.assertTrue(onRoof != null && onRoof.getY() == s.floor + 5, "for a builder on the roof, the cell " + anchor.toShortString() + " isn't laid from the roof: "
                    + (onRoof == null ? "no cell" : onRoof.toShortString()) + " (the control: the roof's top is the best cell by the old rule)");
            BlockPos stand = job.standFor(level, anchor, cells, near);
            h.assertTrue(stand != null && stand.getY() <= s.floor + 2 && anchor.getY() - stand.getY() <= RaiseJob.STAND_UP,
                    "for a builder in the doorway the roof cell " + anchor.toShortString() + " is laid from " + (stand == null ? "no cell" : stand.toShortString()
                            + " (the floor is at " + (s.floor + 1) + ", the roof's top at " + (s.floor + 5) + ")") + ": a builder is sent where no path goes");
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A builder who can't walk to any stand is told so (the one-door stall, finding 2 of f12928d's review: {@code RaiseJob#standFor} then fell back to the
     * old best, a stand inside the hall's walls, and the builder stood out its 400 ticks on a path to nowhere; SiteWork now gives the batch back and sends
     * the builder round to the door). In {@link #oneDoor()}: the claims lay the apron and the walls; then for the roof's middle cell a builder in the
     * doorway has a stand on the floor under the roof and can walk to it, and a builder outside the back wall has the same best stand (the old rule's
     * answer) and can not: {@link RaiseJob.Stand#walkable()} is false.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aBuilderOutsideAHallWithOneDoorCannotWalkToAnyStand(GameTestHelper h) {
        Rooms.Room room = oneDoor();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "the one-door hall");
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            UUID builder = new UUID(0x5a17eL, 6L);
            BlockPos near = s.pl.door();
            for (int rounds = 0; job.courseNow() < 4; rounds++) {
                h.assertTrue(rounds < 4000 && !job.isDone(), "the walls never finished: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size()
                        + " done; open: " + job.openReport(level, 8));
                BlockPos claimed = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                h.assertTrue(claimed != null, "nothing could be handed out: " + job.openReport(level, 8));
                job.gnawSome(level, builder, 1f);
                job.complete(level, builder);
            }
            BlockPos anchor = new BlockPos(s.bx0 + 4, s.floor + 4, s.bz0 + 4);   // the roof's middle cell, the whole roof still to be laid
            h.assertTrue(job.fillsOpen(anchor) && level.getBlockState(anchor).isAir(), "the roof's middle cell " + anchor.toShortString() + " isn't an open cell of the site: "
                    + level.getBlockState(anchor) + ", " + job.openReport(level, 8));
            List<BlockPos> cells = List.of(anchor);
            RaiseJob.Stand inDoor = job.stand(level, anchor, cells, s.pl.door());
            h.assertTrue(inDoor.at() != null && inDoor.walkable() && s.pl.box().isInside(inDoor.at()) && inDoor.at().getY() <= s.floor + 2,
                    "for a builder in the doorway the roof cell " + anchor.toShortString() + " is laid from " + inDoor + ", not a cell on the floor it can walk to");
            BlockPos outside = new BlockPos(s.bx0 + 11, s.floor + 1, s.bz0 + 4);
            h.assertTrue(claude.underempire.core.unit.SettlerMob.standable(level, outside), "the builder's place outside the back wall, " + outside.toShortString()
                    + ", isn't a cell to stand in");
            RaiseJob.Stand out = job.stand(level, anchor, cells, outside);
            h.assertTrue(out.at() != null && !out.walkable(), "for a builder outside the back wall at " + outside.toShortString() + " the roof cell " + anchor.toShortString()
                    + " has the stand " + out + ", which it can walk to: the way in is round the hall, over " + RaiseJob.WALK_STEPS + " steps");
            h.assertTrue(out.at().equals(job.standFor(level, anchor, cells, outside)), "standFor is not the stand's cell: " + job.standFor(level, anchor, cells, outside)
                    + " and " + out);
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A builder who stands where it can't be told to stand is still sent to a stand it can walk to (the flake of aLampHallHasItsLanternsHungByBuilders,
     * 2026-10-06, f26fc41: a builder at the cell under the hall's chain and lantern, claiming the last roof cells, was given the roof's top, 4 over its
     * head, and held the batch its 400 ticks until the cell was shelved and heaved). {@code RaiseJob#flood} begins in the cell the builder is in, and
     * {@code SettlerMob.standable} refuses a cell with a lantern's collision box over its feet: no flood, "can't tell", the old best (the highest cell
     * in each column, the finished roof) and a {@link RaiseJob.Stand} that says it is walkable. In the hall of {@link #lamps()}, laid up to its last
     * cells, the lantern on its chain at (5,2,5): for a builder in the cell under it the roof cell still to be laid is laid from a cell on the floor
     * it can walk to, as for a builder one cell aside (the control: the same cells, the same stand rule).
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "raise")
    public static void aBuilderUnderAHangingLanternIsSentToAStandItCanWalkTo(GameTestHelper h) {
        Rooms.Room room = lamps();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "the lamp hall");
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            for (int k = 0; job.clearing(); k++) {
                h.assertTrue(k < 64, "the site's clearing never ends: " + job.state());
                job.clear(level, SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
            }
            UUID builder = new UUID(0x5a17eL, 7L);
            BlockPos near = s.pl.door();
            for (int rounds = 0; job.taskList().size() - job.finishedCount() > 12; rounds++) {
                h.assertTrue(rounds < 4000, "no end to the claims: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size()
                        + " done; open: " + job.openReport(level, 8));
                BlockPos claimed = job.claim(level, builder, SiteWork.BATCH, level.getGameTime(), near);
                h.assertTrue(claimed != null, "nothing could be handed out: " + job.openReport(level, 8));
                job.gnawSome(level, builder, 1f);
                job.complete(level, builder);
            }
            BlockPos lantern = s.pl.world(new BlockPos(5, 2, 5));          // the lantern on the chain: hangs over the floor cell the builder is in
            if (!RaiseJob.hangs(level.getBlockState(lantern))) {
                level.setBlock(lantern.above(), Blocks.CHAIN.defaultBlockState(), 2);   // laid by hand as the crew does it late: the stand rule doesn't ask who laid it
                level.setBlock(lantern, hangingLantern(), 2);
            }
            BlockPos under = lantern.below();
            BlockPos aside = under.east();
            h.assertTrue(!claude.underempire.core.unit.SettlerMob.standable(level, under) && level.getBlockState(under).isAir(),
                    "the scene is wrong: the cell " + under.toShortString() + " under the lantern is one to stand in (" + level.getBlockState(lantern) + " over it)");
            h.assertTrue(claude.underempire.core.unit.SettlerMob.standable(level, aside), "the scene is wrong: the cell " + aside.toShortString() + " beside it is not one to stand in");
            BlockPos anchor = null;                              // the roof cell still to be laid nearest the lantern
            for (DigJob.Task t : job.taskList()) {
                if (t.pos().getY() == s.floor + 4 && job.fillsOpen(t.pos())
                        && (anchor == null || t.pos().distSqr(under) < anchor.distSqr(under))) {
                    anchor = t.pos();
                }
            }
            h.assertTrue(anchor != null, "no roof cell is left to be laid (4 over the floor): " + job.openReport(level, 12));
            List<BlockPos> cells = List.of(anchor);
            RaiseJob.Stand beside = job.stand(level, anchor, cells, aside);
            h.assertTrue(beside.at() != null && beside.walkable() && beside.at().getY() <= s.floor + 2,
                    "the control: for a builder at " + aside.toShortString() + " the roof cell " + anchor.toShortString() + " is laid from " + beside + ", not a cell on the floor it can walk to");
            RaiseJob.Stand stand = job.stand(level, anchor, cells, under);
            h.assertTrue(stand.at() != null && stand.at().getY() <= s.floor + 2,
                    "for a builder at " + under.toShortString() + " (under the lantern at " + lantern.toShortString() + ") the roof cell " + anchor.toShortString()
                            + " is laid from " + stand + " (the floor is at " + (s.floor + 1) + ", the roof's top at " + (s.floor + 5) + "): a builder is sent where no path goes"
                            + (stand.walkable() ? ", and told it can walk there" : ""));
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A builder swimming in a pool 3 deep, 2 from every shore, has no cell to walk from (the review of 420b3e0: the flood then could not tell,
     * and the stand was the highest cell, said to be walkable): it is told it can't walk to its stand, so the site sends it back to the door. The
     * controls: a builder on the bank, one swimming at the bank's edge (a step up beside it), and one on carpet, a slab, a stair or a path (its
     * feet in the part block's own cell: the review of a235bf3) can each walk to one.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "raise")
    public static void aSwimmingBuilderIsSentBackToTheDoor(GameTestHelper h) {
        Rooms.Room room = lamps();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "the lamp hall");
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            int cx = s.bx1() + 5;                                // a pool 5 by 5, 3 deep, east of the hall's berm and ramp
            int cz = s.bz0 + 4;
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    for (int y = s.floor - 2; y <= s.floor; y++) {
                        s.set(x, y, z, Blocks.WATER.defaultBlockState());
                    }
                }
            }
            BlockPos swimming = new BlockPos(cx, s.floor, cz);
            BlockPos bank = new BlockPos(cx - 3, s.floor + 1, cz);
            h.assertTrue(!claude.underempire.core.unit.SettlerMob.standable(level, swimming) && claude.underempire.core.unit.SettlerMob.standable(level, bank),
                    "the scene is wrong: the pool's middle " + swimming.toShortString() + " is one to stand in, or the bank " + bank.toShortString() + " is not");
            BlockPos anchor = null;
            for (DigJob.Task t : job.taskList()) {
                if (t.pos().getY() == s.floor + 1 && (anchor == null || t.pos().distSqr(bank) < anchor.distSqr(bank))) {
                    anchor = t.pos();                            // a cell of the first course, nearest the pool
                }
            }
            h.assertTrue(anchor != null, "no cell of the first course");
            List<BlockPos> cells = List.of(anchor);
            RaiseJob.Stand fromBank = job.stand(level, anchor, cells, bank);
            h.assertTrue(fromBank.at() != null && fromBank.walkable(), "the control: a builder on the bank at " + bank.toShortString() + " is given " + fromBank);
            RaiseJob.Stand fromPool = job.stand(level, anchor, cells, swimming);
            h.assertTrue(!fromPool.walkable(), "a builder swimming at " + swimming.toShortString() + " is given " + fromPool
                    + ": told it can walk there from water it can't stand in, so it is never sent back to the door");
            BlockPos atBank = new BlockPos(cx - 2, s.floor, cz);       // swimming at the edge: the bank is a step up beside it
            RaiseJob.Stand fromEdge = job.stand(level, anchor, cells, atBank);
            h.assertTrue(fromEdge.walkable(), "a builder swimming at the bank's edge " + atBank.toShortString() + " is given " + fromEdge
                    + ": it can climb out onto the bank beside it");
            // on a part block (the review of a235bf3): carpet, a bottom slab, a stair, a path on level ground; each its own column on the bank
            net.minecraft.world.level.block.state.BlockState[] parts = {Blocks.WHITE_CARPET.defaultBlockState(), Blocks.STONE_SLAB.defaultBlockState(),
                    Blocks.STONE_BRICK_STAIRS.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState()};
            for (int k = 0; k < parts.length; k++) {
                boolean path = parts[k].is(Blocks.DIRT_PATH);
                BlockPos on = new BlockPos(cx - 4 - k, path ? s.floor : s.floor + 1, cz + 3);   // a path is set into the ground, the rest on it
                s.set(on.getX(), on.getY(), on.getZ(), parts[k]);
                RaiseJob.Stand fromPart = job.stand(level, anchor, cells, on);
                h.assertTrue(fromPart.walkable(), "a builder on " + parts[k].getBlock().getName().getString() + " at " + on.toShortString() + " is given " + fromPart
                        + ": standing on a part block of its own cell, it can walk");
            }
            s.clear();
            h.succeed();
        }));
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

    // --- the clearing's foot, and a site that did nothing (surface-builder.md item 20, P1.1 and P1.2) --------------------------------

    /**
     * The builder sent to cut a tree stands at its foot, whatever the tree's size (item 20: "the clearing's cutter can be out of reach": it anchored at the
     * lowest of the next {@link SiteWork#CLEAR_BATCH} cells top-down, which for a tree of more cells than that is its crown, high over the trunk, where no one can
     * stand: the cutter stood out CrewGoal's 400 ticks and the site cleared 20 cells a second after each 400 ticks with no cell down). Oaks of 25, 110 and 255
     * cells (the most a site fells is {@code Siting.TREE_MAX}, 256) stand in the platform's box, and the clearing's batches are taken as SiteWork takes them
     * ({@link RaiseJob#clearNext}, then {@link RaiseJob#clearSome} for each cell of the chore): for every batch of every oak {@link RaiseJob#clearFoot} is the
     * foot of the trunk, the cell of its first log, and the scene shows what the old anchor did: the lowest cell of the batch was more than
     * {@link RaiseJob#STAND_UP} over the foot for the batches of the two big oaks (the first, at least), and never for the small one.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "raise")
    public static void theClearingsFootIsTheTrunksFootWhateverTheTreesSize(GameTestHelper h) {
        Site s = new Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            for (int[] oak : new int[][]{{4, 1, 2, 25}, {14, 2, 4, 110}, {15, 3, 5, 255}}) {
                s.lay((x, z) -> s.floor);
                List<BlockPos> tree = SitingGameTests.tree(s, s.bx0 + 4, s.floor + 1, s.bz0 + 4, oak[0], oak[1], oak[2]);
                h.assertTrue(tree.size() == oak[3], "the test's oak (a trunk of " + oak[0] + ", a crown " + oak[1] + " out over " + oak[2] + " layers) has " + tree.size()
                        + " cells, not " + oak[3]);
                Plan p = s.expectPlan(s.plan(true), "an oak of " + tree.size() + " cells");
                RaiseJob job = new RaiseJob(SitingGameTests.PLATFORM, s.pl.door(), Direction.EAST, p, 0);
                job.ensureLoaded(level);
                BlockPos foot = new BlockPos(s.bx0 + 4, s.floor + 1, s.bz0 + 4);
                BlockPos near = s.pl.door();                     // where a builder is as it is sent: the landing, near enough
                int batches = 0;
                int out = 0;
                int highest = 0;
                for (List<BlockPos> cells = job.clearNext(level, SiteWork.CLEAR_BATCH); !cells.isEmpty(); cells = job.clearNext(level, SiteWork.CLEAR_BATCH)) {
                    h.assertTrue(++batches <= 8, "no end to the clearing's batches of an oak of " + tree.size() + " cells: " + job.state());
                    int lowest = Integer.MAX_VALUE;
                    for (BlockPos c : cells) {
                        lowest = Math.min(lowest, c.getY());
                    }
                    BlockPos at = job.clearFoot(level, cells, near);
                    h.assertTrue(at.equals(foot), "the batch " + batches + " of an oak of " + tree.size() + " cells (" + cells.size() + " cells, the lowest " + (lowest - foot.getY())
                            + " over the foot) is cut from " + at.toShortString() + " (" + (at.getY() - foot.getY()) + " over the foot), not from the foot of the trunk at " + foot.toShortString());
                    if (lowest - foot.getY() > RaiseJob.STAND_UP) {
                        out++;
                    }
                    highest = Math.max(highest, lowest - foot.getY());
                    job.clearSome(level, cells.size());
                }
                h.assertTrue(batches == (tree.size() + SiteWork.CLEAR_BATCH - 1) / SiteWork.CLEAR_BATCH, "an oak of " + tree.size() + " cells was cut in " + batches + " batches");
                h.assertTrue(tree.size() <= SiteWork.CLEAR_BATCH ? out == 0 : out >= 1, "the scene doesn't show the old anchor's fault: " + out + " of the " + batches
                        + " batches of an oak of " + tree.size() + " cells had their lowest cell out of reach of its foot (the highest " + highest + " over it)");
                for (BlockPos c : tree) {
                    h.assertTrue(level.getBlockState(c).isAir(), "the oak's cell " + c.toShortString() + " is " + level.getBlockState(c) + ": the clearing left it standing");
                }
            }
            s.clear();
            h.succeed();
        }));
    }

    /** Every block of {@code box}, x fastest, then z, then y (a grass block read as dirt: it spreads and dies under the random ticks, which no job does). */
    private static BlockState[] states(ServerLevel level, BoundingBox box) {
        BlockState[] out = new BlockState[box.getXSpan() * box.getYSpan() * box.getZSpan()];
        int i = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    BlockState b = level.getBlockState(new BlockPos(x, y, z));
                    out[i++] = b.is(Blocks.GRASS_BLOCK) ? Blocks.DIRT.defaultBlockState() : b;
                }
            }
        }
        return out;
    }

    /** How many cells of {@code box} are not what {@code was} ({@link #states}) says, and the first, in words; null when none. */
    private static String changed(ServerLevel level, BoundingBox box, BlockState[] was) {
        BlockState[] now = states(level, box);
        int n = 0;
        String first = null;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != was[i]) {
                n++;
                if (first == null) {
                    first = new BlockPos(box.minX() + i % box.getXSpan(), box.minY() + i / (box.getXSpan() * box.getZSpan()), box.minZ() + (i / box.getXSpan()) % box.getZSpan())
                            .toShortString() + " was " + was[i] + ", is " + now[i];
                }
            }
        }
        return n == 0 ? null : n + " cells changed, the first " + first;
    }

    /**
     * A site that did nothing leaves nothing (item 20: "a call-off before or during the clearing makes {@code levelFloor} carve the building's shell into a
     * hillside"): {@link RaiseJob#levelFloor} filled every never-laid floor cell that was air over solid ground, and cut every never-laid cell over the floor that
     * held the land, whether the building had begun or not, so a site called off at tick 0 left the hump in its box cut and the dip filled. On
     * {@link #humpy}'s land (a hump of 2 over four columns of the box and one of 1 on another, a dip of 1 under four, a dip of 2 across the south-east corner), a
     * job is torn down at four stages, the world compared with what it was before the leveling (the cells the teardown takes down come out of it):
     * <ul>
     *   <li>nothing done: nothing is taken down ({@code tearOpen} 0) and the floor is left alone: no cell of the area changes;</li>
     *   <li>the clearing done, nothing laid: the same, the clearing's cuts staying as they were;</li>
     *   <li>the plinth of the building's blocks laid in the dip, no course: it comes down to the land's ground, and the floor is made level, a cell over each of the
     *   dip's columns that is air over solid ground, but the hump is not cut: nothing of the building has risen to leave it standing among its cells;</li>
     *   <li>one cell of a course laid: the hump is cut too, as the coordinator decided ("my call: leave level ground").</li>
     * </ul>
     * Each stage is worked out here from the world, not from the engine's own count. Each fails without its guard (the old code carves at the first two
     * stages, and cuts the hump at the third).
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "raise")
    public static void aSiteThatDidNothingLeavesNothingWhenItIsTornDown(GameTestHelper h) {
        Site s = new Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            for (int stage = 1; stage <= 4; stage++) {
                s.lay((x, z) -> humpy(s.floor, s.bx0, s.bz0, x, z));
                Plan p = s.expectPlan(s.plan(true), "the hump and the dip");
                RaiseJob job = new RaiseJob(SitingGameTests.PLATFORM, s.pl.door(), Direction.EAST, p, 0);
                job.ensureLoaded(level);
                int clearing = 0;
                while (clearing < job.taskList().size() && job.phaseOf(clearing) == RaiseJob.CLEARING) {
                    clearing++;
                }
                int firstCourse = clearing;
                while (firstCourse < job.taskList().size() && job.phaseOf(firstCourse) != RaiseJob.COURSE) {
                    firstCourse++;
                }
                String done = switch (stage) {
                    case 1 -> "nothing done";
                    case 2 -> "the clearing done";
                    case 3 -> "the earthworks done, the plinth laid";
                    default -> "the earthworks and one cell of the first course done";
                };
                if (stage >= 2) {
                    job.gnawInOrder(level, stage == 2 ? clearing : stage == 3 ? firstCourse : firstCourse + 1, new ArrayList<>());
                }
                int laid = stage <= 2 ? 0 : stage == 3 ? 4 : 5;  // the four cells of the plinth of the building's blocks in the dip, and a course cell
                h.assertTrue(job.laidCount() == laid, done + ": " + job.laidCount() + " cells of the building are laid, not " + laid);
                BoundingBox area = job.footprint();
                BlockPos hump = new BlockPos(s.bx0 + 3, s.floor + 2, s.bz0 + 5);
                BlockPos dip = new BlockPos(s.bx0 + 1, s.floor, s.bz0 + 7);
                h.assertTrue(level.getBlockState(hump).is(Blocks.GRASS_BLOCK) && !level.getBlockState(hump.below()).isAir() && level.getBlockState(dip).isAir()
                                && !level.getBlockState(dip.below()).isAir(), done + ": the scene has no hump at " + hump.toShortString() + " or no dip at " + dip.toShortString() + ": "
                        + level.getBlockState(hump) + ", " + level.getBlockState(dip));
                BlockState[] before = states(level, area);
                job.startTearDown(level, level.getGameTime());
                int plinth = job.tearOpen();
                job.tearSelf(level, 100);
                h.assertTrue(job.tornDown() && job.tornCount() == plinth && plinth == laid, done + ": the teardown took down " + job.tornCount() + " of " + plinth
                        + " cells, with " + laid + " laid");
                // what leveling must do, from the world: a floor cell that is air over solid ground; and, once a course is laid, a cell the building has over its floor
                // that holds the land
                int fill = 0;
                for (int x = s.bx0; x <= s.bx0 + 8; x++) {
                    for (int z = s.bz0; z <= s.bz0 + 8; z++) {
                        BlockPos c = new BlockPos(x, s.floor, z);
                        fill += level.getBlockState(c).isAir() && !level.getBlockState(c.below()).isAir() ? 1 : 0;
                    }
                }
                int cut = 0;
                for (RoomTemplates.Entry e : s.template) {
                    BlockPos c = s.pl.world(e.local());
                    cut += !e.state().isAir() && e.local().getY() >= 1 && Siting.ground(level.getBlockState(c)) ? 1 : 0;
                }
                BlockState[] torn = states(level, area);
                int n = job.levelFloor(level);
                String moved = changed(level, area, torn);
                if (stage <= 2) {
                    h.assertTrue(n == 0 && moved == null, done + ": the site that did nothing changed the land: levelFloor made " + n + " cells level, " + moved + " (the hump at "
                            + hump.toShortString() + " is " + level.getBlockState(hump) + ", the dip's floor cell at " + dip.toShortString() + " is " + level.getBlockState(dip) + ")");
                    h.assertTrue(changed(level, area, before) == null, done + ": the world changed since the teardown began: " + changed(level, area, before));
                    continue;
                }
                int want = fill + (stage == 4 ? cut : 0);
                h.assertTrue(fill >= 8 && cut >= 9 && n == want, done + ": levelFloor made " + n + " cells level, not the " + want + " the world calls for (" + fill + " floor cells that are air over solid ground, "
                        + (stage == 4 ? cut : 0) + " cells of the land over the floor in the building's cells; the scene has at least 8 and 9)");
                for (int x = s.bx0; x <= s.bx0 + 8; x++) {
                    for (int z = s.bz0; z <= s.bz0 + 8; z++) {
                        BlockPos floorCell = new BlockPos(x, s.floor, z);
                        h.assertTrue(Siting.ground(level.getBlockState(floorCell)), done + ": the floor of the box at " + x + " " + z + " is " + level.getBlockState(floorCell)
                                + ": a hole where the dip was, or masonry");
                    }
                }
                boolean standing = !level.getBlockState(hump).isAir() && !level.getBlockState(hump.below()).isAir();
                h.assertTrue(stage == 3 ? standing : level.getBlockState(hump).isAir() && level.getBlockState(hump.below()).isAir(), done + ": the hump at " + hump.toShortString()
                        + (stage == 3 ? " was cut (" : " still stands (") + level.getBlockState(hump) + ", " + level.getBlockState(hump.below()) + ") "
                        + (stage == 3 ? "by a building that never rose: there is nothing of it left standing among its cells to level" : "though the building rose: nothing stands up out of its floor"));
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A site whose plinth is the land's own kind of block is levelled all the same when it is called off after its plinth and before its first course (the review of P1:
     * the floor was levelled only once a cell of the building was laid, and a plinth of dirt, gravel, stone, tuff or mud is {@code Siting.ground}, so it is no part of the building and
     * is never counted: a 1-deep notch was left over each dip column that the old code levelled). A hand-built apron of dirt on land with a dip of 2 under four columns of its box: the
     * earthworks are laid (the plinth fills the dip with dirt up to the floor's cell), no cell of a course, the site is torn down at once and its floor levelled: the floor cell over each
     * of the four is the land's ground, and the box is level. The test room's plinth is stone bricks and so is counted; this is the one that is not.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "raise")
    public static void aSiteWhoseOwnPlinthIsTheLandsIsLevelledWhenCalledOffBeforeItsFirstCourse(GameTestHelper h) {
        Rooms.Room room = groundApron();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> x >= s.bx0 + 3 && x <= s.bx0 + 4 && z >= s.bz0 + 3 && z <= s.bz0 + 4 ? s.floor - 2 : s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            Plan p = s.expectPlan(s.plan(true), "a dip under a dirt apron");
            RaiseJob job = new RaiseJob(room.id(), s.pl.door(), Direction.EAST, p, 0);
            job.ensureLoaded(level);
            int firstCourse = 0;
            while (firstCourse < job.taskList().size() && job.phaseOf(firstCourse) != RaiseJob.COURSE) {
                firstCourse++;
            }
            job.gnawInOrder(level, firstCourse, new ArrayList<>());
            BlockPos notch = new BlockPos(s.bx0 + 3, s.floor, s.bz0 + 3);
            h.assertTrue(level.getBlockState(notch).isAir() && level.getBlockState(notch.below()).is(Blocks.DIRT) && !level.getBlockState(notch.below(2)).isAir(),
                    "the scene has no plinth of dirt under a floor cell still to be laid at " + notch.toShortString() + ": " + level.getBlockState(notch) + " over " + level.getBlockState(notch.below()));
            h.assertTrue(job.laidCount() == 0, "a plinth of dirt is the land's, not the building's: " + job.laidCount() + " cells of the building are laid");
            job.startTearDown(level, level.getGameTime());
            h.assertTrue(job.tornDown(), "nothing of the building was laid, so nothing is to come down");
            int notches = 0;
            for (int x = s.bx0; x <= s.bx0 + 8; x++) {
                for (int z = s.bz0; z <= s.bz0 + 8; z++) {
                    BlockPos c = new BlockPos(x, s.floor, z);
                    notches += level.getBlockState(c).isAir() && !level.getBlockState(c.below()).isAir() ? 1 : 0;
                }
            }
            int n = job.levelFloor(level);
            h.assertTrue(notches == 4 && n == notches, "the plinth of dirt is laid under 4 floor cells, which are 1-deep notches in the box; levelFloor made " + n + " level (the world calls for " + notches + ")");
            for (int x = s.bx0; x <= s.bx0 + 8; x++) {
                for (int z = s.bz0; z <= s.bz0 + 8; z++) {
                    h.assertTrue(Siting.ground(level.getBlockState(new BlockPos(x, s.floor, z))), "the floor of the box at " + x + " " + z + " is " + level.getBlockState(new BlockPos(x, s.floor, z))
                            + ": a notch left where the dip was");
                }
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * The owed lines of an owner who was away ({@link claude.underempire.core.work.OwedLines}; the review of P1), with no world: a line the same as one kept is counted, not kept again,
     * and said once with its count in the place of the first; the queue holds at most {@link claude.underempire.core.work.OwedLines#MAX}, and a routine line ("X is raised.") never pushes
     * out one that matters: when the queue is full the oldest routine line goes first, a new routine line is dropped when there is none to make room by, and only a full queue of lines that
     * matter drops its oldest; they come out oldest first; and a save round-trips them with their counts and which are routine.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "raise")
    public static void theOwedLinesCollapseKeepWhatMattersAndComeOutOldestFirst(GameTestHelper h) {
        h.runAfterDelay(1, () -> {
            net.minecraft.ChatFormatting y = net.minecraft.ChatFormatting.YELLOW;
            net.minecraft.ChatFormatting g = net.minecraft.ChatFormatting.GREEN;
            claude.underempire.core.work.OwedLines q = new claude.underempire.core.work.OwedLines();
            q.add("The Warp-Orchard is raised.", g, true);
            q.add("The Hoard is down: 10 of the 10 it cost are paid back.", y, false);
            q.add("The Warp-Orchard is raised.", g, true);
            q.add("The Warp-Orchard is raised.", g, true);
            q.add("The Warp-Orchard is raised.", y, true);            // the same words in another colour are another line
            var got = q.lines();
            h.assertTrue(got.size() == 3 && got.get(0).count() == 3 && got.get(0).said().equals("The Warp-Orchard is raised. (x3)") && got.get(1).count() == 1
                            && got.get(1).said().equals("The Hoard is down: 10 of the 10 it cost are paid back.") && got.get(2).color() == y,
                    "identical lines were not collapsed into the place of the first, with a count: " + got);
            // a full queue: 12 lines that matter, then a routine line, then one more that matters
            claude.underempire.core.work.OwedLines full = new claude.underempire.core.work.OwedLines();
            for (int i = 0; i < claude.underempire.core.work.OwedLines.MAX; i++) {
                full.add("Refund " + i, y, false);
            }
            full.add("The Hoard is raised.", g, true);
            h.assertTrue(full.size() == 12 && full.lines().stream().noneMatch(l -> l.routine()) && full.lines().get(0).text().equals("Refund 0"),
                    "a routine line pushed a line that matters out of a full queue: " + full.lines().stream().map(l -> l.text()).toList());
            full.add("Refund 12", y, false);
            h.assertTrue(full.size() == 12 && full.lines().get(0).text().equals("Refund 1") && full.lines().get(11).text().equals("Refund 12"),
                    "a full queue of lines that matter keeps the newest and lets its oldest go: " + full.lines().stream().map(l -> l.text()).toList());
            // routine lines make room for what matters, oldest first
            claude.underempire.core.work.OwedLines mixed = new claude.underempire.core.work.OwedLines();
            for (int i = 0; i < 6; i++) {
                mixed.add("Refund " + i, y, false);              // the oldest are the ones that matter
            }
            for (int i = 0; i < 6; i++) {
                mixed.add("Room " + i + " is raised.", g, true);
            }
            mixed.add("Refund 6", y, false);                       // 13th: the oldest routine line, "Room 0", makes the room, not the oldest line
            mixed.add("Refund 7", y, false);                       // and the next
            var texts = mixed.lines().stream().map(l -> l.text()).toList();
            h.assertTrue(mixed.size() == 12 && !texts.contains("Room 0 is raised.") && !texts.contains("Room 1 is raised.") && texts.contains("Room 2 is raised.")
                            && texts.contains("Refund 0") && texts.contains("Refund 7") && texts.get(0).equals("Refund 0"),
                    "the oldest routine lines should make room for lines that matter, and nothing else go: " + texts);
            for (int i = 0; i < 4; i++) {
                mixed.add("Late " + i + " is raised.", g, true);   // routine into a queue with room made only by routine lines: the oldest routine goes, never a refund
            }
            texts = mixed.lines().stream().map(l -> l.text()).toList();
            h.assertTrue(texts.contains("Refund 0") && texts.contains("Refund 7") && texts.stream().filter(t -> t.startsWith("Refund")).count() == 8,
                    "a routine line pushed a line that matters out: " + texts);
            // saved and read back: counts, colours and which are routine; oldest first; capped
            var back = new claude.underempire.core.work.OwedLines();
            back.load(q.save());
            h.assertTrue(back.lines().equals(q.lines()), "the save didn't read back the same: " + back.lines() + " against " + q.lines());
            h.assertTrue(q.take().size() == 3 && q.isEmpty() && q.take().isEmpty(), "taking the lines doesn't empty the queue");
            h.succeed();
        });
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
