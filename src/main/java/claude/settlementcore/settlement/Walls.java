package claude.settlementcore.settlement;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import claude.settlementcore.room.WallJob;
import claude.settlementcore.room.WallStyle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Whether a settlement is walled in, and what is inside its walls (docs/plans/walls.md, E1). From the heart, the ground is walked outward
 * as a mob walks it: a step up of 1, a drop of at most {@value #DROP}, across water, through any cell with two collision-free blocks over a
 * floor. Fence gates and doors are wall, open or shut (a settlement's own gates never break its ring). If the walk never gets further than
 * {@value #RADIUS} blocks from the heart, the settlement is walled, and the columns it reached are inside the walls; else it is open, and
 * {@link #leak} is where the walk got out (a breach, if it was walled before).
 * <p>One per settlement ({@link SettlementBlockEntity#walls}): scanned every {@value #SCAN_SECONDS} s, and {@value #DIRTY_SECONDS} s after
 * a block near it changes ({@link #dirty}). An unloaded column counts as a way out: it can't be checked.
 */
public final class Walls {

    public static final int RADIUS = 64;
    /** At most this many columns walked: past it, the ground is too big to be a walled settlement. */
    public static final int MAX_COLUMNS = 16_641;
    public static final int DROP = 3;
    public static final int SCAN_SECONDS = 30, DIRTY_SECONDS = 5;
    /** How far above or below the heart a thing inside the walls' columns still counts as inside. */
    public static final int REACH_Y = 24;

    private LongOpenHashSet inside = new LongOpenHashSet();
    private boolean walled;
    private int heartY;
    @Nullable private BlockPos leak;
    private long scannedAt = Long.MIN_VALUE;
    private long dirtyAt = Long.MAX_VALUE;
    private boolean everScanned;
    /** The gates in the ring (fence gates and doors the walk met as wall), found by the last scan. */
    private LongOpenHashSet gates = new LongOpenHashSet();
    @Nullable private BoundingBox bounds;
    /** The walls its settlers raised (E3): what should stand, so what a foe knocks out of them is patched. */
    private final List<Built> built = new ArrayList<>();
    /** Cells of them a player took out on purpose: left out (a way through the player made), never patched. */
    private final LongOpenHashSet forgot = new LongOpenHashSet();

    public boolean walled() {
        return walled;
    }

    /** Whether {@code p} is inside the walls: in a column the walk reached, near the heart's height. False while not walled. */
    public boolean inside(BlockPos p) {
        return walled && Math.abs(p.getY() - heartY) <= REACH_Y && inside.contains(column(p.getX(), p.getZ()));
    }

    /** How many columns are inside the walls (0 while not walled). */
    public int size() {
        return walled ? inside.size() : 0;
    }

    /** Where the last scan's walk got out, or null if it didn't. */
    public @Nullable BlockPos leak() {
        return leak;
    }

    /** A block near the settlement changed: scan again soon. */
    public void dirty(long gameTime) {
        if (dirtyAt == Long.MAX_VALUE) {
            dirtyAt = gameTime + DIRTY_SECONDS * 20L;
        }
    }

    /** Whether a scan is due at {@code gameTime}. */
    public boolean due(long gameTime) {
        return !everScanned || gameTime >= dirtyAt || gameTime - scannedAt >= SCAN_SECONDS * 20L;
    }

    /**
     * Scans and keeps the result. Returns what changed: {@link Change#CLOSED} when it became walled, {@link Change#BROKEN} when it was
     * walled and now isn't (the {@link #leak} says where), else {@link Change#NONE} (and always on the first scan, which only finds out).
     */
    public Change scan(ServerLevel level, BlockPos heart) {
        return scan(level, heart, RADIUS);
    }

    /** {@link #scan} out to {@code radius} (the engine's tests). */
    public Change scan(ServerLevel level, BlockPos heart, int radius) {
        boolean was = walled && everScanned;
        Scan s = walk(level, heart, radius);
        walled = s.walled;
        inside = s.walled ? s.columns : new LongOpenHashSet();
        gates = s.walled ? s.gates : new LongOpenHashSet();
        bounds = s.walled ? box(s.columns, heart.getY()) : null;
        leak = s.leak;
        heartY = heart.getY();
        scannedAt = level.getGameTime();
        dirtyAt = Long.MAX_VALUE;
        boolean first = !everScanned;
        everScanned = true;
        if (first) {
            return Change.NONE;                                  // a loaded settlement finding its wall is no news
        }
        return walled && !was ? Change.CLOSED : was && !walled ? Change.BROKEN : Change.NONE;
    }

    public enum Change { NONE, CLOSED, BROKEN }

    /** How near the wall, outside it, a foe is at the walls: the gates shut and the defenders man the wall (Muster). */
    public static final int AT_THE_WALLS = 4;

    /** The gates the last scan met in the ring (packed BlockPos). */
    public LongOpenHashSet gates() {
        return gates;
    }

    /** The columns inside the walls, as a box (y the heart's, give or take {@value #REACH_Y}); null while not walled. */
    public @Nullable BoundingBox bounds() {
        return walled ? bounds : null;
    }

    /** Whether {@code p} is outside the walls and no more than {@value #AT_THE_WALLS} from a column inside them: a foe there is at the walls. */
    public boolean atWalls(BlockPos p) {
        if (!walled || Math.abs(p.getY() - heartY) > REACH_Y || inside(p)) {
            return false;
        }
        for (int dx = -AT_THE_WALLS; dx <= AT_THE_WALLS; dx++) {
            for (int dz = -AT_THE_WALLS; dz <= AT_THE_WALLS; dz++) {
                if (inside.contains(column(p.getX() + dx, p.getZ() + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every gate of the ring shut ({@code shut}) or opened: a fence gate's or a door's OPEN. Returns how many changed. */
    public int gates(ServerLevel level, boolean shut) {
        int n = 0;
        for (long g : gates) {
            BlockPos p = BlockPos.of(g);
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if ((s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof DoorBlock) && s.hasProperty(BlockStateProperties.OPEN)
                    && s.getValue(BlockStateProperties.OPEN) == shut) {
                if (s.getBlock() instanceof DoorBlock d) {
                    d.setOpen(null, level, s, p, !shut);         // both halves
                } else {
                    level.setBlock(p, s.setValue(BlockStateProperties.OPEN, !shut), Block.UPDATE_CLIENTS);
                }
                n++;
            }
        }
        return n;
    }

    // --- the walls its settlers raised (E3) -------------------------------------------------------------------------------

    /** A wall its settlers raised: its style and its line ({@link WallJob#line}), so its every cell can be worked out again. */
    public record Built(String style, long[] feet, int[] heights, byte[] across, BitSet gates) {
        WallJob.Line line() {
            return new WallJob.Line(feet, heights, across, gates, new long[0], null);
        }
    }

    /** A wall its settlers finished: kept, so what is knocked out of it is patched. */
    public void record(WallJob job) {
        WallJob.Line l = job.line();
        built.add(new Built(job.style().id(), l.feet(), l.heights(), l.across(), l.gates()));
    }

    /** Whether it has anything of its walls to save: a wall raised, or a cell of one a player took out. */
    public boolean recorded() {
        return !built.isEmpty() || !forgot.isEmpty();
    }

    public List<Built> built() {
        return built;
    }

    /** A player broke the block at {@code p}: if it was a wall's, it stays out (their way through), never patched. */
    public void forget(BlockPos p) {
        if (!built.isEmpty()) {
            forgot.add(p.asLong());
        }
    }

    /** What a patch of one of its walls would lay: the wall's style and line, and its cells knocked out. */
    public record Patch(WallStyle style, WallJob.Line line, long[] cells) {
    }

    /**
     * The first of its walls with cells knocked out (by a foe: an explosion, a griefer; not a player, {@link #forget}): a cell that doesn't hold its
     * block and holds nothing but what the ground and its cover are made of (air, water, the land, a plant), loaded. Null when every wall stands.
     */
    public @Nullable Patch missing(ServerLevel level) {
        for (Built b : built) {
            WallStyle style = WallStyle.get(b.style());
            if (style == null) {
                continue;
            }
            it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (WallJob.Laid l : WallJob.courses(style, b.feet(), b.heights(), b.across(), b.gates())) {
                BlockPos p = l.task().pos();
                if (forgot.contains(p.asLong()) || !level.isLoaded(p)) {
                    continue;
                }
                BlockState cur = level.getBlockState(p);
                if (!cur.is(l.task().state().getBlock()) && claude.settlementcore.room.Siting.mayReplace(cur) && level.getBlockEntity(p) == null) {
                    out.add(p.asLong());
                }
            }
            if (!out.isEmpty()) {
                return new Patch(style, b.line(), out.toLongArray());
            }
        }
        return null;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (Built b : built) {
            CompoundTag c = new CompoundTag();
            c.putString("style", b.style());
            c.putLongArray("feet", b.feet());
            c.putIntArray("heights", b.heights());
            c.putByteArray("across", b.across());
            c.putLongArray("gates", b.gates().toLongArray());
            l.add(c);
        }
        t.put("built", l);
        t.putLongArray("forgot", forgot.toLongArray());
        return t;
    }

    public void load(CompoundTag t) {
        built.clear();
        for (Tag x : t.getListOrEmpty("built")) {
            CompoundTag c = (CompoundTag) x;
            long[] feet = c.getLongArray("feet").orElse(new long[0]);
            int[] heights = c.getIntArray("heights").orElse(new int[0]);
            byte[] across = c.getByteArray("across").orElse(new byte[0]);
            if (feet.length > 0 && heights.length == feet.length && across.length == feet.length) {
                built.add(new Built(c.getStringOr("style", ""), feet, heights, across, BitSet.valueOf(c.getLongArray("gates").orElse(new long[0]))));
            }
        }
        forgot.clear();
        for (long k : t.getLongArray("forgot").orElse(new long[0])) {
            forgot.add(k);
        }
    }

    /** What a walk found. */
    public record Scan(boolean walled, LongOpenHashSet columns, @Nullable BlockPos leak, LongOpenHashSet gates) {
    }

    private static BoundingBox box(LongOpenHashSet columns, int y) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long c : columns) {
            int x = (int) (c >> 32), z = (int) c;
            x0 = Math.min(x0, x);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            z1 = Math.max(z1, z);
        }
        return new BoundingBox(x0, y - REACH_Y, z0, x1, y + REACH_Y, z1);
    }

    /** The walk itself, read-only, out to {@value #RADIUS}. */
    public static Scan walk(ServerLevel level, BlockPos heart) {
        return walk(level, heart, RADIUS);
    }

    /** The walk out to {@code radius}: the engine's tests walk a smaller ground (their plot is an island: its edge is a cliff no mob drops off). */
    public static Scan walk(ServerLevel level, BlockPos heart, int radius) {
        BlockPos start = startNear(level, heart);
        if (start == null) {
            return new Scan(false, new LongOpenHashSet(), heart, new LongOpenHashSet());
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        LongOpenHashSet gates = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(column(start.getX(), start.getZ()));
        queue.enqueue(start.asLong());
        int hx = heart.getX(), hz = heart.getZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            BlockPos at = BlockPos.of(queue.dequeueLong());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = at.getX() + d.getStepX(), nz = at.getZ() + d.getStepZ();
                long col = column(nx, nz);
                if (seen.contains(col)) {
                    continue;
                }
                if (Math.abs(nx - hx) > radius || Math.abs(nz - hz) > radius || !level.isLoaded(m.set(nx, at.getY(), nz))) {
                    return new Scan(false, seen, new BlockPos(nx, at.getY(), nz), gates);
                }
                int ny = step(level, at.getX(), at.getY(), at.getZ(), nx, nz, m);
                if (ny == Integer.MIN_VALUE) {
                    for (int dy = 0; dy <= 1; dy++) {           // wall; a gate in it is one of the ring's
                        BlockState s = level.getBlockState(m.set(nx, at.getY() + dy, nz));
                        if (s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof DoorBlock) {
                            gates.add(m.asLong());
                        }
                    }
                    continue;
                }
                seen.add(col);
                if (seen.size() > MAX_COLUMNS) {
                    return new Scan(false, seen, new BlockPos(nx, ny, nz), gates);
                }
                queue.enqueue(BlockPos.asLong(nx, ny, nz));
            }
        }
        return new Scan(true, seen, null, gates);
    }

    /** The cell a mob standing at (x, y, z) reaches in the column (nx, nz): up 1, level, or down to {@value #DROP}; or MIN_VALUE if it can't. */
    static int step(ServerLevel level, int x, int y, int z, int nx, int nz, BlockPos.MutableBlockPos m) {
        if (walkable(level, nx, y + 1, nz, m) && open(level, x, y + 2, z, m)) {
            return y + 1;                                        // a step up, with headroom where it stands
        }
        if (walkable(level, nx, y, nz, m)) {
            return y;
        }
        if (!open(level, nx, y, nz, m) || !open(level, nx, y + 1, nz, m)) {
            return Integer.MIN_VALUE;                            // something in the way at its own height: no drop past it
        }
        for (int dy = 1; dy <= DROP; dy++) {
            if (walkable(level, nx, y - dy, nz, m)) {
                return y - dy;
            }
            if (!open(level, nx, y - dy, nz, m)) {
                return Integer.MIN_VALUE;
            }
        }
        return Integer.MIN_VALUE;                                // a drop too deep: a ditch keeps them out too
    }

    /** Two collision-free cells, no gate or door in them, over a floor (anything with collision, or water). */
    static boolean walkable(ServerLevel level, int x, int y, int z, BlockPos.MutableBlockPos m) {
        if (!open(level, x, y, z, m) || !open(level, x, y + 1, z, m)) {
            return false;
        }
        BlockState below = level.getBlockState(m.set(x, y - 1, z));
        return !below.getCollisionShape(level, m).isEmpty() || !below.getFluidState().isEmpty() || !level.getFluidState(m.set(x, y, z)).isEmpty();
    }

    /** A cell a mob passes through: no collision, and no gate or door (they're wall, open or not). */
    static boolean open(ServerLevel level, int x, int y, int z, BlockPos.MutableBlockPos m) {
        BlockState s = level.getBlockState(m.set(x, y, z));
        if (s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof DoorBlock) {
            return false;
        }
        return s.getCollisionShape(level, m).isEmpty();
    }

    /** A walkable cell beside the heart to start from (the heart is a block: one stands next to it), or null. */
    static @Nullable BlockPos startNear(ServerLevel level, BlockPos heart) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy : new int[]{0, -1, 1, 2, -2}) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos p = heart.relative(d).above(dy);
                if (walkable(level, p.getX(), p.getY(), p.getZ(), m)) {
                    return p;
                }
            }
            if (walkable(level, heart.getX(), heart.getY() + 1 + dy, heart.getZ(), m)) {
                return heart.above(1 + dy);
            }
        }
        return null;
    }

    static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
