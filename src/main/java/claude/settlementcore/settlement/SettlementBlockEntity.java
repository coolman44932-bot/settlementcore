package claude.settlementcore.settlement;

import claude.settlementcore.Nbt;
import claude.settlementcore.defence.Muster;
import claude.settlementcore.food.Fare;
import claude.settlementcore.food.Larder;
import claude.settlementcore.ledger.HaulLog;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.work.Works;
import claude.settlementcore.work.WorksExtension;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The base of a race's settlement block entity: it holds the parts every settlement needs and wires them, so a race writes
 * only what is its own. The parts are the ration ({@link Ration}), the roll of people up and about ({@link Roll}), the reserve
 * that waits ({@link Reserve}), the buildings ({@link BuildingBook}), the rooms' works ({@link Works}), the defence
 * ({@link Muster}), the larder, the haul log, the ledger's stores, the spoils, the currency counter, the owner and the name.
 * It implements the whole of {@link Settlement} from them, and ticks them once a second ({@link #second}). It registers nothing
 * (the engine has no registries): the race registers its block entity type and its ticker (which calls {@link #serverTick}).
 *
 * <p>Lifted from the Warren Heart (docs/plans/greatplan-first-spawning.md, section B item 7), part by part, with the same
 * algorithms, constants and save keys; the Heart itself is not built on it (an optional later step). Left to the race, and
 * abstract: what the people eat ({@link #eatsPerSecond}), how they are made ({@link #spawning}, {@link #spawnSeconds},
 * {@link #reserveCap}), how many are up at once ({@link #peopleCap}), what the currency is ({@link #currencyItem},
 * {@link #currencyBlock}, {@link #currencyRoad}), what its settlers are ({@link #workerType}, {@link #soldierType}) and where it is
 * ({@link #inSettlement}, {@link #settlementBoxes}, {@link #keepOutCells}, {@link #takeSpoils}). Left to it, with a default
 * that does nothing: its famine ({@link #famine}), its pace ({@link #workPace}), its own works ({@link #worksExtension}), the
 * rooms' upkeep ({@link #wallsRooms}, {@link #stacksRooms}, {@link #relightsRooms}) and what to say of it ({@link #upkept}),
 * what it does with a settler as it rises ({@link #raised}) and what a night's sleep does ({@link #passNight}).
 *
 * <p>Saved under {@code owner}, the race's {@link Keys} (its name, currency, roll and reserve), the parts' own keys
 * ({@code larder}, {@code food}, {@code hunger}, {@code starving}; {@code buildings}, {@code shored}), {@code works},
 * {@code ledger_stores}, {@code spoils} and {@code spawn_progress}; a race adds its own around {@link #saveAdditional}.
 */
public abstract class SettlementBlockEntity extends BlockEntity implements Settlement {

    /** The save keys a race names for what only it spells: the Heart's are "clan_name", "warpstone", "rats", "horde_slaves", "horde_clanrats". */
    public record Keys(String name, String currency, String roll, String workers, String soldiers) {
    }

    /** The rooms' upkeep a race may ask for, and tells of when it placed something. */
    public enum Upkeep {
        /** Rooms walled up against caves, drops and the sky. */
        SHORED,
        /** The floors laid between rooms dug over one another. */
        STACKED,
        /** The rooms' lamps brought to their newest plans. */
        RELIT
    }

    private final Keys keys;
    private final Larder larder = new Larder(this);
    private final HaulLog haulLog = new HaulLog();
    private final Ration ration;
    private final Roll roll;
    private final Reserve reserve;
    private final BuildingBook book = new BuildingBook(this::setChanged);
    private final Muster muster = new Muster();
    /** Made when first asked for, so the race's own works ({@link #worksExtension}) are asked of a block entity that is built. */
    @Nullable
    private Works works;
    private LongOpenHashSet ledgerStores = new LongOpenHashSet();
    private final List<ItemStack> spoils = new ArrayList<>();
    private int currency;
    @Nullable
    private UUID owner;
    private String name = "";
    private long ticks;
    /** Seconds of the rooms' clock since the last round of spawning (a settlement whose rooms run faster gains it faster). */
    private float spawnProgress;
    /**
     * Rounds of spawning a second may run at most: a {@link #spawnSeconds} that has just dropped (a pool woken, a race's own change) or a
     * progress saved corrupt would otherwise loop the tick, a round each time, for as many rounds as the progress holds.
     */
    public static final int MAX_ROUNDS_PER_SECOND = 4;
    /**
     * The most spawn progress a running clock keeps, unless the race's round is longer: an hour of the rooms' clock. A round comes every few
     * seconds to few minutes, so more is a backlog to drop, not to catch up on ({@link #sane(float, int)}).
     */
    public static final float MAX_SAVED_PROGRESS = 3600f;

    protected SettlementBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, Keys keys) {
        super(type, pos, state);
        this.keys = keys;
        this.ration = new Ration(Ration.Pantry.of(larder, () -> level instanceof ServerLevel sl ? sl : null),
                (from, to, n) -> log(from, to, null, n), this::setChanged);
        this.roll = new Roll(keys.roll());
        this.reserve = new Reserve(keys.workers(), keys.soldiers());
    }

    // ------------------------------------------------------------------ the parts

    public Ration ration() {
        return ration;
    }

    public Roll roll() {
        return roll;
    }

    /** The reserve's two counts ({@link Settlement#reserve()} is their total). */
    public Reserve reserveCounts() {
        return reserve;
    }

    public BuildingBook book() {
        return book;
    }

    public HaulLog haulLog() {
        return haulLog;
    }

    // ------------------------------------------------------------------ what a race says

    /** Food points the people eat a second (the Heart: each rat a point every 4 minutes, the horde a scrap). */
    protected abstract float eatsPerSecond();

    /** How it makes new people: one round of it every {@link #spawnSeconds} of the rooms' clock. Asked each round, so it may change (a pool gone dormant makes none). */
    protected abstract Spawning spawning();

    /** Seconds of the rooms' clock between rounds of {@link #spawning}; none or less: it makes no one. */
    protected abstract int spawnSeconds();

    /** The most people it can number in all: those up, its crews and its reserve ({@link Brood#room}). */
    protected abstract int reserveCap();

    /** How fast it breeds, 1 for no more or less than its fare says: a Heart's devotion, a pool's warm water ({@link Brood#rate}). */
    protected float breedingMultiplier() {
        return 1f;
    }

    /** What the haul log calls the store its currency is kept in, as the road its deposits and spendings go along. */
    protected abstract String currencyRoad();

    /** The race's own works beside the engine's (Works): none by default. Asked once, when the works are first needed. */
    protected WorksExtension worksExtension() {
        return WorksExtension.NONE;
    }

    /** Whether its walls count (Walls): on unless a race says no (an underground warren is walled by the rock it's dug in). */
    protected boolean takesWalls() {
        return true;
    }

    private final Walls walls = new Walls();

    /** Its walls: whether it is walled in, and what's inside. */
    public Walls walls() {
        return walls;
    }

    @Override
    public boolean walled() {
        return takesWalls() && walls.walled();
    }

    @Override
    public boolean insideWalls(BlockPos p) {
        return takesWalls() && walls.inside(p);
    }

    @Override
    public void wallChanged(long gameTime) {
        walls.dirty(gameTime);
    }

    @Override
    public boolean atWalls(BlockPos p) {
        return takesWalls() && walls.atWalls(p);
    }

    @Override
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.level.levelgen.structure.BoundingBox wallBounds() {
        return takesWalls() ? walls.bounds() : null;
    }

    @Override
    public void shutGates(ServerLevel level, boolean shut) {
        if (takesWalls() && walls.gates(level, shut) > 0) {
            setChanged();                                        // which it shut is saved
        }
    }

    @Override
    public void wallRaised(claude.settlementcore.room.WallJob job) {
        walls.record(job);
        setChanged();
    }

    @Override
    public void wallCellBroken(BlockPos p) {
        if (walls.forget(p)) {
            setChanged();
        }
    }

    /**
     * A wall its settlers raised that a foe knocked blocks out of is patched by its builders, free (docs/plans/walls.md, E3): one patch at a time, never while
     * the alarm is up. Returns whether one was begun. Every {@value Walls#SCAN_SECONDS} s from {@link #second}; public for the tests.
     */
    public boolean mendWalls(ServerLevel level) {
        if (!takesWalls() || muster.alarmed() || walls.built().isEmpty()) {
            return false;
        }
        for (var s : works().sites()) {
            if (s.job() instanceof claude.settlementcore.room.WallJob w && w.patch()) {
                return false;                                    // one patch at a time
            }
        }
        Walls.Patch p = walls.missing(level);
        if (p == null) {
            return false;
        }
        String no = works().patchWall(level, p.style(), p.line(), p.cells());
        if (no != null) {
            return false;
        }
        tellOwner(wallPatchLine(Settlement.the(p.style().displayName()), p.cells().length), ChatFormatting.YELLOW);
        return true;
    }

    /** Whether the rooms are walled up (RoomShoring) every few seconds, those dug before it: a surface race wants none, as its rooms are added walled. */
    protected boolean wallsRooms() {
        return false;
    }

    /** Whether the floors between rooms dug over one another are laid every few seconds (RoomStacking). */
    protected boolean stacksRooms() {
        return false;
    }

    /** Whether the rooms' lamps are brought to their newest plans every few seconds (RoomRelight). */
    protected boolean relightsRooms() {
        return false;
    }

    /** The upkeep placed {@code blocks} (more than none): the race tells its owner, in its own words. */
    protected void upkept(Upkeep what, int blocks) {
    }

    /** A settler has risen out of the reserve and is in the world, bound to this settlement: the race sets it going ({@code teem}: one of the teeming, which is not on the roll). */
    protected void raised(SettlerMob mob, boolean soldier, boolean teem) {
    }

    /** A person is off the roll: what it had claimed (a dig's cells, a chore) goes back. */
    protected void released(UUID id) {
    }

    // ------------------------------------------------------------------ ticking

    /** The ticker the race registers: the rooms' bellows every tick, and {@link #second} every twentieth. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, SettlementBlockEntity settlement) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        settlement.works().tick(sl);
        if (++settlement.ticks % 20 != 0) {
            return;
        }
        settlement.second(sl);
    }

    /**
     * What a settlement does each second, in this order: its people eat or starve (the ration, and its famine's word), its
     * rooms are kept up as the race asked, its rooms work, its spawning clock runs (a round every {@link #spawnSeconds} of the
     * rooms' clock), its defence stands to, it is on the registry of settlements (no monsters spawn inside), and it is saved. A race
     * adds what is its own around a call of this.
     */
    public void second(ServerLevel level) {
        long sec = ticks / 20;
        if (takesWalls() && walls.due(level.getGameTime())) {
            switch (walls.scan(level, getBlockPos())) {
                case CLOSED -> tellOwner(wallClosedLine(), ChatFormatting.GREEN);
                case BROKEN -> {
                    BlockPos at = walls.leak();
                    tellOwner(wallBrokenLine(at == null ? "an unloaded edge" : at.getX() + " " + at.getZ()), ChatFormatting.RED);
                }
                default -> {
                }
            }
        }
        if (takesWalls() && sec % Walls.SCAN_SECONDS == 7) {
            mendWalls(level);
        }
        if (takesWalls() && !muster.alarmed() && walls.anyShut()) {
            shutGates(level, false);                             // shut when it was saved mid-alarm: no alarm now, so open
        }
        ration.second(level, this, eatsPerSecond());
        if (relightsRooms() && sec % 5 == 3) {
            int placed = book.relightRooms(level);
            if (placed > 0) {
                upkept(Upkeep.RELIT, placed);
                larder.changed();
            }
        }
        if (wallsRooms() && sec % 5 == 4) {
            int placed = book.wallUpRooms(level, this);
            if (placed > 0) {
                upkept(Upkeep.SHORED, placed);
            }
        }
        if (stacksRooms() && sec % 5 == 2) {
            int placed = book.stackRooms(level);
            if (placed > 0) {
                upkept(Upkeep.STACKED, placed);
            }
        }
        works().second(level, sec);
        spawnClock(level);
        muster.second(level, this);
        SettlementSpawns.add(level, this);                       // onLoad comes a tick late for a block entity just set down
        setChanged();
    }

    /**
     * A second of the rooms' clock: a round of spawning for each {@link #spawnSeconds} it has gathered, at most
     * {@link #MAX_ROUNDS_PER_SECOND} a second; a backlog past that is dropped, down to one round's worth.
     */
    private void spawnClock(ServerLevel level) {
        int every = spawnSeconds();
        if (every <= 0) {
            return;
        }
        float pace = workPace();
        spawnProgress = sane(spawnProgress, every) + (Float.isFinite(pace) ? Math.max(0f, pace) : 0f);
        for (int rounds = 0; spawnProgress >= every && rounds < MAX_ROUNDS_PER_SECOND; rounds++) {
            spawnProgress -= every;
            spawning().round(level, this, brood(level));
        }
        spawnProgress = Math.min(spawnProgress, every);
    }

    /**
     * {@code progress} as a clock may hold it: none for a negative or a non-number, and at most an hour or one whole round of {@code every}
     * seconds, whichever is longer. A cap of an hour alone, kept each second, held a clock under the round of a race whose rounds are longer
     * than an hour and it never came.
     */
    static float sane(float progress, int every) {
        return progress >= 0f ? Math.min(progress, Math.max(MAX_SAVED_PROGRESS, every)) : 0f;
    }

    /**
     * {@code progress} as a load keeps it: none for a negative, an infinite or a non-number, and the rest whole. A load cannot ask the race how
     * long its round is (the level is not set yet), so it trims nothing: the first second trims it to the round ({@link #sane(float, int)}).
     * A load that kept an hour at most cost a race whose rounds are longer than an hour up to half a round at every reload (the review of
     * d21aef0).
     */
    static float loaded(float progress) {
        return Float.isFinite(progress) && progress > 0f ? progress : 0f;
    }

    /** What a round of spawning may do now: within {@link #reserveCap}, at {@link #breedingMultiplier}, its food out of the ration. */
    public Brood brood(ServerLevel level) {
        return new Brood(level, this, reserveCap(), breedingMultiplier(), ration::spendFood);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel sl) {
            SettlementSpawns.add(sl, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel sl) {
            SettlementSpawns.remove(sl, this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel sl) {
            SettlementSpawns.remove(sl, this);
        }
    }

    // ------------------------------------------------------------------ who it belongs to

    /** It is {@code player}'s, called {@code name}. */
    public void claim(ServerPlayer player, String name) {
        this.owner = player.getUUID();
        this.name = name;
        setChanged();
    }

    @Nullable
    @Override
    public UUID owner() {
        return owner;
    }

    @Nullable
    @Override
    public ServerPlayer ownerPlayer() {
        return owner == null || !(level instanceof ServerLevel sl) ? null : sl.getServer().getPlayerList().getPlayer(owner);
    }

    @Override
    public void tellOwner(String msg, ChatFormatting color) {
        ServerPlayer p = ownerPlayer();
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg).withStyle(color));
        }
    }

    @Override
    public String settlementName() {
        return name;
    }

    // ------------------------------------------------------------------ its rooms

    @Override
    public List<Building> buildings() {
        return book.buildings();
    }

    @Override
    public void addBuilding(Building b, boolean walled) {
        book.add(b, walled, level instanceof ServerLevel sl ? sl : null);
    }

    @Override
    public boolean removeBuilding(ServerLevel level, Building b) {
        if (!book.remove(b)) {
            return false;
        }
        if (b.door() != null) {
            works().forgetRoom(level, b.door());
        }
        return true;
    }

    @Override
    public boolean has(Rooms.Function f) {
        return book.has(f);
    }

    @Override
    public int count(Rooms.Function f) {
        return book.count(f);
    }

    @Override
    public String placeOf(BlockPos p) {
        return book.placeOf(p);
    }

    /** Dens are the race's to have: none by default. */
    @Override
    public boolean inDen(BlockPos bed) {
        return false;
    }

    /** A night slept through in a den: nothing by default, a race passes its own time. */
    @Override
    public void passNight(ServerLevel level, int seconds, List<Component> report) {
    }

    @Override
    public Works works() {
        if (works == null) {
            works = new Works(this, worksExtension());
        }
        return works;
    }

    @Override
    public Muster muster() {
        return muster;
    }

    // ------------------------------------------------------------------ the shoring (RoomShoring.Warren)

    /** Every cell of its rooms in {@code area} but those of the one at {@code except}, those being dug (Works#digs), and its heart. */
    @Override
    public LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except) {
        List<claude.settlementcore.room.PlanPlacement> digs = new java.util.ArrayList<>();
        for (claude.settlementcore.work.DigWork d : works().digs()) {
            digs.add(d.job().placement());
        }
        LongSet out = book.roomCells(level, area, except, digs);
        out.add(worldPosition.asLong());
        return out;
    }

    @Override
    public boolean shored(long pos) {
        return book.shored(pos);
    }

    @Override
    public void shore(long pos) {
        book.shore(pos);
    }

    @Override
    public void unshore(long pos) {
        book.unshore(pos);
    }

    // ------------------------------------------------------------------ its stores

    @Override
    public List<ItemStack> spoils() {
        return spoils;
    }

    /** Into the spoils pile, stacked onto what is there (the pile holds at most MAX_SPOIL_STACKS stacks). */
    @Override
    public void addSpoil(ItemStack st) {
        for (ItemStack have : spoils) {
            if (st.isEmpty()) {
                break;
            }
            if (ItemStack.isSameItemSameComponents(have, st) && have.getCount() < have.getMaxStackSize()) {
                int move = Math.min(st.getCount(), have.getMaxStackSize() - have.getCount());
                have.grow(move);
                st.shrink(move);
            }
        }
        if (!st.isEmpty() && spoils.size() < MAX_SPOIL_STACKS) {
            spoils.add(st);
        }
        setChanged();
    }

    /** {@code n} of {@code item} (null: food) went from {@code from} to {@code to}, now. */
    @Override
    public void log(String from, String to, @Nullable Item item, long n) {
        if (level != null && !from.isEmpty() && !to.isEmpty()) {
            haulLog.add(from, to, item, n, level.getGameTime());
        }
    }

    @Override
    public LongOpenHashSet ledgerStores() {
        return ledgerStores;
    }

    @Override
    public void setLedgerStores(LongOpenHashSet stores) {
        this.ledgerStores = stores;
        setChanged();
    }

    // ------------------------------------------------------------------ food

    @Override
    public int food() {
        return ration.food();
    }

    /** What a thing of this nutrition is worth to the stores: its nutrition, until a race's rooms make more of it. */
    @Override
    public int foodValue(int nutrition) {
        return nutrition;
    }

    @Override
    public void addFood(int n, int grade, String from) {
        ration.addFood(n, grade, from);
    }

    @Override
    public Fare fare() {
        return Fare.of(ration.fareGrade());
    }

    @Override
    public Larder larderStores() {
        return larder;
    }

    // ------------------------------------------------------------------ currency

    @Override
    public int currency() {
        return currency;
    }

    @Override
    public void addCurrency(int n, String from) {
        currency += n;
        log(from, currencyRoad(), currencyItem(), n);
        setChanged();
    }

    @Override
    public boolean spendCurrency(int n, String on) {
        if (currency < n) {
            return false;
        }
        currency -= n;
        log(currencyRoad(), on, currencyItem(), n);
        setChanged();
        return true;
    }

    /** No refined currency by default: a race that refines its own says what it is. */
    @Nullable
    @Override
    public Item refinedCurrency() {
        return null;
    }

    // ------------------------------------------------------------------ its people and its reserve

    @Override
    public List<? extends SettlerMob> loadedPeople() {
        return level instanceof ServerLevel sl ? roll.loaded(sl, SettlerMob.class) : List.of();
    }

    @Override
    public boolean hasPerson(UUID id) {
        return roll.has(id);
    }

    @Override
    public int peopleCount() {
        return roll.count();
    }

    /**
     * How many of its people are up and alive but on neither its roll nor its reserve: the muster's defenders, risen out of the reserve with
     * {@code teem} (never on the roll) and back in it at the all-clear. {@link #population} counts them, so the brood makes no room for new
     * people while they are up (Brood#room is the cap less the population) and the reserve is not over its cap when they sink back. A race
     * with others of its own (the Heart's rats teeming about its god) adds them.
     */
    protected int teeming() {
        return level instanceof ServerLevel sl ? muster.out(sl) : 0;   // walking home after the all-clear too (the review of 8c10211)
    }

    /** Its people up, its rooms' crews, its reserve and those teeming ({@link #teeming}). */
    @Override
    public int population() {
        return Settlement.super.population() + teeming();
    }

    /** {@code mob} is up and about for this settlement: on its roll. */
    public void addPerson(SettlerMob mob) {
        if (roll.add(mob.getUUID())) {
            setChanged();
        }
    }

    @Override
    public boolean takeFromRoll(UUID id) {
        if (!roll.has(id)) {
            return false;
        }
        removePerson(id);
        return true;
    }

    /** {@code id} is off the roll (dead, or gone back down): what it had claimed goes back ({@link #released}). */
    public void removePerson(UUID id) {
        if (roll.remove(id, this::released)) {
            setChanged();
        }
    }

    @Override
    public int reserve() {
        return reserve.count();
    }

    @Override
    public int reserveWorkers() {
        return reserve.workers();
    }

    @Override
    public int reserveSoldiers() {
        return reserve.soldiers();
    }

    @Override
    public void addToReserve(int workers, int soldiers) {
        reserve.add(workers, soldiers);
        setChanged();
    }

    @Override
    public boolean takeFromReserve(boolean soldier) {
        boolean taken = reserve.take(soldier);
        if (taken) {
            setChanged();
        }
        return taken;
    }

    /**
     * One rises out of the reserve at {@code at}: a mob of {@link #workerType} or {@link #soldierType} bound to this settlement and
     * put on its roll (not if {@code teem}), then {@link #raised} sets it going. Null if the reserve holds none of the kind or
     * the type makes none (the one taken goes back).
     */
    @Nullable
    @Override
    public SettlerMob riseFromReserve(ServerLevel level, BlockPos at, boolean soldier, boolean teem) {
        SettlerMob mob = reserve.rise(level, at, soldier, (l, p, s) -> makePerson(l, p, s, teem));
        if (mob != null) {
            setChanged();
        }
        return mob;
    }

    /** The mob of a rise, made and in the world, or null if none can be made (the race may make its own: a settler with a variant, say). */
    @Nullable
    protected SettlerMob makePerson(ServerLevel level, BlockPos at, boolean soldier, boolean teem) {
        SettlerMob mob = (soldier ? soldierType() : workerType()).create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (mob == null) {
            return null;
        }
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        mob.bindTo(this);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.MOB_SUMMONED, null);
        level.addFreshEntity(mob);
        if (!teem) {
            roll.add(mob.getUUID());
        }
        raised(mob, soldier, teem);
        return mob;
    }

    // ------------------------------------------------------------------ save / load

    /**
     * The settlement's state, in one compound under {@value #SAVE_KEY} (since 26.1 a block entity saves through a ValueOutput,
     * which can't hand over a whole tag: the engine's parts still write theirs, and the race's own fields sit beside it). Its
     * items need the level's registries, so a settlement is saved only once it has a level, as every placed one has.
     */
    public static final String SAVE_KEY = "settlement";

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
        super.saveAdditional(out);
        if (level == null) {
            return;
        }
        HolderLookup.Provider registries = level.registryAccess();
        CompoundTag tag = new CompoundTag();
        if (owner != null) {
            Nbt.putUUID(tag, "owner", owner);
        }
        tag.putString(keys.name(), name);
        tag.putInt(keys.currency(), currency);
        ration.save(tag);
        roll.save(tag);
        reserve.save(tag);
        book.save(tag);
        tag.put("works", works().save(registries));
        if (walls.recorded()) {
            tag.put("walls", walls.save());                     // only once it has raised a wall: an old save is as it was
        }
        CompoundTag mustered = muster.save(level instanceof ServerLevel sl ? sl : null);
        if (mustered != null) {
            tag.put("muster", mustered);                        // only with an alarm up or defenders out: a settlement at peace saves as it did
        }
        tag.putLongArray("ledger_stores", ledgerStores.toLongArray());
        ListTag spoilList = new ListTag();
        for (ItemStack st : spoils) {
            if (!st.isEmpty()) {
                spoilList.add(claude.settlementcore.Nbt.item(st, registries));
            }
        }
        tag.put("spoils", spoilList);
        tag.putFloat("spawn_progress", spawnProgress);
        out.store(SAVE_KEY, CompoundTag.CODEC, tag);
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
        super.loadAdditional(in);
        HolderLookup.Provider registries = in.lookup();
        CompoundTag tag = in.read(SAVE_KEY, CompoundTag.CODEC).orElseGet(CompoundTag::new);
        owner = Nbt.hasUUID(tag, "owner") ? Nbt.getUUID(tag, "owner") : null;
        name = tag.getStringOr(keys.name(), "");
        currency = tag.getIntOr(keys.currency(), 0);
        ration.load(tag);
        roll.load(tag);
        reserve.load(tag);
        book.load(tag);
        works().load(tag.getCompoundOrEmpty("works"), registries);
        walls.load(tag.getCompoundOrEmpty("walls"));
        muster.load(tag.getCompoundOrEmpty("muster"));          // none (an old save, or peace): no alarm, no one out
        ledgerStores = new LongOpenHashSet(tag.getLongArray("ledger_stores").orElse(new long[0]));
        spoils.clear();
        for (Tag t : tag.getListOrEmpty("spoils")) {
            Nbt.parseItem(registries, t).ifPresent(spoils::add);
        }
        spawnProgress = loaded(tag.getFloatOr("spawn_progress", 0f));
    }
}
