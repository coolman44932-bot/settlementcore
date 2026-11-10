package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.ledger.LedgerMenu;
import claude.settlementcore.ledger.SettlementLedger;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.SettlementSpawns;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.work.HoardWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.function.Predicate;

/**
 * The Ledger's menu, the server's side (core/ledger/LedgerMenu; the screen is the client's and is looked at in a game): what a
 * click does to the stores, the cursor and the pack, and who may click. Each test has a settlement of the tests' own with an owner
 * (a mock player) and a Hoard room in the air over its plot: a barrel named for the metals with a hundred iron ingots in it, and
 * one named for timber. The settlement stands in no chunk, so it is put on the registry of loaded settlements, where the menu
 * finds it by its heart's place. The clicks are the menu's own handlers, called as the wire would call them: no packet is sent.
 */
@GameTestHolder(Core.MODID)
public class LedgerGameTests {

    static final String HOARD = "ledger_test_hoard";
    private static final Predicate<ServerPlayer> ALWAYS = p -> true;

    /** A settlement, its owner and its Hoard's two barrels. */
    private static final class Hold {
        final ServerLevel level;
        final TestSettlement t;
        final ServerPlayer owner;
        final BlockPos metals, timber;

        Hold(GameTestHelper h) {
            level = h.getLevel();
            t = TestSettlement.of(h, new BlockPos(2, 2, 2));
            owner = h.makeMockServerPlayerInLevel();
            t.claim(owner, "Karak Ledger");
            Rooms.Room room = UnloadedRoomsGameTests.room(HOARD, "Test Hoard", Rooms.Function.HOARD, List.of());
            PlanPlacement pl = PlanPlacement.compute(room, h.absolutePos(new BlockPos(20, 26, 20)), Direction.EAST);
            metals = new BlockPos(pl.box().minX() + 1, pl.box().minY() + 1, pl.box().minZ() + 1);
            timber = metals.offset(2, 0, 0);
            barrel(metals, HoardWork.Kind.METALS.bin);
            barrel(timber, HoardWork.Kind.TIMBER.bin);
            Container c = (Container) level.getBlockEntity(metals);
            c.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
            c.setItem(1, new ItemStack(Items.IRON_INGOT, 36));
            t.addBuilding(new Building(room.id(), pl.box(), pl.door(), Direction.EAST), false);
            t.works().second(level, 1);                          // the Hoard's work made, as a dug room's is
            h.assertTrue(t.works().all(HoardWork.class).size() == 1, "the test's Hoard has no work: a put would sort nothing, the test shows nothing");
            SettlementSpawns.add(level, t);                      // loaded, as a heart that ticks is: the menu finds it by its place
        }

        private void barrel(BlockPos at, String name) {
            level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
            CompoundTag tag = new CompoundTag();
            tag.store("CustomName", ComponentSerialization.CODEC, Component.literal(name));
            level.getBlockEntity(at).loadWithComponents(claude.settlementcore.Nbt.input(tag, level.registryAccess()));
        }

        LedgerMenu menu() {
            return LedgerMenu.serverSide(1, owner, t, ALWAYS);
        }

        int in(BlockPos barrel, Item item) {
            Container c = (Container) level.getBlockEntity(barrel);
            int n = 0;
            for (int i = 0; i < c.getContainerSize(); i++) {
                n += c.getItem(i).is(item) ? c.getItem(i).getCount() : 0;
            }
            return n;
        }

        /** The barrels emptied and gone (emptied first: a barrel broken full spills over the plot), and the settlement off the registry. */
        void close() {
            for (BlockPos p : List.of(metals, timber)) {
                if (level.getBlockEntity(p) instanceof Container c) {
                    c.clearContent();
                }
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
            t.setRemoved();
        }
    }

    private static int inPack(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().getNonEquipmentItems()) {
            n += s.is(item) ? s.getCount() : 0;
        }
        return n;
    }

    private static String held(LedgerMenu m) {
        return m.getCarried().isEmpty() ? "nothing" : m.getCarried().getCount() + " " + m.getCarried().getItem();
    }

