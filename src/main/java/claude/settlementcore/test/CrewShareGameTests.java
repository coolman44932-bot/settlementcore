package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.List;

/**
 * The Works' crews, at the unit level (the test race's TestRaceCrewGameTests have real settlers): a reserve too short for every room's want is shared
 * fairly ({@link Works#fairShares}), and a load a crew settler hands back is put away and never lost ({@link Works#stow}: the Hoard, the spoils
 * pile, then the ground at the heart).
 */
@GameTestHolder(Core.MODID)
public class CrewShareGameTests {

    private static void shares(GameTestHelper h, int[] want, int supply, int[] expect, String scene) {
        int[] got = Works.fairShares(want, supply);
        h.assertTrue(Arrays.equals(got, expect), scene + ": wanting " + Arrays.toString(want) + " with " + supply + " to share, the rooms got "
                + Arrays.toString(got) + ", not " + Arrays.toString(expect));
    }

    /**
     * Every room that wants crew gets one, a small room all it wants, and the big digs share the rest alike; a lone dig takes the whole reserve, and no
     * room gets more than it wants. Since 0.5.6 a dig's want has no cap, and the rooms were staffed in the order they were begun: a dig wanting 350 took
     * the whole reserve and a site begun after it got no builder.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aShortReserveIsSharedFairly(GameTestHelper h) {
        shares(h, new int[]{350, 8}, 20, new int[]{12, 8}, "a dig and a site");
        shares(h, new int[]{13, 1}, 2, new int[]{1, 1}, "a dig holding the reserve of 2, and a site begun after it");
        shares(h, new int[]{100, 100, 100}, 60, new int[]{20, 20, 20}, "three digs begun together");
        shares(h, new int[]{350}, 30, new int[]{30}, "a dig alone");
        shares(h, new int[]{2, 3}, 10, new int[]{2, 3}, "more than enough");
        shares(h, new int[]{0, 4}, 3, new int[]{0, 3}, "a room that wants none");
        shares(h, new int[]{5, 3}, 1, new int[]{1, 0}, "one to share between two");
        shares(h, new int[]{350, 4, 1}, 0, new int[]{0, 0, 0}, "nobody at all");
        int[] many = Works.fairShares(new int[]{350, 350, 8, 1}, 101);
        h.assertTrue(Arrays.stream(many).sum() == 101 && many[2] == 8 && many[3] == 1 && Math.abs(many[0] - many[1]) <= 1,
                "two digs, a site and a hoard sharing 101 got " + Arrays.toString(many) + ": the site and the hoard not whole, or the digs not alike");
        h.succeed();
    }

    /**
     * A load handed back with no Hoard goes onto the spoils pile, stacked onto what is there; with the pile full ({@link Settlement#MAX_SPOIL_STACKS}
     * stacks) it lies on the ground at the heart, whole: before, the spoils pile's addSpoil dropped what it had no room for, and a crew settler's load
     * went with it when it was discarded.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aLoadHandedBackIsPutAwayAndNeverLost(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(22, 18, 22));
        t.addSpoil(new ItemStack(Items.DIAMOND, 10));
        t.works().stow(level, new ItemStack(Items.DIAMOND, 64), "");
        List<ItemStack> pile = t.spoils();
        h.assertTrue(pile.size() == 2 && pile.get(0).is(Items.DIAMOND) && pile.get(0).getCount() == 64 && pile.get(1).is(Items.DIAMOND)
                && pile.get(1).getCount() == 10, "10 diamonds on the pile and 64 handed back made " + pile + ", not 64 and 10");
        while (t.spoils().size() < Settlement.MAX_SPOIL_STACKS) {
            t.addSpoil(new ItemStack(Items.DIAMOND_SWORD));
        }
        t.works().stow(level, new ItemStack(Items.EMERALD, 20), "");
        h.assertTrue(t.spoils().size() == Settlement.MAX_SPOIL_STACKS && t.spoils().stream().noneMatch(s -> s.is(Items.EMERALD)),
                "the full pile took the emeralds, or grew past " + Settlement.MAX_SPOIL_STACKS + " stacks: " + t.spoils().size());
        BlockPos at = t.getBlockPos();
        List<ItemEntity> lying = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3), e -> e.getItem().is(Items.EMERALD));
        int n = lying.stream().mapToInt(e -> e.getItem().getCount()).sum();
        h.assertTrue(n == 20, "with the pile full, " + n + " of the 20 emeralds lie at the heart, not all 20");
        lying.forEach(ItemEntity::discard);
        h.succeed();
    }
}
