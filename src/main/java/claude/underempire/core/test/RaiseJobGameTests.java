package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.DigJob;
import claude.underempire.core.room.RaiseJob;
import claude.underempire.core.room.RoomTemplates;
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
import net.minecraft.world.level.block.Blocks;
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
