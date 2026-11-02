package claude.settlementcore.room;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

/**
 * A wall raised by its settlement's settlers (docs/plans/walls.md, E2): a {@link RaiseJob} whose cells come from a line of posts,
 * not a template, and whose land is never levelled. The posts are joined by straight runs of columns, side by side (a corner
 * between two columns would let nothing through, but a run of them side by side leaves no doubt); each column stands on its own
 * ground, {@link WallStyle#height} courses high, more where it stands in water (its foot on the bed). A tree in the wall's cells is
 * felled first, whole (the clearing), and a leaf there is cut. Then the courses go in, all along the wall: the first course
 * everywhere, then the second, and so on, as a building's do (the course window). A player's block in a cell stays, and is part of
 * the wall; so is a building's.
 *
 * <p>The task list is rebuilt the same way on load from what it saves (the columns' feet and heights, which way is across, the
 * gates, the trees) and the style alone, never from the world. When it is done, the settlement is told its walls changed (the next
 * scan finds the ring: {@code Settlement#wallChanged}).
 */
public class WallJob extends RaiseJob {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The most columns one wall order raises: a ring 128 across. */
    public static final int MAX_COLUMNS = 512;
    /** Water deeper than this at a column's foot: no wall there. */
    public static final int WATER_MAX = 4;

    private final WallStyle style;
    /** Each column's foot (packed BlockPos: the cell over its ground), in the wall's order. */
    private long[] feet = new long[0];
    private int[] heights = new int[0];
    /** Which way is across the wall, toward the settlement, per column (Direction#get2DDataValue). */
    private byte[] across = new byte[0];
    private final BitSet gates = new BitSet();
    /** The trees in the wall's way, felled in the clearing (packed BlockPos, sorted). */
    private long[] trees = new long[0];
    /** A patch's cells (packed BlockPos, sorted); null for the whole wall. */
    @Nullable private long[] only;

    private WallJob(WallStyle style, BlockPos door, Direction facing, int paid) {
        super(style.roomId(), door, facing, null, paid);
        this.style = style;
    }

    /** A wall to raise along {@code line} ({@link #line}), paid {@code paid} for. */
    public WallJob(WallStyle style, Line line, int paid) {
        this(style, BlockPos.of(line.feet()[0]), Direction.from2DDataValue(line.across()[0]), paid);
        this.feet = line.feet().clone();
        this.heights = line.heights().clone();
        this.across = line.across().clone();
        this.gates.or(line.gates());
        this.trees = line.trees().clone();
    }

    public WallStyle style() {
        return style;
    }

    /** How many columns the wall has. */
    public int columns() {
        return feet.length;
    }

    /** The {@code i}'th column's foot. */
    public BlockPos foot(int i) {
        return BlockPos.of(feet[i]);
    }

    public int height(int i) {
        return heights[i];
    }

    public boolean gate(int i) {
        return gates.get(i);
    }

    // --- the line ------------------------------------------------------------------------------------------------------

    /**
     * Where a wall goes: each column's foot and height, which way is across (to the settlement), the gate columns, and the trees to
     * fell; {@link #why} null. Or {@link #why} the reason it can't be, in words for the player, and the rest empty.
     */
    public record Line(long[] feet, int[] heights, byte[] across, BitSet gates, long[] trees, @Nullable String why) {
        static Line no(String why) {
            return new Line(new long[0], new int[0], new byte[0], new BitSet(), new long[0], why);
        }
    }