    /**
     * A take puts a stack on the cursor and takes it out of the store: 64 of the hundred iron ingots, 36 left. A second take onto
     * a full cursor takes nothing, and nor does one with something else carried.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aTakePutsTheStackOnTheCursorAndOutOfTheStore(GameTestHelper h) {
        Hold s = new Hold(h);
        try {
            LedgerMenu m = s.menu();
            h.assertTrue(m != null && m.stillValid(s.owner), "the owner's Ledger did not open, or is not valid");
            ItemStack iron = new ItemStack(Items.IRON_INGOT);
            m.act(s.owner, LedgerMenu.TAKE, iron, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().is(Items.IRON_INGOT) && m.getCarried().getCount() == 64, "the cursor holds " + held(m) + ", not 64 iron ingots");
            h.assertTrue(s.in(s.metals, Items.IRON_INGOT) == 36, "the store holds " + s.in(s.metals, Items.IRON_INGOT) + " iron ingots after the take, not 36");
            m.act(s.owner, LedgerMenu.TAKE, iron, 32, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().getCount() == 64 && s.in(s.metals, Items.IRON_INGOT) == 36,
                    "a take onto a full cursor moved something: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.IRON_INGOT));
            m.setCarried(new ItemStack(Items.STICK, 3));
            m.act(s.owner, LedgerMenu.TAKE, iron, 16, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().is(Items.STICK) && m.getCarried().getCount() == 3 && s.in(s.metals, Items.IRON_INGOT) == 36,
                    "a take with sticks carried moved something: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.IRON_INGOT));
            m.setCarried(new ItemStack(Items.IRON_INGOT, 60));
            m.act(s.owner, LedgerMenu.TAKE, iron, 32, LedgerMenu.FROM_STORES);   // half a stack asked, room for four
            h.assertTrue(m.getCarried().getCount() == 64 && s.in(s.metals, Items.IRON_INGOT) == 32,
                    "a take onto a cursor four short of a stack: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.IRON_INGOT) + ", not 64 and 32");
        } finally {
            s.close();
        }
        h.succeed();
    }

    /**
     * A shift-take goes straight into the pack, the cursor left empty; and with the pack full nothing leaves the store at all
     * (never taken out to be put back, or lost).
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aShiftTakeGoesStraightIntoTheInventory(GameTestHelper h) {
        Hold s = new Hold(h);
        try {
            LedgerMenu m = s.menu();
            ItemStack iron = new ItemStack(Items.IRON_INGOT);
            h.assertTrue(inPack(s.owner, Items.IRON_INGOT) == 0, "the mock player came with iron ingots: the test shows nothing");
            m.act(s.owner, LedgerMenu.TAKE_TO_INVENTORY, iron, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(inPack(s.owner, Items.IRON_INGOT) == 64 && m.getCarried().isEmpty(),
                    "the pack holds " + inPack(s.owner, Items.IRON_INGOT) + " iron ingots and the cursor " + held(m) + ", not 64 and nothing");
            h.assertTrue(s.in(s.metals, Items.IRON_INGOT) == 36, "the store holds " + s.in(s.metals, Items.IRON_INGOT) + " iron ingots after the shift-take, not 36");
            var pack = s.owner.getInventory().getNonEquipmentItems();
            for (int i = 0; i < pack.size(); i++) {
                pack.set(i, new ItemStack(Items.DIRT, 64));
            }
            m.act(s.owner, LedgerMenu.TAKE_TO_INVENTORY, iron, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(s.in(s.metals, Items.IRON_INGOT) == 36 && inPack(s.owner, Items.IRON_INGOT) == 0,
                    "a shift-take into a full pack moved iron: the store " + s.in(s.metals, Items.IRON_INGOT) + ", the pack " + inPack(s.owner, Items.IRON_INGOT));
            pack.set(5, new ItemStack(Items.IRON_INGOT, 54));    // room for ten more, and no empty slot
            m.act(s.owner, LedgerMenu.TAKE_TO_INVENTORY, iron, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(s.in(s.metals, Items.IRON_INGOT) == 26 && inPack(s.owner, Items.IRON_INGOT) == 64,
                    "a shift-take into a pack with room for ten: the store " + s.in(s.metals, Items.IRON_INGOT) + ", the pack " + inPack(s.owner, Items.IRON_INGOT) + ", not 26 and 64");
        } finally {
            s.owner.getInventory().clearContent();
            s.close();
        }
        h.succeed();
    }

    /**
     * A put of the carried stack lands in the Hoard's own section: logs in the barrel named for timber, not the metals'; a
     * right-click puts one; and a shift-click on a stack of the pack puts that stack in, sorted the same way.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aPutOfTheCarriedStackLandsInTheHoardsOwnSection(GameTestHelper h) {
        Hold s = new Hold(h);
        try {
            LedgerMenu m = s.menu();
            m.setCarried(new ItemStack(Items.OAK_LOG, 40));
            m.act(s.owner, LedgerMenu.PUT_CARRIED, ItemStack.EMPTY, 40, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().isEmpty(), "the cursor still holds " + held(m) + " after the put");
            h.assertTrue(s.in(s.timber, Items.OAK_LOG) == 40 && s.in(s.metals, Items.OAK_LOG) == 0,
                    "the logs went " + s.in(s.timber, Items.OAK_LOG) + " to the timber's barrel and " + s.in(s.metals, Items.OAK_LOG) + " to the metals', not 40 and 0");
            m.setCarried(new ItemStack(Items.GOLD_INGOT, 10));
            m.act(s.owner, LedgerMenu.PUT_CARRIED, ItemStack.EMPTY, 1, LedgerMenu.FROM_STORES);   // a right-click: one
            h.assertTrue(m.getCarried().getCount() == 9 && s.in(s.metals, Items.GOLD_INGOT) == 1 && s.in(s.timber, Items.GOLD_INGOT) == 0,
                    "a put of one gold ingot: the cursor " + held(m) + ", the metals' barrel " + s.in(s.metals, Items.GOLD_INGOT) + ", not 9 and 1");
            m.act(s.owner, LedgerMenu.PUT_CARRIED, ItemStack.EMPTY, 500, LedgerMenu.FROM_STORES); // more than is carried: all of it, no more
            h.assertTrue(m.getCarried().isEmpty() && s.in(s.metals, Items.GOLD_INGOT) == 10,
                    "a put of more than is carried: the cursor " + held(m) + ", the metals' barrel " + s.in(s.metals, Items.GOLD_INGOT) + ", not nothing and 10");
            s.owner.getInventory().setItem(0, new ItemStack(Items.SPRUCE_LOG, 12));
            m.quickMoveStack(s.owner, 27);                       // the menu's slots: the pack's three rows (0 to 26), then the hotbar (27: its first)
            h.assertTrue(s.owner.getInventory().getItem(0).isEmpty() && s.in(s.timber, Items.SPRUCE_LOG) == 12,
                    "a shift-click on 12 spruce logs left " + s.owner.getInventory().getItem(0) + " in the slot and put " + s.in(s.timber, Items.SPRUCE_LOG) + " with the timber");
        } finally {
            s.owner.getInventory().clearContent();
            s.close();
        }
        h.succeed();
    }

    /**
     * Only the owner opens it and only the owner acts: a stranger's open is refused and makes no menu; a stranger's click on the
     * owner's open Ledger does nothing; once the settlement changes hands the old owner's Ledger is no longer valid (the server
     * shuts it) and its clicks and shift-clicks do nothing; a race's own condition failing shuts it too; and so does the heart
     * being gone.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void onlyTheOwnerOpensTheLedgerOrActsOnIt(GameTestHelper h) {
        Hold s = new Hold(h);
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        try {
            ItemStack iron = new ItemStack(Items.IRON_INGOT);
            h.assertTrue(!LedgerMenu.open(stranger, s.t) && stranger.containerMenu == stranger.inventoryMenu, "a stranger opened the settlement's Ledger");
            h.assertTrue(LedgerMenu.serverSide(1, stranger, s.t, ALWAYS) == null, "a menu was made for a stranger");
            h.assertTrue(!LedgerMenu.open(s.owner, s.t, p -> false) && s.owner.containerMenu == s.owner.inventoryMenu, "the Ledger opened with the race's own condition unmet");

            LedgerMenu m = s.menu();
            m.act(stranger, LedgerMenu.TAKE, iron, 64, LedgerMenu.FROM_STORES);
            m.act(stranger, LedgerMenu.TAKE_TO_INVENTORY, iron, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().isEmpty() && inPack(stranger, Items.IRON_INGOT) == 0 && s.in(s.metals, Items.IRON_INGOT) == 100,
                    "a stranger's click on the owner's Ledger took iron: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.IRON_INGOT));
            h.assertTrue(!m.stillValid(stranger), "the owner's Ledger is valid for a stranger");

            boolean[] near = {true};
            LedgerMenu tied = LedgerMenu.serverSide(2, s.owner, s.t, p -> near[0]);
            h.assertTrue(tied != null && tied.stillValid(s.owner), "a Ledger with the race's condition met is not valid");
            near[0] = false;
            h.assertTrue(!tied.stillValid(s.owner), "a Ledger stays valid with the race's condition no longer met");

            s.t.claim(stranger, "Karak Taken");                  // the settlement changes hands with the old owner's Ledger open
            h.assertTrue(!m.stillValid(s.owner), "the old owner's Ledger is still valid after the settlement changed hands");
            m.act(s.owner, LedgerMenu.TAKE, iron, 64, LedgerMenu.FROM_STORES);
            s.owner.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 8));
            m.quickMoveStack(s.owner, 27);
            m.setCarried(new ItemStack(Items.OAK_LOG, 4));
            m.act(s.owner, LedgerMenu.PUT_CARRIED, ItemStack.EMPTY, 4, LedgerMenu.FROM_STORES);
            h.assertTrue(s.in(s.metals, Items.IRON_INGOT) == 100 && s.in(s.timber, Items.OAK_LOG) == 0 && m.getCarried().getCount() == 4
                            && s.owner.getInventory().getItem(0).getCount() == 8,
                    "the old owner still took or put: the store " + s.in(s.metals, Items.IRON_INGOT) + " iron and " + s.in(s.timber, Items.OAK_LOG)
                            + " logs, the cursor " + held(m) + ", the slot " + s.owner.getInventory().getItem(0));

            LedgerMenu theirs = LedgerMenu.serverSide(3, stranger, s.t, ALWAYS);
            h.assertTrue(theirs != null && theirs.stillValid(stranger), "the new owner's Ledger did not open");
            s.t.setRemoved();                                    // the heart is broken
            h.assertTrue(!theirs.stillValid(stranger), "a Ledger is still valid with its heart gone");
            theirs.act(stranger, LedgerMenu.TAKE, iron, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(theirs.getCarried().isEmpty() && s.in(s.metals, Items.IRON_INGOT) == 100, "a Ledger with its heart gone still took iron");
        } finally {
            s.owner.getInventory().clearContent();
            s.close();
        }
        h.succeed();
    }

    /**
     * A take of more than is there gives only what is there: five diamonds for a stack asked, then nothing; nothing for a thing
     * the stores never had, or for none or fewer than none asked; and the currency's line is taken through the same call, as its
     * item and out of the settlement's count, three for a stack asked.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aTakeOfMoreThanIsThereGivesOnlyWhatIsThere(GameTestHelper h) {
        Hold s = new Hold(h);
        try {
            ((Container) s.level.getBlockEntity(s.metals)).setItem(5, new ItemStack(Items.DIAMOND, 5));
            LedgerMenu m = s.menu();
            ItemStack diamond = new ItemStack(Items.DIAMOND);
            m.act(s.owner, LedgerMenu.TAKE, diamond, 0, LedgerMenu.FROM_STORES);
            m.act(s.owner, LedgerMenu.TAKE, diamond, -7, LedgerMenu.FROM_STORES);
            m.act(s.owner, LedgerMenu.TAKE, new ItemStack(Items.EMERALD), 64, LedgerMenu.FROM_STORES);
            m.act(s.owner, LedgerMenu.TAKE, ItemStack.EMPTY, 64, LedgerMenu.FROM_STORES);
            m.act(s.owner, LedgerMenu.TAKE, diamond, 64, 7);     // no such source
            m.act(s.owner, 9, diamond, 64, LedgerMenu.FROM_STORES); // no such click
            h.assertTrue(m.getCarried().isEmpty() && s.in(s.metals, Items.DIAMOND) == 5, "a take of nothing, of a thing not there or of no kind gave " + held(m));
            m.act(s.owner, LedgerMenu.TAKE, diamond, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().is(Items.DIAMOND) && m.getCarried().getCount() == 5 && s.in(s.metals, Items.DIAMOND) == 0,
                    "a stack of diamonds asked of five: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.DIAMOND) + ", not 5 and 0");
            m.act(s.owner, LedgerMenu.TAKE, diamond, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().getCount() == 5, "a second take of diamonds, none left, made the cursor " + held(m));
            m.setCarried(ItemStack.EMPTY);
            m.act(s.owner, LedgerMenu.TAKE_TO_INVENTORY, diamond, 64, LedgerMenu.FROM_STORES);
            h.assertTrue(inPack(s.owner, Items.DIAMOND) == 0, "a shift-take of diamonds, none left, gave " + inPack(s.owner, Items.DIAMOND));

            s.t.addCurrency(3, "Test");
            Item unit = s.t.currencyItem();
            m.act(s.owner, LedgerMenu.TAKE, new ItemStack(Items.DIRT), 64, LedgerMenu.FROM_CURRENCY);   // whatever the line's item, the currency is the settlement's own
            h.assertTrue(m.getCarried().is(unit) && m.getCarried().getCount() == 3 && s.t.currency() == 0,
                    "a stack of the currency asked of three: the cursor " + held(m) + ", the settlement holds " + s.t.currency() + ", not 3 and 0");
            m.act(s.owner, LedgerMenu.PUT_CARRIED, ItemStack.EMPTY, 3, LedgerMenu.FROM_STORES);
            h.assertTrue(m.getCarried().isEmpty() && s.t.currency() == 3, "the currency put back: the cursor " + held(m) + ", the settlement holds " + s.t.currency() + ", not nothing and 3");
        } finally {
            s.owner.getInventory().clearContent();
            s.close();
        }
        h.succeed();
    }

    /**
     * A click from the wire names a line of a tally the server made, never an item: the iron's line of the last tally gives iron;
     * a tally the server never sent, or a line past its end, gives nothing; and the tally has the stores as they are (a hundred
     * iron ingots in two stores of one room, none unreached).
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aClickNamesALineOfATallyTheServerMade(GameTestHelper h) {
        Hold s = new Hold(h);
        try {
            LedgerMenu m = s.menu();
            h.assertTrue(m.lastSerial() == 0 && m.lastLines().isEmpty(), "a menu not yet ticked has a tally");
            m.broadcastChanges();                                // its first tick: the tally is made (and sent, to a player with the channel)
            int first = m.lastSerial();
            List<SettlementLedger.Line> lines = m.lastLines();
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).item().is(Items.IRON_INGOT)) {
                    at = i;
                }
            }
            h.assertTrue(first == 1 && at >= 0 && lines.get(at).count() == 100 && lines.get(at).source() == LedgerMenu.FROM_STORES,
                    "the first tally (number " + first + ") has no line of 100 iron ingots: " + lines);
            SettlementLedger.Summary sum = SettlementLedger.tally(s.level, s.t).summary();
            h.assertTrue(sum.stores() == 2 && sum.rooms() == 1 && sum.unreached() == 0 && sum.used() == 2,
                    "the summary counts " + sum.stores() + " stores in " + sum.rooms() + " rooms, " + sum.unreached() + " unreached, " + sum.used() + " slots used, not 2, 1, 0 and 2");
            m.click(s.owner, LedgerMenu.TAKE, first + 40, at, 64);
            m.click(s.owner, LedgerMenu.TAKE, first, lines.size(), 64);
            m.click(s.owner, LedgerMenu.TAKE, first, -1, 64);
            h.assertTrue(m.getCarried().isEmpty() && s.in(s.metals, Items.IRON_INGOT) == 100, "a click on a tally never sent, or past its end, took " + held(m));
            m.click(s.owner, LedgerMenu.TAKE, first, at, 64);
            h.assertTrue(m.getCarried().is(Items.IRON_INGOT) && m.getCarried().getCount() == 64 && s.in(s.metals, Items.IRON_INGOT) == 36,
                    "a click on the iron's line: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.IRON_INGOT) + ", not 64 and 36");
            h.assertTrue(m.lastSerial() > first, "the take made no new tally: the screen would show the old count");
            m.click(s.owner, LedgerMenu.PUT_CARRIED, first, at, 64);       // a put names no line: the carried stack goes back in
            h.assertTrue(m.getCarried().isEmpty() && s.in(s.metals, Items.IRON_INGOT) == 100, "the iron put back: the cursor " + held(m) + ", the store " + s.in(s.metals, Items.IRON_INGOT));
            m.click(s.owner, LedgerMenu.TAKE, first, at, 16);            // the older tally is still held: a click made on it a moment ago counts
            h.assertTrue(m.getCarried().getCount() == 16, "a click on the tally before the last took " + held(m) + ", not 16 iron ingots");
        } finally {
            s.close();
        }
        h.succeed();
    }
}
