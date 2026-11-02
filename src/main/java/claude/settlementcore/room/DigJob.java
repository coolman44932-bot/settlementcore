package claude.settlementcore.room;

import claude.settlementcore.Nbt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One room being gnawed out. The work is the room template as a list of block tasks, ordered
 * outward from the door (and top-down within a distance) so rats always dig from space they
 * can already stand in. Rats claim small batches of neighbouring tasks, walk over, and complete
 * them; a batch a rat abandons expires and is handed out again.
 *
 * <p>Only the room id, where it is and which tasks are finished are saved. The task list is
 * rebuilt from the template in the same order on load.
 */
public class DigJob {

    public record Task(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
    }

    private record Claim(int[] indices, long expires) {
    }

    public final String roomId;
    public final BlockPos door;
    public final Direction facing;
    private BitSet finished = new BitSet();
    /** The fingerprint of the room's template the tasks were made from (RoomTemplates#print); 0 before digs kept one. */
    private int templatePrint;

    private List<Task> tasks;
    private final BitSet claimed = new BitSet();
    private final Map<UUID, Claim> claims = new HashMap<>();
    private int low;
    /** Cells the dig leaves alone (other rooms' open space, the Heart). Worked out from the world
     *  when the dig begins, then saved, so a reload rebuilds the same task list for the saved progress. */
    @Nullable private long[] keepOut;
    @Nullable private java.util.function.BiFunction<ServerLevel, PlanPlacement, long[]> keepOutSource;

    /** Where the keep-out cells come from, if the dig has not begun yet. */
    public void keepOutFrom(java.util.function.BiFunction<ServerLevel, PlanPlacement, long[]> source) {
        this.keepOutSource = source;
    }

    /** The warren the dig walls up for (RoomShoring): its other rooms, and where it has walled up. */
    @Nullable private RoomShoring.Warren warren;
    @Nullable private RoomShoring shoring;
    /** Whether every hole beside the room has been walled up as it was dug (a dig begun before the shoring, or one
     *  beside a chunk that was not loaded, is walled up once the room is finished: RoomShoring#wallUp). */
    private boolean walled = true;
    private boolean shores = true;

    public void walls(RoomShoring.Warren w) {
        this.warren = w;
    }

    private RoomShoring.Warren warren() {
        if (warren == null) {
            warren = RoomShoring.Warren.alone();
        }
        return warren;
    }

    public boolean walled() {
        return walled && (shoring == null || !shoring.deferred);
    }

    /**
     * Its holes left open, as the mod dug before the shoring (2026-09-25): an open-air room (a race's portal pit, open to
     * the sky by design), and the tests. Saved with the dig.
     */
    public DigJob unshored() {
        this.shores = false;
        this.walled = false;
        return this;
    }

    /** Whether the dig walls up the holes beside its room (RoomShoring), or leaves them open ({@link #unshored()}). */
    public boolean shores() {
        return shores;
    }

    public DigJob(String roomId, BlockPos door, Direction facing) {
        this.roomId = roomId;
        this.door = door;
        this.facing = facing;
    }

    /**
     * A dig nobody aimed (the Heart Chamber round a new Heart, the horde's deep levels): it cuts only
     * the ground itself (natural()), never anything a player put there, except inside {@code alsoCut}
     * (the room a way down opens off: its wall is the clan's own to breach).
     */
    public DigJob naturalOnly(@Nullable net.minecraft.world.level.levelgen.structure.BoundingBox alsoCut) {
        this.naturalOnly = true;
        this.alsoCut = alsoCut;
        return this;
    }

    private boolean naturalOnly;
    @Nullable private net.minecraft.world.level.levelgen.structure.BoundingBox alsoCut;

