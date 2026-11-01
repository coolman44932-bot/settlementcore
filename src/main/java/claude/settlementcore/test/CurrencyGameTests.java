package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.ledger.HaulLog;
import claude.settlementcore.ledger.SettlementLedger;
import claude.settlementcore.settlement.Currencies;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.work.Works;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * What a currency is worth, and the places that say so (docs/plans/greatplan-first-spawning.md, section B item 5): the ledger takes a
 * settlement's own currency by {@link Currencies#worth}, whole units only, never another race's, and a raise that costs too much names
 * the currency in the settlement's own words. Currencies of the tests' own, an item neither race counts: copper (ingot 1, block 9,
 * flint scraps a ninth) and iron (ingot 1, block 9, nugget a ninth), each put away again when its test is over.
 */
@GameTestHolder(Core.MODID)
public class CurrencyGameTests {

    /** Copper is a currency (an ingot a unit, nine flint scraps an ingot) while {@code test} runs, and is taken away again however it ends. */
    private static void withCopper(Runnable test) {
        Currencies.Mark before = Currencies.mark();
        try {
            registerCopper();
            test.run();
        } finally {
            Currencies.rollBack(before);
        }
    }

    /** Copper and iron both are, as two races' currencies are once both are loaded. */
    private static void withCopperAndIron(Runnable test) {
        Currencies.Mark before = Currencies.mark();
        try {
            registerCopper();
            Currencies.register(() -> Items.IRON_INGOT, () -> Items.IRON_BLOCK, null);
            Currencies.alsoWorth(() -> Items.IRON_INGOT, () -> Items.IRON_NUGGET, 1, 9);
            test.run();
        } finally {
            Currencies.rollBack(before);
        }
    }

    private static void registerCopper() {
        Currencies.register(() -> Items.COPPER_INGOT, () -> Items.COPPER_BLOCK, null, () -> Blocks.COPPER_ORE);
        Currencies.alsoWorth(() -> Items.COPPER_INGOT, () -> Items.FLINT, 1, 9);
    }

    /** A settlement whose currency is {@code unit} and {@code block}. */
    private static StubSettlement settlementOf(Item unit, Item block) {
        return new StubSettlement().is("currencyItem", unit).is("currencyBlock", block);
    }

    private static void worthIs(GameTestHelper h, Settlement of, ItemStack s, int units, int per) {
        Currencies.Worth w = Currencies.worth(of, s);
        h.assertTrue(w != null && w.units() == units && w.per() == per, s + " is worth " + w + ", not " + units + " per " + per);
    }