    /**
     * The line of a wall of {@code style} through {@code posts} (their x and z; a ring closes back to the first), the posts in
     * {@code gatePosts} (their indices) each the middle of a gate {@link WallStyle#gateWidth} wide, {@code heart} the settlement's
     * heart (the side the wall faces). Read from the world as it is now: nothing is changed.
     */
    public static Line line(ServerLevel level, BlockPos heart, List<BlockPos> posts, boolean ring, Collection<Integer> gatePosts, WallStyle style) {
        if (posts.size() < (ring ? 3 : 2)) {
            return Line.no(ring ? "A ring wall needs three posts at least" : "A wall needs two posts at least");
        }
        LongLinkedOpenHashSet cols = new LongLinkedOpenHashSet();
        IntArrayList postAt = new IntArrayList();
        IntArrayList dirs = new IntArrayList();                  // the run each column was laid along: 0 along x, 1 along z
        int n = ring ? posts.size() : posts.size() - 1;
        for (int k = 0; k < n; k++) {
            BlockPos a = posts.get(k), b = posts.get((k + 1) % posts.size());
            int ax = a.getX(), az = a.getZ(), bx = b.getX(), bz = b.getZ();
            int dirOfRun = Math.abs(bx - ax) >= Math.abs(bz - az) ? 0 : 1;
            int x = ax, z = az;
            if (cols.add(column(x, z))) {
                dirs.add(dirOfRun);
            }
            postAt.add(indexOf(cols, column(ax, az)));
            int sx = Integer.signum(bx - ax), sz = Integer.signum(bz - az);
            long dx = bx - ax, dz = bz - az;
            while (x != bx || z != bz) {
                // a step along x or along z, whichever stays nearer the straight line from a to b: the columns side by side
                long ex = x == bx ? Long.MAX_VALUE : Math.abs((x + sx - ax) * dz - (z - az) * dx);
                long ez = z == bz ? Long.MAX_VALUE : Math.abs((x - ax) * dz - (z + sz - az) * dx);
                if (ex <= ez) {
                    x += sx;
                } else {
                    z += sz;
                }
                if (cols.add(column(x, z))) {
                    dirs.add(dirOfRun);
                }
                if (cols.size() > MAX_COLUMNS) {
                    return Line.no("That wall is more than " + MAX_COLUMNS + " columns long: wall in less at a time");
                }
            }
        }
        if (!ring) {
            postAt.add(indexOf(cols, column(posts.getLast().getX(), posts.getLast().getZ())));
        }
        long[] cs = cols.toLongArray();
        BitSet gate = new BitSet();
        for (int g : gatePosts) {
            if (g < 0 || g >= postAt.size()) {
                continue;
            }
            int mid = postAt.getInt(g);
            for (int w = 0; w < style.gateWidth(); w++) {
                int i = mid - (style.gateWidth() - 1) / 2 + w;
                if (ring) {
                    i = Math.floorMod(i, cs.length);
                }
                if (i >= 0 && i < cs.length) {
                    gate.set(i);
                }
            }
        }
        long[] feet = new long[cs.length];
        int[] heights = new int[cs.length];
        byte[] across = new byte[cs.length];
        LongOpenHashSet trees = new LongOpenHashSet();
        for (int i = 0; i < cs.length; i++) {
            int x = (int) (cs[i] >> 32), z = (int) cs[i];
            if (Math.abs(x - heart.getX()) > claude.settlementcore.settlement.Walls.RADIUS
                    || Math.abs(z - heart.getZ()) > claude.settlementcore.settlement.Walls.RADIUS) {
                return Line.no("The wall at " + x + " " + z + " is more than " + claude.settlementcore.settlement.Walls.RADIUS
                        + " from the settlement: no wall reaches round so much");
            }
            if (!level.hasChunkAt(new BlockPos(x, heart.getY(), z))) {
                return Line.no("The wall at " + x + " " + z + " runs where the world isn't loaded");
            }
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
            int water = 0;
            while (m.getY() > level.getMinY()) {
                m.move(Direction.DOWN);
                BlockState s = level.getBlockState(m);
                if (!s.getFluidState().isEmpty() && s.getCollisionShape(level, m).isEmpty()) {
                    if (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                        return Line.no("Lava at " + m.getX() + " " + m.getY() + " " + m.getZ() + ", where the wall goes");
                    }
                    water++;
                    continue;
                }
                if (water == 0 && (s.isAir() || standsOn(s))) {
                    continue;                                    // over the ground: a tree, the cover, snow
                }
                break;
            }
            if (water > WATER_MAX) {
                return Line.no("The water at " + x + " " + z + " is " + water + " deep: too deep to wall (" + WATER_MAX + " at most)");
            }
            BlockPos foot = m.above().immutable();
            feet[i] = foot.asLong();
            heights[i] = style.height() + water;
            across[i] = (byte) acrossOf(dirs.getInt(i), foot, heart).get2DDataValue();
            for (int k = 0; k < heights[i]; k++) {
                BlockPos p = foot.above(k);
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.LOGS)) {
                    String no = fell(level, p, trees);
                    if (no != null) {
                        return Line.no(no);
                    }
                } else if (wildLeaf(s)) {
                    trees.add(p.asLong());
                }
            }
        }
        long[] t = trees.toLongArray();
        Arrays.sort(t);
        return new Line(feet, heights, across, gate, t, null);
    }

    private static int indexOf(LongLinkedOpenHashSet cols, long c) {
        int i = 0;
        for (long x : cols) {
            if (x == c) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** What stands over the ground and isn't it: a tree, its leaves, the cover, snow. */
    private static boolean standsOn(BlockState s) {
        return s.is(BlockTags.LOGS) || s.getBlock() instanceof LeavesBlock || Siting.cover(s) || DigJob.plant(s)
                || s.getBlock() instanceof SnowLayerBlock;
    }

    /** What a tree may touch and still be felled (Siting's rule): the land, its cover, water, ice and snow, and trees. */
    private static boolean wild(BlockState s) {
        return DigJob.natural(s) || Siting.cover(s) || DigJob.plant(s) || s.is(BlockTags.ICE) || s.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
                || s.getBlock() instanceof SnowLayerBlock;
    }

    private static boolean wildLeaf(BlockState s) {
        return s.getBlock() instanceof LeavesBlock && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
    }

    /** Across a run along x is north or south, along z east or west: whichever faces {@code heart}. */
    private static Direction acrossOf(int dirOfRun, BlockPos foot, BlockPos heart) {
        if (dirOfRun == 0) {
            return heart.getZ() >= foot.getZ() ? Direction.SOUTH : Direction.NORTH;
        }
        return heart.getX() >= foot.getX() ? Direction.EAST : Direction.WEST;
    }

    /**
     * The tree whose log stands at {@code log}, its logs and wild leaves (every neighbour, corners too), into {@code into}; or why
     * it can't be felled: too big ({@link Siting#TREE_MAX}), or its logs touch none of a tree's leaves (a player's log: it stays
     * then, and the wall goes round it, as round any player's block: null).
     */
    @Nullable
    private static String fell(ServerLevel level, BlockPos log, LongOpenHashSet into) {
        if (into.contains(log.asLong())) {
            return null;
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        seen.add(log.asLong());
        q.enqueue(log.asLong());
        boolean leafy = false;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!q.isEmpty()) {
            BlockPos p = BlockPos.of(q.dequeueLong());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        m.set(p.getX() + dx, p.getY() + dy, p.getZ() + dz);
                        long k = m.asLong();
                        if (seen.contains(k) || !level.isLoaded(m)) {
                            continue;
                        }
                        BlockState s = level.getBlockState(m);
                        boolean leaf = wildLeaf(s);
                        if (!s.is(BlockTags.LOGS) && !leaf) {
                            if (dx * dx + dy * dy + dz * dz == 1 && !wild(s)) {
                                return null;                     // a log touching a player's block (planks, glass, a door): a cabin's, it stays
                            }
                            continue;
                        }
                        leafy |= leaf;
                        seen.add(k);
                        if (seen.size() > Siting.TREE_MAX) {
                            return "The tree at " + log.getX() + " " + log.getY() + " " + log.getZ() + " is too big to fell for the wall";
                        }
                        q.enqueue(k);
                    }
                }
            }
        }
        if (leafy) {
            into.addAll(seen);
        }
        return null;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // --- the task list -------------------------------------------------------------------------------------------------

    @Override
    protected List<Task> buildTasks(ServerLevel level, PlanPlacement pl, LongSet keep) {
        List<Task> out = new ArrayList<>();
        ByteArrayList ph = new ByteArrayList();
        IntArrayList lf = new IntArrayList();
        Long[] felled = new Long[trees.length];
        for (int i = 0; i < trees.length; i++) {
            felled[i] = trees[i];
        }
        Arrays.sort(felled, (a, b) -> {                          // top down, so nothing is left hanging
            int c = Integer.compare(BlockPos.getY(b), BlockPos.getY(a));
            return c != 0 ? c : Long.compare(a, b);
        });
        for (long t : felled) {
            out.add(new Task(BlockPos.of(t), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), null));
            ph.add(CLEARING);
            lf.add(0);
        }
        for (Laid l : courses(style, feet, heights, across, gates)) {
            if (only != null && Arrays.binarySearch(only, l.task().pos().asLong()) < 0) {
                continue;                                        // a patch: only the cells that were knocked out
            }
            out.add(l.task());
            ph.add(COURSE);
            lf.add(l.course());
        }
        index(out, ph.toByteArray(), lf.toIntArray(), pl);
        return out;
    }

    /** A cell of a wall and its course. */
    public record Laid(Task task, int course) {
    }

    /**
     * Every cell of a wall of {@code style} along its columns, course by course (the first all along, then the second), its blocks as the
     * style answers them: the whole wall a job lays, and what a settlement's record of it says should stand (Walls#missing).
     */
    public static List<Laid> courses(WallStyle style, long[] feet, int[] heights, byte[] across, BitSet gates) {
        List<Laid> out = new ArrayList<>();
        int top = 0;
        for (int h : heights) {
            top = Math.max(top, h);
        }
        for (int k = 0; k < top; k++) {
            for (int i = 0; i < feet.length; i++) {
                if (k >= heights[i]) {
                    continue;
                }
                BlockState s = state(style, i, k, heights[i], across[i], gates.get(i));
                if (s == null || s.isAir()) {
                    continue;
                }
                out.add(new Laid(new Task(BlockPos.of(feet[i]).above(k), s, null), k));
            }
        }
        return out;
    }

    /** What goes in course {@code k} of column {@code i}: the style's, a column standing in water footed on its foot's block. */
    @Nullable
    private static BlockState state(WallStyle style, int i, int k, int height, byte across, boolean gate) {
        int water = height - style.height();
        int course = Math.max(0, k - water);
        Direction a = Direction.from2DDataValue(across);
        return gate ? style.gate().at(i, course, style.height(), a) : style.column().at(i, course, style.height(), a);
    }

    /** The wall's line as it was laid out (no trees: they're felled), for the settlement's record of its walls. */
    public Line line() {
        return new Line(feet.clone(), heights.clone(), across.clone(), (BitSet) gates.clone(), new long[0], null);
    }

    /** Whether it is a patch of a wall that stands (only the cells knocked out of it), not a new wall. */
    public boolean patch() {
        return only != null;
    }

    /** A patch of the wall {@code line} of {@code style}: only its cells at {@code cells}, laid again, free. */
    public static WallJob patch(WallStyle style, Line line, long[] cells) {
        long[] o = cells.clone();
        Arrays.sort(o);
        Line l = new Line(line.feet(), line.heights(), line.across(), line.gates(), new long[0], null);
        WallJob j = new WallJob(style, BlockPos.of(o[0]), Direction.from2DDataValue(line.across()[0]), 0);
        j.feet = l.feet().clone();
        j.heights = l.heights().clone();
        j.across = l.across().clone();
        j.gates.or(l.gates());
        j.only = o;
        return j;
    }

    /** Besides the ground and its cover: the trees it fells, where they stood. */
    @Override
    protected boolean mayTake(BlockPos p, BlockState cur) {
        if (Siting.mayReplace(cur)) {
            return true;
        }
        return (cur.is(BlockTags.LOGS) || wildLeaf(cur)) && Arrays.binarySearch(trees, p.asLong()) >= 0;
    }

    /** Its builders come up on the settlement's side of the first column still to lay. */
    @Override
    @Nullable
    public BlockPos comeUpNear(ServerLevel level) {
        List<Task> ts = tasks();
        if (ts == null || feet.length == 0) {
            return null;
        }
        for (int i = 0; i < ts.size(); i++) {
            if (!finished(i)) {
                BlockPos p = ts.get(i).pos();
                int c = columnOf(p);
                Direction a = Direction.from2DDataValue(across[c < 0 ? 0 : c]);
                return BlockPos.of(feet[c < 0 ? 0 : c]).relative(a, 2);
            }
        }
        return BlockPos.of(feet[0]).relative(Direction.from2DDataValue(across[0]), 2);
    }

    private int columnOf(BlockPos p) {
        for (int i = 0; i < feet.length; i++) {
            if (BlockPos.getX(feet[i]) == p.getX() && BlockPos.getZ(feet[i]) == p.getZ()) {
                return i;
            }
        }
        return -1;
    }

    /** A wall's block goes in joined to what is beside it (a palisade's fences, a gate set in them): it never stands a loose post. */
    @Override
    protected boolean put(ServerLevel level, int i, Task t) {
        boolean done = super.put(level, i, t);
        if (done && !t.state().isAir() && level.isLoaded(t.pos())) {
            join(level, t.pos());
        }
        return done;
    }

    @Override
    public void joinUp(ServerLevel level) {
        List<Task> ts = tasks();
        for (int i = 0; ts != null && i < ts.size(); i++) {
            if (phaseOf(i) == COURSE && level.isLoaded(ts.get(i).pos())) {
                join(level, ts.get(i).pos());
            }
        }
    }

    /** The cell at {@code p} and the four beside it, their shapes as their neighbours make them (fences joined, gates lowered in a wall). */
    private static void join(ServerLevel level, BlockPos p) {
        reshape(level, p);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = p.relative(d);
            if (level.isLoaded(q)) {
                reshape(level, q);
            }
        }
    }

    private static void reshape(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir()) {
            return;
        }
        BlockState j = Block.updateFromNeighbourShapes(s, level, p);
        if (j != s) {
            level.setBlock(p, j, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
    }

    // --- saving --------------------------------------------------------------------------------------------------------

    @Override
    protected void saveMore(CompoundTag t) {
        super.saveMore(t);
        t.putBoolean("wall", true);
        t.putString("style", style.id());
        t.putLongArray("feet", feet);
        t.putIntArray("heights", heights);
        t.putByteArray("across", across);
        t.putLongArray("gates", gates.toLongArray());
        t.putLongArray("trees", trees);
        if (only != null) {
            t.putLongArray("only", only);
        }
    }

    @Override
    protected void loadMore(CompoundTag t) {
        super.loadMore(t);
        feet = t.getLongArray("feet").orElse(new long[0]);
        heights = t.getIntArray("heights").orElse(new int[0]);
        across = t.getByteArray("across").orElse(new byte[0]);
        gates.clear();
        gates.or(BitSet.valueOf(t.getLongArray("gates").orElse(new long[0])));
        trees = t.getLongArray("trees").orElse(new long[0]);
        Arrays.sort(trees);
        only = t.getLongArray("only").map(long[]::clone).orElse(null);
        if (only != null) {
            Arrays.sort(only);
        }
    }

    /** Whether {@code t} is a saved wall's ({@link #save}). */
    public static boolean isWall(CompoundTag t) {
        return t.getBooleanOr("wall", false);
    }

    /** A saved wall back; null if its style isn't registered any more, or its columns don't add up (logged). */
    @Nullable
    public static WallJob loadWall(CompoundTag t) {
        WallStyle style = WallStyle.get(t.getStringOr("style", ""));
        if (style == null || Rooms.get(style.roomId()) == null) {
            LOGGER.warn("Settlement Core: a saved wall of the style '{}' can't be raised (no such style): dropped", t.getStringOr("style", ""));
            return null;
        }
        WallJob j = new WallJob(style, savedDoor(t), savedFacing(t), 0).readSaved(t);
        if (j.only != null && j.only.length == 0) {
            return null;
        }
        if (j.feet.length == 0 || j.heights.length != j.feet.length || j.across.length != j.feet.length) {
            LOGGER.warn("Settlement Core: a saved wall of '{}' at {} has {} feet, {} heights, {} across: dropped", style.id(), j.door, j.feet.length,
                    j.heights.length, j.across.length);
            return null;
        }
        return j;
    }
}
