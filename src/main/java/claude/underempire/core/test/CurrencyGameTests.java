package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.ledger.HaulLog;
import claude.underempire.core.ledger.SettlementLedger;
import claude.underempire.core.settlement.Currencies;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.work.Works;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * What a currency is worth, and the two places that say so (docs/plans/greatplan-first-spawning.md, section B item 5): the
 * ledger takes a race's currency by {@link Currencies#worth}, whole units only, and a raise that costs too much names the
 * currency in the settlement's own words. A gold currency of the tests' own (ingot 1, block 9, nugget a ninth), put away again
 * when each test is over.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class CurrencyGameTests {

    /** Runs {@code test} with gold a currency (an ingot a unit, nine nuggets an ingot), and takes it away again however it ends. */
    private static void withGold(Runnable test) {
        Currencies.Mark before = Currencies.mark();
        try {
            Currencies.register(() -> Items.GOLD_INGOT, () -> Items.GOLD_BLOCK, null);
            Currencies.alsoWorth(() -> Items.GOLD_NUGGET, 1, 9);
            test.run();
        } finally {
            Currencies.rollBack(before);
        }
    }

    private static void worthIs(GameTestHelper h, ItemStack s, int units, int per) {
        Currencies.Worth w = Currencies.worth(s);
        h.assertTrue(w != null && w.units() == units && w.per() == per, s + " is worth " + w + ", not " + units + " per " + per);
    }

    /**
     * The unit is worth one, the block nine and a nugget a ninth, and only whole units are counted: 20 nuggets come to 2 and
     * take 18, 8 come to nothing and take none. What is no one's currency is worth nothing; and the test's gold is forgotten after.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aCurrencyIsWorthItsUnitItsBlockAndWhatARaceCountsIn(GameTestHelper h) {
        withGold(() -> {
            worthIs(h, new ItemStack(Items.GOLD_INGOT), 1, 1);
            worthIs(h, new ItemStack(Items.GOLD_BLOCK), 9, 1);
            worthIs(h, new ItemStack(Items.GOLD_NUGGET), 1, 9);
            h.assertTrue(Currencies.worth(new ItemStack(Items.IRON_INGOT)) == null, "iron is worth something: " + Currencies.worth(new ItemStack(Items.IRON_INGOT)));
            Currencies.Worth nugget = Currencies.worth(new ItemStack(Items.GOLD_NUGGET));
            h.assertTrue(nugget.unitsOf(20) == 2 && nugget.itemsFor(20) == 18 && nugget.unitsOf(8) == 0 && nugget.itemsFor(8) == 0
                            && nugget.unitsOf(9) == 1 && nugget.itemsFor(9) == 9 && nugget.unitsOf(0) == 0,
                    "20 nuggets come to " + nugget.unitsOf(20) + " and take " + nugget.itemsFor(20) + ", 8 to " + nugget.unitsOf(8) + " and take " + nugget.itemsFor(8));
            Currencies.Worth block = Currencies.worth(new ItemStack(Items.GOLD_BLOCK));
            h.assertTrue(block.unitsOf(3) == 27 && block.itemsFor(3) == 3, "3 blocks come to " + block.unitsOf(3) + " and take " + block.itemsFor(3));
        });
        h.assertTrue(Currencies.worth(new ItemStack(Items.GOLD_INGOT)) == null && Currencies.worth(new ItemStack(Items.GOLD_NUGGET)) == null
                && !Currencies.unit(new ItemStack(Items.GOLD_INGOT)), "the test's gold is still a currency after it");
        h.succeed();
    }

    /**
     * The ledger puts a race's currency in by its worth: an ingot a unit, a block nine, and of 20 nuggets only the 18 that make
     * 2 ingots go in, the 2 over coming back; 8 nuggets make nothing, go in as nothing and all come back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theLedgerTakesOnlyWholeUnitsOfWhatIsWorthAFraction(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        withGold(() -> {
            List<Integer> paid = new ArrayList<>();
            List<String> from = new ArrayList<>();
            Settlement heart = new StubSettlement().answer("addCurrency", a -> {
                paid.add((Integer) a[0]);
                from.add((String) a[1]);
                return null;
            }).settlement();
            ItemStack rest = SettlementLedger.put(level, heart, new ItemStack(Items.GOLD_NUGGET, 20));
            h.assertTrue(paid.equals(List.of(2)) && rest.is(Items.GOLD_NUGGET) && rest.getCount() == 2,
                    "20 nuggets paid " + paid + " and gave back " + rest + ", not 2 and 2 nuggets");
            paid.clear();
            rest = SettlementLedger.put(level, heart, new ItemStack(Items.GOLD_NUGGET, 8));
            h.assertTrue(paid.isEmpty() && rest.is(Items.GOLD_NUGGET) && rest.getCount() == 8, "8 nuggets paid " + paid + " and gave back " + rest + ", not nothing and all 8");
            rest = SettlementLedger.put(level, heart, new ItemStack(Items.GOLD_INGOT, 5));
            h.assertTrue(paid.equals(List.of(5)) && rest.isEmpty(), "5 ingots paid " + paid + " and gave back " + rest);
            paid.clear();
            rest = SettlementLedger.put(level, heart, new ItemStack(Items.GOLD_BLOCK, 3));
            h.assertTrue(paid.equals(List.of(27)) && rest.isEmpty(), "3 blocks paid " + paid + " and gave back " + rest + ", not 27 and nothing");
            h.assertTrue(from.stream().allMatch(HaulLog.WARLORD::equals), "the deposits were logged as from " + from + ", not the owner's hand");
        });
        h.succeed();
    }

    /**
     * A raise that costs more than the settlement has is refused in the settlement's own words: "costs 12 gold ingots, and it has
     * 3" when its race names its currency, and, as it always read, "costs 10 of the settlement's currency" when it does not.
     * Nothing is spent (the stub is asked once and says no).
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "settlement")
    public static void aRaiseTooDearNamesTheSettlementsCurrency(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            int cost = s.pl.room().cost();
            for (String name : new String[]{"gold ingots", null}) {
                List<Integer> spent = new ArrayList<>();
                StubSettlement stub = new StubSettlement().is("inSettlement", true).is("currency", 3).quiet("setChanged")
                        .answer("roomCells", a -> new LongOpenHashSet()).answer("spendCurrency", a -> {
                            spent.add((Integer) a[0]);
                            return false;
                        });
                if (name != null) {
                    stub.is("currencyName", name);
                }
                String no = new Works(stub.settlement()).raise(s.level, s.pl, false);
                String want = "The Test Platform costs " + (name == null ? "10 of the settlement's currency" : "10 gold ingots") + ", and it has 3";
                h.assertTrue(cost == 10 && want.equals(no) && spent.equals(List.of(10)),
                        "the refusal for " + (name == null ? "an unnamed currency" : name) + " is \"" + no + "\" (asked to spend " + spent + "), not \"" + want + "\"");
            }
            s.clear();
            h.succeed();
        }));
    }
}
