package claude.underempire.core.room;

import claude.underempire.core.unit.SettlerMob;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A building raised on the surface by its settlement's settlers (docs/plans/surface-builder.md, sections 2 and 9): the land
 * worked as {@link Siting} planned it, then the template laid course by course.
 *
 * <p>The task list, rebuilt the same way on load from the saved plan and the template alone (never from the world):
 * <ol>
 *   <li>the <b>clearing</b> (the template's air in the box, what stands over it, the cover of every column cut or filled, the
 *   pad's trees), which the site does itself ({@link #clear}, the horde's {@code gnawInOrder}) and no settler is handed;</li>
 *   <li>the <b>plinth</b> under the box (and the water beside the pad's cuts, walled), the <b>berm and landing</b>, then the
 *   <b>ramp</b>, each by lift: cuts from the top, fills from the bottom;</li>
 *   <li>the template's solid cells, <b>course</b> by course (its local y), each course spreading out from the door.</li>
 * </ol>
 * Strictly in that order: a phase's cells are handed out only once every cell before it is done. Within a phase a claim's
 * score goes up by {@value #PER_LIFT} per lift (or course) past the lowest open one, and a course more than one over the
 * lowest open course is never handed out, so a building rises course by course.
 *
 * <p>A cell is handed out, and put in when its builder gets to it, only when it is ready: a block with something under it (or,
 * in a course, a sturdy face beside it to hang from: eaves), and a cut from the top of its column with a way out beside it
 * (some neighbour no more than a block over the new ground), so nobody digs a pit. A cell that is not ready when its builder
 * swings at it is left open and handed out again. Every change is a builder's: a solid cell made another solid block (the
 * old top buried, a hump's earth in a course) is laid too, never refaced for free as a dug room's walls are.
 *
 * <p>Only natural ground, its cover, air, water and the plan's own trees are ever taken out ({@link Siting.Plan#mayReplace}).
 * A player's block met at work time is left standing: in the earthworks its whole column leaves them (it is not undercut or
 * buried); in the plinth or a course only that cell.
 *
 * <p>No stalls: a cell shelved {@value #HEAVE_AFTER} times (no builder could get to it) is heaved into place, and counted; and so
 * is the lowest open cell when builders are up with nothing to hand them and none at work (SiteWork#second).
 *
 * <p>Hanging things in a course (a hanging lantern, a hanging chain, vines by their top face: {@link #hangs}) are held by what is
 * over them, or by their own column laid over them, else as any block is, by eaves ({@link #held}). They wait on the courses over
 * them, and so does every cell of a vine (a column's lower cells are held by the vine over them: {@link #waitsOnTheCourses}), so
 * they never hold the course window down ({@link #windowCourse}), and what they hang from never waits for them
 * ({@link #underToCome}). The site's stall rule heaves the window's lead, the lowest open cell that isn't one of them
 * ({@link #heaveFront}), and its words (the Ledger's course line) follow the window ({@link #windowCell}), not the lowest open
 * cell, which may be a lantern waiting for its roof. A cell over the build height, or under the world, is done with nothing put
 * (as a dig's is), and nothing ever loads a chunk to lay a cell.
 *
 * <p>Its builders are sure-footed on it ({@link #footing}): a fall inside it does no harm (SiteWork.SureFooting).
 */
public class RaiseJob extends DigJob {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The phases of the task list, in its order. */
    public static final byte CLEARING = 0, PLINTH = 1, PAD = 2, RAMP = 3, COURSE = 4;
    /** A cell shelved this often (its builder could not get to it) is heaved into place, and counted. */
    public static final int HEAVE_AFTER = 3;
    /** How long a shelved cell is let be before it is handed out again (RoomWork#shelve's 30 s). */
    public static final int SHELVED_FOR = 600;
    /** Added to a claim's score per lift, or course, past the lowest open one. */
    public static final double PER_LIFT = 6;
    /** How far a builder stands from the first cell of its batch, at most, across; and up or down. */
    public static final int STAND_ACROSS = 4, STAND_UP = 3;
    /** The footing ({@link #footing}) is the site's area this much wider all round, and this much higher than its highest cell. */
    public static final int FOOTING_MARGIN = 2, FOOTING_ABOVE = 4;
    /**
     * How far a builder is walked to a stand ({@link #standFor}), in steps, and the most cells that flood visits. A vanilla path reaches only
     * so far (FOLLOW_RANGE 20, also its node budget), and round a wall its search gives out well short of that.
     */
    public static final int WALK_STEPS = 12, WALK_CELLS = 3000;

    @Nullable private Siting.Plan plan;
    /** A loaded job's saved plan, until the level is at hand to read the template ({@link #buildTasks}). */
    @Nullable private CompoundTag planTag;
    private int paid;
    private int heaved;
    private int spared;
    /** Put in whatever the ground (a creative raise, the finish, a heave): only a player's block is still left standing. */
    private boolean forcing;
    @Nullable private Spoils sink;

    // --- made with the tasks ----------------------------------------------------------------------------------------
    private byte[] phase = new byte[0];
    private int[] lift = new int[0];
    private final BitSet seal = new BitSet();
    private int clearEnd;
    /** The index after each phase's last task. */
    private final int[] phaseEnd = new int[COURSE + 1];
    /** The index after each course's last task, by course (the template's local y). */
    private int[] courseEnd = new int[0];
    /** Cells a task fills (a block, not air), and the last task that does. */
    private final Long2IntOpenHashMap fills = new Long2IntOpenHashMap();
    /**
     * The course's cells that wait on the courses over them ({@link #waitsOnTheCourses}): what hangs from what is over it
     * ({@link #hangs}: a lantern under its ceiling, a chain, a hanging vine) and every cell of a vine.
     */
    private final BitSet hanging = new BitSet();
    /**
     * The building's own cells, what a teardown takes down and what the price is spread over: the courses' solid cells, and the earthworks made of the
     * building's blocks and not the land's (the plinth of its ground-layer block under the box). Earth the land had (dirt, grass, the ramps and the
     * berm) is not: it stays.
     */
    private final BitSet building = new BitSet();
    /** Cells a settler's task empties (a cut), and the task. */
    private final Long2IntOpenHashMap cuts = new Long2IntOpenHashMap();
    /** The cuts and fills of the berm, landing and ramp, by column. */
    private final Long2ObjectOpenHashMap<IntArrayList> columns = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet cells = new LongOpenHashSet();
    @Nullable private BoundingBox footprint;
    /** Where its builders' falls do no harm ({@link #footing}); made with the footprint. */
    @Nullable private BoundingBox footing;
    /** The first task not done (monotone: a task once done stays done). */
    private int front;
    /** The first task not done that is not one of the {@link #hanging} (monotone as well): where the course window stands. */
    private int lead;

    // --- builders who could not get to their cells -------------------------------------------------------------------
    private final Int2IntOpenHashMap shelves = new Int2IntOpenHashMap();
    private final Int2LongOpenHashMap shelvedUntil = new Int2LongOpenHashMap();
    private final IntArrayList heaveDue = new IntArrayList();

    /**
     * A building to raise at its placement, on the land {@code plan} sited for it; {@code paid}: what the settlement paid for
     * it (saved, for a cancel's refund later).
     */
    public RaiseJob(String roomId, BlockPos door, Direction facing, Siting.Plan plan, int paid) {
        super(roomId, door, facing);
        this.plan = plan;
        this.paid = paid;
        unshored();                                              // on the surface: nothing to wall up against the sky
    }

    private RaiseJob(String roomId, BlockPos door, Direction facing) {
        super(roomId, door, facing);
        unshored();
    }

    /**
     * A saved site back ({@link #save}): its plan comes back from the tag, the template when the level is at hand. Null if
     * the tag isn't a raised building's, its room isn't registered any more, or its plan is broken (logged).
     */
    @Nullable
    public static RaiseJob load(CompoundTag t) {
        String room = t.getString("room");
        if (!t.getBoolean("raise") || Rooms.get(room) == null) {
            LOGGER.warn("Settlement Core: a saved site of '{}' can't be raised (no such building): dropped", room);
            return null;
        }
        RaiseJob j = new RaiseJob(room, savedDoor(t), savedFacing(t)).readSaved(t);
        if (j.planTag == null || Siting.Plan.load(j.planTag, j.placement(), List.of()) == null) {
            LOGGER.warn("Settlement Core: a saved site of '{}' at {} has no plan: dropped", room, j.door);
            return null;
        }
        return j;
    }

    @Override
    protected void saveMore(CompoundTag t) {
        t.putBoolean("raise", true);
        CompoundTag p = plan != null ? plan.save() : planTag;
        if (p != null) {
            t.put("reshape", p.copy());
        }
        t.putInt("paid", paid);
        t.putInt("heaved", heaved);
        t.putInt("spared", spared);                              // the tally the Ledger shows; it began again at 0 on every load before
        if (tearing) {                                           // only then: a site being raised saves as it did
            t.putBoolean("tearing", true);
            t.putLongArray("torn", torn.toLongArray());
        }
    }

    @Override
    protected void loadMore(CompoundTag t) {
        planTag = t.contains("reshape") ? t.getCompound("reshape") : null;
        paid = t.getInt("paid");
        heaved = t.getInt("heaved");
        spared = t.getInt("spared");
        tearing = t.getBoolean("tearing");
        torn.clear();
        if (tearing) {
            torn.or(BitSet.valueOf(t.getLongArray("torn")));
        }
        tearIdx = null;                                          // listed again from the world and the cells taken out (tearEnsure)
    }

    // --- the task list -------------------------------------------------------------------------------------------------

    @Override
    protected List<Task> buildTasks(ServerLevel level, PlanPlacement pl, LongSet keep) {
        List<RoomTemplates.Entry> template = RoomTemplates.get(level, roomId);
        if (plan == null && planTag != null) {
            plan = Siting.Plan.load(planTag, pl, template);
        }
        List<Task> out = new ArrayList<>();
        ByteArrayList ph = new ByteArrayList();
        IntArrayList lf = new IntArrayList();
        if (plan != null) {
            for (Siting.Earthwork e : plan.earthworks()) {
                out.add(new Task(e.pos(), e.state(), null));
                ph.add(switch (e.phase()) {
                    case CLEARING -> CLEARING;
                    case PLINTH -> PLINTH;
                    case PAD -> PAD;
                    case RAMP -> RAMP;
                });
                lf.add(e.lift());
            }
        }
        int doorX = pl.room().doorX();
        int doorZ = pl.room().doorZ();
        List<RoomTemplates.Entry> solid = new ArrayList<>();
        for (RoomTemplates.Entry e : template) {
            if (!e.state().isAir()) {
                solid.add(e);
            }
        }
        solid.sort(Comparator.<RoomTemplates.Entry>comparingInt(e -> e.local().getY())
                .thenComparingInt(e -> Math.abs(e.local().getX() - doorX) + Math.abs(e.local().getZ() - doorZ))
                .thenComparingInt(e -> e.local().getZ())
                .thenComparingInt(e -> e.local().getX()));
        for (RoomTemplates.Entry e : solid) {
            out.add(new Task(pl.world(e.local()), e.state().rotate(pl.rotation()), e.nbt()));
            ph.add(COURSE);
            lf.add(e.local().getY());
        }
        index(out, ph.toByteArray(), lf.toIntArray(), pl);
        return out;
    }

    private void index(List<Task> tasks, byte[] ph, int[] lf, PlanPlacement pl) {
        phase = ph;
        lift = lf;
        front = 0;
        lead = 0;
        clearEnd = 0;
        fills.clear();
        hanging.clear();
        building.clear();
        cuts.clear();
        columns.clear();
        cells.clear();
        seal.clear();
        while (clearEnd < ph.length && ph[clearEnd] == CLEARING) {
            clearEnd++;
        }
        java.util.Arrays.fill(phaseEnd, 0);
        int courses = 0;
        BoundingBox box = pl.box();
        int x0 = box.minX(), y0 = box.minY(), z0 = box.minZ(), x1 = box.maxX(), y1 = box.maxY(), z1 = box.maxZ();
        for (int i = 0; i < tasks.size(); i++) {
            BlockPos p = tasks.get(i).pos();
            phaseEnd[ph[i]] = i + 1;
            if (i > 0 && ph[i] < ph[i - 1]) {
                LOGGER.error("Settlement Core: {}'s earthworks are out of order at task {} ({} after {})", roomId, i, ph[i], ph[i - 1]);
            }
            if (ph[i] == COURSE) {
                courses = Math.max(courses, lf[i] + 1);
            }
            long k = p.asLong();
            cells.add(k);
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
            if (ph[i] == CLEARING) {
                continue;
            }
            if (tasks.get(i).state().isAir()) {
                cuts.put(k, i);
            } else {
                fills.put(k, i);
                if (ph[i] == COURSE && waitsOnTheCourses(tasks.get(i).state())) {
                    hanging.set(i);
                }
            }
            if (ph[i] == COURSE || !tasks.get(i).state().isAir() && !Siting.ground(tasks.get(i).state())) {
                building.set(i);
            }
            boolean inBox = box.isInside(p.getX(), box.minY(), p.getZ());
            if (ph[i] == PLINTH && !inBox) {
                seal.set(i);                                     // water beside a cut of the pad, walled
            }
            if (ph[i] == PAD || ph[i] == RAMP) {
                columns.computeIfAbsent(column(p.getX(), p.getZ()), c -> new IntArrayList()).add(i);
            }
        }
        for (int p = 1; p < phaseEnd.length; p++) {
            phaseEnd[p] = Math.max(phaseEnd[p], phaseEnd[p - 1]);   // a phase with no tasks ends where the last one did
        }
        courseEnd = new int[courses];
        for (int i = 0; i < tasks.size(); i++) {
            if (ph[i] == COURSE) {
                courseEnd[lf[i]] = i + 1;
            }
        }
        for (int c = 0; c < courses; c++) {
            courseEnd[c] = Math.max(courseEnd[c], c == 0 ? phaseEnd[RAMP] : courseEnd[c - 1]);
        }
        footprint = new BoundingBox(x0, y0, z0, x1, y1, z1);
        footing = new BoundingBox(x0 - FOOTING_MARGIN, y0, z0 - FOOTING_MARGIN, x1 + FOOTING_MARGIN, y1 + FOOTING_ABOVE, z1 + FOOTING_MARGIN);
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** The first task not yet done. */
    private int front() {
        List<Task> ts = tasks();
        if (ts == null) {
            return 0;
        }
        while (front < ts.size() && finished(front)) {
            front++;
        }
        return front;
    }

    /**
     * The course the window stands at: the lowest open course not counting the {@link #hanging} cells, which wait on the courses
     * over them (their ceiling) and so never hold the window down: a lantern on a chain under the roof, the lowest open cell,
     * would keep the roof, two courses over it, from ever being handed out, and so itself from ever being held. With nothing but
     * hanging cells left there is no window: each goes in once what it hangs from is in ({@link #held}), top of a column first.
     */
    private int windowCourse() {
        List<Task> ts = tasks();
        int f = front();
        if (lead < f) {
            lead = f;
        }
        while (lead < ts.size() && (finished(lead) || hanging.get(lead))) {
            lead++;
        }
        return lead < ts.size() ? lift[lead] : Integer.MAX_VALUE / 2;
    }

    /**
     * The cell the course window stands at: the lowest open cell that isn't one of the {@link #hanging} (the first task not done,
     * when only those are left). Where the site's words are ({@link #state}, {@link #courseNow}, {@link #phaseNow}) and where its stall
     * rule heaves ({@link #heaveFront}): the lowest open cell can be a lantern, waiting for the roof the window has yet to reach.
     * Needs the tasks built, and the site not done.
     */
    private int windowCell() {
        windowCourse();                                          // brings the lead up to the first open cell that doesn't hang
        return lead < tasks().size() ? lead : front;
    }

    /**
     * Whether a cell of a course waits on the courses over it, and so never holds the window down: what hangs from what is over it
     * ({@link #hangs}), and every cell of a vine. A column of three or more plain vines (each held by the vine over it, only the top one
     * by a wall or a ceiling) pinned the window: its lowest cell, the window's, waited for the vine over it, which the window let
     * nothing past one course over it hand out.
     */
    private static boolean waitsOnTheCourses(BlockState s) {
        return hangs(s) || s.getBlock() instanceof net.minecraft.world.level.block.VineBlock;
    }

    // --- where it stands ---------------------------------------------------------------------------------------------

    /** The site's plan (null only before a loaded site's tasks are built). */
    @Nullable
    public Siting.Plan plan() {
        return plan;
    }

    /** What the settlement paid for it. */
    public int paid() {
        return paid;
    }

    /** Whether task {@code i} is done (the tests count the tasks: {@link #taskList}, {@link #phaseOf}). */
    public boolean done(int i) {
        return finished(i);
    }

    /**
     * {@code paid} in proportion to {@code open} of {@code total}: {@code paid * open / total} to the nearest unit (a half goes up), never more than
     * {@code paid} and never less than 0. All of it when all is open (or there is nothing to count), none when none is. What a called-off site pays back
     * ({@link #refundNow}: the price over the building's cells, less those that stayed). Pure, so a test can drive it without a world.
     */
    public static int share(int paid, int open, int total) {
        if (paid <= 0) {
            return 0;
        }
        if (total <= 0 || open >= total) {
            return paid;
        }
        if (open <= 0) {
            return 0;
        }
        return (int) Math.min(paid, (2L * paid * open + total) / (2L * total));
    }

    /** Cells heaved into place because no builder could get to them. */
    public int heaved() {
        return heaved;
    }

    /** A player's blocks the work met and left standing. */
    public int spared() {
        return spared;
    }

    /**
     * The clearing's next cells that hold a block, top-down, at most {@code max} of them, nothing broken: what a builder is sent to cut down
     * ({@code SiteWork#nextClearChore}). The cells that are free on the way (air over air, off the height of the world) are done as they are passed.
     */
    public List<BlockPos> clearNext(ServerLevel level, int max) {
        ensureLoaded(level);
        List<BlockPos> out = new ArrayList<>();
        List<Task> ts = tasks();
        for (int i = front(); i < clearEnd && out.size() < max; i++) {
            if (finished(i)) {
                continue;
            }
            BlockPos p = ts.get(i).pos();
            if (level.isOutsideBuildHeight(p)) {
                markDone(i);
                continue;
            }
            if (!level.isLoaded(p)) {
                break;
            }
            if (level.getBlockState(p).isAir()) {
                markDone(i);                                     // air over air: nothing to cut
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * The clearing's next cells, top-down, at most {@code changes} of them taking a block out ({@link #gnawChanges}: a tree's logs and leaves, the
     * cover, whatever stands in the box's air); what gave way, for the dust and the noise. What the site does a tick, once a builder is at it
     * ({@code SiteWork#tick}); {@link #clear} is the same with no pace, for the tests and the finish.
     */
    public List<Crumb> clearSome(ServerLevel level, int changes) {
        ensureLoaded(level);
        return gnawChanges(level, changes, clearEnd);
    }

    /** The site clears the land itself: its clearing isn't done yet (and its builders have nothing to do). */
    public boolean clearing() {
        return !tearing && tasks() != null && front() < clearEnd;
    }

    /** Its builders have work: the clearing is done and the building isn't. */
    public boolean working() {
        return !tearing && tasks() != null && !clearing() && !isDone();
    }

    /** The phase the work stands at, the window's ({@link #CLEARING} to {@link #COURSE}), or -1 when done or not begun. */
    public int phaseNow() {
        List<Task> ts = tasks();
        int f = front();
        return ts == null || f >= ts.size() ? -1 : phase[windowCell()];
    }

    /**
     * The course the work stands at (the template's local y): the window's, not the lowest open cell's, which may be a lantern
     * waiting for its roof. -1 while the land is worked, or when done.
     */
    public int courseNow() {
        return phaseNow() == COURSE ? lift[windowCell()] : -1;
    }

    /** Every cell the site works (its earthworks and its building), packed: another site's keep-out. Built with the tasks. */
    public LongSet cells() {
        return LongSets.unmodifiable(cells);
    }

    /** The box round every cell the site works, once its tasks are built; null before. */
    @Nullable
    public BoundingBox footprint() {
        return footprint;
    }

    /**
     * Where its builders are sure-footed (the user's pick 4, SiteWork.SureFooting: a builder of the site who falls here takes no
     * damage): the whole area the site works, its box and the earthworks' margin (and a scaffold's tiers, which are cells of the
     * site), {@value #FOOTING_MARGIN} wider all round, from its lowest cell up to its highest and {@value #FOOTING_ABOVE} more. The
     * earthworks can leave a bank of 20 or more where the land is steep and the builders stand on the ramp beside it, so it covers
     * the area, not only the box. Null before the tasks are built.
     */
    @Nullable
    public BoundingBox footing() {
        return footing;
    }

    /** The whole task list, as built (the tests: a reload builds the same). */
    public List<Task> taskList() {
        List<Task> ts = tasks();
        return ts == null ? List.of() : java.util.Collections.unmodifiableList(ts);
    }

    /** The phase of task {@code i}. */
    public byte phaseOf(int i) {
        return phase[i];
    }

    /** The lift of task {@code i}: the course, for a course's cell. */
    public int liftOf(int i) {
        return lift[i];
    }

    /** The open cells a builder may be handed now: the phase's, or the lowest open course's and the one over it. */
    public int openNow() {
        List<Task> ts = tasks();
        int f = front();
        if (ts == null || f >= ts.size() || f < clearEnd) {
            return 0;
        }
        int end = Math.min(ts.size(), claimEnd());
        int n = 0;
        for (int i = f; i < end; i++) {
            if (!finished(i)) {
                n++;
            }
        }
        return n;
    }

    /** What its builders are at, in words: "levelling the floor", "laying the 3rd course". */
    public String state() {
        List<Task> ts = tasks();
        if (ts == null) {
            return "not begun";
        }
        if (tearing) {
            return "pulling the building down";
        }
        if (isDone()) {
            return "raised";
        }
        int f = front();
        return f < clearEnd ? "clearing the site" : doing(windowCell());
    }

    /** What {@code rat}'s batch is, in words ("cutting the ground"), or "building" if it holds none. */
    public String doing(UUID rat) {
        if (tearing) {
            return "pulling the building down";
        }
        int[] c = claimOf(rat);
        return c == null ? "building" : doing(c[0]);
    }

    private String doing(int i) {
        return switch (phase[i]) {
            case PLINTH, PAD -> "levelling the floor";
            case RAMP -> {
                BlockPos p = tasks().get(i).pos();
                yield plan != null && plan.newHeight(p.getX(), p.getZ()) < plan.oldGround(p.getX(), p.getZ()) ? "cutting the ground" : "banking up the ground";
            }
            case COURSE -> "laying the " + ordinal(lift[i] + 1) + " course";
            default -> "clearing the site";
        };
    }

    private static String ordinal(int n) {
        int t = n % 100;
        String s = t >= 11 && t <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + s;
    }

    /** The cells of {@code rat}'s batch, the one it stands to first; empty if it holds none. */
    public List<Task> batch(UUID rat) {
        int[] c = claimOf(rat);
        if (c == null) {
            return List.of();
        }
        List<Task> out = new ArrayList<>(c.length);
        for (int i : c) {
            out.add(tasks().get(i));
        }
        return out;
    }

    /**
     * The open cells a builder may be handed now, up to {@code max}, each with where it stands: held in a batch, set aside
     * (shelved, how often, until when), or not ready and why. For test failures and the bridge.
     */
    public String openReport(ServerLevel level, int max) {
        List<Task> ts = tasks();
        int f = front();
        if (ts == null || f >= ts.size()) {
            return "nothing open";
        }
        StringBuilder sb = new StringBuilder(state()).append(": ");
        int end = Math.min(ts.size(), claimEnd());
        int n = 0;
        int open = 0;
        long now = level.getGameTime();
        for (int i = f; i < end; i++) {
            if (finished(i)) {
                continue;
            }
            open++;
            if (n >= max) {
                continue;
            }
            n++;
            Task t = ts.get(i);
            sb.append(t.pos().toShortString()).append(phase[i] == COURSE ? " c" + lift[i] : " p" + phase[i] + "/" + lift[i]);
            if (isClaimed(i)) {
                sb.append(" held");
            }
            if (shelves.get(i) > 0) {
                sb.append(" shelved ").append(shelves.get(i)).append("x").append(shelvedUntil.get(i) > now ? " for " + (shelvedUntil.get(i) - now) : "");
            }
            if (!ready(level, i, t)) {
                sb.append(" not ready");
            }
            sb.append("; ");
        }
        return sb.append(open).append(" open").toString();
    }

    /** Cells set aside now because no builder could get to them (the far-peeker's "needs"). */
    public int shelvedNow(long now) {
        int n = 0;
        for (var e : shelvedUntil.int2LongEntrySet()) {
            if (e.getLongValue() > now && !finished(e.getIntKey())) {
                n++;
            }
        }
        return n;
    }

    // --- the work ------------------------------------------------------------------------------------------------------

    /**
     * The site's own clearing, up to {@code max} cells in list order (top-down), stopping at a chunk not loaded; a few of the
     * cells that gave way go into {@code sample}. Returns how many were done.
     */
    public int clear(ServerLevel level, int max, List<BlockPos> sample) {
        ensureLoaded(level);
        return gnawInOrder(level, max, sample, clearEnd);
    }

    /** Settlers are handed the earthworks and courses, one phase after another, never the clearing. */
    @Override
    protected int claimEnd() {
        List<Task> ts = tasks();
        int f = front();
        if (f >= ts.size()) {
            return ts.size();
        }
        if (f < clearEnd) {
            return clearEnd;
        }
        if (phase[f] == COURSE) {
            int c = windowCourse() + 1;                          // the lowest open course and the one over it
            return c < courseEnd.length ? courseEnd[c] : ts.size();
        }
        return phaseEnd[phase[f]];
    }

    @Override
    protected boolean claimable(ServerLevel level, int i, Task t) {
        int f = front();
        if (i < clearEnd || f < clearEnd || phase[i] != phase[f]) {
            return false;                                        // strictly in order: this phase only
        }
        if (phase[i] == COURSE && lift[i] > windowCourse() + 1) {
            return false;                                        // never more than a course over the lowest open one
        }
        if (shelvedUntil.get(i) > level.getGameTime()) {
            return false;                                        // no builder could get to it lately: another first
        }
        BlockState cur = level.getBlockState(t.pos());
        if (!replaceable(level, t.pos(), cur)) {
            spare(i);
            return false;
        }
        return ready(level, i, t);
    }

    @Override
    protected double anchorBias(int i, Task t) {
        return PER_LIFT * Math.max(0, lift[i] - windowCourse());
    }

    /** Every change on the surface is a builder's, in the open. */
    @Override
    protected boolean refacesFree() {
        return false;
    }

    /** The claims always ask {@link #claimable} and {@link #anchorBias}: the rowclaim A/B flag is a dug room's. */
    @Override
    protected boolean rowClaim() {
        return false;
    }

    /**
     * Whether task {@code i} can go in now. A cut (air): from the top of its column (nothing solid over it), with a way out
     * beside it; the clearing's, always. A block: {@link #held}; the pad's seals (walls against water) as they are.
     */
    private boolean ready(ServerLevel level, int i, Task t) {
        BlockPos p = t.pos();
        if (t.state().isAir()) {
            return i < clearEnd || open(level, p.above()) && wayOut(level, p);
        }
        return seal.get(i) || held(level, p, t.state(), phase[i] == COURSE, underToCome(p));
    }

    /**
     * Whether the site still has a block to lay under {@code p} that the cell at {@code p} would rest on. A piece that hangs from
     * {@code p} ({@link #hangs}: a lantern under the ceiling) rests on nothing there, and doesn't count: the ceiling never waits
     * for what hangs from it (while the lantern waited for its ceiling, neither could go in).
     */
    private boolean underToCome(BlockPos p) {
        int i = fills.getOrDefault(BlockPos.asLong(p.getX(), p.getY() - 1, p.getZ()), -1);
        return i >= 0 && !finished(i) && !hanging.get(i);
    }

    /**
     * Whether the block {@code s} put at {@code p} is held there: by something under it; or, in a building's course (never the
     * earthworks), by what it hangs from when it is a hanging thing ({@link #hungFrom}: something solid over it, or its own column
     * over it, laid), or else, where the site puts nothing under it ({@code underToCome} false: eaves), by a sturdy face beside it
     * (for a hanging thing too: a wall vine, a chain laid between two walls and a cobweb in a corner are held by their walls). A
     * course's cell over one still to be laid waits for it, never hangs off its neighbour; a hanging lantern waits for its ceiling
     * (the review of 51b59f8: every hanging cell waited out the stall).
     */
    public static boolean held(ServerLevel level, BlockPos p, BlockState s, boolean course, boolean underToCome) {
        if (supported(level, p, false)) {
            return true;
        }
        if (!course) {
            return false;
        }
        if (hungFrom(level, p, s)) {
            return true;
        }
        return !underToCome && supported(level, p, true);
    }

    /**
     * Whether {@code s} at {@code p} hangs from what is over it, laid: something solid over it (a piece that {@link #hangs}), or its
     * own column over it ({@link #sameColumn}: a vine under a vine, a cave vine under its plant, weeping vines). Every cell of a
     * column of vines but the top one has a vine over it, and a vine has no collision shape to count as solid.
     */
    private static boolean hungFrom(ServerLevel level, BlockPos p, BlockState s) {
        boolean hangs = hangs(s);
        if (!hangs && !(s.getBlock() instanceof net.minecraft.world.level.block.VineBlock)) {
            return false;
        }
        BlockPos a = p.above();
        if (!level.isLoaded(a)) {
            return false;
        }
        BlockState up = level.getBlockState(a);
        return hangs && !up.getCollisionShape(level, a).isEmpty() || sameColumn(s, up);
    }

    /** Whether {@code up} is the same kind of hanging piece as {@code s}: vines, a cave vine and its plants, weeping vines and theirs. */
    private static boolean sameColumn(BlockState s, BlockState up) {
        net.minecraft.world.level.block.Block b = s.getBlock();
        net.minecraft.world.level.block.Block u = up.getBlock();
        return b == u || b instanceof net.minecraft.world.level.block.CaveVines && u instanceof net.minecraft.world.level.block.CaveVines
                || (b instanceof net.minecraft.world.level.block.WeepingVinesBlock || b instanceof net.minecraft.world.level.block.WeepingVinesPlantBlock)
                && (u instanceof net.minecraft.world.level.block.WeepingVinesBlock || u instanceof net.minecraft.world.level.block.WeepingVinesPlantBlock);
    }

    /**
     * A thing that hangs from what is over it, and from nothing else: a hanging lantern, a chain hanging (not one laid sideways), a
     * vine by its top face (not a wall vine, held by its wall), cave vines and the weeping, hanging roots, a spore blossom, a hanging
     * sign, a cobweb, a dripstone pointing down, a bell on the ceiling.
     */
    public static boolean hangs(BlockState s) {
        net.minecraft.world.level.block.Block b = s.getBlock();
        return s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HANGING)
                && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HANGING)
                || b instanceof net.minecraft.world.level.block.ChainBlock && s.getValue(net.minecraft.world.level.block.ChainBlock.AXIS) == Direction.Axis.Y
                || b instanceof net.minecraft.world.level.block.VineBlock && s.getValue(net.minecraft.world.level.block.VineBlock.UP)
                || b instanceof net.minecraft.world.level.block.CaveVines || b instanceof net.minecraft.world.level.block.WeepingVinesBlock
                || b instanceof net.minecraft.world.level.block.WeepingVinesPlantBlock || b instanceof net.minecraft.world.level.block.HangingRootsBlock
                || b instanceof net.minecraft.world.level.block.SporeBlossomBlock || b instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
                || b instanceof net.minecraft.world.level.block.WebBlock
                || b instanceof net.minecraft.world.level.block.PointedDripstoneBlock
                        && s.getValue(net.minecraft.world.level.block.PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN
                || b instanceof net.minecraft.world.level.block.BellBlock
                        && s.getValue(net.minecraft.world.level.block.BellBlock.ATTACHMENT) == net.minecraft.world.level.block.state.properties.BellAttachType.CEILING;
    }

    /** Whether a block at {@code p} would have something under it (or, for {@code eaves}, a sturdy face beside it). */
    public static boolean supported(ServerLevel level, BlockPos p, boolean eaves) {
        BlockPos b = p.below();
        if (level.isOutsideBuildHeight(b) || level.isLoaded(b) && !level.getBlockState(b).getCollisionShape(level, b).isEmpty()) {
            return true;                                         // (at the world's floor nothing falls further)
        }
        if (eaves) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(d);
                if (level.isLoaded(n) && level.getBlockState(n).isFaceSturdy(level, n, d.getOpposite())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        return level.isOutsideBuildHeight(p) || level.isLoaded(p) && level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** Some neighbour of a cut at {@code p} stands no more than a block over the new ground (the cell over it is open). */
    private static boolean wayOut(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (open(level, p.relative(d).above())) {
                return true;
            }
        }
        return false;
    }

    /** What may be taken out: air, the ground, its cover, water, the plan's own trees; never a block entity. */
    private boolean replaceable(ServerLevel level, BlockPos p, BlockState cur) {
        if (cur.isAir()) {
            return true;
        }
        boolean may = plan != null ? plan.mayReplace(p, cur) : Siting.mayReplace(cur);
        return may && level.getBlockEntity(p) == null;
    }

    /**
     * A player's block where task {@code i} works: left standing. A cut or fill of the berm, landing or ramp gives up its whole
     * column (it is not undercut or buried); the clearing, the plinth and a course give up only that cell.
     */
    private void spare(int i) {
        if (phase[i] == PAD || phase[i] == RAMP) {
            BlockPos p = tasks().get(i).pos();
            IntArrayList col = columns.get(column(p.getX(), p.getZ()));
            if (col != null) {
                for (int k : col) {
                    if (!finished(k)) {
                        markDone(k);
                    }
                }
            }
        }
        markDone(i);
        spared++;
    }

    @Override
    protected boolean put(ServerLevel level, int i, Task t) {
        if (level.isOutsideBuildHeight(t.pos())) {
            return true;                                         // nothing goes there, ever: done
        }
        if (!level.isLoaded(t.pos())) {
            return false;                                        // its chunk went since it was handed out: never loaded to lay it
        }
        BlockState was = level.getBlockState(t.pos());
        if (was == t.state() && t.nbt() == null) {
            return true;                                         // so already
        }
        if (!replaceable(level, t.pos(), was)) {
            spare(i);
            return true;
        }
        if (!forcing && !ready(level, i, t)) {
            return false;                                        // not yet: left open, and handed out again
        }
        if (sink != null && was.is(BlockTags.LOGS)) {
            sink.take(level, t.pos(), was, level.getBlockEntity(t.pos()));   // a felled tree's timber
        }
        return super.put(level, i, t);
    }

    @Override
    public void spoilsTo(Spoils sink) {
        super.spoilsTo(sink);
        this.sink = sink;
    }

    /** Everything in place at once (a creative raise, and the finish), whatever the ground; only a player's block is spared. */
    @Override
    public void finishAll(ServerLevel level) {
        forcing = true;
        try {
            super.finishAll(level);
        } finally {
            forcing = false;
        }
    }

    /**
     * No builder could get to {@code rat}'s batch: it goes back, set aside {@link #SHELVED_FOR} ticks, and a cell set aside
     * {@link #HEAVE_AFTER} times is heaved into place at the site's next second ({@link #heaveDue}).
     */
    public void shelve(UUID rat, long now) {
        int[] c = claimOf(rat);
        if (c == null) {
            return;
        }
        for (int i : c) {
            if (finished(i)) {
                continue;
            }
            int n = shelves.addTo(i, 1) + 1;
            shelvedUntil.put(i, now + SHELVED_FOR);
            if (n >= HEAVE_AFTER && !heaveDue.contains(i)) {
                heaveDue.add(i);
            }
        }
        release(rat);
    }

    /** The cells shelved {@link #HEAVE_AFTER} times, heaved into place. Returns how many. */
    public int heaveDue(ServerLevel level) {
        int n = 0;
        for (int i : heaveDue) {
            n += heave(level, i) ? 1 : 0;
        }
        heaveDue.clear();
        return n;
    }

    /**
     * The window's lead heaved into place: the lowest open cell that isn't one of the {@link #hanging} (the lowest open cell, when
     * only those are left). The site has stalled: builders up, none at work, nothing to hand them. A hanging cell waiting for its
     * roof is waiting correctly, and heaving it would leave the real blocker where it was: the blocker is the window's.
     */
    public boolean heaveFront(ServerLevel level) {
        ensureLoaded(level);
        if (front() >= tasks().size()) {
            return false;
        }
        return heave(level, windowCell());
    }

    private boolean heave(ServerLevel level, int i) {
        if (finished(i)) {
            return false;
        }
        Task t = tasks().get(i);
        if (level.isOutsideBuildHeight(t.pos())) {
            markDone(i);                                         // nothing goes there, ever: done, not heaved
            return false;
        }
        if (!level.isLoaded(t.pos())) {
            return false;                                        // never a chunk loaded to heave a cell in
        }
        if (!replaceable(level, t.pos(), level.getBlockState(t.pos()))) {
            spare(i);                                            // a player's block: left standing, not heaved over
            return false;
        }
        forcing = true;
        try {
            put(level, i, t);
        } finally {
            forcing = false;
        }
        markDone(i);
        heaved++;
        LOGGER.info("Settlement Core: {} at {}: no builder could get to {} ({}), heaved into place ({} so far)", roomId, door, t.pos().toShortString(),
                doing(i), heaved);
        return true;
    }

    /** Whether any builder holds a batch at {@code now} (claims past their time let go first). */
    public boolean anyOut(long now) {
        return claimCount(now) > 0;
    }

    /**
     * Whether any open cell could be handed out now (what {@link #claim} looks over): the site's stall rule asks this before it
     * heaves (SiteWork#second). A cell the world already has as it should be counts: a claim would finish it.
     */
    public boolean anyClaimable(ServerLevel level) {
        List<Task> ts = tasks();
        if (ts == null) {
            return false;
        }
        int end = Math.min(ts.size(), claimEnd());
        for (int i = front(); i < end; i++) {
            if (finished(i) || isClaimed(i)) {
                continue;
            }
            Task t = ts.get(i);
            if (level.isOutsideBuildHeight(t.pos())) {
                return true;
            }
            if (!level.isLoaded(t.pos())) {
                continue;
            }
            if (level.getBlockState(t.pos()) == t.state() && t.nbt() == null || claimable(level, i, t)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the site still has a block to put in the cell {@code p}. */
    public boolean fillsOpen(BlockPos p) {
        return tasks() != null && openAt(fills, p.asLong());
    }

    /** Whether a batch someone holds has the cell {@code p} in it. */
    public boolean heldAt(BlockPos p) {
        if (tasks() == null) {
            return false;
        }
        int i = fills.getOrDefault(p.asLong(), cuts.getOrDefault(p.asLong(), -1));
        return i >= 0 && isClaimed(i);
    }

    // --- where a builder stands --------------------------------------------------------------------------------------

    /**
     * Where a builder stands to work a batch whose first cell is {@code anchor}: a cell one can stand in within
     * {@value #STAND_ACROSS} across and {@value #STAND_UP} up or down of it, never in a column the batch works (not in, under or
     * on its cells), never in a cell the site will fill or on ground it will cut. Best: a block or two from the work, level
     * with it or a block over (the course under it, when laying a course), on finished work or the ground itself, near the
     * builder. A bounded scan (9 by 9 columns, 7 high, the first cell one can stand in from the top of each); null if none.
     *
     * <p>Only a cell the builder at {@code near} can walk to ({@link #walkable}: within {@value #WALK_STEPS} steps, up a block at a time and
     * down as far as 3), the cell nearest by those steps and not by the crow's flight: the finished top of a closed hall's roof, with no way
     * up to it, was the best cell for the last roof cells (the review of 30f4859's hanging pieces' test, 1 raise in 4), and a cell inside
     * the walls, for a builder outside, was a walk through the door that a vanilla path gave out on; the builder stood out its 400 ticks at
     * the foot of a wall. A column's cells under one that can't be walked to are looked at too. With none, or when the builder stands
     * where one can't tell, the best cell as before (nothing is known better).
     */
    @Nullable
    public BlockPos standFor(ServerLevel level, BlockPos anchor, List<BlockPos> batch, BlockPos near) {
        return stand(level, anchor, batch, near).at();
    }

    /**
     * A stand ({@link #standFor}), and whether its builder can walk to it. Not when the flood ran and found no cell in reach of the work that it can
     * walk to: {@code at} is then the old best, which no path may reach (a builder outside the back wall of a closed hall with one door is more
     * than {@value #WALK_STEPS} steps from it). It is when the flood found one, and when it could not tell (nothing is known better).
     */
    public record Stand(@Nullable BlockPos at, boolean walkable) {
    }

    /** {@link #standFor}, with whether the builder can walk there ({@link Stand}). */
    public Stand stand(ServerLevel level, BlockPos anchor, List<BlockPos> batch, BlockPos near) {
        LongOpenHashSet mine = new LongOpenHashSet(batch.size());
        for (BlockPos b : batch) {
            mine.add(column(b.getX(), b.getZ()));
        }
        Long2IntOpenHashMap walk = walkable(level, near, anchor);
        BlockPos there = walk == null ? null : scanStands(level, anchor, mine, near, walk);
        return there != null ? new Stand(there, true) : new Stand(scanStands(level, anchor, mine, near, null), walk == null);
    }

    /**
     * The best cell to stand in ({@link #standFor}); with {@code walk} (a cell and how many steps to it), only a cell in it, scored by its
     * steps, and the cells under a cell not in it in its column are looked at too (the old rule is the highest cell in each column, scored by
     * its distance from {@code near}).
     */
    @Nullable
    private BlockPos scanStands(ServerLevel level, BlockPos anchor, LongOpenHashSet mine, BlockPos near, @Nullable Long2IntOpenHashMap walk) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -STAND_ACROSS; dx <= STAND_ACROSS; dx++) {
            for (int dz = -STAND_ACROSS; dz <= STAND_ACROSS; dz++) {
                int across = dx * dx + dz * dz;
                int x = anchor.getX() + dx;
                int z = anchor.getZ() + dz;
                if (across > STAND_ACROSS * STAND_ACROSS || mine.contains(column(x, z))) {
                    continue;
                }
                for (int dy = STAND_UP; dy >= -STAND_UP; dy--) {
                    p.set(x, anchor.getY() + dy, z);
                    if (!level.isLoaded(p)) {
                        break;
                    }
                    if (!SettlerMob.standable(level, p)) {
                        continue;
                    }
                    boolean walks = walk == null || walk.containsKey(p.asLong());
                    if (walks && stays(p) && !pit(level, p)) {
                        double h = Math.sqrt(across);
                        double score = (h - 1.5) * (h - 1.5) + Math.abs(dy - 0.5) * 2 + (steady(level, p.below()) ? 0 : 3)
                                + (walk == null ? Math.sqrt(p.distSqr(near)) : walk.get(p.asLong())) * 0.25;
                        if (score < bestScore) {
                            bestScore = score;
                            best = p.immutable();
                        }
                    }
                    if (walks) {
                        break;                                   // the highest cell in reach in this column (that can be walked to)
                    }
                }
            }
        }
        return best;
    }

    /**
     * The cells {@code from} (a cell its builder stands in) can be walked to within {@value #WALK_STEPS} steps, each with its number of
     * steps, on the way to a stand near {@code anchor}: a flood over {@link SettlerMob#standable} cells, one step on level ground, up a
     * block (with the cell over its head free) or down as far as 3 (with the way down free), as vanilla's ground paths go, in a box round
     * both and 6 more, of at most {@value #WALK_CELLS} cells. Null when it can't tell (the builder isn't in a cell one can stand in, or it
     * is far from the work): then any stand is as good.
     */
    @Nullable
    private static Long2IntOpenHashMap walkable(ServerLevel level, BlockPos from, BlockPos anchor) {
        int r = STAND_ACROSS + 2;
        int x0 = Math.min(from.getX(), anchor.getX()) - r;
        int x1 = Math.max(from.getX(), anchor.getX()) + r;
        int z0 = Math.min(from.getZ(), anchor.getZ()) - r;
        int z1 = Math.max(from.getZ(), anchor.getZ()) + r;
        int y0 = Math.min(from.getY(), anchor.getY()) - STAND_UP - 3;
        int y1 = Math.max(from.getY(), anchor.getY()) + STAND_UP + 2;
        if (x1 - x0 > 40 || z1 - z0 > 40 || !level.isLoaded(from) || !SettlerMob.standable(level, from)) {
            return null;
        }
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.put(from.asLong(), 0);
        queue.enqueue(from.asLong());
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && seen.size() < WALK_CELLS) {
            long k = queue.dequeueLong();
            int steps = seen.get(k);
            if (steps >= WALK_STEPS) {
                continue;
            }
            int x = BlockPos.getX(k);
            int y = BlockPos.getY(k);
            int z = BlockPos.getZ(k);
            boolean headroom = free(level, n.set(x, y + 2, z));
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = x + d.getStepX();
                int nz = z + d.getStepZ();
                if (nx < x0 || nx > x1 || nz < z0 || nz > z1) {
                    continue;
                }
                for (int dy = headroom ? 1 : 0; dy >= -3; dy--) {
                    n.set(nx, y + dy, nz);
                    if (n.getY() < y0 || n.getY() > y1 || !level.isLoaded(n)) {
                        break;
                    }
                    if (!SettlerMob.standable(level, n)) {
                        continue;
                    }
                    if (dy < 0 && !fallFree(level, nx, y, nz, y + dy)) {
                        break;                                   // a wall of the way down: no stepping off the edge here
                    }
                    if (!seen.containsKey(n.asLong())) {
                        seen.put(n.asLong(), steps + 1);
                        queue.enqueue(n.asLong());
                    }
                    break;                                       // the highest cell it can step to in that direction
                }
            }
        }
        return seen;
    }

    /** Whether the cells a builder passes stepping off a ledge at {@code y} into the column (x, z) down to its feet at {@code feet} are free. */
    private static boolean fallFree(ServerLevel level, int x, int y, int z, int feet) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        for (int cy = y + 1; cy >= feet + 2; cy--) {
            if (!free(level, c.set(x, cy, z))) {
                return false;
            }
        }
        return true;
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** A pit: every neighbour of {@code feet} stands 2 or more over it (the cell over its feet is solid all round). */
    public static boolean pit(ServerLevel level, BlockPos feet) {
        return Clearance.pit(level, feet, null);
    }

    /** The cells the site is still to fill or cut under: a builder is set down in one last ({@link Clearance#before}), it would be built in or dropped. */
    @Override
    protected boolean cellTaken(BlockPos p) {
        return !stays(p);
    }

    /** Whether one standing at {@code p} stays standing: nothing the site fills where it is, and no cut under it. */
    private boolean stays(BlockPos p) {
        return !openAt(fills, p.asLong()) && !openAt(fills, BlockPos.asLong(p.getX(), p.getY() + 1, p.getZ()))
                && !openAt(cuts, BlockPos.asLong(p.getX(), p.getY() - 1, p.getZ()));
    }

    private boolean openAt(Long2IntOpenHashMap m, long cell) {
        int i = m.getOrDefault(cell, -1);
        return i >= 0 && !finished(i);
    }

    // --- being torn down (Works#cancel) ----------------------------------------------------------------------------------

    /** A builder's batch of cells to take down: their task indices, and when the claim lapses. */
    private record TearClaim(int[] idx, long expires) {
    }

    /** Whether the site was called off and is coming down: what its builders laid is taken out, top first ({@link #startTearDown}). */
    private boolean tearing;
    /** The course cells the site took out; saved only while it is being torn down, so the cells it took out are told from a player's air. */
    private final BitSet torn = new BitSet();
    /** The cells to take out, in the order they come down; rebuilt from the world and {@link #torn} ({@link #tearEnsure}); null until then. */
    @Nullable private int[] tearIdx;
    /** Cells that no longer hold the block that was laid (someone's now): they stay. Rebuilt with {@link #tearIdx}, never saved. */
    private final BitSet tearGone = new BitSet();
    private final BitSet tearClaimed = new BitSet();
    private final java.util.Map<UUID, TearClaim> tearClaims = new java.util.HashMap<>();
    /** When a cell last came down, or the teardown began (game time): a site no builder takes down takes itself down after a while. */
    private long tearLast;
    /** The site is taking itself down (no builder could): once begun it goes on. Not saved. */
    private boolean tearSelfPaced;

    /** Whether the site is being torn down. */
    public boolean tearing() {
        return tearing;
    }

    /**
     * Begins tearing the site down: every course cell it laid that still holds the block it laid comes down (air, and the land's own ground at the floor and
     * under it: {@link #tearCell}), the earthworks never (the land can't be un-reshaped). The order is the reverse of the laying: the hanging pieces first (a lantern, a vine, a chain: the lowest first, so a column of vines
     * comes off from its foot), then the courses top-down, and within a course the cells furthest from the door first. Nothing else about the site changes:
     * the clearing and the courses stop, and its builders' laying batches are let go of by the caller. The plinth under the box, made of the building's own
     * blocks, comes down last, and the land's own ground goes in its cells; earth the land had stays.
     */
    public void startTearDown(ServerLevel level, long now) {
        ensureLoaded(level);
        tearing = true;
        torn.clear();
        tearLast = now;
        tearSelfPaced = false;
        tearIdx = null;
        tearEnsure(level);
    }

    /** The cells to take down listed (after a load, from the saved {@link #torn} and the world); at the first call only. */
    public void tearEnsure(ServerLevel level) {
        if (!tearing || tearIdx != null) {
            return;
        }
        ensureLoaded(level);
        List<Task> ts = tasks();
        tearGone.clear();
        tearClaimed.clear();
        tearClaims.clear();
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < ts.size(); i++) {
            if (!building.get(i) || !finished(i) || torn.get(i)) {
                continue;
            }
            BlockPos p = ts.get(i).pos();
            if (level.isLoaded(p) && !level.getBlockState(p).is(ts.get(i).state().getBlock())) {
                tearGone.set(i);                                 // someone's block there now (or the cell was changed since): it stays
                continue;
            }
            list.add(i);
        }
        list.sort(Comparator.<Integer>comparingInt(this::tearRank).thenComparing(Comparator.<Integer>naturalOrder().reversed()));
        tearIdx = list.stream().mapToInt(Integer::intValue).toArray();
        tearLast = level.getGameTime();
    }

    /**
     * Where a cell comes down in the order: a hanging piece by its height up (the foot of a column first), then the courses by their height down, then
     * the plinth of the building's own blocks that lies under them.
     */
    private int tearRank(int i) {
        if (phase[i] != COURSE) {
            return 5000;
        }
        return hanging.get(i) ? lift[i] : 1000 + (255 - lift[i]);
    }

    /** The cells still to take down. */
    public int tearOpen() {
        int n = 0;
        if (tearIdx != null) {
            for (int i : tearIdx) {
                if (!torn.get(i) && !tearGone.get(i)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** How many cells the teardown has to take down: those it has taken out, and those still to go (a cell found to be someone's now is not one). */
    public int tearTotal() {
        return torn.cardinality() + tearOpen();
    }

    /** How many cells the teardown has taken out. */
    public int tornCount() {
        return torn.cardinality();
    }

    /** The teardown is over: every cell that was to come down has, or was left standing (someone's now). */
    public boolean tornDown() {
        return tearing && tearIdx != null && tearOpen() == 0;
    }

    /** How far the teardown has come, 0 to 1. */
    public float tearProgress() {
        int total = tearTotal();
        return total == 0 ? 1f : torn.cardinality() / (float) total;
    }

    /** When a cell last came down, or the teardown began (game time). */
    public long tearLast() {
        return tearLast;
    }

    /** Whether the site is taking itself down ({@link #tearSelf}). */
    public boolean tearSelfPaced() {
        return tearSelfPaced;
    }

    /** The building's own cells (the courses' solid cells and the plinth made of its blocks): what the price is spread over. */
    public int buildingCells() {
        return building.cardinality();
    }

    /** Whether task {@code i} is one of the building's own cells (a course's solid cell, or the plinth of its blocks): what a teardown takes down. */
    public boolean building(int i) {
        return building.get(i);
    }

    /** The building's cells laid: those of them done. */
    public int laidCount() {
        int n = 0;
        for (int i = building.nextSetBit(0); i >= 0; i = building.nextSetBit(i + 1)) {
            if (finished(i)) {
                n++;
            }
        }
        return n;
    }

    /**
     * What a teardown pays back, once it is over ({@link #share}): the price spread over the building's cells, less the cells that stayed (laid, and not
     * taken out: a player's block in the cell now, or one that was changed while it came down). All of it when every laid cell came down.
     */
    public int refundNow() {
        int c = buildingCells();
        return share(paid, c - (laidCount() - torn.cardinality()), c);
    }

    /**
     * What a call-off pays back if every cell that still holds the block it laid comes down: the whole price less the share of the laid cells that already
     * don't (a player's block in the cell). What the bridge says a site would pay back ({@code level} reads the cells now).
     */
    public int refundIfCalledOff(ServerLevel level) {
        List<Task> ts = tasks();
        int c = buildingCells();
        if (tearing) {
            return share(paid, c - (laidCount() - torn.cardinality() - tearOpen()), c);   // what stays: laid, not taken out, and not still to come down
        }
        int stayed = 0;
        for (int i = 0; ts != null && i < ts.size(); i++) {
            if (building.get(i) && finished(i)) {
                BlockPos p = ts.get(i).pos();
                if (level.isLoaded(p) && !level.getBlockState(p).is(ts.get(i).state().getBlock())) {
                    stayed++;
                }
            }
        }
        return share(paid, c - stayed, c);
    }

    private void tearExpire(long now) {
        tearClaims.entrySet().removeIf(e -> {
            if (e.getValue().expires() < now) {
                for (int i : e.getValue().idx()) {
                    tearClaimed.clear(i);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Hands a builder its next cells to take down and returns the one it stands to first, or null when there is nothing for it now (every cell of the
     * course that is coming down is held by another builder: the next course waits for it). Only the cells of the course the teardown stands at are
     * handed out, so the building comes down top first whoever is at it: the anchor is the cell nearest {@code near} and away from the others' batches, and
     * the batch is up to {@code batch} cells of that course within 3 of it.
     */
    @Nullable
    public BlockPos tearClaim(ServerLevel level, UUID rat, int batch, long now, BlockPos near) {
        tearEnsure(level);
        if (!tearing || tearIdx == null) {
            return null;
        }
        tearExpire(now);
        tearRelease(rat);
        List<Task> ts = tasks();
        int rank = -1;
        IntArrayList cand = new IntArrayList();
        for (int i : tearIdx) {
            if (torn.get(i) || tearGone.get(i)) {
                continue;
            }
            if (rank < 0) {
                rank = tearRank(i);                              // the course the teardown stands at: the first cell still up
            } else if (tearRank(i) != rank) {
                break;
            }
            if (!tearClaimed.get(i) && level.isLoaded(ts.get(i).pos())) {
                cand.add(i);
            }
        }
        if (cand.isEmpty()) {
            return null;
        }
        List<BlockPos> others = new ArrayList<>(tearClaims.size());
        for (TearClaim c : tearClaims.values()) {
            others.add(ts.get(c.idx()[0]).pos());
        }
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int k = 0; k < cand.size(); k++) {
            BlockPos p = ts.get(cand.getInt(k)).pos();
            double score = Math.sqrt(p.distSqr(near));
            for (BlockPos o : others) {
                int d = o.distManhattan(p);
                if (d <= 3) {
                    score += (4 - d) * 2;
                }
            }
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        int anchorIdx = cand.getInt(best);
        BlockPos anchor = ts.get(anchorIdx).pos();
        IntArrayList mine = new IntArrayList();
        for (int k = 0; k < cand.size(); k++) {
            int i = cand.getInt(k);
            if (i != anchorIdx && ts.get(i).pos().distManhattan(anchor) <= 3) {
                mine.add(i);
            }
        }
        mine.sort((a, b) -> Integer.compare(ts.get(a).pos().distManhattan(anchor), ts.get(b).pos().distManhattan(anchor)));
        int[] idx = new int[1 + Math.min(batch - 1, mine.size())];
        idx[0] = anchorIdx;                                      // the anchor first: the builder stands to it
        for (int k = 1; k < idx.length; k++) {
            idx[k] = mine.getInt(k - 1);
        }
        for (int i : idx) {
            tearClaimed.set(i);
        }
        tearClaims.put(rat, new TearClaim(idx, now + 600));
        return anchor;
    }

    /** The cells of {@code rat}'s batch to take down, the one it stands to first; empty if it holds none. */
    public List<BlockPos> tearBatch(UUID rat) {
        TearClaim c = tearClaims.get(rat);
        if (c == null) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>(c.idx().length);
        for (int i : c.idx()) {
            out.add(tasks().get(i).pos());
        }
        return out;
    }

    /** Whether {@code rat} holds a batch of cells to take down. */
    public boolean tearHolds(UUID rat) {
        return tearClaims.containsKey(rat);
    }

    /** Part-way through a batch: the first {@code fraction} of the cells come down now, bite by bite. Returns what gave way. */
    public List<Crumb> tearSome(ServerLevel level, UUID rat, float fraction) {
        TearClaim c = tearClaims.get(rat);
        if (c == null) {
            return List.of();
        }
        int n = Math.min(c.idx().length, (int) Math.ceil(c.idx().length * Math.min(1f, fraction)));
        List<Crumb> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Crumb x = tearCell(level, c.idx()[k]);
            if (x != null) {
                out.add(x);
            }
        }
        return out;
    }

    /** The builder is done with its batch: whatever of it is still up comes down. */
    public List<Crumb> tearComplete(ServerLevel level, UUID rat) {
        TearClaim c = tearClaims.remove(rat);
        List<Crumb> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (int i : c.idx()) {
            tearClaimed.clear(i);
            Crumb x = tearCell(level, i);
            if (x != null) {
                out.add(x);
            }
        }
        return out;
    }

    /** A builder is gone: its batch goes back. */
    public void tearRelease(UUID rat) {
        TearClaim c = tearClaims.remove(rat);
        if (c != null) {
            for (int i : c.idx()) {
                tearClaimed.clear(i);
            }
        }
    }

    /**
     * The site takes cells down itself, {@code n} of them in the order they come down, whoever holds them: what it does when no builder can come (an empty
     * reserve) or none has taken a cell down for a while, so a called-off site is never left half taken down. Once begun it goes on. What gave way.
     */
    public List<Crumb> tearSelf(ServerLevel level, int n) {
        tearEnsure(level);
        tearSelfPaced = true;
        List<Crumb> out = new ArrayList<>();
        if (tearIdx == null) {
            return out;
        }
        for (int i : tearIdx) {
            if (out.size() >= n) {
                break;
            }
            if (torn.get(i) || tearGone.get(i)) {
                continue;
            }
            Crumb x = tearCell(level, i);
            if (x != null) {
                out.add(x);
            }
        }
        return out;
    }

    /**
     * What a teardown leaves of the building's box, made level, once it is over (the user, 2026-10-01: "level ground, not a hole"): in its floor layer (the
     * ground-layer cells of the courses at the floor or under it) that were never laid, a cell that is air over something solid (the corner of a dip a plinth
     * filled to a block under the floor) gets the land's own ground, as {@link #tearCell} gives the cells that came down; and in the cells above the floor
     * that were never laid, the land that still stands there (a hump the courses had not got to) is cut, so nothing stands up out of the floor either.
     * Returns how many cells it changed.
     */
    public int levelFloor(ServerLevel level) {
        ensureLoaded(level);
        int n = 0;
        List<Task> ts = tasks();
        for (int i = 0; plan != null && i < ts.size(); i++) {
            if (phase[i] != COURSE || finished(i)) {
                continue;
            }
            BlockPos p = ts.get(i).pos();
            if (!plan.inArea(p.getX(), p.getZ()) || !level.isLoaded(p)) {
                continue;
            }
            BlockState now = level.getBlockState(p);
            int flags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;
            if (p.getY() <= plan.floor()) {
                if (now.isAir() && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) {
                    level.setBlock(p, plan.look(p.getX(), p.getZ()).at(plan.floor() - p.getY()), flags);
                    n++;
                }
            } else if (Siting.ground(now)) {
                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
                n++;
            }
        }
        return n;
    }

    /**
     * One cell comes down: air where it was, or, at the floor or under it (the ground layer the building put in the place of the land's own), the ground the
     * land round it has (the column's own look, sampled when the site was planned: grass on top, its soil under it), so a torn-down site leaves level
     * ground and no hole. What it held (a barrel's stores) is handed to the settlement's spoils, never dropped. It stays if it no longer holds the block that
     * was laid. Null (nothing gave way) for a cell that stays, or whose chunk is not loaded (it waits).
     */
    @Nullable
    private Crumb tearCell(ServerLevel level, int i) {
        if (torn.get(i) || tearGone.get(i)) {
            return null;
        }
        Task t = tasks().get(i);
        BlockPos p = t.pos();
        if (!level.isLoaded(p)) {
            return null;
        }
        BlockState was = level.getBlockState(p);
        if (!was.is(t.state().getBlock())) {
            tearGone.set(i);                                     // someone's block now: it stays
            return null;
        }
        // SLICE 3 HOOK (surface-builder.md, section 7): a block that was laid from the Hoard goes back to the Hoard here (Bins.insert), not into the air
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(p);
        if (sink != null && be != null) {
            sink.take(level, p, was, be);
            if (be instanceof net.minecraft.world.Container c) {
                c.clearContent();                                // its stores went to the settlement, not onto the ground
            }
        }
        BlockState after = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        if (plan != null && p.getY() <= plan.floor() && plan.inArea(p.getX(), p.getZ())) {
            after = plan.look(p.getX(), p.getZ()).at(plan.floor() - p.getY());      // the land's own: grass on top, its soil under it
        }
        level.setBlock(p, after, net.minecraft.world.level.block.Block.UPDATE_CLIENTS | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        torn.set(i);
        tearLast = level.getGameTime();
        return new Crumb(p, was);
    }

    /** A floor that stays as it is: the site's own finished work, or the ground itself. */
    private boolean steady(ServerLevel level, BlockPos floor) {
        int i = fills.getOrDefault(floor.asLong(), -1);
        if (i >= 0) {
            return finished(i);
        }
        return Siting.ground(level.getBlockState(floor));
    }
}
