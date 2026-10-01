package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.defence.Muster;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The muster in the open (core/defence/Muster, docs/plans/greatplan-first-spawning.md section B item 4): a settlement that says
 * {@link Settlement#mustersUnderSky} gets its defenders up on open ground, and one that does not (the Warren Heart: nothing
 * overrides the default) gets none there, as it always did. The ground is a flat plot of grass the test lays at +360 over its
 * origin, with nothing over it; the settlement is a stub, and the foe is a hoglin with its AI off (an Enemy that the peaceful
 * test world does not delete).
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class MusterGameTests {

    private static final int UP = 360;
    /** The plot is 41 by 41 round its middle. */
    private static final int HALF = 20;

    /** A plot of open grass, nothing over it up to the build limit. Returns the cell to stand in at its middle. */
    private static BlockPos openGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(0, UP, 0));
        BlockPos b = h.absolutePos(new BlockPos(2 * HALF, UP, 2 * HALF));
        int x0 = Math.min(a.getX(), b.getX());
        int z0 = Math.min(a.getZ(), b.getZ());
        int floor = a.getY();
        h.assertTrue(floor - 4 > level.getMinBuildHeight() && floor + 12 < level.getMaxBuildHeight(), "no room for the plot at " + floor);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x0 + 2 * HALF; x++) {
            for (int z = z0; z <= z0 + 2 * HALF; z++) {
                for (int y = floor - 3; y < level.getMaxBuildHeight(); y++) {
                    BlockState want = y == floor ? Blocks.GRASS_BLOCK.defaultBlockState() : y < floor ? Blocks.STONE.defaultBlockState() : air;
                    if (level.getBlockState(p.set(x, y, z)) != want) {
                        level.setBlockAndUpdate(p, want);
                    }
                }
            }
        }
        return new BlockPos(x0 + HALF, floor + 1, z0 + HALF);
    }

    /** Everything the plot laid, back to air (the next batches' plots start from nothing). */
    private static void clearGround(GameTestHelper h, BlockPos middle) {
        ServerLevel level = h.getLevel();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = middle.getX() - HALF; x <= middle.getX() + HALF; x++) {
            for (int z = middle.getZ() - HALF; z <= middle.getZ() + HALF; z++) {
                for (int y = middle.getY() - 4; y < level.getMaxBuildHeight(); y++) {
                    if (!level.getBlockState(p.set(x, y, z)).isAir()) {
                        level.setBlock(p, air, 2);
                    }
                }
            }
        }
    }

    private static boolean near(BlockPos a, BlockPos b, int r) {
        return Math.abs(a.getX() - b.getX()) <= r && Math.abs(a.getZ() - b.getZ()) <= r && Math.abs(a.getY() - b.getY()) <= r;
    }

    /**
     * A settlement at {@code heart} that holds the whole of its test's plot and nothing past it (tests of a batch run side by side,
     * and the muster must not see the next test's foe), with reserve counts and a record of where it was asked to raise defenders.
     */
    private static StubSettlement city(BlockPos plot, BlockPos heart, List<BlockPos> asked) {
        return new StubSettlement()
                .is("getBlockPos", heart)
                .is("owner", UUID.randomUUID())
                .is("settlementBoxes", List.of())
                .answer("inSettlement", a -> near((BlockPos) a[0], plot, HALF))
                .is("placeOf", "")
                .quiet("tellOwner")
                .is("reserve", 6)
                .is("reserveWorkers", 6)
                .is("reserveSoldiers", 0)
                .answer("riseFromReserve", a -> {
                    asked.add((BlockPos) a[1]);
                    return null;                                   // the stub makes no mob: the cell it was asked for is the answer
                });
    }

    private static Hoglin foeAt(ServerLevel level, BlockPos at, boolean inTheWorld) {
        Hoglin foe = EntityType.HOGLIN.create(level);
        foe.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        foe.setNoAi(true);
        foe.setPersistenceRequired();
        foe.setImmuneToZombification(true);
        if (inTheWorld) {
            level.addFreshEntity(foe);
        }
        return foe;
    }

    /**
     * Where a defender comes up (Muster#cell): a settlement that musters under the sky gets a cell on open ground, a standable
     * one in the settlement and with the sky over it, for a worker and a soldier alike; the default (the Warren Heart's) gets
     * none, since there is no cell under cover on a plot of grass.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDefenderComesUpInTheOpenOnlyWhereTheSettlementMustersUnderTheSky(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos heart = middle;
            var foe = foeAt(level, middle.offset(12, 0, 3), false);
            Settlement open = city(middle, heart, new ArrayList<>()).is("mustersUnderSky", true).settlement();
            Settlement tunnels = city(middle, heart, new ArrayList<>()).settlement();
            h.assertTrue(!tunnels.mustersUnderSky(), "the default settlement musters under the sky");
            for (int i = 0; i < 20; i++) {
                boolean soldier = i % 2 == 1;
                BlockPos cell = Muster.cell(level, open, foe, soldier);
                h.assertTrue(cell != null, "no cell on open ground for " + (soldier ? "a soldier" : "a worker") + " (round " + i + ")");
                h.assertTrue(SettlerMob.standable(level, cell) && SettlerMob.underOpenSky(level, cell) && open.inSettlement(cell),
                        "the cell " + cell.toShortString() + " is not a standable, open, settlement cell");
                h.assertTrue(near(cell, middle, HALF) && cell.getY() == middle.getY(), "the cell " + cell.toShortString() + " is off the plot or off the ground");
                h.assertTrue(Muster.cell(level, tunnels, foe, soldier) == null,
                        "a settlement that does not muster under the sky was given an open cell (round " + i + ")");
            }
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A round of the muster (Muster#second): a foe in the open, the alarm goes up either way (the foe is in the settlement), and
     * the defenders are asked up out of the reserve, STEP of them a second, each at a standable cell in the open, only for the
     * settlement that says it musters under the sky. The Warren Heart's way, the default, asks for none.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aMusterRaisesDefendersInTheOpenOnlyForASurfaceSettlement(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            var foe = foeAt(level, middle.offset(10, 0, 0), true);
            List<BlockPos> openAsked = new ArrayList<>();
            List<BlockPos> tunnelsAsked = new ArrayList<>();
            Muster open = new Muster();
            Muster tunnels = new Muster();
            open.second(level, city(middle, middle, openAsked).is("mustersUnderSky", true).settlement());
            tunnels.second(level, city(middle, middle, tunnelsAsked).settlement());
            h.assertTrue(foe.isAlive(), "the foe was removed from the world");
            h.assertTrue(open.alarmed() && tunnels.alarmed(), "the alarm did not go up for the foe in the open: " + open.alarmed() + " / " + tunnels.alarmed());
            h.assertTrue(openAsked.size() == Muster.STEP, "the surface settlement was asked for " + openAsked.size() + " defenders, not " + Muster.STEP);
            for (BlockPos at : openAsked) {
                h.assertTrue(SettlerMob.standable(level, at) && SettlerMob.underOpenSky(level, at) && near(at, middle, HALF),
                        "a defender was to come up at " + at.toShortString() + ", not standable, not in the open, or off the plot");
            }
            h.assertTrue(tunnelsAsked.isEmpty(), "the default settlement raised " + tunnelsAsked.size() + " defenders in the open");
            foe.discard();
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * How far round its heart the muster looks (Settlement#reach): a foe 25 blocks off is past the Warren Heart's 16 and not seen,
     * and seen by a settlement that reaches 30.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void theMusterLooksAsFarAsTheSettlementReaches(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos heart = middle.offset(-12, 0, 0);
            var foe = foeAt(level, middle.offset(13, 0, 0), true);
            Muster near = new Muster();
            Muster far = new Muster();
            near.second(level, city(middle, heart, new ArrayList<>()).settlement());
            far.second(level, city(middle, heart, new ArrayList<>()).is("reach", 30).settlement());
            h.assertTrue(near.lastSeen == 0 && !near.alarmed(), "the default reach saw the foe 25 blocks off (" + near.lastSeen + ")");
            h.assertTrue(far.lastSeen == 1 && far.alarmed(), "a reach of 30 missed the foe 25 blocks off (" + far.lastSeen + ")");
            foe.discard();
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }
}
