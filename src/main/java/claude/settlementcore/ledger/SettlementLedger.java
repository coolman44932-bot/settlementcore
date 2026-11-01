package claude.settlementcore.ledger;

import claude.settlementcore.settlement.Currencies;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.work.Bins;
import claude.settlementcore.work.HoardWork;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The clan's hoard as one (user, 2026-09-26: "you know how applied energistics has a whole thing for storage to manage
 * everything? we should probably do all of that for items and food and stuff"). Every chest, barrel and shulker box in
 * the warren's rooms (the Clan Hoard's barrels, the work rooms' bins, the Warlord's own chests), the Heart's spoils pile
 * and its warpstone hoard, counted together and drawn on as one store: what the Hoard-Ledger and the Tally-Scroll show.
 *
 * <p>Taking draws on the Clan Hoard first, then the rest, and the work rooms' supply bins (the Pantry, the Ore and Fuel
 * Bins, the Armoury, the Feeding Trough...) last, so the rooms keep working. Putting in sorts into the Hoard's sections
 * (HoardWork#put), then any chest of the Warlord's in the warren, then the Heart's spoils pile (which the horde hauls to
 * the Hoard); warpstone shards and blocks go into the Heart's warpstone hoard, as feeding it would.
 *
 * <p>A store whose chunk is not loaded is reached through the tunnels: {@link #wake} holds its chunk loaded (not
 * ticking) for a while, so a Tally-Scroll works anywhere, and the tally fills in as the chunks come in. The stores the
 * clan knows of are kept by the Heart (WarrenHeartBlockEntity#ledgerStores), so it knows which chunks to wake.
 */
public final class SettlementLedger {

    /** Where a line of the tally is kept: in the stores (and the spoils pile), or the Heart's warpstone hoard. */
    public static final int STORES = 0, WARPSTONE = 1;

    /** The work rooms' supply bins: taken from last, never put into. */
    private static final Set<String> SUPPLY = Set.of("pantry", "ore bin", "fuel bin", "fodder bin", "sapling crate",
            "repair chest", "armoury", "feeding trough");

    /** How long a store's chunk is held loaded after the Ledger last reached for it. */

    private SettlementLedger() {
    }

    /** One kind of thing in the hoard: a single item standing for all of it (count 1), how many, and where it is kept. */
    public record Line(ItemStack item, long count, int source) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, Line::item, ByteBufCodecs.VAR_LONG, Line::count, ByteBufCodecs.VAR_INT, Line::source, Line::new);
    }

    /** The clan's state beside the tally: its warpstone, larder and fare, its stores, and how many it could not reach yet. */
    public record Summary(String clan, int currency, int food, String fare, int stores, int rooms, int unreached, int slots, int used) {
        public static final Summary NONE = new Summary("", 0, 0, "", 0, 0, 0, 0, 0);
        public static final StreamCodec<ByteBuf, Summary> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Summary decode(ByteBuf b) {
                return new Summary(ByteBufCodecs.STRING_UTF8.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.STRING_UTF8.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b));
            }

            @Override
            public void encode(ByteBuf b, Summary s) {
                ByteBufCodecs.STRING_UTF8.encode(b, s.clan());
                ByteBufCodecs.VAR_INT.encode(b, s.currency());
                ByteBufCodecs.VAR_INT.encode(b, s.food());
                ByteBufCodecs.STRING_UTF8.encode(b, s.fare());
                ByteBufCodecs.VAR_INT.encode(b, s.stores());
                ByteBufCodecs.VAR_INT.encode(b, s.rooms());
                ByteBufCodecs.VAR_INT.encode(b, s.unreached());
                ByteBufCodecs.VAR_INT.encode(b, s.slots());
                ByteBufCodecs.VAR_INT.encode(b, s.used());
            }
        };
    }

    public record Tally(List<Line> lines, Summary summary) {
    }

    /** One kind of item, whatever the count: equal when the item and its components are. */
    private record Key(ItemStack item) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && ItemStack.isSameItemSameComponents(item, k.item);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(item);
        }
    }

    /** A chest, barrel or shulker box (or any block tagged a chest or barrel): not a furnace, hopper or dispenser. */
    public static boolean store(BlockEntity be) {
        return be instanceof Container && (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof ShulkerBoxBlockEntity || be.getBlockState().is(Tags.Blocks.CHESTS) || be.getBlockState().is(Tags.Blocks.BARRELS));
    }

    /**
     * What the Ledger makes of the container at {@code p}: -1 if it is none of the warren's stores, else 0 (the Clan
     * Hoard's, taken from first), 1 (any other store in the rooms) or 2 (a work room's supply bin, taken from last).
     */
    public static int role(ServerLevel level, Settlement heart, BlockPos p) {
        if (!store(level.getBlockEntity(p))) {
            return -1;
        }
        boolean inRoom = false;
        List<BoundingBox> hoards = new ArrayList<>();
        for (Building b : heart.buildings()) {
            inRoom |= b.box().isInside(p);
            Rooms.Room r = Rooms.get(b.room());
            if (r != null && r.function() == Rooms.Function.HOARD) {
                hoards.add(b.box());
            }
        }
        return inRoom ? rank(level, p, hoards) : -1;
    }

    private static List<BoundingBox> rooms(Settlement heart) {
        List<BoundingBox> out = new ArrayList<>();
        for (Building b : heart.buildings()) {
            out.add(b.box());
        }
        return out;
    }

    /**
     * The warren's stores, in the order they are taken from (the Clan Hoard, the rest, the work rooms' supply bins).
     * The ones in loaded chunks are looked for afresh; the rest are the ones the Heart knew of when last it looked.
     */
    public static List<BlockPos> stores(ServerLevel level, Settlement heart) {
        LongOpenHashSet known = heart.ledgerStores();
        Set<BlockPos> found = new LinkedHashSet<>();
        LongOpenHashSet looked = new LongOpenHashSet();                  // chunks looked in
        for (BoundingBox box : rooms(heart)) {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    if (level.getChunkSource().getChunkNow(cx, cz) != null) {
                        looked.add(ChunkPos.pack(cx, cz));
                    }
                }
            }
            for (BlockPos p : Bins.containers(level, box)) {
                if (store(level.getBlockEntity(p))) {
                    found.add(p);
                }
            }
        }
        LongOpenHashSet now = new LongOpenHashSet();
        for (BlockPos p : found) {
            now.add(p.asLong());
        }
        for (long p : known) {
            BlockPos bp = BlockPos.of(p);
            if (!looked.contains(ChunkPos.pack(bp.getX() >> 4, bp.getZ() >> 4))) {
                now.add(p);                                             // not looked at: kept as it was
                found.add(bp);
            }
        }
        if (!now.equals(known)) {
            heart.setLedgerStores(now);
        }
        List<BoundingBox> hoards = new ArrayList<>();
        for (Building b : heart.buildings()) {
            Rooms.Room r = Rooms.get(b.room());
            if (r != null && r.function() == Rooms.Function.HOARD) {
                hoards.add(b.box());
            }
        }
        List<BlockPos> out = new ArrayList<>(found);
        out.sort(Comparator.comparingInt((BlockPos p) -> rank(level, p, hoards)).thenComparingLong(BlockPos::asLong));
        return out;
    }

    /** The haul log's name for the owner's own chests in the settlement (the Hoard's is the settlement's: Settlement#hoardName). */
    static final String OWN_CHESTS = "Your chests";

    /**
     * The haul log's name for the store at {@code p}: the Hoard's name in a hoard room ({@link Settlement#hoardName}, the name every other road
     * of the Hoard's goes by: a race whose hoard room is named otherwise has one row for it, not two), else its room, or the owner's own chests.
     */
    static String place(Settlement heart, BlockPos p) {
        for (Building b : heart.buildings()) {
            Rooms.Room r = Rooms.get(b.room());
            if (r != null && r.function() == Rooms.Function.HOARD && b.box().isInside(p)) {
                return heart.hoardName();
            }
        }
        String room = heart.placeOf(p);
        return room.isEmpty() ? OWN_CHESTS : room;
    }

    /** 0: the Clan Hoard's; 1: anything else; 2: a work room's supply bin. */
    private static int rank(ServerLevel level, BlockPos p, List<BoundingBox> hoards) {
        for (BoundingBox h : hoards) {
            if (h.isInside(p)) {
                return 0;
            }
        }
        return level.isLoaded(p) && SUPPLY.contains(Bins.nameOf(level, p).toLowerCase(Locale.ROOT)) ? 2 : 1;
    }

    /**
     * Holds the chunks of the warren's stores (and the Heart's) loaded for a while, not ticking, so a Ledger far from the
     * warren can count and take. Returns how many of those chunks are not loaded yet.
     */
    public static int wake(ServerLevel level, Settlement heart) {
        LongOpenHashSet chunks = new LongOpenHashSet();
        chunks.add(ChunkPos.pack(heart.getBlockPos()));
        for (long p : heart.ledgerStores()) {
            chunks.add(ChunkPos.pack(BlockPos.of(p)));
        }
        if (heart.ledgerStores().isEmpty()) {
            for (BoundingBox box : rooms(heart)) {                      // never counted: every room of the warren, once
                for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                    for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                        chunks.add(ChunkPos.pack(cx, cz));
                    }
                }
            }
        }
        int unloaded = 0;
        for (long c : chunks) {
            ChunkPos cp = ChunkPos.unpack(c);
            level.getChunkSource().addTicketWithRadius(claude.settlementcore.CoreRegistries.LEDGER_TICKET.get(), cp, 0);   // FULL: its block entities, no ticking
            if (level.getChunkSource().getChunkNow(cp.x(), cp.z()) == null) {
                unloaded++;
            }
        }
        return unloaded;
    }

    /** Everything the clan has, most first, the warpstone hoard at the head. */
    public static Tally tally(ServerLevel level, Settlement heart) {
        Map<Key, long[]> counts = new HashMap<>();
        List<BlockPos> stores = stores(level, heart);
        int unreached = 0, slots = 0, used = 0;
        Set<BoundingBox> rooms = new java.util.HashSet<>();
        for (BlockPos p : stores) {
            Container c = Bins.at(level, p);
            if (c == null) {
                unreached++;
                continue;
            }
            for (BoundingBox r : rooms(heart)) {
                if (r.isInside(p)) {
                    rooms.add(r);
                    break;
                }
            }
            slots += c.getContainerSize();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) {
                    used++;
                    counts.computeIfAbsent(new Key(s.copyWithCount(1)), k -> new long[1])[0] += s.getCount();
                }
            }
        }
        for (ItemStack s : heart.spoils()) {
            if (!s.isEmpty()) {
                counts.computeIfAbsent(new Key(s.copyWithCount(1)), k -> new long[1])[0] += s.getCount();
            }
        }
        List<Line> lines = new ArrayList<>(counts.size() + 1);
        for (Map.Entry<Key, long[]> e : counts.entrySet()) {
            lines.add(new Line(e.getKey().item(), e.getValue()[0], STORES));
        }
        lines.sort(Comparator.comparingLong(Line::count).reversed()
                .thenComparing(l -> l.item().getItem().builtInRegistryHolder().key().identifier().toString()));
        if (heart.currency() > 0) {
            lines.add(0, new Line(new ItemStack(heart.currencyItem()), heart.currency(), WARPSTONE));
        }
        return new Tally(lines, new Summary(heart.settlementName(), heart.currency(), heart.food(), heart.fare().label,
                stores.size(), rooms.size(), unreached, slots, used));
    }

    /**
     * Up to {@code n} (at most a stack) of what {@code like} stands for, out of the clan's hoard: from {@code source}, in
     * the taking order. Returns what was taken (empty: none of it is there, or none of it could be reached).
     */
    public static ItemStack take(ServerLevel level, Settlement heart, ItemStack like, int n, int source) {
        if (like.isEmpty() || n <= 0) {
            return ItemStack.EMPTY;
        }
        if (source == WARPSTONE) {
            int got = Math.min(Math.min(n, 64), heart.currency());
            return got > 0 && heart.spendCurrency(got, HaulLog.WARLORD) ? new ItemStack(heart.currencyItem(), got) : ItemStack.EMPTY;
        }
        int want = Math.min(n, like.getMaxStackSize());
        ItemStack got = like.copyWithCount(0);
        for (BlockPos p : stores(level, heart)) {
            Container c = Bins.at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize() && got.getCount() < want; i++) {
                ItemStack have = c.getItem(i);
                if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, like)) {
                    int move = Math.min(want - got.getCount(), have.getCount());
                    have.shrink(move);
                    got.grow(move);
                    if (have.isEmpty()) {
                        c.setItem(i, ItemStack.EMPTY);
                    }
                    c.setChanged();
                    heart.log(place(heart, p), HaulLog.WARLORD, like.getItem(), move);
                }
            }
            if (got.getCount() >= want) {
                return got;
            }
        }
        for (ItemStack have : heart.spoils()) {
            if (got.getCount() >= want) {
                break;
            }
            if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, like)) {
                int move = Math.min(want - got.getCount(), have.getCount());
                have.shrink(move);
                got.grow(move);
                heart.log(heart.spoilsName(), HaulLog.WARLORD, like.getItem(), move);
            }
        }
        heart.spoils().removeIf(ItemStack::isEmpty);
        heart.setChanged();
        return got.isEmpty() ? ItemStack.EMPTY : got;
    }

    /**
     * Into the clan's hoard: warpstone shards and blocks into the Heart's warpstone hoard, the rest sorted into the Clan
     * Hoard, then into the Warlord's own chests in the warren, then onto the Heart's spoils pile. Returns what would not go.
     * Currency goes in by what it is worth to this settlement ({@link Currencies#worth}: its own currency, not another race's), in
     * whole units only: of 20 gold nuggets, nine to the ingot, 18 go in as 2 and the 2 over are given back.
     */
    public static ItemStack put(ServerLevel level, Settlement heart, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Currencies.Worth worth = Currencies.worth(heart, stack);
        if (worth != null) {
            int units = worth.unitsOf(stack.getCount());
            if (units > 0) {
                heart.addCurrency(units, HaulLog.WARLORD);
            }
            int over = stack.getCount() - worth.itemsFor(stack.getCount());
            return over <= 0 ? ItemStack.EMPTY : stack.copyWithCount(over);
        }
        ItemStack rest = stack.copy();
        net.minecraft.world.item.Item item = stack.getItem();
        int was = rest.getCount();
        for (HoardWork h : heart.works().all(HoardWork.class)) {
            rest = h.put(level, rest);
            if (rest.isEmpty()) {
                break;
            }
        }
        heart.log(HaulLog.WARLORD, heart.hoardName(), item, was - rest.getCount());
        if (rest.isEmpty()) {
            return rest;
        }
        List<BlockPos> own = new ArrayList<>();
        for (BlockPos p : stores(level, heart)) {
            if (level.isLoaded(p) && Bins.nameOf(level, p).isEmpty()) {
                own.add(p);                                            // the Warlord's chests, not the rooms' bins
            }
        }
        was = rest.getCount();
        rest = Bins.insert(level, own, rest);
        heart.log(HaulLog.WARLORD, OWN_CHESTS, item, was - rest.getCount());
        was = rest.getCount();
        while (!rest.isEmpty() && heart.spoils().size() < Settlement.MAX_SPOIL_STACKS) {
            ItemStack part = rest.split(rest.getMaxStackSize());
            heart.addSpoil(part);
        }
        heart.log(HaulLog.WARLORD, heart.spoilsName(), item, was - rest.getCount());
        return rest;
    }
}
