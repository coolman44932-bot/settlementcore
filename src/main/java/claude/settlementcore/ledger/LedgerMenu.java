package claude.settlementcore.ledger;

import claude.settlementcore.Core;
import claude.settlementcore.CoreRegistries;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.SettlementSpawns;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Ledger's screen, server and client ({@link SettlementLedger}): everything a settlement keeps, in a grid over its owner's own
 * inventory, like an ME terminal. Only the owner's inventory is real slots; the stores are sent as a tally
 * ({@link LedgerNet.LedgerData}) and drawn on by clicks ({@link LedgerNet.LedgerAct}): take a stack onto the cursor or into the
 * inventory, put the carried stack in. Shift-clicking a stack of the inventory puts it in. The Under-Empire's Hoard-Ledger, made
 * the engine's (2026-11-10): a race opens it from a block or an item of its own with {@link #open}, and gives it its own look
 * (Settlement#ledgerLook, client/LedgerLooks).
 *
 * <p>The server finds the settlement afresh from its heart's place each time it is asked anything, and the menu shuts itself
 * the tick the heart is gone or is no longer this player's. While it is open the stores' chunks are held loaded
 * ({@link SettlementLedger#wake}), so it works from anywhere.
 */
public class LedgerMenu extends AbstractContainerMenu {

    /** The engine's look's id (client/LedgerLooks): what a settlement that names none opens in. */
    public static final String DEFAULT_LOOK = Core.MODID;

    /** The screen: the panel, the stores' grid (and its scrollbar), the owner's inventory. The sheet is drawn by the same numbers (tools/gen_ledger_gui.py). */
    public static final int W = 195, H = 242;
    public static final int GRID_X = 8, GRID_Y = 36, COLS = 9, ROWS = 6;
    public static final int INV_X = 9, INV_Y = 160, HOTBAR_Y = 218;

    /** What a click on the grid asks. */
    public static final int TAKE = 0, TAKE_TO_INVENTORY = 1, PUT_CARRIED = 2;
    /** Where a line is kept, in words that name no race: the stores (and the spoils), or the settlement's currency. */
    public static final int FROM_STORES = SettlementLedger.STORES, FROM_CURRENCY = SettlementLedger.WARPSTONE;

    /** The Flows page shows the busiest roads, this many at most. */
    public static final int MAX_FLOWS = 300;
    /**
     * The bytes of lines one tally carries at most: a packet to the client holds 1048576, and a store of full shulker boxes could
     * pass it. The tally comes most first, so what is left out is the rarest, and the screen says how many kinds it could not show.
     */
    public static final int LINE_BUDGET = 900_000;
    /** The lines sent when their size cannot be measured: few enough to fit whatever they are, short of the outlandish. */
    private static final int UNMEASURED_LINES = 2048;
    /** The tallies the server remembers sending: a click names one, and the one on the screen may be a second or two old. */
    private static final int REMEMBERED = 4;

    private static final StreamCodec<RegistryFriendlyByteBuf, Item> ITEM = ByteBufCodecs.registry(Registries.ITEM);

    /** The settlement's heart, its look's id, its name, and one of its currency as an item (the summary's first picture). */
    public final BlockPos heartPos;
    public final String look;
    public final String settlementName;
    public final Item currencyItem;
    private final Player player;

    /** Client: the tally as last sent, its number, how many kinds it left out, and a count that goes up whenever a new one comes. */
    public List<SettlementLedger.Line> lines = List.of();
    public SettlementLedger.Summary summary = SettlementLedger.Summary.NONE;
    /** Client: where things went in the last minutes ({@link HaulLog}), for the Flows page. */
    public List<HaulLog.Flow> flows = List.of();
    public int serial;
    public int more;
    public int version;

    /** Server: the heart's world, and what else must stay true for the Ledger to stay open (the race's: near its block, its item in hand). */
    @Nullable
    private final ResourceKey<Level> dimension;
    private final Predicate<ServerPlayer> stays;
    private int ticks;
    private boolean dirty = true;
    private int sentHash;
    private int lastSerial;
    private final ArrayDeque<Sent> sent = new ArrayDeque<>();

    /** A tally the server sent: a click names its number and a line of it. */
    private record Sent(int serial, List<SettlementLedger.Line> lines) {
    }

    /** The client's, made of what the server wrote as it opened ({@link #open}). */
    public LedgerMenu(int id, Inventory inv, @Nullable RegistryFriendlyByteBuf buf) {
        this(id, inv, buf == null ? BlockPos.ZERO : buf.readBlockPos(), buf == null ? DEFAULT_LOOK : buf.readUtf(64),
                buf == null ? "" : buf.readUtf(128), buf == null ? Items.AIR : ITEM.decode(buf), null, p -> true);
    }

    private LedgerMenu(int id, Inventory inv, BlockPos heartPos, String look, String settlementName, Item currencyItem,
                       @Nullable ResourceKey<Level> dimension, Predicate<ServerPlayer> stays) {
        super(CoreRegistries.LEDGER_MENU.get(), id);
        this.heartPos = heartPos;
        this.look = look;
        this.settlementName = settlementName;
        this.currencyItem = currencyItem;
        this.dimension = dimension;
        this.stays = stays;
        this.player = inv.player;
        addStandardInventorySlots(inv, INV_X, INV_Y);              // the three rows, then the hotbar 58 down: HOTBAR_Y
    }

    // ------------------------------------------------------------------ opening

    /** Open {@code heart}'s Ledger for {@code player}. False, and nothing opens, for anyone but its owner. */
    public static boolean open(ServerPlayer player, Settlement heart) {
        return open(player, heart, p -> true);
    }

    /**
     * Open {@code heart}'s Ledger for {@code player}, to stay open only while {@code stays} holds for them: the race's own
     * condition (still within reach of its ledger block, its tally item still on them), asked every tick on the server. False, and
     * nothing opens, for anyone but its owner or with the condition unmet.
     */
    public static boolean open(ServerPlayer player, Settlement heart, Predicate<ServerPlayer> stays) {
        if (!owns(player, heart) || !(heart.getLevel() instanceof ServerLevel) || !stays.test(player)) {
            return false;
        }
        BlockPos pos = heart.getBlockPos().immutable();
        String look = cut(heart.ledgerLook(), 64), name = cut(heart.settlementName(), 128);
        Item currency = heart.currencyItem() == null ? Items.AIR : heart.currencyItem();
        return player.openMenu(new SimpleMenuProvider((id, inv, p) -> serverSide(id, player, heart, stays), Component.literal(name)), buf -> {
            buf.writeBlockPos(pos);
            buf.writeUtf(look, 64);
            buf.writeUtf(name, 128);
            ITEM.encode(buf, currency);
        }).isPresent();
    }

    /**
     * The server's side of {@code heart}'s Ledger for {@code player}, or null if it isn't theirs: what {@link #open} hands the
     * player, and what a test acts on without a screen.
     */
    @Nullable
    public static LedgerMenu serverSide(int containerId, ServerPlayer player, Settlement heart, Predicate<ServerPlayer> stays) {
        if (!owns(player, heart) || !(heart.getLevel() instanceof ServerLevel level)) {
            return null;
        }
        return new LedgerMenu(containerId, player.getInventory(), heart.getBlockPos().immutable(), cut(heart.ledgerLook(), 64),
                cut(heart.settlementName(), 128), heart.currencyItem() == null ? Items.AIR : heart.currencyItem(), level.dimension(), stays);
    }

    private static String cut(@Nullable String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }

    private static boolean owns(Player player, Settlement heart) {
        return !heart.isRemoved() && heart.owner() != null && heart.owner().equals(player.getUUID());
    }

    /**
     * The settlement, found afresh: the block entity at its heart's place, if that chunk is loaded (never loaded for the asking),
     * else a loaded settlement registered at that place (the engine's tests', which stand in no chunk). Null on the client, with
     * the heart gone, or once it is no longer this player's.
     */
    @Nullable
    private Settlement heart() {
        if (dimension == null || !(player instanceof ServerPlayer sp)) {
            return null;
        }
        ServerLevel level = sp.level().getServer().getLevel(dimension);
        if (level == null) {
            return null;
        }
        if (level.getChunkSource().getChunkNow(heartPos.getX() >> 4, heartPos.getZ() >> 4) != null
                && level.getBlockEntity(heartPos) instanceof Settlement s) {
            return owns(sp, s) ? s : null;
        }
        for (Settlement s : SettlementSpawns.loaded(level)) {
            if (s.getBlockPos().equals(heartPos) && owns(sp, s)) {
                return s;
            }
        }
        return null;
    }

    /** False the tick the heart is gone, the settlement changes hands or the race's own condition fails: the server then shuts the screen. */
    @Override
    public boolean stillValid(Player p) {
        if (!(p instanceof ServerPlayer sp)) {
            return true;                                         // the client's copy: the server says when it shuts
        }
        return sp == player && heart() != null && stays.test(sp);
    }

    // ------------------------------------------------------------------ putting and taking

    /** Shift-clicking a stack of the owner's inventory puts it into the stores; what will not go stays in the slot. */
    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (!(p instanceof ServerPlayer) || p != player || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot s = slots.get(index);
        Settlement heart = heart();
        if (!s.hasItem() || !s.mayPickup(p) || heart == null || !(heart.getLevel() instanceof ServerLevel level)) {
            return ItemStack.EMPTY;
        }
        ItemStack rest = SettlementLedger.put(level, heart, s.getItem().copy());
        s.set(rest);
        dirty = true;
        return ItemStack.EMPTY;                                    // empty: the click is done, whatever is left in the slot
    }

    /**
     * A click on the grid as the wire brings it ({@link LedgerNet.LedgerAct}): a take names a line of a tally this menu sent, and
     * the item is the server's own copy of that line. A tally it no longer holds, or a line it never had, takes nothing, and a
     * fresh tally goes out.
     */
    public void click(ServerPlayer sp, int action, int serial, int index, int amount) {
        if (action == PUT_CARRIED) {
            act(sp, PUT_CARRIED, ItemStack.EMPTY, amount, FROM_STORES);
            return;
        }
        SettlementLedger.Line line = sentLine(serial, index);
        if (line == null) {
            dirty = true;
            return;
        }
        act(sp, action, line.item(), amount, line.source());
    }

    /** Line {@code index} of the tally sent as {@code serial}, or null. */
    @Nullable
    private SettlementLedger.Line sentLine(int serial, int index) {
        for (Sent s : sent) {
            if (s.serial() == serial) {
                return index >= 0 && index < s.lines().size() ? s.lines().get(index) : null;
            }
        }
        return null;
    }

    /**
     * What a click does, for the player this menu is open for and no other, and only while the settlement is still theirs:
     * <ul>
     *   <li>{@link #TAKE}: up to {@code amount} of what {@code like} stands for onto the cursor, as far as the cursor has room
     *   (nothing, with something else carried);</li>
     *   <li>{@link #TAKE_TO_INVENTORY}: up to {@code amount}, as far as the inventory has room, straight into it;</li>
     *   <li>{@link #PUT_CARRIED}: {@code amount} of the carried stack into the stores; what will not go stays carried.</li>
     * </ul>
     * Never more than a stack, never more than is there ({@link SettlementLedger#take} gives only what it finds), and
     * {@code source} {@link #FROM_CURRENCY} takes the settlement's currency as its item, whatever {@code like} is.
     */
    public void act(ServerPlayer sp, int action, ItemStack like, int amount, int source) {
        Settlement heart = sp == player ? heart() : null;
        if (heart == null || !(heart.getLevel() instanceof ServerLevel level) || amount <= 0 || source != FROM_STORES && source != FROM_CURRENCY) {
            return;
        }
        ItemStack carried = getCarried();
        switch (action) {
            case TAKE -> {
                ItemStack kind = kind(heart, like, source);
                if (kind.isEmpty() || !carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, kind)) {
                    return;                                      // hands full of something else
                }
                int room = carried.isEmpty() ? kind.getMaxStackSize() : carried.getMaxStackSize() - carried.getCount();
                if (room <= 0) {
                    return;
                }
                ItemStack got = SettlementLedger.take(level, heart, kind, Math.min(amount, room), source);
                if (got.isEmpty()) {
                    return;
                }
                if (carried.isEmpty()) {
                    setCarried(got);
                } else {
                    carried.grow(got.getCount());
                }
            }
            case TAKE_TO_INVENTORY -> {
                ItemStack kind = kind(heart, like, source);
                int room = kind.isEmpty() ? 0 : Math.min(roomFor(sp.getInventory(), kind), kind.getMaxStackSize());
                if (room <= 0) {
                    return;                                      // a full pack: nothing leaves the stores to come straight back
                }
                ItemStack got = SettlementLedger.take(level, heart, kind, Math.min(amount, room), source);
                if (got.isEmpty()) {
                    return;
                }
                sp.getInventory().add(got);
                if (!got.isEmpty()) {
                    ItemStack back = SettlementLedger.put(level, heart, got);    // it did not all go in after all: the rest goes back
                    if (!back.isEmpty()) {
                        sp.drop(back, false);
                    }
                }
            }
            case PUT_CARRIED -> {
                if (carried.isEmpty()) {
                    return;
                }
                ItemStack rest = SettlementLedger.put(level, heart, carried.split(Math.min(amount, carried.getCount())));
                if (!rest.isEmpty()) {
                    if (carried.isEmpty()) {
                        setCarried(rest);
                    } else {
                        carried.grow(rest.getCount());
                    }
                } else if (carried.isEmpty()) {
                    setCarried(ItemStack.EMPTY);
                }
            }
            default -> {
                return;
            }
        }
        dirty = true;
        broadcastChanges();
    }

    /** One of what a take gives: the line's item, or for the currency's line the settlement's own currency item. */
    private static ItemStack kind(Settlement heart, ItemStack like, int source) {
        if (source == FROM_CURRENCY) {
            return heart.currencyItem() == null ? ItemStack.EMPTY : new ItemStack(heart.currencyItem());
        }
        return like.isEmpty() ? ItemStack.EMPTY : like.copyWithCount(1);
    }

    /** How many of {@code kind} the pack's 36 slots would take: the empty ones a stack each, and what its like stacks lack. */
    private static int roomFor(Inventory inv, ItemStack kind) {
        int room = 0, max = kind.getMaxStackSize();
        for (ItemStack s : inv.getNonEquipmentItems()) {
            if (s.isEmpty()) {
                room += max;
            } else if (s.isStackable() && ItemStack.isSameItemSameComponents(s, kind)) {
                room += Math.max(0, s.getMaxStackSize() - s.getCount());
            }
            if (room >= max) {
                return room;                                     // a stack is the most a click takes
            }
        }
        return room;
    }

    // ------------------------------------------------------------------ the tally

    /** Server, every tick: the tally goes to the client when it changes (looked at every second, and after every move). */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(player instanceof ServerPlayer sp) || !(dirty || ++ticks % 20 == 0)) {
            return;
        }
        dirty = false;
        Settlement heart = heart();
        if (heart == null || !(heart.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SettlementLedger.wake(level, heart);                      // its stores' chunks held loaded while it is open
        SettlementLedger.Tally t = SettlementLedger.tally(level, heart);
        int hash = t.summary().hashCode();
        for (SettlementLedger.Line l : t.lines()) {
            hash = hash * 31 + ItemStack.hashItemAndComponents(l.item());
            hash = hash * 31 + Long.hashCode(l.count()) + l.source();
        }
        List<HaulLog.Flow> roads = flows(level, heart);
        hash = hash * 31 + roads.hashCode();
        if (hash == sentHash && ticks % 200 != 0 && !sent.isEmpty()) {
            return;
        }
        sentHash = hash;
        List<SettlementLedger.Line> lines = fitting(sp, level, t.lines());
        int serial = ++lastSerial;
        sent.addLast(new Sent(serial, lines));
        while (sent.size() > REMEMBERED) {
            sent.removeFirst();
        }
        if (sp.connection != null && sp.connection.hasChannel(LedgerNet.LedgerData.TYPE)) {
            PacketDistributor.sendToPlayer(sp, new LedgerNet.LedgerData(containerId, serial, lines, t.lines().size() - lines.size(), t.summary(), roads));
        }
    }

    /**
     * As many of {@code all}, from the head, as fit {@link #LINE_BUDGET}: each is written once to a scratch buffer to learn its size
     * (a line is an item with all its components, a few bytes or a shulker box's worth). A line that cannot be written here is
     * not this method's to report: the count is capped instead and the send says what it says.
     */
    private static List<SettlementLedger.Line> fitting(ServerPlayer sp, ServerLevel level, List<SettlementLedger.Line> all) {
        RegistryFriendlyByteBuf scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess(),
                sp.connection != null ? sp.connection.getConnectionType() : ConnectionType.OTHER);
        try {
            for (int i = 0; i < all.size(); i++) {
                SettlementLedger.Line.STREAM_CODEC.encode(scratch, all.get(i));
                if (scratch.writerIndex() > LINE_BUDGET) {
                    return List.copyOf(all.subList(0, i));
                }
            }
            return all;
        } catch (RuntimeException e) {
            return all.size() > UNMEASURED_LINES ? List.copyOf(all.subList(0, UNMEASURED_LINES)) : all;
        } finally {
            scratch.release();
        }
    }

    /** The roads the Flows page shows: the busiest {@link #MAX_FLOWS} of the settlement's haul log. */
    public static List<HaulLog.Flow> flows(ServerLevel level, Settlement heart) {
        List<HaulLog.Flow> all = heart.hauls(level.getGameTime());
        return all.size() > MAX_FLOWS ? List.copyOf(all.subList(0, MAX_FLOWS)) : all;
    }

    /** Server: the number of the tally last made (0: none yet) and its lines, as a click would name them (the tests'). */
    public int lastSerial() {
        return lastSerial;
    }

    public List<SettlementLedger.Line> lastLines() {
        return sent.isEmpty() ? List.of() : sent.getLast().lines();
    }

    /** Client: a new tally. */
    public void receive(LedgerNet.LedgerData data) {
        this.lines = data.lines();
        this.summary = data.summary();
        this.flows = data.flows();
        this.serial = data.serial();
        this.more = data.more();
        version++;
    }
}
