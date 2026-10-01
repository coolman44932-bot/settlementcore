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

    static Hoglin foeAt(ServerLevel level, BlockPos at, boolean inTheWorld) {
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

    /**
     * A race's reach widens what the muster defends, not only where it looks (the review of 20da67f: {@code intruders} widened its search by the
     * reach and then kept only the foes {@code inSettlement} called the settlement's, so the reach changed nothing for a race whose ground was
     * narrower). A settlement that holds nothing but its heart's own cell: with the default reach of 16 a foe 17 off is no one's business; with a
     * reach of 18 it is seen, the alarm goes up and the defenders are asked up on the open ground between it and the heart, within the reach.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aRaceWhoseReachIsWiderThanItsGroundStillDefendsWhatIsWithinIt(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            var foe = foeAt(level, middle.offset(17, 0, 0), true);
            List<BlockPos> asked = new ArrayList<>();
            Muster narrow = new Muster();
            Muster wide = new Muster();
            narrow.second(level, city(middle, middle, new ArrayList<>()).answer("inSettlement", a -> a[0].equals(middle)).is("mustersUnderSky", true).settlement());
            wide.second(level, city(middle, middle, asked).answer("inSettlement", a -> a[0].equals(middle)).is("mustersUnderSky", true).is("reach", 18).settlement());
            h.assertTrue(narrow.lastSeen == 0 && !narrow.alarmed(), "the default reach of 16 saw the foe 17 off, in no ground of the settlement's (" + narrow.lastSeen + ")");
            h.assertTrue(wide.lastSeen == 1 && wide.alarmed(), "a reach of 18 missed the foe 17 off (" + wide.lastSeen + "): the reach only widened the search");
            h.assertTrue(asked.size() == Muster.STEP, "the settlement that reaches 18 was asked for " + asked.size() + " defenders, not " + Muster.STEP
                    + ": none may come up on ground that is within its reach and not its own");
            for (BlockPos at : asked) {
                h.assertTrue(SettlerMob.standable(level, at) && SettlerMob.underOpenSky(level, at) && near(at, middle, 18),
                        "a defender was to come up at " + at.toShortString() + ", not standable, not in the open, or past the reach of 18");
            }
            foe.discard();
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * The muster looks no higher or lower than the heart's reach, however far it looks across (the review of 20da67f: the box was a cube of the
     * reach, so a wide plain's muster looked as far into the sky and the rock). A foe on the ground in a settlement that reaches 20 across is seen
     * from a heart 10 over it, and not from a heart 22 over it: 16 is as far up or down as the muster looks, whatever the reach.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void theMusterLooksNoFurtherUpOrDownThanTheHeartsReachWhateverItsReachAcross(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            var foe = foeAt(level, middle.offset(5, 0, 0), true);
            Muster low = new Muster();
            Muster high = new Muster();
            low.second(level, city(middle, middle.above(10), new ArrayList<>()).is("reach", HALF).settlement());
            high.second(level, city(middle, middle.above(22), new ArrayList<>()).is("reach", HALF).settlement());
            h.assertTrue(low.lastSeen == 1 && low.alarmed(), "a heart 10 over the foe, reaching 20 across, missed it (" + low.lastSeen + ")");
            h.assertTrue(high.lastSeen == 0 && !high.alarmed(), "a heart 22 over the foe saw it (" + high.lastSeen + "): the box is a cube of the reach, not flat");
            foe.discard();
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * {@code Muster.cell} stops at the first open cell for a settlement that musters under the sky (the review of 20da67f: every one of its 48 tries
     * of 14 cells was looked through though any open cell was as good as another), where the default settlement, the Warren Heart's, still looks
     * through them all for a tunnel. Counted by how often each is asked whether a cell is its own: the surface settlement at most a few times, the
     * default one on every cell it looks at (672 when all are loaded; well over a hundred is enough here).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSurfaceSettlementTakesTheFirstOpenCellAndTheDefaultOneLooksThroughAllItsTries(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            var foe = foeAt(level, middle.offset(12, 0, 3), false);
            int[] open = {0};
            int[] covered = {0};
            Settlement surface = city(middle, middle, new ArrayList<>()).is("mustersUnderSky", true)
                    .answer("inSettlement", a -> {
                        open[0]++;
                        return near((BlockPos) a[0], middle, HALF);
                    }).settlement();
            Settlement tunnels = city(middle, middle, new ArrayList<>())
                    .answer("inSettlement", a -> {
                        covered[0]++;
                        return near((BlockPos) a[0], middle, HALF);
                    }).settlement();
            for (int i = 0; i < 10; i++) {
                open[0] = 0;
                BlockPos at = Muster.cell(level, surface, foe, i % 2 == 1);
                h.assertTrue(at != null && SettlerMob.underOpenSky(level, at), "the surface settlement was given no open cell (round " + i + ")");
                h.assertTrue(open[0] <= 5, "the surface settlement asked " + open[0] + " times whether a cell was its own before it took one (round " + i
                        + "): it looks on after the first open cell");
            }
            h.assertTrue(Muster.cell(level, tunnels, foe, false) == null, "the default settlement was given a cell on open ground");
            h.assertTrue(covered[0] > 100, "the default settlement looked at " + covered[0] + " cells: its search was cut short, and the Warren Heart's with it");
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A defender never comes up in a cell shut in on every side (Muster#opens). A foe sealed in a stone cell made the tightest "tunnel"
     * there is, and the alarm brought defenders up in it, on top of the foe (the Warren Heart's aRatGivesUpAFoeItCannotReach: a husk
     * shut in stone died in one of its runs in about ninety). The foe stands in the pocket of a stone block, the heart 3 under it and
     * 4 off as in that test, and the cell is asked for 1500 times, as the alarm asks: none may be the pocket. With a side of it opened
     * (the control) the same asking finds it, so the sealed none is the rule's doing and not the ground's (before the rule: about 2 in
     * 100 asks of the sealed one too).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDefenderNeverComesUpInACellShutInOnEverySide(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos pocket = middle.offset(-1, 3, 4);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        boolean air = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                        level.setBlock(pocket.offset(dx, dy, dz), air ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
            var foe = foeAt(level, pocket, false);
            Settlement tunnels = city(middle, middle, new ArrayList<>()).settlement();
            int sealed = 0;
            for (int i = 0; i < 1500; i++) {
                if (pocket.equals(Muster.cell(level, tunnels, foe, false))) {           // (the ground under the block is a cell too, a wide one)
                    sealed++;
                }
            }
            h.assertTrue(sealed == 0, "a defender was to come up " + sealed + " times of 1500 in a cell shut in on every side, at " + pocket.toShortString());
            level.setBlock(pocket.east(), Blocks.AIR.defaultBlockState(), 2);        // the control: a way out of the pocket
            level.setBlock(pocket.east().above(), Blocks.AIR.defaultBlockState(), 2);
            int opened = 0;
            for (int i = 0; i < 1500; i++) {
                BlockPos at = Muster.cell(level, tunnels, foe, false);
                if (pocket.equals(at) || pocket.east().equals(at)) {
                    opened++;
                }
            }
            h.assertTrue(opened > 0, "the pocket with a way out was never asked for in 1500 asks: the ground gives none a cell, so the sealed none proves nothing");
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }
}
