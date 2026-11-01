package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.settlement.Currencies;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.work.Crafter;
import claude.settlementcore.work.ForgeWork;
import claude.settlementcore.work.HoardWork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A settlement counts, refines, mines and pays only in its own currency (docs/plans/greatplan-first-spawning.md, section B item 5,
 * and the reviews of P2.1-P2.6): with two races' currencies registered at once, the hoard, the forge, the mine and the crafter ask
 * {@link Currencies#unit}, {@link Currencies#block}, {@link Currencies#refined} and {@link Currencies#ore} of their own settlement, never
 * of every race's. The currencies are the tests' own, copper and iron (never a race's real one), put away when the test ends; the
 * Skaven's side of the same scoping (a real mine, a real forge) is in the root project's SeamGameTests.
 */
@GameTestHolder(Core.MODID)
public class CurrencyScopeGameTests {

    /** Copper and iron are both registered, each with its own ore, while {@code test} runs. */
    private static void withCopperAndIron(Runnable test) {
        Currencies.Mark before = Currencies.mark();
        try {
            Currencies.register(() -> Items.COPPER_INGOT, () -> Items.COPPER_BLOCK, null, () -> Blocks.COPPER_ORE, () -> Blocks.DEEPSLATE_COPPER_ORE);
            Currencies.register(() -> Items.IRON_INGOT, () -> Items.IRON_BLOCK, null, () -> Blocks.IRON_ORE);
            test.run();
        } finally {
            Currencies.rollBack(before);
        }
    }

    /**
     * An ore is the ore of the one currency it was registered beside, and only a settlement of that currency finds it: a copper
     * settlement's mine cuts copper ore (deepslate too) as currency and iron ore as plain ore, an iron settlement the other way, and
     * a settlement of neither currency finds none. Put away again, copper's ore is no one's.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aSettlementsOreIsOnlyItsOwnCurrencysOre(GameTestHelper h) {
        Settlement copper = new StubSettlement().is("currencyItem", Items.COPPER_INGOT).is("currencyBlock", Items.COPPER_BLOCK).settlement();
        Settlement iron = new StubSettlement().is("currencyItem", Items.IRON_INGOT).is("currencyBlock", Items.IRON_BLOCK).settlement();
        Settlement neither = new StubSettlement().is("currencyItem", Items.AMETHYST_SHARD).is("currencyBlock", Items.AMETHYST_BLOCK).settlement();
        withCopperAndIron(() -> {
            var copperOre = Blocks.COPPER_ORE.defaultBlockState();
            var deepCopper = Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
            var ironOre = Blocks.IRON_ORE.defaultBlockState();
            h.assertTrue(Currencies.ore(copper, copperOre) && Currencies.ore(copper, deepCopper), "a copper settlement does not find copper ore");
            h.assertTrue(!Currencies.ore(copper, ironOre), "a copper settlement found iron's ore as its currency");
            h.assertTrue(Currencies.ore(iron, ironOre), "an iron settlement does not find iron ore");
            h.assertTrue(!Currencies.ore(iron, copperOre) && !Currencies.ore(iron, deepCopper), "an iron settlement found copper's ore as its currency");
            h.assertTrue(!Currencies.ore(neither, copperOre) && !Currencies.ore(neither, ironOre), "a settlement of neither found either's ore");
        });
        h.assertTrue(!Currencies.ore(copper, Blocks.COPPER_ORE.defaultBlockState()), "the test's copper ore is still a currency's after it");
        h.succeed();
    }

    /**
     * The hoard puts a settlement's own unit and refined form with the metals whatever they are made of, and no other race's: of two
     * settlements, one counting string (refined into leather) and one feathers, string is metal in the first's hoard and a spoil in the
     * second's, as leather is; and only the settlement that refines leather says it is refined (the forge's check, and the one that
     * refines nothing says no to everything).
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aHoardAndAForgeKnowOnlyTheirOwnSettlementsUnitAndRefinedForm(GameTestHelper h) {
        Settlement strings = new StubSettlement().is("currencyItem", Items.STRING).is("currencyBlock", Items.WHITE_WOOL).is("refinedCurrency", Items.LEATHER).settlement();
        Settlement feathers = new StubSettlement().is("currencyItem", Items.FEATHER).is("currencyBlock", Items.WHITE_CARPET).is("refinedCurrency", null).settlement();
        ItemStack string = new ItemStack(Items.STRING);
        ItemStack leather = new ItemStack(Items.LEATHER);
        Currencies.Mark before = Currencies.mark();
        try {
            Currencies.register(() -> Items.STRING, () -> Items.WHITE_WOOL, () -> Items.LEATHER);          // both races' currencies are registered,
            Currencies.register(() -> Items.FEATHER, () -> Items.WHITE_CARPET, null);                       // as they are with both mods loaded
            h.assertTrue(HoardWork.kind(strings, string) == HoardWork.Kind.METALS && HoardWork.kind(strings, leather) == HoardWork.Kind.METALS,
                    "string and its leather are not metals to the settlement that counts string: " + HoardWork.kind(strings, string) + ", " + HoardWork.kind(strings, leather));
            h.assertTrue(HoardWork.kind(feathers, string) == HoardWork.Kind.SPOILS && HoardWork.kind(feathers, leather) == HoardWork.Kind.SPOILS,
                    "another race's string or leather is metal to a settlement that counts feathers: " + HoardWork.kind(feathers, string) + ", " + HoardWork.kind(feathers, leather));
            h.assertTrue(HoardWork.kind(feathers, new ItemStack(Items.FEATHER)) == HoardWork.Kind.METALS, "a settlement's own unit is not metal in its hoard");
            h.assertTrue(ForgeWork.refined(strings, leather) && !ForgeWork.refined(feathers, leather) && !ForgeWork.refined(strings, string),
                    "the forge's refined form: string's settlement " + ForgeWork.refined(strings, leather) + " for leather, feathers' " + ForgeWork.refined(feathers, leather));
        } finally {
            Currencies.rollBack(before);
        }
        h.succeed();
    }

    /** What a settlement's purse is asked: the copper settlement of the crafter tests, with five in hand or none, counting what it paid out and took back. */
    private static final class Purse {
        int has;
        int spent;
        int added;
        final Settlement settlement = new StubSettlement()
                .is("currencyItem", Items.COPPER_INGOT)
                .is("currencyBlock", Items.COPPER_BLOCK)
                .answer("currency", a -> has)
                .answer("spendCurrency", a -> {
                    int n = (Integer) a[0];
                    if (has < n) {
                        return false;
                    }
                    has -= n;
                    spent += n;
                    return true;
                })
                .answer("addCurrency", a -> {
                    has += (Integer) a[0];
                    added += (Integer) a[0];
                    return null;
                })
                .quiet("log", "addSpoil")
                .settlement();
    }

    /** A spyglass is an amethyst shard over two copper ingots; the plan is made as if the hoard held them, the making is with what it really holds. */
    private static Crafter.Plan spyglassPlan(GameTestHelper h) {
        Map<Crafter.Key, Integer> stock = new HashMap<>();
        stock.put(Crafter.Key.of(new ItemStack(Items.AMETHYST_SHARD)), 1);
        stock.put(Crafter.Key.of(new ItemStack(Items.COPPER_INGOT)), 2);
        Crafter.Plan plan = Crafter.plan(h.getLevel(), stock, new ItemStack(Items.SPYGLASS));
        h.assertTrue(plan.ok() && plan.steps().size() == 1 && plan.steps().get(0).grid().size() == 3, "the spyglass's plan: " + plan.steps().size() + " steps, short of " + plan.shortOf());
        return plan;
    }

    /**
     * The crafter pays a making out of its settlement's own currency only: a copper settlement holding five, asked to make a spyglass out
     * of a hoard with no amethyst in it, is short of the amethyst (another race's unit, which it must not buy with its copper), and
     * the purse is untouched: nothing paid, nothing given back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aMakingNeverBuysAnotherRacesUnitWithTheSettlementsCurrency(GameTestHelper h) {
        Crafter.Plan plan = spyglassPlan(h);
        Purse purse = new Purse();
        purse.has = 5;
        List<ItemStack> made = withCopperAndAmethyst(() -> Crafter.make(h.getLevel(), purse.settlement, List.of(), plan, "Workshop"));
        h.assertTrue(made == null, "the spyglass was made out of nothing: " + made);
        h.assertTrue(purse.spent == 0 && purse.added == 0 && purse.has == 5, "the purse was touched: spent " + purse.spent + ", given back " + purse.added + ", holds " + purse.has + " of 5");
        h.succeed();
    }

    /**
     * A making gone half-way gives back only what the settlement's own currency paid: the hoard holds the amethyst and the purse one copper, so
     * the amethyst and the one copper are taken, the second copper is short, and everything goes back: the amethyst into the hoard, the
     * copper into the purse (once), and no copper left over in the hoard.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aMakingGoneHalfWayGivesBackTheSettlementsCurrencyToItsPurseOnly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Crafter.Plan plan = spyglassPlan(h);
        BlockPos at = h.absolutePos(new BlockPos(1, 360, 1));
        level.setBlockAndUpdate(at, Blocks.BARREL.defaultBlockState());
        try {
            Container barrel = (Container) level.getBlockEntity(at);
            barrel.setItem(0, new ItemStack(Items.AMETHYST_SHARD));
            Purse purse = new Purse();
            purse.has = 1;
            List<ItemStack> made = withCopperAndAmethyst(() -> Crafter.make(level, purse.settlement, List.of(at), plan, "Workshop"));
            h.assertTrue(made == null, "the spyglass was made with one copper: " + made);
            int amethyst = 0, copper = 0;
            for (int i = 0; i < barrel.getContainerSize(); i++) {
                ItemStack s = barrel.getItem(i);
                amethyst += s.is(Items.AMETHYST_SHARD) ? s.getCount() : 0;
                copper += s.is(Items.COPPER_INGOT) ? s.getCount() : 0;
            }
            h.assertTrue(purse.spent == 1 && purse.added == 1 && purse.has == 1, "the purse paid " + purse.spent + ", got back " + purse.added + " and holds " + purse.has + ", not 1, 1 and 1");
            h.assertTrue(amethyst == 1 && copper == 0, "the hoard holds " + amethyst + " amethyst and " + copper + " copper after the failed making, not 1 and 0");
        } finally {
            level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }

    /** Copper is a currency and amethyst another's (neither is the purse's to spend on the other's) while {@code test} runs. */
    private static <T> T withCopperAndAmethyst(java.util.function.Supplier<T> test) {
        Currencies.Mark before = Currencies.mark();
        try {
            Currencies.register(() -> Items.COPPER_INGOT, () -> Items.COPPER_BLOCK, null);
            Currencies.register(() -> Items.AMETHYST_SHARD, () -> Items.AMETHYST_BLOCK, null);
            return test.get();
        } finally {
            Currencies.rollBack(before);
        }
    }
}
