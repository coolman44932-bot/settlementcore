package claude.settlementcore.settlement;

import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.defence.Muster;
import claude.settlementcore.food.Fare;
import claude.settlementcore.room.RoomShoring;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.unit.Footing;
import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.work.Works;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A race's settlement, as the engine sees it: the block entity at its heart that owns its rooms, its people, its stores and
 * its reserve (Under-Empire: the Warren Heart). Everything the engine does to a settlement goes through here.
 *
 * <p>The words are the engine's, not a race's: its <b>people</b> are the settlers up and about as mobs; its <b>reserve</b> is
 * the rest, kept as counts (Under-Empire's horde), split into <b>workers</b> and <b>soldiers</b> (slaves and clanrats); its
 * <b>currency</b> is what it counts wealth and pays for things in (warpstone shards).
 */
public interface Settlement extends RoomShoring.Warren {

    /** How far round the heart itself counts as the settlement (with its rooms and digs). */
    int HEART_REACH = 16;
    /** Stacks of spoils kept at the heart at most. */
    int MAX_SPOIL_STACKS = 216;

    // ------------------------------------------------------------------ the block entity itself

    BlockPos getBlockPos();

    @Nullable
    Level getLevel();

    void setChanged();

    /** Broken or unloaded: no longer the settlement's heart. */
    boolean isRemoved();

    // ------------------------------------------------------------------ digging

    /** Cells of a new dig at {@code pl} it must leave alone (the settlement's other rooms, where they meet it: core/room/RoomStacking). */
    long[] keepOutCells(ServerLevel level, PlanPlacement pl);

    /** A dig cut {@code was} at {@code pos} (with its block entity, if it had one): ores and containers become the settlement's spoils. */
    void takeSpoils(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState was,
                    @Nullable net.minecraft.world.level.block.entity.BlockEntity be);

    // ------------------------------------------------------------------ who it belongs to

    @Nullable
    UUID owner();

    @Nullable
    ServerPlayer ownerPlayer();

    /** Tell the owner, if they are about. */
    void tellOwner(String msg, ChatFormatting color);

    /** Its name, as its owner gave it. */
    String settlementName();

    // ------------------------------------------------------------------ its rooms

    List<Building> buildings();

    void addBuilding(Building b, boolean walled);

    /**
     * A building is gone, for a race's own reason (the Waaagh!'s Idol Heap wakes up and walks off as a Rogue Idol): out of its buildings, and its
     * room's work and crew sent home. True if it was one of them. The blocks are the race's to deal with. The default does nothing: a race that
     * implements Settlement itself must remove it its own way. Since buildings can go, their count is no sign the list changed.
     */
    default boolean removeBuilding(ServerLevel level, Building b) {
        return false;
    }

    boolean has(Rooms.Function f);

    int count(Rooms.Function f);

    /** Whether {@code p} is in the settlement: near its heart, or in or beside any of its rooms or digs. */
    boolean inSettlement(BlockPos p);

    /** The boxes of everything it has dug and is digging. */
    List<BoundingBox> settlementBoxes();

    /**
     * The boxes the muster looks for foes in, besides the ring about the heart ({@link #reach}): {@link #settlementBoxes}, until a race says. A
     * race whose land is a great cube round its heart (Khazuk!'s 81 blocks across, deep in the rock of caves) names only what it holds: a cave
     * mob 40 blocks off in the dark, which no Dawi will ever meet, kept the alarm up for ever and every Miner a defender.
     */
    default List<BoundingBox> musterBoxes() {
        return settlementBoxes();
    }

    /** Whether the muster takes {@code p} as ground to defend, besides the ring about the heart: {@link #inSettlement}, until a race says. */
    default boolean musterHolds(BlockPos p) {
        return inSettlement(p);
    }

    /**
     * How far round its heart the muster looks for foes, and defends, besides {@link #settlementBoxes}: a foe within this of the heart
     * (a box about it this wide each way across, and {@link #HEART_REACH} at most up and down) counts whether or not {@link #inSettlement}
     * says the ground is the settlement's, and a defender may come up and fight there. {@link #HEART_REACH}, the Warren Heart's, until a
     * race says. A race that holds open ground may widen it.
     */
    default int reach() {
        return HEART_REACH;
    }

    /**
     * Whether its defenders come up and fight out under the open sky (core/defence/Muster). False, as the Warren Heart's: the
     * muster brings its defenders up only on ground under cover, the tunnels. A surface race says true, and they stand where
     * they are needed.
     */
    default boolean mustersUnderSky() {
        return false;
    }

    /** What part of the settlement {@code p} is in, for the ledger ("the Clan Hoard", "the tunnels"). */
    String placeOf(BlockPos p);

    /**
     * What a line to the player calls the settlement's centre, in "raise it near its centre": neutral until a race names it (the engine's
     * words name no race: the test of core/test/EngineWordsGameTests reads every line of a raise).
     */
    default String centreName() {
        return "centre";
    }

    /**
     * Whether this race's builders fill the hollows under what they raise (a cave, a pocket of water or lava under the ground the pad or ramp
     * lays: room/Siting#plan with fillHollows) instead of the site being refused for hollow ground. Off until a race takes it up (the Dwarfs,
     * 2026-11-08).
     */
    default boolean fillsHollows() {
        return false;
    }

    /**
     * The words of the muster's two lines to the owner (core/defence/Muster): the alarm, "The warren is attacked! 3 Zombies in the tunnels: the horde
     * rises to meet them.", and the all-clear, "The warren is clear: 3 foes slain, 1 of ours lost. 4 defenders sink back into the tunnels." (the Warren
     * Heart's words). {@code place} is what the settlement is called in them ("warren"), {@code ground} where a foe is that is in none of its rooms
     * ("the tunnels"), {@code rises} what its defenders do at the alarm, before "to meet it" ("the horde rises"), and {@code verb} (plural: "sink";
     * the singular adds an s) and {@code back} what they do at the all-clear ("back into the tunnels"). The engine's own name no race.
     * <p>Two templates for the usual ways defenders come: {@link #rising} (up out of the ground, a pool, the tunnels) and {@link #marching} (out
     * of halls, barracks, gates). A race whose voice won't fit the sentence at all writes the whole line itself ({@link #alarmLine},
     * {@link #allClearLine}).
     */
    record MusterWords(String place, String ground, String rises, String verb, String back) {
        /**
         * What a race that says nothing of its own gets: defenders that "turn out" and "stand down", true of any race however its defenders
         * come (it said "rise" once, which only a race that comes up out of the ground does). The test of core/test/EngineWordsGameTests
         * reads every line made of it.
         */
        public static final MusterWords ENGINE = new MusterWords("settlement", "the settlement", "its defenders turn out", "stand", "down");

        /**
         * Defenders who come up out of something and go back down into it: {@code who} ("the spawn") rise from {@code from} ("the pool") and
         * sink back into it. {@code place} names the settlement ("temple-city"); a foe in none of its rooms is "in the " + place.
         */
        public static MusterWords rising(String place, String who, String from) {
            return new MusterWords(place, "the " + place, who + " rise from " + from, "sink", "back into " + from);
        }

        /**
         * Defenders who march out of a hall, barracks or gate and back to it: {@code who} ("the garrison") marches out of {@code from} ("the
         * gates") and its defenders march back to it. {@code who} is one body, so "marches".
         */
        public static MusterWords marching(String place, String who, String from) {
            return new MusterWords(place, "the " + place, who + " marches out of " + from, "march", "back to " + from);
        }
    }

    /** Whether it is walled in (Walls, docs/plans/walls.md): then only what is inside its walls is in it, for the muster. */
    default boolean walled() {
        return false;
    }

    /** Whether {@code p} is inside its walls (false while it isn't walled). */
    default boolean insideWalls(BlockPos p) {
        return false;
    }

    /** A block near it was placed or broken at {@code gameTime}: its walls are scanned again soon. */
    default void wallChanged(long gameTime) {
    }

    /** Whether {@code p} is just outside its walls ({@code Walls#atWalls}): a foe there is at the walls, and the gates shut. */
    default boolean atWalls(BlockPos p) {
        return false;
    }

    /** The ground inside its walls, as a box; null while it isn't walled. */
    @org.jetbrains.annotations.Nullable
    default net.minecraft.world.level.levelgen.structure.BoundingBox wallBounds() {
        return null;
    }

    /** Its walls' gates shut ({@code shut}: foes at the walls or inside them) or opened (the all-clear). */
    default void shutGates(ServerLevel level, boolean shut) {
    }

    /** A wall its settlers raised is finished: kept, so what a foe knocks out of it is patched. */
    default void wallRaised(claude.settlementcore.room.WallJob job) {
    }

    /** A player broke the block at {@code p}: a wall's cell they took out stays out (their way through). */
    default void wallCellBroken(BlockPos p) {
    }

    /**
     * What it tells its owner when foes come to its walls (outside them, near: the gates shut and its defenders man the wall); {@code foes} as the
     * alarm names them ("A Zombie", "3 Zombies"), {@code one} for one. A race says it in its own voice.
     */
    default String besiegedLine(String foes, boolean one) {
        return foes + (one ? " is" : " are") + " at the walls of the " + placeName() + ": the gates are shut, and its defenders man the wall.";
    }

    /** What it tells its owner when its builders go to patch a wall a foe knocked {@code cells} out of. */
    default String wallPatchLine(String wall, int cells) {
        return claude.settlementcore.Words.capital(wall) + " has " + cells + (cells == 1 ? " block" : " blocks") + " knocked out of it: the builders go to patch it.";
    }

    /** What it tells its owner when its walls close round it. */
    default String wallClosedLine() {
        return "The " + placeName() + " is walled in: now only what gets inside the walls raises the alarm.";
    }

    /** What it tells its owner when its walls are broken: {@code where} is the way out the scan found. */
    default String wallBrokenLine(String where) {
        return "The " + placeName() + "'s wall is broken at " + where + ": it isn't walled in any more.";
    }

    /**
     * The alarm's whole line in the race's own voice, or null for the engine's sentence made of {@link #musterWords}: {@code foes} are what
     * came, capitalised for the start of a sentence ("A Zombie", "3 Zombies and a Pillager"), {@code where} the room the nearest is in (none:
     * in no room), {@code one} whether it is the one of them. For a race the sentence won't fit ("Oi! A Creeper in da camp!").
     */
    default String alarmLine(String foes, String where, boolean one) {
        return null;
    }

    /**
     * The all-clear's whole line in the race's own voice, or null for the engine's sentence: {@code slain} foes slain, {@code lost} of its own
     * lost, {@code home} defenders stood down.
     */
    default String allClearLine(int slain, int lost, int home) {
        return null;
    }

    /** The words of the muster's lines: {@link MusterWords#ENGINE} until a race says. */
    default MusterWords musterWords() {
        return MusterWords.ENGINE;
    }

    /** What a line to the player calls the settlement, after "the" or "your" ("at another room of the settlement"): the Warren Heart's "warren". */
    default String placeName() {
        return "settlement";
    }

    /** Its people as a line names them, mid-sentence ("While you slept in your Den, your people worked on:"): the Warren Heart's "the clan". */
    default String peopleName() {
        return "your people";
    }

    /** The haul log's name for the settlement's Hoard (the Ledger's roads: "Hoard", the Warren Heart's "Clan Hoard"). */
    default String hoardName() {
        return "Hoard";
    }

    /** The haul log's name for the spoils its owner hands it: "Spoils", the Warren Heart's "Heart's spoils". */
    default String spoilsName() {
        return "Spoils";
    }

    /** Its currency where it is kept, in a line ("{currency} in store past 29 to refine"): the Warren Heart's "warpstone in the Heart". */
    default String currencyHeld() {
        return currencyName() + " in store";
    }

    /**
     * The words of the lines about digging down under a room (core/room/DeepLevels), the engine's until a race says. {@code levels} names the levels
     * down, the settlement itself first ("the warren", "the Deep Warrens", "the Underdeep": the second is plural, "are dug out"); {@code who} begins
     * a sentence about those who dig ("The horde can find no way down"); {@code starts} is them beginning ("the horde begins to gnaw its way
     * down"); {@code whose} begins a sentence with their way ("The horde's way down"); {@code home} is the settlement when no room is named ("your
     * warren"); {@code looks} is them trying again ("it looks for another way down"); {@code dug} is a level finished ("gnawed out"); and
     * {@code deeper} and {@code deepest} end the line for the first level down and for those below it.
     */
    record DeepWords(List<String> levels, String who, String starts, String whose, String home, String looks, String dug, String deeper, String deepest) {
        public static final DeepWords ENGINE = new DeepWords(List.of("the settlement", "the Deep Halls", "the Deepest Hall"), "Your builders",
                "your builders begin to dig their way down", "Your builders' way down", "your settlement", "they look for another way down", "dug out",
                "Go down: the deeper you go, the more of your people you will see.", "Your people are thickest here.");

        /** The level {@code tier} down ({@code 0}: the settlement itself), and how far down past the named ones. */
        public String level(int tier) {
            return levels.get(Math.min(tier, levels.size() - 1)) + (tier > 2 ? " (" + tier + " down)" : "");
        }
    }

    /** How its people's digging is said: "dig", "digging", "dug" (the Warren Heart's "gnaw", "gnawing", "gnawed": an Ore Mine's drifts). */
    record DigWords(String dig, String digging, String dug) {
        public static final DigWords ENGINE = new DigWords("dig", "digging", "dug");
    }

    /** The words of its digging: {@link DigWords#ENGINE} until a race says. */
    default DigWords digWords() {
        return DigWords.ENGINE;
    }

    /** The words of the deep levels' lines: {@link DeepWords#ENGINE} until a race says. */
    default DeepWords deepWords() {
        return DeepWords.ENGINE;
    }

    /** Whether {@code bed} is in one of its dens (core/work/Den). */
    boolean inDen(BlockPos bed);

    /** The owner slept in the den: {@code seconds} of the settlement's life at once. */
    void passNight(ServerLevel level, int seconds, List<Component> report);

    Works works();

    /**
     * How fast its rooms work, a multiple of their own pace: their crews' swings and chores (SettlerMob#tempo is a race's to
     * scale), each room's own clock (Works), and a night's work. A race's favoured ground may work faster.
     */
    default float workPace() {
        return 1f;
    }

    Muster muster();

    Fare fare();

    // ------------------------------------------------------------------ its stores

    /** What its digs turned up, kept at the heart. */
    List<ItemStack> spoils();

    /** Onto the spoils pile; what a full pile ({@link #MAX_SPOIL_STACKS}) has no room for is put away elsewhere, never lost. */
    void addSpoil(ItemStack st);

    /** A haul logged: {@code n} of {@code item} from one place to another (core/ledger/HaulLog). */
    void log(String from, String to, @Nullable Item item, long n);

    /** The stores the ledger knows of, as block positions. */
    LongOpenHashSet ledgerStores();

    void setLedgerStores(LongOpenHashSet stores);

    // ------------------------------------------------------------------ food

    /** All the food the settlement has: its ration and what its larder's containers hold (core/food/Larder). */
    int food();

    /** Food points a thing of this nutrition is worth to the stores. */
    int foodValue(int nutrition);

    /** Food points straight into the ration (eaten before anything in the containers). */
    void addFood(int n, int grade, String from);

    /** What it does when it has people and no food (core/settlement/Famine, counted by Ration#second): nothing, until a race says. */
    default Famine famine() {
        return Famine.NONE;
    }

    /** The larder: real food in real containers (core/food/Larder). */
    claude.settlementcore.food.Larder larderStores();

    /** Whether every container in this room is the larder (a Skaven Heart Chamber); elsewhere only those named Larder. */
    default boolean larderRoom(Building b) {
        return false;
    }

    /**
     * Food into the larder's containers. The haul log gets the things on the road to where they went ("Cook-Pits -> Clan
     * Hoard"), and their worth in food on the road into the larder (its balance). Returns how many of the stack went in;
     * the stack is left as it was.
     */
    default int stockLarder(ServerLevel level, ItemStack s, String from) {
        int each = foodValue(Fare.nutrition(s));
        Item item = s.getItem();
        ItemStack rest = larderStores().put(level, s.copy(), (room, n) -> log(from, room.isEmpty() ? claude.settlementcore.food.Larder.NAME : room, item, n));
        int in = s.getCount() - rest.getCount();
        if (in > 0) {
            log(from, claude.settlementcore.ledger.HaulLog.LARDER, null, (long) in * each);
        }
        return in;
    }

    // ------------------------------------------------------------------ currency

    int currency();

    void addCurrency(int n, String from);

    /** Whether it had {@code n} to spend (on {@code on}, for the ledger). */
    boolean spendCurrency(int n, String on);

    /** One unit of its currency as an item (a warpstone shard). */
    Item currencyItem();

    /** Its currency's block, worth nine (a block of warpstone). */
    Item currencyBlock();

    /** Its currency refined, if it refines it (refined warpstone), or null. */
    @Nullable
    Item refinedCurrency();

    /**
     * What a line to the player calls its currency, plural ("gold ingots"): "the settlement's currency" until a race names it (a
     * raise that costs too much says "costs 12 gold ingots, and it has 3").
     */
    default String currencyName() {
        return "the settlement's currency";
    }

    /** {@code n} of its currency in words: "12 gold ingots", and "12 of the settlement's currency" for the unnamed. */
    default String currencyAmount(int n) {
        String name = currencyName();
        return n + (name.regionMatches(true, 0, "the ", 0, 4) ? " of " : " ") + name;
    }

    // ------------------------------------------------------------------ what its people have done

    /** A deed of the settlement's rooms that its race may keep count of (the Skaven: which Great Clans rise). */
    enum Deed {
        /** A block of currency ore pressed at the Forge. */
        REFINED,
        /** A beast born in the pens. */
        BRED
    }

    /** {@code n} more of a deed; a race that keeps no count ignores it. */
    default void tally(Deed deed, int n) {
    }

    // ------------------------------------------------------------------ its people and its reserve

    /** Its settlers up and about, loaded. */
    List<? extends SettlerMob> loadedPeople();

    boolean hasPerson(UUID id);

    int peopleCount();

    /**
     * Everyone it counts: its people up and about, its rooms' crews and its reserve. A settlement with none has no one to starve (Ration#second).
     * The Warren Heart's own count also has the rats teeming about its god.
     */
    default int population() {
        return peopleCount() + works().crewCount() + reserve();
    }

    /** How many settlers can be up at once. */
    int peopleCap();

    /** The reserve, workers and soldiers together. */
    int reserve();

    int reserveWorkers();

    int reserveSoldiers();

    void addToReserve(int workers, int soldiers);

    /** One out of the reserve (a soldier, or a worker), if there is one. */
    boolean takeFromReserve(boolean soldier);

    /**
     * {@code id}, one of its people up and about, off its roll to be a room's crew (Works#enlist: idle hands go to the work before the reserve is
     * dug into). False if it isn't on the roll; a settlement that keeps no roll of its own never gives one up.
     */
    default boolean takeFromRoll(UUID id) {
        return false;
    }

    /** One up out of the reserve at {@code at}, as a mob; {@code teem}: one of the teeming, never saved. */
    @Nullable
    SettlerMob riseFromReserve(ServerLevel level, BlockPos at, boolean soldier, boolean teem);

    /** What its workers are (slaves). */
    EntityType<? extends SettlerMob> workerType();

    /** What its soldiers are (clanrats). */
    EntityType<? extends SettlerMob> soldierType();

    // ------------------------------------------------------------------ helpers

    /** A room's display name by id (the id itself if no race has it). */
    static String roomName(String room) {
        Rooms.Room r = Rooms.get(room);
        return r == null ? room : r.displayName();
    }

    /**
     * "the Heart Chamber", but "the Deep Warrens" once: some rooms' names carry their own "the" ("beneath the the Deep
     * Warrens" was seen live, 2026-09-27).
     */
    static String theRoom(String room) {
        return the(roomName(room));
    }

    /** "the " before a name, unless it has one. */
    static String the(String name) {
        return name.regionMatches(true, 0, "the ", 0, 4) ? name : "the " + name;
    }

    /**
     * A cell two high with a solid floor near {@code near} (rings out to 4), or the cell above {@code near}: one of the nearest at random, and
     * one with a way out ({@link Footing#escapes}), never a cell of a hollow shut in on every side, as a rat that comes up in one is shut in
     * with whatever is there. The cell above {@code near}, the last resort, is not asked.
     */
    static BlockPos findStandable(ServerLevel level, BlockPos near) {
        Footing.Known known = new Footing.Known();
        for (int r = 1; r < 5; r++) {
            List<BlockPos> ring = new ArrayList<>();
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos p = near.offset(dx, dy, dz);
                        if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                                && level.getBlockState(p.below()).isSolidRender()) {
                            ring.add(p);
                        }
                    }
                }
            }
            while (!ring.isEmpty()) {
                BlockPos p = ring.remove(level.getRandom().nextInt(ring.size()));
                if (Footing.escapes(level, p, Footing.LIMIT, known)) {
                    return p;
                }
            }
        }
        return near.above();
    }

    /** Where {@code mob} comes up in room {@code b}, as near its middle as it can: {@link #standIn(ServerLevel, Building, Entity, BlockPos)}. */
    static BlockPos standIn(ServerLevel level, Building b, Entity mob) {
        return standIn(level, b, mob, b.box().getCenter());
    }

    /**
     * Where {@code mob} comes up in room {@code b}, as near {@code near}'s column as it can (a Rat Ogre climbs out of the
     * Fleshpits by their vat): on the room's floor ({@link Building#standingY}), where the whole of it fits (a Rat Ogre is
     * wider and taller than the cell two high {@link #findStandable} asks for) over a floor that holds it; one of the nearest
     * such cells at random, so those raised there scatter. The floor first: the Breeding Pens' feeding heap stands three high
     * in their middle, and their litter came up on its shoulders, two over the floor. Only a room with no room anywhere on its
     * floor sends it to {@link #findStandable} round {@code near}.
     */
    static BlockPos standIn(ServerLevel level, Building b, Entity mob, BlockPos near) {
        BoundingBox box = b.box();
        BlockPos mid = new BlockPos(near.getX(), b.standingY(), near.getZ());
        EntityDimensions size = mob.getDimensions(Pose.STANDING);
        int reach = Math.max(Math.max(mid.getX() - box.minX(), box.maxX() - mid.getX()), Math.max(mid.getZ() - box.minZ(), box.maxZ() - mid.getZ()));
        for (int r = 1; r <= reach; r++) {
            List<BlockPos> ring = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 1 && Math.abs(dx) < r && Math.abs(dz) < r) {
                        continue;                                // the cells inside were looked at on the rings before
                    }
                    BlockPos p = mid.offset(dx, 0, dz);
                    if (box.isInside(p) && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                            && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)
                            && level.noCollision(mob, size.makeBoundingBox(Vec3.atBottomCenterOf(p)))) {
                        ring.add(p);
                    }
                }
            }
            if (!ring.isEmpty()) {
                return ring.get(level.getRandom().nextInt(ring.size()));
            }
        }
        return findStandable(level, mid);
    }
}
