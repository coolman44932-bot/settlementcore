package claude.underempire.core.room;

import claude.underempire.core.unit.SettlerMob;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
 * them, so they never hold the course window down ({@link #windowCourse}), and what they hang from never waits for them
 * ({@link #underToCome}). A cell over the build height, or under the world, is done with nothing put (as a dig's is), and
 * nothing ever loads a chunk to lay a cell.
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
    /** The course's cells that hang from what is over them ({@link #hangs}): a lantern under its ceiling, a chain, a hanging vine. */
    private final BitSet hanging = new BitSet();
    /** Cells a settler's task empties (a cut), and the task. */
    private final Long2IntOpenHashMap cuts = new Long2IntOpenHashMap();
    /** The cuts and fills of the berm, landing and ramp, by column. */
    private final Long2ObjectOpenHashMap<IntArrayList> columns = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet cells = new LongOpenHashSet();
    @Nullable private BoundingBox footprint;
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
    }

    @Override
    protected void loadMore(CompoundTag t) {
        planTag = t.contains("reshape") ? t.getCompound("reshape") : null;
        paid = t.getInt("paid");
        heaved = t.getInt("heaved");
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
                if (ph[i] == COURSE && hangs(tasks.get(i).state())) {
                    hanging.set(i);
                }
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

    /** Cells heaved into place because no builder could get to them. */
    public int heaved() {
        return heaved;
    }

    /** A player's blocks the work met and left standing. */
    public int spared() {
        return spared;
    }

    /** The site clears the land itself: its clearing isn't done yet (and its builders have nothing to do). */
    public boolean clearing() {
        return tasks() != null && front() < clearEnd;
    }

    /** Its builders have work: the clearing is done and the building isn't. */
    public boolean working() {
        return tasks() != null && !clearing() && !isDone();
    }

    /** The phase of the lowest open task ({@link #CLEARING} to {@link #COURSE}), or -1 when done or not begun. */
    public int phaseNow() {
        List<Task> ts = tasks();
        int f = front();
        return ts == null || f >= ts.size() ? -1 : phase[f];
    }

    /** The lowest open course (the template's local y), or -1 while the land is worked, or when done. */
    public int courseNow() {
        return phaseNow() == COURSE ? lift[front()] : -1;
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
        if (isDone()) {
            return "raised";
        }
        int f = front();
        return f < clearEnd ? "clearing the site" : doing(f);
    }

    /** What {@code rat}'s batch is, in words ("cutting the ground"), or "building" if it holds none. */
    public String doing(UUID rat) {
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

    /** The lowest open cell heaved into place (the site has stalled: builders up, none at work, nothing to hand them). */
    public boolean heaveFront(ServerLevel level) {
        ensureLoaded(level);
        int f = front();
        return f < tasks().size() && heave(level, f);
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
     */
    @Nullable
    public BlockPos standFor(ServerLevel level, BlockPos anchor, List<BlockPos> batch, BlockPos near) {
        LongOpenHashSet mine = new LongOpenHashSet(batch.size());
        for (BlockPos b : batch) {
            mine.add(column(b.getX(), b.getZ()));
        }
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
                    if (stays(p) && !pit(level, p)) {
                        double h = Math.sqrt(across);
                        double score = (h - 1.5) * (h - 1.5) + Math.abs(dy - 0.5) * 2 + (steady(level, p.below()) ? 0 : 3)
                                + Math.sqrt(p.distSqr(near)) * 0.25;
                        if (score < bestScore) {
                            bestScore = score;
                            best = p.immutable();
                        }
                    }
                    break;                                       // the highest cell in reach in this column
                }
            }
        }
        return best;
    }

    /** A pit: every neighbour of {@code feet} stands 2 or more over it (the cell over its feet is solid all round). */
    public static boolean pit(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d).above();
            if (!level.isLoaded(n) || level.getBlockState(n).getCollisionShape(level, n).isEmpty()) {
                return false;
            }
        }
        return true;
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

    /** A floor that stays as it is: the site's own finished work, or the ground itself. */
    private boolean steady(ServerLevel level, BlockPos floor) {
        int i = fills.getOrDefault(floor.asLong(), -1);
        if (i >= 0) {
            return finished(i);
        }
        return Siting.ground(level.getBlockState(floor));
    }
}