    /**
     * The unit is worth one, the block nine and a scrap a ninth, and only whole units are counted: 20 scraps come to 2 and
     * take 18, 8 come to nothing and take none. What is no one's currency is worth nothing; and the test's copper is forgotten after.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aCurrencyIsWorthItsUnitItsBlockAndWhatARaceCountsIn(GameTestHelper h) {
        Settlement copper = settlementOf(Items.COPPER_INGOT, Items.COPPER_BLOCK).settlement();
        withCopper(() -> {
            worthIs(h, copper, new ItemStack(Items.COPPER_INGOT), 1, 1);
            worthIs(h, copper, new ItemStack(Items.COPPER_BLOCK), 9, 1);
            worthIs(h, copper, new ItemStack(Items.FLINT), 1, 9);
            h.assertTrue(Currencies.worth(copper, new ItemStack(Items.IRON_INGOT)) == null, "iron is worth something: " + Currencies.worth(copper, new ItemStack(Items.IRON_INGOT)));
            Currencies.Worth scrap = Currencies.worth(copper, new ItemStack(Items.FLINT));
            h.assertTrue(scrap.unitsOf(20) == 2 && scrap.itemsFor(20) == 18 && scrap.unitsOf(8) == 0 && scrap.itemsFor(8) == 0
                            && scrap.unitsOf(9) == 1 && scrap.itemsFor(9) == 9 && scrap.unitsOf(0) == 0,
                    "20 scraps come to " + scrap.unitsOf(20) + " and take " + scrap.itemsFor(20) + ", 8 to " + scrap.unitsOf(8) + " and take " + scrap.itemsFor(8));
            Currencies.Worth block = Currencies.worth(copper, new ItemStack(Items.COPPER_BLOCK));
            h.assertTrue(block.unitsOf(3) == 27 && block.itemsFor(3) == 3, "3 blocks come to " + block.unitsOf(3) + " and take " + block.itemsFor(3));
        });
        h.assertTrue(Currencies.worth(copper, new ItemStack(Items.FLINT)) == null && !Currencies.ore(copper, Blocks.COPPER_ORE.defaultBlockState()),
                "the test's copper is still a currency after it (its scraps are worth something, or its ore is its ore)");
        h.succeed();
    }

    /**
     * Two currencies registered at once (as two races' are once both are loaded) are each worth something only to the settlement they
     * belong to: a copper settlement counts its ingot (1), block (9) and flint scraps (1 per 9) and refuses iron's ingot, block and
     * nuggets, and an iron settlement counts its own and refuses copper's, scraps included (a worth is tied to the unit it was named
     * for); a settlement of neither counts neither.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aSettlementCountsOnlyItsOwnCurrency(GameTestHelper h) {
        Settlement copper = settlementOf(Items.COPPER_INGOT, Items.COPPER_BLOCK).settlement();
        Settlement iron = settlementOf(Items.IRON_INGOT, Items.IRON_BLOCK).settlement();
        Settlement neither = settlementOf(Items.AMETHYST_SHARD, Items.AMETHYST_BLOCK).settlement();
        ItemStack[] coppers = {new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.COPPER_BLOCK), new ItemStack(Items.FLINT)};
        ItemStack[] irons = {new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_BLOCK), new ItemStack(Items.IRON_NUGGET)};
        withCopperAndIron(() -> {
            worthIs(h, copper, coppers[0], 1, 1);
            worthIs(h, copper, coppers[1], 9, 1);
            worthIs(h, copper, coppers[2], 1, 9);
            worthIs(h, iron, irons[0], 1, 1);
            worthIs(h, iron, irons[1], 9, 1);
            worthIs(h, iron, irons[2], 1, 9);
            String[] kinds = {"unit", "block", "fraction"};
            for (int i = 0; i < 3; i++) {
                h.assertTrue(Currencies.worth(copper, irons[i]) == null, "a copper settlement counted iron's " + kinds[i] + " as " + Currencies.worth(copper, irons[i]));
                h.assertTrue(Currencies.worth(iron, coppers[i]) == null, "an iron settlement counted copper's " + kinds[i] + " as " + Currencies.worth(iron, coppers[i]));
                h.assertTrue(Currencies.worth(neither, coppers[i]) == null && Currencies.worth(neither, irons[i]) == null,
                        "a settlement of another currency counted a " + kinds[i] + " of copper or iron");
            }
            h.assertTrue(Currencies.unit(copper, coppers[0]) && !Currencies.unit(copper, irons[0]) && Currencies.block(iron, irons[1]) && !Currencies.block(iron, coppers[1]),
                    "a settlement's unit and block are not its own currency's alone");
        });
        h.succeed();
    }

    /**
     * The ledger puts a settlement's currency in by its worth: an ingot a unit, a block nine, and of 20 scraps only the 18 that make
     * 2 ingots go in, the 2 over coming back; 8 scraps make nothing, go in as nothing and all come back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theLedgerTakesOnlyWholeUnitsOfWhatIsWorthAFraction(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        withCopper(() -> {
            List<Integer> paid = new ArrayList<>();
            List<String> from = new ArrayList<>();
            Settlement heart = settlementOf(Items.COPPER_INGOT, Items.COPPER_BLOCK).answer("addCurrency", a -> {
                paid.add((Integer) a[0]);
                from.add((String) a[1]);
                return null;
            }).settlement();
            ItemStack rest = SettlementLedger.put(level, heart, new ItemStack(Items.FLINT, 20));
            h.assertTrue(paid.equals(List.of(2)) && rest.is(Items.FLINT) && rest.getCount() == 2,
                    "20 scraps paid " + paid + " and gave back " + rest + ", not 2 and 2 scraps");
            paid.clear();
            rest = SettlementLedger.put(level, heart, new ItemStack(Items.FLINT, 8));
            h.assertTrue(paid.isEmpty() && rest.is(Items.FLINT) && rest.getCount() == 8, "8 scraps paid " + paid + " and gave back " + rest + ", not nothing and all 8");
            rest = SettlementLedger.put(level, heart, new ItemStack(Items.COPPER_INGOT, 5));
            h.assertTrue(paid.equals(List.of(5)) && rest.isEmpty(), "5 ingots paid " + paid + " and gave back " + rest);
            paid.clear();
            rest = SettlementLedger.put(level, heart, new ItemStack(Items.COPPER_BLOCK, 3));
            h.assertTrue(paid.equals(List.of(27)) && rest.isEmpty(), "3 blocks paid " + paid + " and gave back " + rest + ", not 27 and nothing");
            h.assertTrue(from.stream().allMatch(HaulLog.WARLORD::equals), "the deposits were logged as from " + from + ", not the owner's hand");
        });
        h.succeed();
    }

    /**
     * A raise that costs more than the settlement has is refused in the settlement's own words: "costs 12 copper ingots, and it has
     * 3" when its race names its currency, and, as it always read, "costs 10 of the settlement's currency" when it does not.
     * Nothing is spent (the stub is asked once and says no).
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "settlement")
    public static void aRaiseTooDearNamesTheSettlementsCurrency(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            int cost = s.pl.room().cost();
            for (String name : new String[]{"copper ingots", null}) {
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
                String want = "The Test Platform costs " + (name == null ? "10 of the settlement's currency" : "10 copper ingots") + ", and it has 3";
                h.assertTrue(cost == 10 && want.equals(no) && spent.equals(List.of(10)),
                        "the refusal for " + (name == null ? "an unnamed currency" : name) + " is \"" + no + "\" (asked to spend " + spent + "), not \"" + want + "\"");
            }
            s.clear();
            h.succeed();
        }));
    }
}
