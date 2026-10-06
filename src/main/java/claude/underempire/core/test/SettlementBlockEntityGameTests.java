package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.food.Fare;
import claude.underempire.core.ledger.HaulLog;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.settlement.Brood;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.settlement.Famine;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.settlement.SettlementBlockEntity;
import claude.underempire.core.settlement.SettlementSpawns;
import claude.underempire.core.settlement.Spawning;
import claude.underempire.core.unit.SettlerMob;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The base of a race's settlement block entity (core/settlement/SettlementBlockEntity, docs/plans/greatplan-first-spawning.md
 * section B item 7): the Warren Heart's parts wired into one block entity that a race fills in the few places that are its own.
 * The engine cannot place a block entity of its own (it registers nothing), so the tests make one the way the Great Plan's pool
 * will, a subclass with the hooks answered, and never put it in the world (it wears a chest's type, as no type can be made after the
 * registries freeze): it is given the level and ticked by hand, a second being twenty calls of the ticker. A chunk save is a {@code saveAdditional} into a tag and a {@code loadAdditional} out of it into a fresh
 * one. What is left to the Great Plan's own tests (the pool's, slice G2) is a settler of a real type rising into the world.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class SettlementBlockEntityGameTests {

    private static final SettlementBlockEntity.Keys KEYS = new SettlementBlockEntity.Keys("name", "gold", "people", "skinks", "saurus");
    private static final Brood.Cost HATCH = new Brood.Cost(1, 0, "Hatching");

    /** A settlement as a race writes one: every hook answered from fields the tests set, and what it was told kept. */
    static final class TestSettlement extends SettlementBlockEntity {
        /** The type of a chest: a block entity type cannot be made once the registries are frozen, and this one is never placed, so it never reaches a chunk. */
        static final BlockEntityType<?> TYPE = BlockEntityType.CHEST;

        float eats;
        int every = 5;
        int cap = 100;
        int peopleCap = 10;
        float pace = 1f;
        float multiplier = 1f;
        boolean walls;
        boolean stacks;
        boolean relights;
        boolean dormant;
        Famine famine = Famine.NONE;
        int rounds;
        /** Those teeming (up, on neither the roll nor the reserve): -1 leaves the base's own count, the muster's defenders. */
        int teeming = -1;
        /** Whether everything is in the settlement ({@link #inSettlement}): a building is raised only inside it, or beside what it has built. */
        boolean holds;
        /** The lines it told its owner: it has none, so they are kept here. */
        final List<String> heard = new ArrayList<>();
        Spawning spawning;
        final List<String> told = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        TestSettlement(BlockPos pos, BlockState state) {
            super(TYPE, pos, state, KEYS);
            spawning = (level, settlement, brood) -> {
                rounds++;
                brood.add(false, 3, HATCH);
            };
        }

        /** A settlement of the level, at {@code at} of the test (not placed: only given the level). */
        static TestSettlement of(GameTestHelper h, BlockPos at) {
            TestSettlement s = new TestSettlement(h.absolutePos(at), Blocks.CHEST.defaultBlockState());
            s.setLevel(h.getLevel());
            return s;
        }

        @Override
        protected float eatsPerSecond() {
            return eats;
        }

        @Override
        protected Spawning spawning() {
            return dormant ? Spawning.NONE : spawning;
        }

        @Override
        protected int spawnSeconds() {
            return every;
        }

        @Override
        protected int reserveCap() {
            return cap;
        }

        @Override
        protected float breedingMultiplier() {
            return multiplier;
        }

        @Override
        protected String currencyRoad() {
            return "The Pool's gold";
        }

        @Override
        public float workPace() {
            return pace;
        }

        @Override
        public Famine famine() {
            return famine;
        }

        @Override
        protected boolean wallsRooms() {
            return walls;
        }

        @Override
        protected boolean stacksRooms() {
            return stacks;
        }

        @Override
        protected boolean relightsRooms() {
            return relights;
        }

        @Override
        protected void upkept(Upkeep what, int blocks) {
            told.add(what + ":" + blocks);
        }

        @Override
        public int peopleCap() {
            return peopleCap;
        }

        @Override
        protected int teeming() {
            return teeming >= 0 ? teeming : super.teeming();
        }

        /** The raise that cannot be made (a mob needs a registered type with its attributes, and the engine has no mob of its own): asked, and none made. */
        @Override
        protected SettlerMob makePerson(ServerLevel level, BlockPos at, boolean soldier, boolean teem) {
            asked.add(soldier + "@" + at + "/" + teem);
            return null;
        }

        /** No mob type: the engine has none, and the reserve of the tests that ask is empty, so nothing is ever made (Works#spawnCrew compares the two types and stops at the empty reserve). */
        @Override
        public EntityType<? extends SettlerMob> workerType() {
            return null;
        }

        @Override
        public EntityType<? extends SettlerMob> soldierType() {
            return null;
        }

        @Override
        public void tellOwner(String msg, ChatFormatting color) {
            heard.add(msg);
        }

        @Override
        public net.minecraft.world.item.Item currencyItem() {
            return Items.AMETHYST_SHARD;
        }

        @Override
        public net.minecraft.world.item.Item currencyBlock() {
            return Items.AMETHYST_BLOCK;
        }

        @Override
        public boolean inSettlement(BlockPos p) {
            return holds;
        }

        @Override
        public List<BoundingBox> settlementBoxes() {
            return List.of();
        }

        @Override
        public long[] keepOutCells(ServerLevel level, claude.underempire.core.room.PlanPlacement pl) {
            return new long[0];
        }

        @Override
        public void takeSpoils(ServerLevel level, BlockPos pos, BlockState was, net.minecraft.world.level.block.entity.BlockEntity be) {
        }

        /** A chunk's save of it: what {@code saveAdditional} writes. */
        CompoundTag saved(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            saveAdditional(tag, registries);
            return tag;
        }

        /** A chunk's load of it, as a fresh block entity reads what was saved. */
        void load(CompoundTag tag, HolderLookup.Provider registries) {
            loadAdditional(tag, registries);
        }
    }

    /** {@code n} seconds of the ticker: twenty ticks each. */
    private static void seconds(GameTestHelper h, TestSettlement s, int n) {
        for (int i = 0; i < n * 20; i++) {
            SettlementBlockEntity.serverTick(h.getLevel(), s.getBlockPos(), s.getBlockState(), s);
        }
    }

    private static CompoundTag emptyLarder() {
        CompoundTag t = new CompoundTag();
        t.putIntArray("larder", new int[5]);
        return t;
    }

    /**
     * Everything it keeps is saved and comes back in a fresh one: its owner and name (the race's key), its currency (the race's
     * key), the ration by grade and what it owes, the roll and the two reserve counts (the race's keys), its buildings with their
     * flags and the shoring, the ledger's stores, the spoils and how far the spawning clock is. And the tag holds those keys and no others
     * (the ration's four, the buildings' two, works), so a race's own are the only ones it adds.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aSettlementSavesEveryPartUnderTheKeysItWasGiven(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        ServerPlayer owner = h.makeMockServerPlayerInLevel();
        s.claim(owner, "The Pool of Sotek");
        s.addCurrency(7, "Mining");
        s.ration().load(emptyLarder());
        s.addFood(9, Fare.FEAST_GRADE, "");
        s.addFood(4, Fare.RAW, "");
        s.ration().owe(0.5f);
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        s.roll().add(one);
        s.roll().add(two);
        s.addToReserve(5, 2);
        Rooms.Room room = BuildingBookGameTests.cellsRoom();
        Building built = BuildingBookGameTests.dug(room, h.absolutePos(new BlockPos(2, 4, 8)));
        Building plain = new Building("an_old_room", new BoundingBox(1, 2, 3, 4, 5, 6));
        s.addBuilding(built, true);
        s.addBuilding(plain, false);
        s.shore(BlockPos.asLong(8, 9, 10));
        LongOpenHashSet stores = new LongOpenHashSet(new long[]{BlockPos.asLong(1, 1, 1), BlockPos.asLong(2, 2, 2)});
        s.setLedgerStores(stores);
        s.addSpoil(new ItemStack(Items.DIAMOND, 5));
        s.every = 10;
        s.eats = 0f;
        seconds(h, s, 3);                                          // 3 of the 10 seconds to the next round

        CompoundTag tag = s.saved(level.registryAccess());
        Set<String> expected = Set.of("owner", "name", "gold", "larder", "food", "hunger", "starving", "people", "skinks", "saurus", "buildings", "shored",
                "works", "ledger_stores", "spoils", "spawn_progress");
        h.assertTrue(tag.getAllKeys().equals(expected), "the tag held " + tag.getAllKeys() + ", not " + expected);

        TestSettlement back = TestSettlement.of(h, new BlockPos(2, 2, 2));
        back.load(tag, level.registryAccess());
        h.assertTrue(owner.getUUID().equals(back.owner()) && back.settlementName().equals("The Pool of Sotek") && back.currency() == 7,
                "the owner, the name or the currency came back as " + back.owner() + ", " + back.settlementName() + ", " + back.currency());
        h.assertTrue(java.util.Arrays.equals(back.ration().grades(), s.ration().grades()) && back.ration().hunger() == 0.5f && back.food() == 13,
                "the ration came back as " + java.util.Arrays.toString(back.ration().grades()) + ", hunger " + back.ration().hunger());
        h.assertTrue(back.peopleCount() == 2 && back.hasPerson(one) && back.hasPerson(two) && back.reserveWorkers() == 5 && back.reserveSoldiers() == 2 && back.reserve() == 7,
                "the roll or the reserve came back as " + back.peopleCount() + ", " + back.reserveWorkers() + " and " + back.reserveSoldiers());
        h.assertTrue(back.buildings().equals(List.of(built, plain)) && back.book().walled(built) && !back.book().walled(plain) && back.shored(BlockPos.asLong(8, 9, 10)),
                "the buildings or the shoring came back as " + back.buildings());
        h.assertTrue(back.ledgerStores().equals(stores) && back.spoils().size() == 1 && back.spoils().get(0).is(Items.DIAMOND) && back.spoils().get(0).getCount() == 5,
                "the stores or the spoils came back as " + back.ledgerStores() + " and " + back.spoils());
        CompoundTag again = back.saved(level.registryAccess());
        long[] storesAgain = again.getLongArray("ledger_stores"), storesWas = tag.getLongArray("ledger_stores");
        java.util.Arrays.sort(storesAgain);
        java.util.Arrays.sort(storesWas);
        again.remove("ledger_stores");
        tag.remove("ledger_stores");
        h.assertTrue(java.util.Arrays.equals(storesAgain, storesWas) && again.equals(tag), "saved again it differs: " + again + " vs " + tag);
        back.every = 10;
        seconds(h, back, 6);
        h.assertTrue(back.rounds == 0, "the spawning clock was not kept: it made a round 6 seconds after 3 of 10");
        seconds(h, back, 1);
        h.assertTrue(back.rounds == 1, "the spawning clock was not kept: 3 seconds then 7 of 10 made " + back.rounds + " rounds, not 1");
        s.setRemoved();
        back.setRemoved();
        h.succeed();
    }

    /**
     * A second is twenty ticks and no fewer: 19 do nothing, the 20th has the people eat what the race says (1 a second from 60 raw
     * leaves 57 after 3 seconds, so the ration is spent, at the race's pace); a settlement with food and no people has no one to starve;
     * with people and no food its famine is told 1, 2 and 3 (every second), and when food is back once, "fed"; and it is told nothing
     * for a settlement whose famine is none.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aSecondEatsTheRationAndTellsItsFamineAsTheHeartDoes(GameTestHelper h) {
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        s.eats = 1f;
        for (int i = 0; i < 19; i++) {
            SettlementBlockEntity.serverTick(h.getLevel(), s.getBlockPos(), s.getBlockState(), s);
        }
        h.assertTrue(s.food() == 60, "19 ticks ate: " + s.food() + " left of 60");
        SettlementBlockEntity.serverTick(h.getLevel(), s.getBlockPos(), s.getBlockState(), s);
        h.assertTrue(s.food() == 59, "the 20th tick left " + s.food() + " of 60, not 59");
        seconds(h, s, 2);
        h.assertTrue(s.food() == 57, "3 seconds at 1 a second left " + s.food() + " of 60, not 57");

        s.ration().load(emptyLarder());
        List<String> heard = new ArrayList<>();
        s.famine = (level, settlement, seconds) -> heard.add("s" + seconds);
        s.eats = 0f;
        seconds(h, s, 2);
        h.assertTrue(heard.isEmpty(), "a settlement with no people and no food was told of a famine: " + heard);
        s.addToReserve(2, 0);
        seconds(h, s, 3);
        h.assertTrue(heard.equals(List.of("s1", "s2", "s3")), "3 hungry seconds told its famine " + heard + ", not 1, 2 and 3");
        List<String> fed = new ArrayList<>();
        s.famine = new Famine() {
            @Override
            public void starving(ServerLevel level, Settlement settlement, int seconds) {
                fed.add("s" + seconds);
            }

            @Override
            public void fed(ServerLevel level, Settlement settlement) {
                fed.add("fed");
            }
        };
        s.addFood(5, Fare.RAW, "");
        seconds(h, s, 3);
        h.assertTrue(fed.equals(List.of("fed")), "food back after a hunger told its famine " + fed + ", not fed once");
        s.setRemoved();
        h.succeed();
    }

    /**
     * Rounds of spawning come every {@code spawnSeconds} of the rooms' clock: none in 4 seconds of 5, one at the 5th, two at the 10th; a
     * settlement whose rooms run twice as fast has them at the 3rd and 5th second; one that is dormant, or whose seconds are none, makes
     * none; each round's brood is bounded by the race's cap (4 asked for 3 at a time make 3 then 1, then none), the food goes out of
     * the ration on the road to what it was for, and the brood's rate is the fare's times the race's own.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aSecondRunsTheSpawningRoundsOnTheRoomsClockWithinTheBroodsBounds(GameTestHelper h) {
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        seconds(h, s, 4);
        h.assertTrue(s.rounds == 0 && s.reserve() == 0, "4 seconds of a round every 5 made " + s.rounds + " rounds");
        seconds(h, s, 1);
        h.assertTrue(s.rounds == 1 && s.reserveWorkers() == 3 && s.food() == 57, "the 5th second made " + s.rounds + " rounds, " + s.reserveWorkers() + " workers and left "
                + s.food() + " food, not 1, 3 and 57");
        seconds(h, s, 5);
        h.assertTrue(s.rounds == 2 && s.reserveWorkers() == 6, "the 10th second made " + s.rounds + " rounds and " + s.reserveWorkers() + " workers, not 2 and 6");
        long spent = s.haulLog().flows(h.getLevel().getGameTime()).stream().filter(f -> f.from().equals(HaulLog.LARDER) && f.to().equals("Hatching")).mapToLong(HaulLog.Flow::count).sum();
        h.assertTrue(spent == 6, "the haul log has " + spent + " food gone to Hatching, not 6");

        TestSettlement fast = TestSettlement.of(h, new BlockPos(2, 2, 2));
        fast.pace = 2f;
        seconds(h, fast, 2);
        h.assertTrue(fast.rounds == 0, "2 seconds at twice the pace made a round of 5");
        seconds(h, fast, 1);
        h.assertTrue(fast.rounds == 1, "3 seconds at twice the pace made " + fast.rounds + " rounds, not 1");
        seconds(h, fast, 2);
        h.assertTrue(fast.rounds == 2, "5 seconds at twice the pace made " + fast.rounds + " rounds, not 2");

        TestSettlement still = TestSettlement.of(h, new BlockPos(2, 2, 2));
        still.dormant = true;
        seconds(h, still, 10);
        h.assertTrue(still.rounds == 0 && still.reserve() == 0, "a dormant settlement made " + still.rounds + " rounds");
        still.dormant = false;
        still.every = 0;
        seconds(h, still, 10);
        h.assertTrue(still.rounds == 0, "a settlement with no seconds between rounds made " + still.rounds);

        TestSettlement small = TestSettlement.of(h, new BlockPos(2, 2, 2));
        small.cap = 4;
        small.every = 1;
        seconds(h, small, 3);
        h.assertTrue(small.rounds == 3 && small.reserve() == 4 && small.food() == 56, "a cap of 4 with 3 asked for each of 3 rounds left " + small.reserve() + " and " + small.food()
                + " food, not 4 and 56");

        TestSettlement warm = TestSettlement.of(h, new BlockPos(2, 2, 2));
        warm.multiplier = 1.5f;
        warm.cap = 10;
        Brood brood = warm.brood(h.getLevel());
        h.assertTrue(Math.abs(brood.rate() - 1.5f) < 1e-5f && brood.room() == 10, "the brood's rate was " + brood.rate() + " and its room " + brood.room() + ", not 1.5 and 10");
        for (TestSettlement t : List.of(s, fast, still, small, warm)) {
            t.setRemoved();
        }
        h.succeed();
    }

    /**
     * A second runs at most {@link SettlementBlockEntity#MAX_ROUNDS_PER_SECOND} rounds of spawning, and a saved progress is made sane on load: a
     * progress of a billion, or a clock that was 59 seconds along a round of 60 when the round became 1 second long, makes 4 rounds in its
     * next second (the backlog is dropped, not caught up on), never a thousand; a negative, an infinite or a non-number loads as none, and
     * the rest whole (its first second trims it to the round); and the clock still runs a round a second after.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theSpawnClockRunsAtMostAFewRoundsASecondAndSavesNothingCorrupt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement source = TestSettlement.of(h, new BlockPos(2, 2, 2));
        CompoundTag base = source.saved(level.registryAccess());
        float[] saved = {1e9f, Float.NaN, -5f, Float.POSITIVE_INFINITY, 12.5f};
        float[] kept = {1e9f, 0f, 0f, 0f, 12.5f};
        for (int i = 0; i < saved.length; i++) {
            CompoundTag tag = base.copy();
            tag.putFloat("spawn_progress", saved[i]);
            TestSettlement back = TestSettlement.of(h, new BlockPos(2, 2, 2));
            back.load(tag, level.registryAccess());
            float now = back.saved(level.registryAccess()).getFloat("spawn_progress");
            h.assertTrue(now == kept[i], "a saved progress of " + saved[i] + " was kept as " + now + ", not " + kept[i]);
            back.setRemoved();
        }

        CompoundTag tag = base.copy();
        tag.putFloat("spawn_progress", 1e9f);
        TestSettlement corrupt = TestSettlement.of(h, new BlockPos(2, 2, 2));
        corrupt.load(tag, level.registryAccess());
        corrupt.every = 1;
        seconds(h, corrupt, 1);
        h.assertTrue(corrupt.rounds == SettlementBlockEntity.MAX_ROUNDS_PER_SECOND,
                "a corrupt progress made " + corrupt.rounds + " rounds in its second, not " + SettlementBlockEntity.MAX_ROUNDS_PER_SECOND);

        tag = base.copy();
        tag.putFloat("spawn_progress", 59f);
        TestSettlement dropped = TestSettlement.of(h, new BlockPos(2, 2, 2));
        dropped.load(tag, level.registryAccess());
        dropped.every = 1;
        seconds(h, dropped, 1);
        h.assertTrue(dropped.rounds == SettlementBlockEntity.MAX_ROUNDS_PER_SECOND, "59 seconds along a round that became 1 second long made " + dropped.rounds
                + " rounds in its second, not " + SettlementBlockEntity.MAX_ROUNDS_PER_SECOND);
        int made = dropped.rounds;
        seconds(h, dropped, 3);
        h.assertTrue(dropped.rounds - made >= 3 && dropped.rounds - made <= 4, "the clock did not come back to a round a second: " + (dropped.rounds - made) + " rounds in 3 seconds more");
        h.assertTrue(corrupt.saved(level.registryAccess()).getFloat("spawn_progress") <= corrupt.every,
                "a billion loaded was not trimmed to the round by its first second: " + corrupt.saved(level.registryAccess()).getFloat("spawn_progress"));
        for (TestSettlement t : List.of(source, corrupt, dropped)) {
            t.setRemoved();
        }
        h.succeed();
    }

    /**
     * A round longer than the hour the clock is kept to still comes (the review of 20da67f: the clock was clamped to an hour every second, so a
     * {@code spawnSeconds} of more never gathered it): a round of 7200 seconds at a pace of 1000 a second, 8 seconds, is not made at the 7th and
     * is at the 8th, and the next at the 15th. A clamp of an hour alone holds the clock at 4600 for ever.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aRoundLongerThanTheHourTheClockIsKeptToStillComes(GameTestHelper h) {
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        s.every = 2 * (int) SettlementBlockEntity.MAX_SAVED_PROGRESS;
        s.pace = 1000f;
        seconds(h, s, 7);
        h.assertTrue(s.rounds == 0, "7 seconds at a pace of 1000 made " + s.rounds + " rounds of a round of " + s.every + ": 7000 is not 7200");
        seconds(h, s, 1);
        h.assertTrue(s.rounds == 1, "8 seconds at a pace of 1000 made " + s.rounds + " rounds of a round of " + s.every + ", not 1: the clock is held under the round");
        seconds(h, s, 7);
        h.assertTrue(s.rounds == 2, "15 seconds at a pace of 1000 made " + s.rounds + " rounds of a round of " + s.every + ", not 2");
        s.setRemoved();
        h.succeed();
    }

    /**
     * A reload keeps a long round's progress (the review of d21aef0: the load kept an hour at most): a round of 7200 seconds, 5000 along it, is
     * saved and loaded at 5000, and at a pace of 1000 a second it comes in its 3rd second, not its 4th (an hour kept would have needed 4).
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aReloadKeepsALongRoundsProgress(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        s.every = 2 * (int) SettlementBlockEntity.MAX_SAVED_PROGRESS;
        s.pace = 1000f;
        seconds(h, s, 5);
        h.assertTrue(s.rounds == 0, "5 seconds at a pace of 1000 made " + s.rounds + " rounds of a round of " + s.every);
        CompoundTag tag = s.saved(level.registryAccess());
        TestSettlement back = TestSettlement.of(h, new BlockPos(2, 2, 2));
        back.load(tag, level.registryAccess());
        back.every = s.every;
        back.pace = s.pace;
        float kept = back.saved(level.registryAccess()).getFloat("spawn_progress");
        h.assertTrue(kept == 5000f, "a round 5000 seconds along was loaded at " + kept + ", not 5000");
        seconds(h, back, 2);
        h.assertTrue(back.rounds == 0, "the reloaded round came early: " + back.rounds + " rounds at 7000");
        seconds(h, back, 1);
        h.assertTrue(back.rounds == 1, "the reloaded round did not come at 8000: " + back.rounds + " rounds");
        for (TestSettlement t : List.of(s, back)) {
            t.setRemoved();
        }
        h.succeed();
    }

    /**
     * The population counts those teeming (up and alive, on neither the roll nor the reserve: the muster's defenders), so a round of spawning
     * makes no room for new people while they are up: 1 on the roll, 2 in the reserve and 3 teeming are 6 under a cap of 10, which leaves a
     * room of 4, not 7; and with none teeming (the base counts the muster's defenders, none here) it is the roll and the reserve alone.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void thePopulationCountsThoseTeemingSoTheBroodMakesNoRoomForThem(GameTestHelper h) {
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        s.cap = 10;
        s.roll().add(UUID.randomUUID());
        s.addToReserve(2, 0);
        h.assertTrue(s.population() == 3 && s.brood(h.getLevel()).room() == 7, "with no one teeming the population was " + s.population() + " and the room " + s.brood(h.getLevel()).room()
                + ", not 3 and 7");
        s.teeming = 3;
        h.assertTrue(s.population() == 6, "with 3 teeming the population was " + s.population() + ", not 6");
        h.assertTrue(s.brood(h.getLevel()).room() == 4, "with 3 teeming the brood's room was " + s.brood(h.getLevel()).room() + ", not 4");
        s.setRemoved();
        h.succeed();
    }

    /**
     * The upkeep is done only for what the race asks, at its own second of five, and the race is told what it placed: with none asked for
     * a room unwalled, unstacked and at no lamps stays so through 10 seconds, and nothing is told; asked for all, a room is stacked at the 2nd
     * second, its lamps brought up at the 3rd (2 blocks, told as RELIT:2, a glowstone and a sea lantern) and its walls done at the 4th (a
     * room with nothing open places none, and is not told of it).
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theUpkeepRunsOnlyWhenTheRaceAsksAndTellsWhatItPlaced(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        BlockPos door = h.absolutePos(new BlockPos(2, 4, 2));
        Building lit = BuildingBookGameTests.dug(BuildingBookGameTests.lampsRoom(), door);
        for (BlockPos p : BuildingBookGameTests.lamps(door)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        s.eats = 0f;
        s.every = 0;
        s.addBuilding(lit, false);
        s.book().forgetLamps(lit);
        seconds(h, s, 10);
        h.assertTrue(!s.book().walled(lit) && !s.book().stacked(lit) && s.book().relitVersion(lit) == 0 && s.told.isEmpty()
                        && level.getBlockState(BuildingBookGameTests.lamps(door)[0]).isAir(),
                "the upkeep ran though the race asked for none: walled " + s.book().walled(lit) + ", stacked " + s.book().stacked(lit) + ", lamps " + s.book().relitVersion(lit));

        s.walls = true;
        s.stacks = true;
        s.relights = true;
        seconds(h, s, 1);                                          // second 11: nothing is due
        h.assertTrue(!s.book().stacked(lit) && !s.book().walled(lit) && s.book().relitVersion(lit) == 0, "something was done at the 11th second, which is none of the 2nd, 3rd or 4th");
        seconds(h, s, 1);                                          // second 12: the stacking
        h.assertTrue(s.book().stacked(lit) && !s.book().walled(lit) && s.book().relitVersion(lit) == 0, "the 12th second did not do just the stacking");
        seconds(h, s, 1);                                          // second 13: the lamps
        h.assertTrue(s.book().relitVersion(lit) == 2 && !s.book().walled(lit) && s.told.equals(List.of("RELIT:2"))
                        && level.getBlockState(BuildingBookGameTests.lamps(door)[0]).is(Blocks.GLOWSTONE) && level.getBlockState(BuildingBookGameTests.lamps(door)[1]).is(Blocks.SEA_LANTERN),
                "the 13th second did not do just the lamps: version " + s.book().relitVersion(lit) + ", told " + s.told);
        seconds(h, s, 1);                                          // second 14: the walls
        h.assertTrue(s.book().walled(lit) && s.told.equals(List.of("RELIT:2")), "the 14th second did not wall the room, or told of walls that placed none: " + s.told);
        for (BlockPos p : BuildingBookGameTests.lamps(door)) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        s.setRemoved();
        h.succeed();
    }

    /**
     * Its currency is a counter with a road: 7 in from Mining and 3 out to Raising leave 4, each logged on the road the race named (the
     * item the race's currency is); 5 more than it has is refused and changes nothing. Spoils stack onto what is there (two stacks of
     * 40 and 30 stone make one of 64 and one of 6, to a maximum of {@code MAX_SPOIL_STACKS}), and past the cap what does not fit is dropped.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aSettlementKeepsItsCurrencyOnItsRoadAndItsSpoilsInStacks(GameTestHelper h) {
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        s.addCurrency(7, "Mining");
        h.assertTrue(s.spendCurrency(3, "Raising") && s.currency() == 4, "7 in and 3 out left " + s.currency() + ", not 4");
        h.assertTrue(!s.spendCurrency(5, "Raising") && s.currency() == 4, "5 of 4 was spent, leaving " + s.currency());
        List<HaulLog.Flow> flows = s.haulLog().flows(h.getLevel().getGameTime());
        h.assertTrue(flows.contains(new HaulLog.Flow("Mining", "The Pool's gold", Items.AMETHYST_SHARD, 7)) && flows.contains(new HaulLog.Flow("The Pool's gold", "Raising", Items.AMETHYST_SHARD, 3))
                        && flows.size() == 2, "the roads were " + flows);
        s.addSpoil(new ItemStack(Items.STONE, 40));
        s.addSpoil(new ItemStack(Items.STONE, 30));
        h.assertTrue(s.spoils().size() == 2 && s.spoils().get(0).getCount() == 64 && s.spoils().get(1).getCount() == 6,
                "40 and 30 stone made " + s.spoils().stream().map(ItemStack::getCount).toList() + ", not 64 and 6");
        for (int i = 0; i < Settlement.MAX_SPOIL_STACKS + 20; i++) {
            s.addSpoil(new ItemStack(Items.DIAMOND_SWORD));
        }
        h.assertTrue(s.spoils().size() == Settlement.MAX_SPOIL_STACKS, "the spoils were " + s.spoils().size() + " stacks, not the " + Settlement.MAX_SPOIL_STACKS + " at most");
        s.setRemoved();
        h.succeed();
    }

    /**
     * The people and the reserve: the reserve counts and takes by kind (a take of none refuses), the population is those up, the crews
     * and the reserve together, and a rise from the reserve that the race cannot make (it is asked to make the mob, with the kind, the place and
     * whether it teems) gives the one taken back and returns none (and asks nothing of an empty reserve). The settlement is on the registry of settlements once it has ticked and off it when
     * its chunk unloads, so no monsters spawn inside it while it stands and it is not held after.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theReserveRisesOrGoesBackAndTheSettlementIsOnTheRegistryWhileItStands(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement s = TestSettlement.of(h, new BlockPos(2, 2, 2));
        s.addToReserve(2, 1);
        s.roll().add(UUID.randomUUID());
        h.assertTrue(s.reserve() == 3 && s.population() == 4 && s.peopleCount() == 1, "the reserve was " + s.reserve() + ", the population " + s.population() + ", the people " + s.peopleCount());
        h.assertTrue(s.takeFromReserve(false) && s.takeFromReserve(false) && !s.takeFromReserve(false) && s.reserveWorkers() == 0 && s.reserveSoldiers() == 1,
                "taking 3 workers of 2 left " + s.reserveWorkers());
        BlockPos at = h.absolutePos(new BlockPos(3, 3, 3));
        h.assertTrue(s.riseFromReserve(level, at, true, false) == null && s.reserveSoldiers() == 1 && s.asked.equals(List.of("true@" + at + "/false")),
                "a rise the race could not make lost the soldier or was not asked once: " + s.reserveSoldiers() + ", asked " + s.asked);
        h.assertTrue(s.riseFromReserve(level, at, false, true) == null && s.reserveWorkers() == 0 && s.asked.size() == 1,
                "a rise from an empty reserve asked the race to make a worker, or went under none: asked " + s.asked);
        h.assertTrue(s.loadedPeople().isEmpty(), "an id on the roll that is no mob was a loaded person");

        h.assertTrue(!SettlementSpawns.loaded(level).contains(s), "a settlement that has not ticked was on the registry");
        seconds(h, s, 1);
        h.assertTrue(SettlementSpawns.loaded(level).contains(s), "a settlement that has ticked was not on the registry");
        s.onChunkUnloaded();
        h.assertTrue(!SettlementSpawns.loaded(level).contains(s), "a settlement whose chunk unloaded was still on the registry");
        s.setRemoved();
        h.succeed();
    }
}
