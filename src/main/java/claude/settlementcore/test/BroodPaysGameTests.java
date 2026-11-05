package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.food.Fare;
import claude.settlementcore.settlement.Brood;
import claude.settlementcore.settlement.Settlement;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * A brood makes no one it cannot pay for (core/settlement/Brood#add, the bug audit of 2026-11-04). A settlement's food counts its larder's
 * containers in unloaded chunks (Larder keeps their counts on purpose), but a spend reaches only the loaded ones: Brood#add asked the food,
 * spent, and made the settler whatever the spend took, so a settlement whose food was all out of reach made its people for nothing, every
 * round, up to its cap. No settlement is placed: a stub says it holds the food, and the spend is the test's own.
 */
@GameTestHolder(Core.MODID)
public class BroodPaysGameTests {

    private static final Brood.Cost WORKER = new Brood.Cost(4, 0, "Workers raised");
    private static final Brood.Cost GUARD = new Brood.Cost(6, 0, "Guards raised");

    /**
     * A settlement that says it holds 40 food, all of it out of reach (a spend takes nothing), makes no worker and no guard of the 3 asked
     * for; with 3 points in reach of a worker's 4 it makes none, and the 3 go back into the ration (as slop, the grade a spend takes first);
     * with 9 in reach it makes the 2 that are paid in full, and the third's 1 goes back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aBroodMakesNoOneFromFoodItCannotReach(GameTestHelper h) {
        int[] reserve = {0, 0};
        List<String> back = new ArrayList<>();
        Settlement s = new StubSettlement()
                .answer("population", a -> reserve[0] + reserve[1])
                .is("food", 40)                                    // the larder's counts, its containers in unloaded chunks
                .answer("addToReserve", a -> {
                    reserve[0] += (Integer) a[0];
                    reserve[1] += (Integer) a[1];
                    return null;
                })
                .answer("addFood", a -> {
                    back.add(a[0] + "@" + a[1]);
                    return null;
                })
                .settlement();

        Brood none = new Brood(h.getLevel(), s, 100, 1f, (n, on) -> 0);
        int made = none.add(false, 2, WORKER) + none.add(true, 1, GUARD);
        h.assertTrue(made == 0 && reserve[0] == 0 && reserve[1] == 0 && back.isEmpty(), "40 food out of reach made " + made + " (reserve "
                + reserve[0] + " and " + reserve[1] + "), not none: the spend took nothing");

        int[] reach = {3};
        Brood.Spend some = (n, on) -> {
            int took = Math.min(n, reach[0]);
            reach[0] -= took;
            return took;
        };
        made = new Brood(h.getLevel(), s, 100, 1f, some).add(false, 2, WORKER);
        h.assertTrue(made == 0 && reserve[0] == 0 && back.equals(List.of("3@" + Fare.SLOP_GRADE)), "3 food in reach of a cost of 4 made " + made
                + ", and put back " + back + ", not none made and the 3 back");

        reach[0] = 9;
        back.clear();
        made = new Brood(h.getLevel(), s, 100, 1f, some).add(false, 3, WORKER);
        h.assertTrue(made == 2 && reserve[0] == 2 && back.equals(List.of("1@" + Fare.SLOP_GRADE)), "9 food in reach at 4 a head made " + made
                + " (reserve " + reserve[0] + ") and put back " + back + ", not 2 made and the third's 1 back");
        h.succeed();
    }
}
