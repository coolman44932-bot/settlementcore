package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.food.Fare;
import claude.settlementcore.settlement.Brood;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Spawning;
import claude.settlementcore.unit.Footing;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * How a settlement makes new people (core/settlement/Spawning and Brood, docs/plans/greatplan-first-spawning.md section B item 3):
 * the engine keeps the bounds of a round (the room it has, the food it costs, the people it may have up at once) and the race
 * says what the round makes. No settlement is placed; a stub says what the brood asks of one, and the food goes out through a
 * spend of the test's own, which keeps what it was asked.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class SpawningGameTests {

    private static final Brood.Cost PUP = new Brood.Cost(1, 0, "Breeding");

    /** A settlement of counts: the people up, the reserve's two counts, the food, and what the brood asked of it. */
    private static final class City {
        int people;
        int workers;
        int soldiers;
        int food;
        int peopleCap = 100;
        Fare fare = Fare.PLAIN;
        final List<String> spent = new ArrayList<>();
        final List<String> asked = new ArrayList<>();
        /** The cells the settlement was asked to raise someone at, in the order asked. */
        final List<BlockPos> where = new ArrayList<>();
        final Settlement settlement;

        City() {
            settlement = new StubSettlement()
                    .answer("population", a -> people + workers + soldiers)
                    .answer("food", a -> food)
                    .answer("fare", a -> fare)
                    .answer("peopleCount", a -> people)
                    .answer("peopleCap", a -> peopleCap)
                    .answer("addToReserve", a -> {
                        workers += (Integer) a[0];
                        soldiers += (Integer) a[1];
                        return null;
                    })
                    .answer("riseFromReserve", a -> {
                        asked.add(a[2] + "@" + a[1] + "/" + a[3]);
                        where.add((BlockPos) a[1]);
                        return null;
                    })
                    .settlement();
        }

        Brood brood(GameTestHelper h, int cap, float multiplier) {
            return new Brood(h.getLevel(), settlement, cap, multiplier, (n, on) -> {
                int gone = Math.min(n, food);
                food -= gone;
                spent.add(on + ":" + n);
                return gone;
            });
        }
    }

    /**
     * A round adds to the reserve only while the settlement has room, which is its cap less everyone it counts: 4 up and 3 in the
     * reserve under a cap of 10 leave room for 3, so 5 asked for come to 3 (workers or soldiers alike), each costing its food, and
     * the next ask makes none; over its cap there is no room, never a negative one.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aBroodAddsNoMoreThanTheRoomTheSettlementHas(GameTestHelper h) {
        City c = new City();
        c.people = 4;
        c.workers = 2;
        c.soldiers = 1;
        c.food = 50;
        Brood brood = c.brood(h, 10, 1f);
        h.assertTrue(brood.room() == 3, "4 up and 3 waiting under a cap of 10 left room for " + brood.room() + ", not 3");
        int made = brood.add(false, 5, PUP);
        h.assertTrue(made == 3 && c.workers == 5 && c.soldiers == 1, "5 workers asked for made " + made + ", leaving " + c.workers + " and " + c.soldiers
                + ", not 3 made and 5 and 1");
        h.assertTrue(c.spent.equals(List.of("Breeding:1", "Breeding:1", "Breeding:1")) && c.food == 47,
                "the food spent was " + c.spent + " leaving " + c.food + ", not one point on each of the 3");
        h.assertTrue(brood.room() == 0 && brood.add(false, 1, PUP) == 0 && brood.add(true, 1, PUP) == 0 && c.soldiers == 1 && c.spent.size() == 3,
                "a settlement with no room made another, or spent food on it: " + c.workers + " and " + c.soldiers + ", spent " + c.spent);
        Brood over = c.brood(h, 5, 1f);
        h.assertTrue(over.room() == 0 && over.add(true, 2, PUP) == 0, "a cap under the settlement's numbers left room for " + over.room());
        City fresh = new City();
        fresh.food = 9;
        h.assertTrue(fresh.brood(h, 20, 1f).add(true, 2, PUP) == 2 && fresh.soldiers == 2, "2 soldiers asked for of a fresh settlement were not made");
        h.succeed();
    }

    /**
     * A round without the food makes no one and spends nothing: a cost of 1 a head with the pens quiet below 10 food, and 9 in
     * the larder, makes none of the 5 asked for; with 12 and a cost of 2 a head it makes 2 (12 then 10 is enough for the second, 8
     * is not) and spends 2 twice; a cost larger than the larder alone is not made either; and a whole round of the race's
     * (a {@link Spawning}) handed the brood adds what the brood lets it.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRoundWithNoFoodAddsNothingAndSpendsNothing(GameTestHelper h) {
        City c = new City();
        c.food = 9;
        Brood.Cost quiet = new Brood.Cost(1, 10, "Breeding the horde");
        h.assertTrue(c.brood(h, 100, 1f).add(false, 5, quiet) == 0 && c.workers == 0 && c.spent.isEmpty() && c.food == 9,
                "9 food under a floor of 10 made " + c.workers + " and spent " + c.spent);
        c.food = 12;
        Brood.Cost two = new Brood.Cost(2, 10, "Raising clanrats");
        int made = c.brood(h, 100, 1f).add(true, 5, two);
        h.assertTrue(made == 2 && c.soldiers == 2 && c.food == 8 && c.spent.equals(List.of("Raising clanrats:2", "Raising clanrats:2")),
                "12 food at 2 a head over a floor of 10 made " + made + " (soldiers " + c.soldiers + ", food left " + c.food + ", spent " + c.spent + "), not 2 and 8");
        c.food = 4;
        c.spent.clear();
        h.assertTrue(c.brood(h, 100, 1f).add(false, 3, new Brood.Cost(5, 0, "Hatching")) == 0 && c.spent.isEmpty(),
                "4 food made one that costs 5, or spent on it: " + c.spent);

        City round = new City();
        round.food = 3;
        Spawning pens = (level, settlement, brood) -> brood.add(false, 10, PUP);
        pens.round(h.getLevel(), round.settlement, round.brood(h, 100, 1f));
        h.assertTrue(round.workers == 3 && round.food == 0, "a round of 10 on 3 food made " + round.workers + " and left " + round.food + ", not 3 and none");
        pens.round(h.getLevel(), round.settlement, round.brood(h, 100, 1f));
        h.assertTrue(round.workers == 3, "a second round with no food made " + (round.workers - 3) + " more");
        Spawning.NONE.round(h.getLevel(), round.settlement, round.brood(h, 100, 1f));
        h.assertTrue(round.workers == 3, "the round of no spawning made someone");
        h.succeed();
    }

    /**
     * One comes up out of the reserve as a mob only while fewer are up than the settlement's people cap: 2 up of a cap of 3 asks the
     * settlement once (with the kind asked for, the place, and not as one of the teeming), 3 of 3 and 4 of 3 ask it nothing and
     * give nothing.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aBroodBringsNoMoreUpThanThePeopleCapAllows(GameTestHelper h) {
        City c = new City();
        c.peopleCap = 3;
        c.people = 2;
        BlockPos at = new BlockPos(3, 4, 5);
        Brood brood = c.brood(h, 100, 1f);
        h.assertTrue(brood.bringUp(true, at) == null && c.asked.equals(List.of("true@" + at + "/false")),
                "2 up of a cap of 3 asked the settlement " + c.asked + ", not once for a soldier at " + at);
        c.people = 3;
        h.assertTrue(brood.bringUp(false, at) == null && c.asked.size() == 1, "3 up of a cap of 3 asked the settlement again: " + c.asked);
        c.people = 4;
        h.assertTrue(brood.bringUp(false, at) == null && c.asked.size() == 1, "4 up of a cap of 3 asked the settlement again: " + c.asked);
        c.peopleCap = 5;
        brood.bringUp(false, at);
        h.assertTrue(c.asked.size() == 2 && c.asked.get(1).startsWith("false@"), "a raised cap was not asked: " + c.asked);
        h.succeed();
    }

    /**
     * How fast it breeds is the fare's breeding times the settlement's own: a gorging fare (1.75) with the race's multiplier of 2 is
     * 3.5, so 4 scaled are 14 exactly; and a fraction is rounded at random, up or down: half of 1 (a plain fare, a multiplier of 0.5)
     * is 0 or 1, one time in two (the mean of 2000 within a tenth of it), 3 times 1.5 is 4 or 5, never another.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theBroodsRateIsTheFaresBreedingTimesTheSettlementsOwn(GameTestHelper h) {
        City c = new City();
        c.fare = Fare.GORGING;
        Brood fast = c.brood(h, 100, 2f);
        int fourteen = fast.scaled(4);
        h.assertTrue(Math.abs(fast.rate() - 3.5f) < 1e-5f && fourteen == 14, "a gorging fare at twice scaled 4 to " + fourteen + " at rate " + fast.rate() + ", not 14 at 3.5");
        c.fare = Fare.PLAIN;
        Brood half = c.brood(h, 100, 0.5f);
        int sum = 0;
        for (int i = 0; i < 2000; i++) {
            int one = half.scaled(1);
            h.assertTrue(one == 0 || one == 1, "half of 1 scaled to " + one);
            sum += one;
        }
        h.assertTrue(Math.abs(sum / 2000.0 - 0.5) < 0.1, "half of 1 scaled to " + sum + " in 2000, not about 1000");
        c.fare = Fare.SLOP;
        Brood slop = c.brood(h, 100, 1f);
        int seven = slop.scaled(10);
        h.assertTrue(Math.abs(slop.rate() - 0.7f) < 1e-5f && seven == 7, "10 on slop scaled to " + seven + " at rate " + slop.rate() + ", not 7");
        c.fare = Fare.PLAIN;
        for (int i = 0; i < 200; i++) {
            int n = c.brood(h, 100, 1.5f).scaled(3);
            h.assertTrue(n == 4 || n == 5, "3 times 1.5 scaled to " + n + ", not 4 or 5");
        }
        h.succeed();
    }

    /**
     * A brood never brings one up in a hollow shut in on every side (Footing#escapes, Brood#bringUp): asked for a cell of a sealed hollow of
     * 2 by 2 in a block of stone, it asks the settlement for no cell of it. With no other cell near that has a way out it asks for none,
     * and none is taken out of the reserve; with a ledge of stone off the block (open to the sky) it asks for a cell of the ledge, the
     * nearest; and asked for a cell on open ground it asks for that very cell, as ever.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aBroodNeverBringsOneUpInAHollowShutInOnEverySide(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos hollow = middle.offset(-1, 3, 4);
            MusterGameTests.hollow(level, hollow, 2, 2);
            City c = new City();
            Brood brood = c.brood(h, 100, 1f);
            BlockPos[] cells = {hollow, hollow.east(), hollow.south(), hollow.offset(1, 0, 1)};
            for (BlockPos cell : cells) {
                brood.bringUp(false, cell);
            }
            h.assertTrue(c.where.isEmpty(), "the brood asked for " + c.where + " with no cell to come up in but those of a hollow shut in on every side");
            for (int dx = 3; dx <= 5; dx++) {                                    // a ledge of stone off the block's east side, 3 by 3, with open air over it
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(hollow.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            for (BlockPos cell : cells) {
                brood.bringUp(false, cell);
            }
            h.assertTrue(c.where.size() == cells.length, "the brood asked for " + c.where.size() + " cells, not " + cells.length + ", with a ledge to come up on");
            for (BlockPos at : c.where) {
                h.assertTrue(at.getY() == hollow.getY() && at.getX() >= hollow.getX() + 3 && Math.abs(at.getZ() - hollow.getZ()) <= 1
                                && Footing.escapes(level, at, Footing.LIMIT),
                        "the brood asked for " + at.toShortString() + ", not a cell of the ledge off the block");
            }
            c.where.clear();
            brood.bringUp(true, middle);
            h.assertTrue(c.where.equals(List.of(middle)), "the brood asked for " + c.where + " for a cell on open ground, not " + middle.toShortString());
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }
}