    /**
     * Cover: what grows on or hangs from the ground (flowers, grass, ferns, saplings, vines, moss carpet, roots, dripleaf,
     * azalea leaves, seagrass, kelp). A dig or a winze clears it; a raised building's earthworks clear it off every column
     * they cut or fill (surface-builder.md, section 9). Moved from the Ore Mine's winze (work/MineWork), 2026-09-30.
     */
    public static boolean plant(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockTags.SMALL_FLOWERS)
                || s.getBlock() instanceof net.minecraft.world.level.block.TallFlowerBlock || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || s.is(net.minecraft.tags.BlockTags.CAVE_VINES) || s.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)
                || s.is(net.minecraft.world.level.block.Blocks.TALL_GRASS) || s.is(net.minecraft.world.level.block.Blocks.FERN)
                || s.is(net.minecraft.world.level.block.Blocks.LARGE_FERN) || s.is(net.minecraft.world.level.block.Blocks.DEAD_BUSH)
                || s.is(net.minecraft.world.level.block.Blocks.BROWN_MUSHROOM) || s.is(net.minecraft.world.level.block.Blocks.RED_MUSHROOM)
                || s.is(net.minecraft.world.level.block.Blocks.VINE) || s.is(net.minecraft.world.level.block.Blocks.GLOW_LICHEN)
                || s.is(net.minecraft.world.level.block.Blocks.HANGING_ROOTS) || s.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)
                || s.is(net.minecraft.world.level.block.Blocks.SPORE_BLOSSOM) || s.is(net.minecraft.world.level.block.Blocks.SMALL_DRIPLEAF)
                || s.is(net.minecraft.world.level.block.Blocks.BIG_DRIPLEAF) || s.is(net.minecraft.world.level.block.Blocks.BIG_DRIPLEAF_STEM)
                || s.is(net.minecraft.world.level.block.Blocks.AZALEA_LEAVES) || s.is(net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA_LEAVES)
                || s.is(net.minecraft.world.level.block.Blocks.SEAGRASS) || s.is(net.minecraft.world.level.block.Blocks.TALL_SEAGRASS)
                || s.is(net.minecraft.world.level.block.Blocks.KELP) || s.is(net.minecraft.world.level.block.Blocks.KELP_PLANT);
    }

    /**
     * The ground itself: stone and deepslate, dirt, sand and gravel, ores, clay, dripstone, calcite, the
     * cave's moss and vines and lichen, sculk, amethyst, and air and water and lava. Not cobblestone,
     * planks, bricks or anything else a player (or a dungeon) could have put there; nor blackstone, bone,
     * nylium or soul soil, which the rooms are furnished with (the Realm of Ruin's rock is tuff, deepslate,
     * gravel, mud and dirt, all ground here; its bone and soul-soil patches are left standing).
     */
    public static boolean natural(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty()
                || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.DIRT)
                || s.is(net.minecraft.tags.BlockTags.SAND) || s.is(net.neoforged.neoforge.common.Tags.Blocks.GRAVELS)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || s.is(net.minecraft.tags.BlockTags.CAVE_VINES)
                || s.is(net.minecraft.tags.BlockTags.SCULK_REPLACEABLE) || s.is(net.minecraft.tags.BlockTags.MOSS_REPLACEABLE)
                || s.is(net.minecraft.world.level.block.Blocks.CLAY) || s.is(net.minecraft.world.level.block.Blocks.CALCITE)
                || s.is(net.minecraft.world.level.block.Blocks.DRIPSTONE_BLOCK) || s.is(net.minecraft.world.level.block.Blocks.POINTED_DRIPSTONE)
                || s.is(net.minecraft.world.level.block.Blocks.SMOOTH_BASALT) || s.is(net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK)
                || s.is(net.minecraft.world.level.block.Blocks.BUDDING_AMETHYST) || s.is(net.minecraft.world.level.block.Blocks.AMETHYST_CLUSTER)
                || s.is(net.minecraft.world.level.block.Blocks.GLOW_LICHEN) || s.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)
                || s.is(net.minecraft.world.level.block.Blocks.HANGING_ROOTS) || s.is(net.minecraft.world.level.block.Blocks.SCULK)
                || s.is(net.minecraft.world.level.block.Blocks.SCULK_VEIN) || s.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                || s.is(net.minecraft.world.level.block.Blocks.COBWEB) || s.is(net.minecraft.world.level.block.Blocks.INFESTED_STONE)
                || s.is(net.minecraft.world.level.block.Blocks.INFESTED_DEEPSLATE);
    }

    /**
     * The horde's gnawing, not a rat's: the next {@code max} tasks in dig order put in place at once,
     * stopping at the first whose chunk is not loaded (the horde waits there). A few of the cells that
     * gave way go into {@code sample}, for the dust and the noise. Returns how many were done.
     */
    public int gnawInOrder(ServerLevel level, int max, List<BlockPos> sample) {
        ensureLoaded(level);
        return gnawInOrder(level, max, sample, tasks.size());
    }

    /**
     * {@link #gnawInOrder(ServerLevel, int, List)} over the tasks before {@code end} only: a job whose first phase the settlement
     * does itself (a raised building's clearing) and whose later ones its settlers work.
     */
    protected int gnawInOrder(ServerLevel level, int max, List<BlockPos> sample, int end) {
        ensureLoaded(level);
        int n = 0;
        for (int i = finished.nextClearBit(0); i < Math.min(end, tasks.size()) && n < max; i = finished.nextClearBit(i + 1)) {
            Task t = tasks.get(i);
            if (level.isOutsideBuildHeight(t.pos())) {
                finished.set(i);                                 // over the sky's limit or under the world: nothing to gnaw there, ever
                continue;
            }
            if (!level.isLoaded(t.pos())) {
                break;
            }
            BlockState was = level.getBlockState(t.pos());
            if (!put(level, i, t)) {
                break;                                           // not yet (a kind of job's own rule): the horde waits there too
            }
            finished.set(i);
            n++;
            gnawed++;
            if (!was.isAir() && sample.size() < 4 && (n % 17 == 1)) {
                sample.add(t.pos());
            }
        }
        return n;
    }

    /**
     * {@link #gnawInOrder(ServerLevel, int, List, int)} for a clearing its settlers' neighbours can see: the tasks before {@code end} in order, until
     * {@code maxChanges} of them have taken a block out (a block that was there and is gone: a cell already as it should be, air over air, is done free
     * and never counts, and neither does a player's block left standing), so a tree comes down over a second or two and not in a tick. Returns what gave
     * way, for the dust and the noise ({@link Crumb}); stops, as the other does, at the first cell whose chunk isn't loaded or that its kind of job
     * won't put yet.
     */
    protected List<Crumb> gnawChanges(ServerLevel level, int maxChanges, int end) {
        ensureLoaded(level);
        List<Crumb> out = new ArrayList<>();
        for (int i = finished.nextClearBit(0); i < Math.min(end, tasks.size()) && out.size() < maxChanges; i = finished.nextClearBit(i + 1)) {
            Task t = tasks.get(i);
            if (level.isOutsideBuildHeight(t.pos())) {
                finished.set(i);                                 // over the sky's limit or under the world: nothing to gnaw there, ever
                continue;
            }
            if (!level.isLoaded(t.pos())) {
                break;
            }
            BlockState was = level.getBlockState(t.pos());
            if (!put(level, i, t)) {
                break;                                           // not yet (a kind of job's own rule)
            }
            finished.set(i);
            gnawed++;
            if (!was.isAir() && level.getBlockState(t.pos()) != was) {
                out.add(new Crumb(t.pos(), was));
            }
        }
        return out;
    }

    public PlanPlacement placement() {
        if (placed == null) {
            placed = PlanPlacement.compute(Rooms.get(roomId), door, facing);   // asked often (is this in the warren?)
        }
        return placed;
    }

    @Nullable private PlanPlacement placed;

    public void ensureLoaded(ServerLevel level) {
        if (tasks != null) {
            return;
        }
        PlanPlacement pl = placement();
        if (keepOut == null) {
            keepOut = keepOutSource == null ? new long[0] : keepOutSource.apply(level, pl);
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keep = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(keepOut);
        tasks = buildTasks(level, pl, keep);
        for (Task t : tasks) {
            taskCells.add(t.pos().asLong());
        }
        // what is open beside the room (a cave, a drop, the sky; water and loose gravel) is walled up as it is dug
        // (a job left open, an open-air pit or a raised building, walls nothing up: its shoring is never worked out)
        shoring = shores ? RoomShoring.of(level, pl, RoomTemplates.get(level, roomId), keep, warren()) : null;
        // the room was changed since this dig began (a new version of the mod: the rooms' warpstone lamps,
        // 2026-09-25): its tasks are not the ones the saved progress counted, so what is done is read off the world
        int print = RoomTemplates.print(level, roomId);
        if (finished.cardinality() > 0 && templatePrint != print) {
            BitSet done = new BitSet();
            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                if (level.isLoaded(t.pos()) && level.getBlockState(t.pos()).is(t.state().getBlock())) {
                    done.set(i);
                }
            }
            finished = done;
            walled = false;                     // what was done is not walled round: the room is, when it is finished
        }
        templatePrint = print;
    }

    /**
     * The job's task list, in the order it is worked: rebuilt the same way on load, so the saved progress bits mean the same
     * cells. A dug room is its template's cells, outward from the door ({@link #order}), less the keep-out's; a raised
     * building (surface-builder.md) puts its earthworks and courses in its own order.
     */
    protected List<Task> buildTasks(ServerLevel level, PlanPlacement pl, it.unimi.dsi.fastutil.longs.LongSet keep) {
        BlockPos doorLocal = new BlockPos(pl.room().doorX(), pl.room().doorY(), pl.room().doorZ());
        List<RoomTemplates.Entry> entries = new ArrayList<>(RoomTemplates.get(level, roomId));
        entries.sort(order(doorLocal));
        List<Task> out = new ArrayList<>(entries.size());
        for (RoomTemplates.Entry e : entries) {
            BlockPos w = pl.world(e.local());
            if (keep.contains(w.asLong())) {
                continue;                       // another room's open space: leave it, the rooms join here
            }
            out.add(new Task(w, e.state().rotate(pl.rotation()), e.nbt()));
        }
        return out;
    }

    /** A dug room's order: outward from the door, top-down within a distance, so rats always dig from space they can stand in. */
    protected Comparator<RoomTemplates.Entry> order(BlockPos doorLocal) {
        return Comparator.<RoomTemplates.Entry>comparingInt(e -> e.local().distManhattan(doorLocal)).thenComparingInt(e -> -e.local().getY());
    }

    /** The tasks, once built ({@link #ensureLoaded}); null before. */
    @Nullable
    protected List<Task> tasks() {
        return tasks;
    }

    /** Whether task {@code i} is done. */
    protected boolean finished(int i) {
        return finished.get(i);
    }

    /**
     * Whether an open task may be handed out now. Every task of a dug room may; a raised building's cell only once there is
     * something under it to hold it (surface-builder.md, section 2).
     */
    protected boolean claimable(ServerLevel level, int i, Task t) {
        return true;
    }

    /** Added to a task's score as a batch's anchor (lower is sooner): a raised building's upper courses wait for the lower. */
    protected double anchorBias(int i, Task t) {
        return 0;
    }

    /**
     * Where a claim stops looking for tasks (an index past the last it may hand out): the whole list for a dug room; a raised
     * building's current phase, or its courses in reach, so a claim never walks the rest of a big building's list.
     */
    protected int claimEnd() {
        return tasks.size();
    }

    /**
     * Whether a solid cell the task makes another solid block is done for free as the claim passes it (a dug room's walls
     * refaced as the front goes by: invisible work in the rock). A raised building's are laid by its settlers, in the open.
     */
    protected boolean refacesFree() {
        return true;
    }

    /**
     * Whether the claim may go the old way under {@code -Pperfflags=rowclaim} (the A/B loop), which never asks
     * {@link #claimable} or {@link #anchorBias}: a raised building's claims always go through them.
     */
    protected boolean rowClaim() {
        return PerfFlags.on("rowclaim");
    }

    /** Task {@code i} done without a block put (a kind of job that leaves a player's block standing, or heaves one in). */
    protected final void markDone(int i) {
        finished.set(i);
    }

    /** The tasks {@code rat}'s batch holds, the one it stands to first; null if it holds none. */
    @Nullable
    protected final int[] claimOf(UUID rat) {
        Claim c = claims.get(rat);
        return c == null ? null : c.indices().clone();
    }

    /** How many batches are out at {@code now} (the expired ones let go first). */
    protected final int claimCount(long now) {
        expire(now);
        return claims.size();
    }

    /** Whether task {@code i} is in a batch someone holds. */
    protected final boolean isClaimed(int i) {
        return claimed.get(i);
    }

    /** How many tasks are done. */
    public int finishedCount() {
        return finished.cardinality();
    }

    public boolean isDone() {
        return tasks != null && finished.cardinality() >= tasks.size();
    }

    public float progress() {
        return tasks == null || tasks.isEmpty() ? 0f : finished.cardinality() / (float) tasks.size();
    }

    /**
     * Hands a rat its next batch of up to {@code batch} tasks and returns where to go, or null
     * when nothing is left to hand out. Tasks the world already satisfies, and invisible
     * rock-to-rock refacing, are ticked off for free on the way.
     *
     * <p>Dig order: outward from the door. A batch is a place at the face near the rat and away from the other
     * rats ({@link #claimAtFace}). Measured 2026-09-25 (-Pperf): 80 slaves dig the Breeding Pens in 420 ticks
     * against 1320 for the old way (the first open task in dig order plus open tasks up to 400 further along within
     * 3 blocks of it, still here behind -Pperfflags=rowclaim), and 12 slaves have them done by tick 2000 against 79%.
     * (The 2026-09-23 finding that nearest-to-the-rat batches were smaller and slower was measured with half the
     * rats never digging, and with batch members taken only after the anchor in dig order.)
     */
    @Nullable
    public BlockPos claim(ServerLevel level, UUID rat, int batch, long now, BlockPos near) {
        ensureLoaded(level);
        expire(now);
        Claim existing = claims.get(rat);
        if (existing != null) {
            return tasks.get(existing.indices()[0]).pos();
        }
        while (low < tasks.size() && finished.get(low)) {
            low++;
        }
        if (!rowClaim()) {
            return claimAtFace(level, rat, batch, now, near);   // (-Pperfflags=rowclaim: the old way, for A/B)
        }
        int[] picked = new int[batch];
        int m = 0;
        BlockPos anchor = null;
        int freebies = 0;
        boolean wantExposed = PerfFlags.on("exposed");
        for (int i = low; i < tasks.size() && m < batch; i++) {
            if (finished.get(i) || claimed.get(i)) {
                continue;
            }
            Task t = tasks.get(i);
            BlockState cur = level.getBlockState(t.pos());
            if (cur.equals(t.state()) && t.nbt() == null) {
                finished.set(i);
                if (shores && RoomShoring.open(t.state())) {
                    shoring.around(level, t.pos());      // already open (a cave): walled round all the same
                }
                if (++freebies > 4096) {
                    break;
                }
                continue;
            }
            // Re-facing rock with rock (stone -> the room's lining) is invisible work: the walls
            // are shaped as the dig front passes. Rats only spend time carving and furnishing.
            if (!cur.isAir() && cur.getFluidState().isEmpty() && !t.state().isAir() && t.nbt() == null
                    && cur.isSolid() && t.state().isSolid()) {
                if (put(level, i, t)) {
                    finished.set(i);
                    freeApplied++;
                }
                if (++freebies > 512) {
                    break;
                }
                continue;
            }
            if (anchor == null) {
                if (wantExposed && i - low < 96 && !exposed(level, t.pos())) {
                    continue;
                }
                anchor = t.pos();
            } else if (t.pos().distManhattan(anchor) > 3) {
                continue;
            }
            picked[m++] = i;
            if (i - low > 400) {
                break;
            }
        }
        if (m == 0) {
            return null;
        }
        int[] idx = java.util.Arrays.copyOf(picked, m);
        for (int i : idx) {
            claimed.set(i);
        }
        claims.put(rat, new Claim(idx, now + 600));
        return anchor;
    }

    /** How many open tasks past the dig's front a claim looks over for a place at the face. */
    private static final int WINDOW = 600;
    /** Other rats' batches this near a place at the face count against it (the horde spreads along the face). */
    private static final int CROWD = 3;

    /**
     * A batch at the face (user, 2026-09-25: "they crowd around but take turns digging slowing them down"): of the
     * next {@link #WINDOW} open tasks in dig order, the anchor is one the rat can get at now (open beside it), near
     * the rat and away from the other rats' batches; failing that, the nearest one a layer in. The batch is the
     * anchor and the open tasks nearest round it. Handing out the front of the dig order instead sent most of a big
     * horde to batches round the far side of the front, or deep in the rock, where they waited out the 5 s squeeze
     * with their tasks locked (measured: 80 slaves, 80% of the time waiting, 2-8% gnawing within reach).
     */
    @Nullable
    private BlockPos claimAtFace(ServerLevel level, UUID rat, int batch, long now, BlockPos near) {
        it.unimi.dsi.fastutil.ints.IntArrayList cand = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int freebies = 0;
        int end = Math.min(tasks.size(), claimEnd());
        boolean reface = refacesFree();
        for (int i = low; i < end && cand.size() < WINDOW; i++) {
            if (finished.get(i) || claimed.get(i)) {
                continue;
            }
            Task t = tasks.get(i);
            if (level.isOutsideBuildHeight(t.pos())) {
                finished.set(i);                // over the sky's limit or under the world (a room planned near the top): no block
                continue;                       // goes there, ever, and the dig must still finish (isLoaded is false there too)
            }
            if (!level.isLoaded(t.pos())) {
                continue;                       // never loaded for a look: a wide dig's far side waits for its chunk
            }
            BlockState cur = level.getBlockState(t.pos());
            if (cur.equals(t.state()) && t.nbt() == null) {
                finished.set(i);
                if (shores && RoomShoring.open(t.state())) {
                    shoring.around(level, t.pos());
                }
                if (++freebies > 4096) {
                    break;
                }
                continue;
            }
            if (reface && !cur.isAir() && cur.getFluidState().isEmpty() && !t.state().isAir() && t.nbt() == null
                    && cur.isSolid() && t.state().isSolid()) {
                if (put(level, i, t)) {
                    finished.set(i);
                    freeApplied++;
                }
                if (++freebies > 512) {
                    break;
                }
                continue;
            }
            if (!claimable(level, i, t)) {
                continue;
            }
            cand.add(i);
        }
        if (cand.isEmpty()) {
            return null;
        }
        List<BlockPos> others = new ArrayList<>(claims.size());
        for (Claim c : claims.values()) {
            others.add(tasks.get(c.indices()[0]).pos());
        }
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int k = 0; k < cand.size(); k++) {
            int ti = cand.getInt(k);
            BlockPos p = tasks.get(ti).pos();
            double score = Math.sqrt(p.distSqr(near)) + k * 0.01 + (exposed(level, p) ? 0 : 4) + anchorBias(ti, tasks.get(ti));
            for (BlockPos o : others) {
                int d = o.distManhattan(p);
                if (d <= CROWD) {
                    score += (CROWD + 1 - d) * 2;
                }
            }
            if (score < bestScore) {
                bestScore = score;
                best = k;
            }
        }
        BlockPos anchor = tasks.get(cand.getInt(best)).pos();
        it.unimi.dsi.fastutil.ints.IntArrayList mine = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int k = 0; k < cand.size(); k++) {
            if (tasks.get(cand.getInt(k)).pos().distManhattan(anchor) <= 3) {
                mine.add(cand.getInt(k));
            }
        }
        mine.sort((a, b) -> Integer.compare(tasks.get(a).pos().distManhattan(anchor), tasks.get(b).pos().distManhattan(anchor)));
        int[] idx = mine.subList(0, Math.min(batch, mine.size())).toIntArray();
        java.util.Arrays.sort(idx);
        // the anchor first: the rat stands to it, and gnaws it first
        int a = cand.getInt(best);
        for (int j = 0; j < idx.length; j++) {
            if (idx[j] == a) {
                System.arraycopy(idx, 0, idx, 1, j);
                idx[0] = a;
                break;
            }
        }
        for (int i : idx) {
            claimed.set(i);
        }
        claims.put(rat, new Claim(idx, now + 600));
        return anchor;
    }

    /** How many tasks the job has (built when it is loaded; 0 before). */
    public int taskCount() {
        return tasks == null ? 0 : tasks.size();
    }

    /** How many cells {@code rat}'s batch holds (0 if none). */
    public int heldCount(UUID rat) {
        int[] c = claimOf(rat);
        return c == null ? 0 : c.length;
    }

    /** Whether {@code rat} holds a batch of this dig. */
    public boolean holds(UUID rat) {
        return claims.containsKey(rat);
    }

    /** Benchmark counters: blocks rats actually gnawed, and blocks refaced for free. */
    public long gnawed;
    public long freeApplied;

    private static boolean exposed(ServerLevel level, BlockPos p) {
        if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
            return true;                        // furnishing an open cell
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos q = p.relative(d);
            // a look never loads a chunk; only a neighbour across a chunk's edge can be in one not loaded (the claim's loop asks
            // this of hundreds of cells, so the lookup is spared for the rest)
            boolean sameChunk = (q.getX() >> 4) == (p.getX() >> 4) && (q.getZ() >> 4) == (p.getZ() >> 4);
            if ((sameChunk || level.isLoaded(q)) && level.getBlockState(q).getCollisionShape(level, q).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Claim from the front of the dig order (tests, and anything without a position). */
    @Nullable
    public BlockPos claim(ServerLevel level, UUID rat, int batch, long now) {
        ensureLoaded(level);
        return claim(level, rat, batch, now, door);
    }

    /** A block that just gave way under a rat's teeth: where, and what it was. */
    public record Crumb(BlockPos pos, BlockState was) {
    }

    /**
     * Part-way through a batch: put the first {@code fraction} of the rat's claimed blocks in place
     * now, so the rock crumbles bite by bite instead of all at once. Returns what gave way.
     */
    public List<Crumb> gnawSome(ServerLevel level, UUID rat, float fraction) {
        Claim c = claims.get(rat);
        if (c == null) {
            return List.of();
        }
        int n = Math.min(c.indices().length, (int) Math.ceil(c.indices().length * Math.min(1f, fraction)));
        List<Crumb> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = c.indices()[k];
            if (!finished.get(i)) {
                Task t = tasks.get(i);
                BlockState was = level.getBlockState(t.pos());
                if (put(level, i, t)) {
                    out.add(new Crumb(t.pos(), was));
                    finished.set(i);
                    gnawed++;
                }
            }
        }
        return out;
    }

    /**
     * The rat has gnawed its batch: put each block in place. Returns how many were done. A cell a kind of job won't put yet
     * ({@link #put}) is left open, and handed out again.
     */
    public int complete(ServerLevel level, UUID rat) {
        Claim c = claims.remove(rat);
        if (c == null) {
            return 0;
        }
        for (int i : c.indices()) {
            claimed.clear(i);
            if (!finished.get(i) && put(level, i, tasks.get(i))) {
                finished.set(i);
                gnawed++;
            }
        }
        return c.indices().length;
    }

    public void release(UUID rat) {
        Claim c = claims.remove(rat);
        if (c != null) {
            for (int i : c.indices()) {
                claimed.clear(i);
            }
        }
    }

    /** Creative shortcut, and what a finished job guarantees: everything in place at once. */
    public void finishAll(ServerLevel level) {
        ensureLoaded(level);
        for (int i = 0; i < tasks.size(); i++) {
            if (!finished.get(i) && put(level, i, tasks.get(i))) {
                finished.set(i);
            }
        }
        claims.clear();
        claimed.clear();
        joinUp(level);
    }

    /**
     * Join the finished room's fences, walls, bars and stairs to what is round them. The dig puts every block without
     * its neighbours' shape updates (its template states are placed as they are), so a fence came out a loose post
     * with gaps a chicken, or a player off a gallery, slipped through (user, 2026-09-25: "the pens don't work").
     */
    public void joinUp(ServerLevel level) {
        RoomRelight.joinUp(level, placement(), RoomTemplates.get(level, roomId));
    }

    private void expire(long now) {
        claims.entrySet().removeIf(e -> {
            if (e.getValue().expires() < now) {
                for (int i : e.getValue().indices()) {
                    claimed.clear(i);
                }
                return true;
            }
            return false;
        });
    }

    /**
     * Where a real world pushes back on a dig. Anything gnawed out that is worth having (ores, anything
     * with an inventory) goes to the Heart as spoils first; bedrock and other unbreakable blocks stay
     * where they are; and a cell carved open next to water, lava or loose gravel/sand outside the room
     * gets that neighbour walled up with the room's lining, so the warren neither floods nor caves in.
     *
     * <p>Returns whether task {@code i} is done (a cell left as it is, bedrock or a player's block, is done too); false only
     * from a kind of job whose cell can't go in yet (a raised building's block with nothing under it), which is left open.
     */
    protected boolean put(ServerLevel level, int i, Task t) {
        BlockState was = level.getBlockState(t.pos());
        if (was.getDestroySpeed(level, t.pos()) < 0) {
            return true;                                         // bedrock, barriers: no rat gnaws those
        }
        boolean ours = warren().shored(t.pos().asLong());
        if (naturalOnly && !natural(was) && !ours && (alsoCut == null || !alsoCut.isInside(t.pos()))) {
            return true;                                         // someone put that there: nobody aimed this dig at it
        }
        if (ours) {
            warren().unshore(t.pos().asLong());                  // the warren's own walling, dug through: this room's now
        }
        BlockEntity be = level.getBlockEntity(t.pos());
        if (spoils != null && !was.isAir() && (was.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || be != null
                || was.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) && !t.state().is(was.getBlock()))) {   // rock cut away: sifted
            spoils.take(level, t.pos(), was, be);
            if (be instanceof net.minecraft.world.Container c) {
                c.clearContent();                                // its contents went to the Heart, not onto the floor
            }
        }
        apply(level, t, this::cellTaken);
        if (shores && RoomShoring.open(t.state())) {
            shoring.around(level, t.pos());                      // a cave, a drop, water, loose gravel beside it: walled up
        }
        return true;
    }

    /** Takes what a dig turns up (WarrenHeartBlockEntity#takeSpoils). */
    public interface Spoils {
        void take(ServerLevel level, BlockPos pos, BlockState was, @Nullable BlockEntity be);
    }

    @Nullable private Spoils spoils;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet taskCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    public void spoilsTo(Spoils sink) {
        this.spoils = sink;
    }

    /** One cell of a room put in place as a dig puts it (its shape as the template has it, its block entity's data). */
    public static void place(ServerLevel level, Task t) {
        apply(level, t);
    }

    private static void apply(ServerLevel level, Task t) {
        apply(level, t, null);
    }

    /**
     * Whether {@code p} is a cell this job is still to put a block in or out of, which nobody who must be set down clear of the block going in
     * is set down in while another spot will do (Clearance#before): none for a dug room, whose rats work inside the rock.
     */
    protected boolean cellTaken(BlockPos p) {
        return false;
    }

    private static void apply(ServerLevel level, Task t, @Nullable java.util.function.Predicate<BlockPos> taken) {
        Clearance.before(level, t.pos(), t.state(), taken);      // a wall over a cave's open cell is no wall over a rat at the face
        // template states are already shaped (stairs, fences, bars), so skip neighbour shape updates
        level.setBlock(t.pos(), t.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (t.nbt() != null) {
            BlockEntity be = level.getBlockEntity(t.pos());
            if (be != null) {
                CompoundTag tag = t.nbt().copy();
                tag.putInt("x", t.pos().getX());
                tag.putInt("y", t.pos().getY());
                tag.putInt("z", t.pos().getZ());
                be.loadWithComponents(Nbt.input(tag, level.registryAccess()));
                be.setChanged();
            }
        }
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("room", roomId);
        t.put("door", Nbt.pos(door));
        t.putString("facing", facing.getSerializedName());
        t.putLongArray("finished", finished.toLongArray());
        t.putInt("template", templatePrint);
        t.putBoolean("walled", walled());
        if (!shores) {
            t.putBoolean("unshored", true);
        }
        if (keepOut != null) {
            t.putLongArray("keep_out", keepOut);
        }
        if (naturalOnly) {
            t.putBoolean("natural_only", true);
            if (alsoCut != null) {
                t.putIntArray("also_cut", new int[]{alsoCut.minX(), alsoCut.minY(), alsoCut.minZ(), alsoCut.maxX(), alsoCut.maxY(), alsoCut.maxZ()});
            }
        }
        saveMore(t);
        return t;
    }

    /** A kind of job's own keys, beside a dig's (a raised building's earthworks); nothing for a dug room. */
    protected void saveMore(CompoundTag t) {
    }

    /** Its own keys back ({@link #saveMore}), before the tasks are built: called by {@link #readSaved}. */
    protected void loadMore(CompoundTag t) {
    }

    public static DigJob load(CompoundTag t) {
        return new DigJob(t.getStringOr("room", ""), savedDoor(t), savedFacing(t)).readSaved(t);
    }

    /** The door a saved job was dug from: a kind of job's own load makes itself with it ({@link #readSaved}). */
    protected static BlockPos savedDoor(CompoundTag t) {
        return Nbt.readPos(t, "door").orElse(BlockPos.ZERO);
    }

    protected static Direction savedFacing(CompoundTag t) {
        Direction facing = Direction.byName(t.getStringOr("facing", ""));
        return facing == null ? Direction.EAST : facing;
    }

    /**
     * A dig's keys back into this job, then its kind's own ({@link #loadMore}). A kind of job loads as
     * {@code new RaiseJob(t.getString("room"), savedDoor(t), savedFacing(t)).readSaved(t)}.
     */
    protected final <J extends DigJob> J readSaved(CompoundTag t) {
        finished = BitSet.valueOf(t.getLongArray("finished").orElse(new long[0]));
        templatePrint = t.getIntOr("template", 0);
        walled = t.getBooleanOr("walled", false) || finished.isEmpty();   // begun before the shoring: walled up when finished
        if (t.contains("keep_out")) {
            keepOut = t.getLongArray("keep_out").orElse(new long[0]);
        } else if (finished.cardinality() > 0) {
            keepOut = new long[0];          // begun before keep-out existed: keep its task list as it was
        }
        if (t.getBooleanOr("unshored", false)) {
            unshored();
        }
        if (t.getBooleanOr("natural_only", false)) {
            int[] b = t.getIntArray("also_cut").orElse(new int[0]);
            naturalOnly(b.length == 6 ? new net.minecraft.world.level.levelgen.structure.BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]) : null);
        }
        loadMore(t);
        @SuppressWarnings("unchecked")
        J self = (J) this;
        return self;
    }
}
