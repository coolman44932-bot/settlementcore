package claude.settlementcore.work;

import claude.settlementcore.room.DigJob;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.unit.SettlerMob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A room being dug out by a settlement's workers (the 26.1 engine's dig side; on 1.21.1 the Skaven's rats dug their own rooms, the
 * Warren Heart's queue and the units' dig goal): its {@link DigJob} (the template's cells in dig order, outward from the door and
 * top-down, so a digger always works from space it can already stand in) and the workers who dig it, crewed out of the reserve by
 * {@code workerType} and running the engine's CrewGoal as any room's crew do. The Works keep it beside the rooms and the raised
 * sites, at its door's key, until every cell is done; then it is handed over (Works#finishDigs) as the settlement's building.
 *
 * <p>Each digger's chore is a batch of up to {@value #BATCH} cells claimed near where it stands ({@link DigJob#claim}), dug bite by
 * bite on its swings, about {@value #TICKS_PER_BLOCK} ticks a cell over its tempo; the cells the template furnishes (its walls,
 * pillars, lamps, tables) go in the same way. It wants {@value #CREW_MAX} diggers at most, one per {@value #CELLS_PER_DIGGER} cells
 * still to do. With no digger to come (an empty reserve), or the crew getting nowhere for {@value #STALL_SECONDS} s (a cell nobody
 * can stand to), the settlement digs on itself, {@value #SELF_PER_SECOND} cells a second in dig order, so a dig never stalls for good.
 */
public final class DigWork extends RoomWork {

    public static final int CREW_MAX = 6;
    public static final int CELLS_PER_DIGGER = 40;
    public static final int BATCH = 4;
    public static final int TICKS_PER_BLOCK = 5;
    public static final int SELF_PER_SECOND = 4;
    public static final int STALL_SECONDS = 10;
    /** How far across from the work a digger's stand may be. */
    static final int STAND_ACROSS = 3;

    /** A digger's claim: the chore for its {@code serial}th batch. */
    public record DigClaim(UUID rat, int serial) {
    }

    private final DigJob job;
    private final Map<UUID, Integer> serials = new HashMap<>();
    private int serial;
    private int seen = -1;
    private int still;

    public DigWork(Settlement settlement, DigJob job) {
        super(settlement, new Building(job.roomId, job.placement().box(), job.door, job.facing), job.placement());
        this.job = job;
        job.spoilsTo(settlement::takeSpoils);
        job.keepOutFrom(settlement::keepOutCells);
        job.walls(settlement);
    }

    public DigJob job() {
        return job;
    }

    /** Whether every cell is done: the Works hand it over. */
    public boolean dug() {
        return job.isDone();
    }

    @Override
    public void opened(ServerLevel level) {
        job.ensureLoaded(level);
    }

    @Override
    public int crewWanted() {
        if (job.isDone()) {
            return 0;
        }
        int open = Math.max(0, job.taskCount() - job.finishedCount());
        return Math.max(1, Math.min(CREW_MAX, (open + CELLS_PER_DIGGER - 1) / CELLS_PER_DIGGER));
    }

    /** A digger comes up outside the door, in the space the room opens off, or the nearest cell to stand in there. */
    @Override
    @Nullable
    public BlockPos comeUpAt(ServerLevel level) {
        BlockPos out = job.door.relative(job.facing.getOpposite());
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy : new int[]{0, -1, 1, -2, 2}) {
                        BlockPos p = out.offset(dx, dy, dz);
                        if (level.isLoaded(p) && SettlerMob.standable(level, p) && !box().isInside(p)) {
                            return p;
                        }
                    }
                }
            }
        }
        return Works.standable(level, this);
    }

    /** A digger with nothing to do waits where it stands: the room is rock still to be dug. */
    @Override
    public boolean millsAbout() {
        return false;
    }

    @Override
    public boolean reachInside(BlockPos p) {
        return box().inflatedBy(4).isInside(p);
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        job.ensureLoaded(level);
        if (job.isDone()) {
            return;
        }
        int done = job.finishedCount();
        still = done == seen ? still + 1 : 0;
        seen = done;
        if (crewHere == 0 && heart.reserveWorkers() <= 0 || crewHere > 0 && still >= STALL_SECONDS) {
            List<BlockPos> sample = new ArrayList<>();
            job.gnawInOrder(level, SELF_PER_SECOND, sample);    // nobody to dig it, or nobody getting on: the settlement digs on
            for (BlockPos p : sample) {
                level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, p, Block.getId(level.getBlockState(p)));
            }
        }
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        job.ensureLoaded(level);
        if (job.isDone()) {
            return null;
        }
        UUID id = rat.getUUID();
        BlockPos anchor = job.claim(level, id, BATCH, level.getGameTime(), rat.blockPosition());
        if (anchor == null) {
            return null;
        }
        int n = Math.max(1, job.heldCount(id));
        int work = Math.max(4, Math.round(TICKS_PER_BLOCK * n / rat.tempo()));
        BlockPos stand = standFor(level, anchor);
        double reach = 2.6;
        if (stand != null) {
            double dx = stand.getX() - anchor.getX();
            double dz = stand.getZ() - anchor.getZ();
            reach = Math.max(1.5, Math.sqrt(dx * dx + dz * dz) + 0.8);
        }
        DigClaim key = new DigClaim(id, ++serial);
        serials.put(id, key.serial());
        Chore c = new Chore("digging out the " + room().displayName(), anchor, reach, work, key, (l, r) -> {
            effects(l, job.gnawSome(l, r.getUUID(), 1f));
            job.complete(l, r.getUUID());
            return nextChore(l, r);
        }).swinging((l, r, fraction) -> effects(l, job.gnawSome(l, r.getUUID(), fraction)));
        return stand == null ? c : c.standing(stand).lifted();
    }

    /** How far down from the work a digger's stand is looked for: a hall's whole height. */
    static final int STAND_DOWN = 16;

    /**
     * Where a digger stands to dig the cell {@code at}: the cell one can stand in nearest under it, in its column or within
     * {@value #STAND_ACROSS} across, down to {@value #STAND_DOWN} below. A dig goes top-down within each distance from the door, so a
     * tall hall's upper cells are dug before the floor under them: the digger stands on the floor already dug nearer the door and reaches
     * up (the chore is lifted: CrewGoal's 3-up reach waived), as the raise side lays a tall wall from its foot. Null when there is none.
     */
    @Nullable
    private BlockPos standFor(ServerLevel level, BlockPos at) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -STAND_ACROSS; dx <= STAND_ACROSS; dx++) {
            for (int dz = -STAND_ACROSS; dz <= STAND_ACROSS; dz++) {
                int across = dx * dx + dz * dz;
                if (across > STAND_ACROSS * STAND_ACROSS) {
                    continue;
                }
                for (int dy = 1; dy >= -STAND_DOWN; dy--) {
                    p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!level.isLoaded(p)) {
                        break;
                    }
                    if (SettlerMob.standable(level, p)) {
                        double score = Math.sqrt(across) * 2 + Math.abs(dy) * 0.25;
                        if (score < bestScore) {
                            bestScore = score;
                            best = p.immutable();
                        }
                        break;                                   // the highest floor in this column
                    }
                }
            }
        }
        return best;
    }

    /** The cells that gave way on a swing: a cut's dust, a furnishing's sound and a puff of it. */
    private static void effects(ServerLevel level, List<DigJob.Crumb> crumbs) {
        for (DigJob.Crumb c : crumbs) {
            BlockPos p = c.pos();
            BlockState now = level.getBlockState(p);
            if (now == c.was()) {
                continue;
            }
            if (now.isAir()) {
                if (!c.was().isAir() && c.was().getFluidState().isEmpty()) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, p, Block.getId(c.was()));
                }
                continue;
            }
            SoundType st = now.getSoundType();
            level.playSound(null, p, st.getPlaceSound(), SoundSource.BLOCKS, (st.getVolume() + 1f) / 2f, st.getPitch() * 0.8f);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, now), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.02);
        }
    }

    // --- diggers' claims: keyed by batch, held by the job --------------------------------------------------------------

    private boolean current(DigClaim c) {
        Integer s = serials.get(c.rat());
        return s != null && s == c.serial();
    }

    @Override
    public void claim(@Nullable Object key, long now) {
    }

    @Override
    public void release(@Nullable Object key) {
        if (key instanceof DigClaim c && current(c)) {
            job.release(c.rat());
        }
    }

    @Override
    public void shelve(@Nullable Object key, long now) {
        release(key);                                            // the batch goes back; the stall rule digs on if nobody can reach it
    }

    /** A digger is gone: whatever batch it held goes back. */
    public void let(UUID id) {
        serials.remove(id);
        job.release(id);
    }

    // --- what it shows -------------------------------------------------------------------------------------------------

    @Override
    public Gauge gauge(ServerLevel level) {
        job.ensureLoaded(level);
        return new Gauge(job.progress(), "digging");
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        job.ensureLoaded(level);
        out.add(Component.literal(room().displayName() + " (being dug): " + Math.round(job.progress() * 100) + "% dug").withStyle(ChatFormatting.DARK_GREEN));
    }
}
