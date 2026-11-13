package claude.settlementcore.testrace;

import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.settlement.Brood;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.SettlementBlockEntity;
import claude.settlementcore.settlement.Spawning;
import claude.settlementcore.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The test race's settlement: the engine's {@link SettlementBlockEntity} with the race's hooks answered in the plainest way. Its people eat
 * a little, a round of spawning every {@value #ROUND_SECONDS} s makes two workers for every guard out of food, and it brings them up beside
 * it while it has room ({@value #UP_AT_ONCE} up at once, {@value #RESERVE_CAP} in all). Its land is a {@value #RADIUS}-block square round it.
 */
public class HeartBlockEntity extends SettlementBlockEntity {

    static final Keys KEYS = new Keys("hold_name", "emeralds", "people", "reserve_workers", "reserve_guards");
    public static final int ROUND_SECONDS = 30;
    public static final int UP_AT_ONCE = 8;
    public static final int RESERVE_CAP = 24;
    public static final int RADIUS = 24;
    /** A worker costs 4 food points, a guard 6. */
    static final Brood.Cost WORKER_COST = new Brood.Cost(4, 0, "Workers raised");
    static final Brood.Cost GUARD_COST = new Brood.Cost(6, 0, "Guards raised");

    /** Rounds of spawning so far, for the tests. */
    public int rounds;

    public HeartBlockEntity(BlockPos pos, BlockState state) {
        super(TRRegistry.HEART_ENTITY.get(), pos, state, KEYS);
    }

    /** The Heart {@code p} owns, among the loaded ones of its level. */
    @Nullable
    static HeartBlockEntity ownedBy(ServerPlayer p) {
        for (Settlement s : claude.settlementcore.settlement.SettlementSpawns.loaded(p.level())) {
            if (s instanceof HeartBlockEntity h && p.getUUID().equals(h.owner())) {
                return h;
            }
        }
        return null;
    }

    @Override
    protected float eatsPerSecond() {
        return peopleCount() * 0.02f;                            // a point each per 50 s
    }

    @Override
    protected Spawning spawning() {
        return (level, settlement, brood) -> {
            rounds++;
            int workers = brood.add(false, 2, WORKER_COST);
            int guards = brood.add(true, 1, GUARD_COST);
            callUp(brood);
        };
    }

    /**
     * Brings people up out of the reserve (the ones this round made and any that sank home or were bred while the settlement stood at its cap)
     * until {@value #UP_AT_ONCE} are up or the reserve is empty, two workers to a guard. Before 2026-11-13 a round brought up only the ones it
     * had just made: births past the cap and everyone sent home stayed in the reserve for good, and a settlement at its reserve cap (people up +
     * crews + reserve = {@value #RESERVE_CAP}) made nobody and brought nobody up, so the soak's settlement drained to none up and 23 below.
     */
    private void callUp(Brood brood) {
        int workers = 0;
        int guards = 0;
        for (SettlerMob up : loadedPeople()) {
            if (up.getType() == soldierType()) {
                guards++;
            } else {
                workers++;
            }
        }
        for (int i = 0; i < UP_AT_ONCE && reserve() > 0 && peopleCount() < peopleCap(); i++) {
            boolean guard = reserveWorkers() <= 0 || reserveSoldiers() > 0 && guards * 2 < workers;
            if (brood.bringUp(guard, getBlockPos().above()) == null) {
                return;                                          // at its cap, or no cell to come up in: asked again next round
            }
            if (guard) {
                guards++;
            } else {
                workers++;
            }
        }
    }

    @Override
    protected int spawnSeconds() {
        return ROUND_SECONDS;
    }

    @Override
    protected int reserveCap() {
        return RESERVE_CAP;
    }

    @Override
    public int peopleCap() {
        return UP_AT_ONCE;
    }

    @Override
    protected String currencyRoad() {
        return "The Heart's emeralds";
    }

    @Override
    public String settlementName() {
        String n = super.settlementName();
        return n == null || n.isEmpty() ? "The Hold" : n;
    }

    @Override
    public EntityType<? extends SettlerMob> workerType() {
        return TRRegistry.WORKER.get();
    }

    @Override
    public EntityType<? extends SettlerMob> soldierType() {
        return TRRegistry.GUARD.get();
    }

    @Override
    protected void raised(SettlerMob mob, boolean soldier, boolean teem) {
        mob.hearten(100f);
    }

    @Override
    public Item currencyItem() {
        return Items.EMERALD;
    }

    @Override
    public Item currencyBlock() {
        return Items.EMERALD_BLOCK;
    }

    @Override
    public boolean inSettlement(BlockPos p) {
        BlockPos c = getBlockPos();
        return Math.abs(p.getX() - c.getX()) <= RADIUS && Math.abs(p.getZ() - c.getZ()) <= RADIUS && Math.abs(p.getY() - c.getY()) <= RADIUS;
    }

    @Override
    public List<BoundingBox> settlementBoxes() {
        BlockPos c = getBlockPos();
        return List.of(new BoundingBox(c.getX() - RADIUS, c.getY() - RADIUS, c.getZ() - RADIUS, c.getX() + RADIUS, c.getY() + RADIUS, c.getZ() + RADIUS));
    }

    @Override
    public long[] keepOutCells(ServerLevel level, PlanPlacement pl) {
        return new long[]{getBlockPos().asLong()};               // never dig out the Heart itself
    }

    @Override
    public void takeSpoils(ServerLevel level, BlockPos pos, BlockState was, @Nullable BlockEntity be) {
    }

    /** Tests: one round of spawning now, whatever its clock says. */
    public void roundForTests(ServerLevel level) {
        spawning().round(level, this, brood(level));
    }

    /** A unit of this Heart is back at it: off the roll (or out of its crew) and into the reserve. */
    void absorb(TestUnit unit) {
        boolean soldier = !unit.isWorker();
        if (works().forget(unit.getUUID())) {
            return;
        }
        boolean person = hasPerson(unit.getUUID());
        if (!person && !muster().defends(unit.getUUID())) {
            return;
        }
        if (person) {
            removePerson(unit.getUUID());
        }
        addToReserve(soldier ? 0 : 1, soldier ? 1 : 0);
    }
}
