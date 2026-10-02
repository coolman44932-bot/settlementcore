package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.defence.Muster;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.unit.Footing;
import claude.underempire.core.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

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
    static BlockPos openGround(GameTestHelper h) {
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
    static void clearGround(GameTestHelper h, BlockPos middle) {
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
     * A defender never comes up in a cell shut in on every side (Footing#escapes, first Muster#opens). A foe sealed in a stone cell made the
     * tightest "tunnel" there is, and the alarm brought defenders up in it, on top of the foe (the Warren Heart's aRatGivesUpAFoeItCannotReach: a
     * husk shut in stone died in one of its runs in about ninety). The foe stands in the pocket of a stone block, the heart 3 under it and
     * 4 off as in that test, and the cell is asked for 1500 times, as the alarm asks: none may be the pocket. With a side of it opened
     * (the control) the same asking finds it, so the sealed none is the rule's doing and not the ground's (before the rule: about 2 in
     * 100 asks of the sealed one too). The hollows of more cells, the tunnel, the half blocks and the shaft are the tests that follow.
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

    private static final int ASKS = 500;

    /**
     * A block of stone round a hollow of {@code w} by {@code d} cells, two high, the first of them at {@code at}: a wall one thick all round
     * it, a floor under and a roof over, and air in the hollow, so a settler stands in each of its cells under cover and shut in. A wall
     * opened is a way out.
     */
    static void hollow(ServerLevel level, BlockPos at, int w, int d) {
        hollow(level, at, w, d, 2);
    }

    /** {@link #hollow(ServerLevel, BlockPos, int, int)} {@code high} cells high: a tall one has cells of air a body fits in over the ones it stands in. */
    static void hollow(ServerLevel level, BlockPos at, int w, int d, int high) {
        for (int dx = -1; dx <= w; dx++) {
            for (int dy = -1; dy <= high; dy++) {
                for (int dz = -1; dz <= d; dz++) {
                    boolean air = dx >= 0 && dx < w && dz >= 0 && dz < d && dy >= 0 && dy < high;
                    level.setBlock(at.offset(dx, dy, dz), air ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
    }

    /** The block of {@link #hollow} laid at {@code at} for {@code w} by {@code d}, back to air. */
    static void unhollow(ServerLevel level, BlockPos at, int w, int d) {
        unhollow(level, at, w, d, 2);
    }

    /** The block of {@link #hollow(ServerLevel, BlockPos, int, int, int)} laid at {@code at} for {@code w} by {@code d} by {@code high}, back to air. */
    static void unhollow(ServerLevel level, BlockPos at, int w, int d, int high) {
        for (int dx = -1; dx <= w; dx++) {
            for (int dy = -1; dy <= high; dy++) {
                for (int dz = -1; dz <= d; dz++) {
                    level.setBlock(at.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** Tall hollows the limit once counted floating air layers in: wide by deep by high, each with fewer than {@link Footing#LIMIT} cells to stand in. */
    static final int[][] TALL = {{3, 3, 4}, {4, 3, 3}, {2, 2, 8}, {1, 12, 3}};

    /** Whether {@code p} is a cell of the floor of the hollow of {@code w} by {@code d} cells that begins at {@code at}. */
    private static boolean in(BlockPos p, BlockPos at, int w, int d) {
        return p.getY() == at.getY() && p.getX() >= at.getX() && p.getX() < at.getX() + w && p.getZ() >= at.getZ() && p.getZ() < at.getZ() + d;
    }

    /**
     * Of {@code asks} asks of Muster#cell for a worker's cell, as the alarm asks, how many came back a cell that {@code is}: all the asks, or
     * (with {@code untilOne}) as many as it took to find the first.
     */
    private static int asked(ServerLevel level, Settlement city, Mob foe, int asks, boolean untilOne, Predicate<BlockPos> is) {
        int n = 0;
        for (int i = 0; i < asks; i++) {
            BlockPos cell = Muster.cell(level, city, foe, false);
            if (cell != null && is.test(cell)) {
                n++;
                if (untilOne) {
                    break;
                }
            }
        }
        return n;
    }

    /**
     * A defender never comes up in a hollow shut in on every side, whatever the number of its cells (the first rule, Muster#opens, asked only
     * for a side with two free cells beyond it, so every hollow of two cells or more passed it: the review of 8f199d2). A sealed hollow of
     * 2 by 1, 1 by 2, 2 by 2 and 3 by 3 cells, and a tunnel of 6 sealed at both ends, each in a stone block with the foe in its first cell,
     * the heart under and off to one side as in the test of the pocket of one: the cell is asked for {@link #ASKS} times, and none may be
     * a cell of it. Then its west wall is opened (the control: the same asking finds a cell of it, so the sealed none is the rule's doing).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDefenderNeverComesUpInASealedHollowOfSeveralCells(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            Settlement tunnels = city(middle, middle, new ArrayList<>()).settlement();
            for (int[] size : new int[][]{{2, 1}, {1, 2}, {2, 2}, {3, 3}, {6, 1}}) {
                int w = size[0];
                int d = size[1];
                hollow(level, at, w, d);
                var foe = foeAt(level, at, false);
                int sealed = asked(level, tunnels, foe, ASKS, false, c -> in(c, at, w, d));
                h.assertTrue(sealed == 0, "a defender was to come up " + sealed + " times of " + ASKS + " in a sealed hollow of " + w + " by " + d
                        + " cells at " + at.toShortString());
                level.setBlock(at.west(), Blocks.AIR.defaultBlockState(), 2);                    // the control: a way out
                level.setBlock(at.west().above(), Blocks.AIR.defaultBlockState(), 2);
                int opened = asked(level, tunnels, foe, 4 * ASKS, true, c -> in(c, at, w, d));
                h.assertTrue(opened > 0, "the hollow of " + w + " by " + d + " with a way out was never asked for in " + 4 * ASKS
                        + " asks: the ground gives none a cell, so the sealed none proves nothing");
                unhollow(level, at, w, d);
            }
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A tunnel one wide, open at one end, is a place to come up in all the way along it, to the dead end: the way out is the cells of the
     * tunnel, 8 of them (fewer than {@link Footing#LIMIT}), and then the open air at its end. The foe stands in the blind end, and the cell is
     * asked for until one comes in the half of the tunnel furthest from the opening.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDefenderComesUpDeepInATunnelOpenAtOneEnd(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            hollow(level, at, 8, 1);
            level.setBlock(at.offset(8, 0, 0), Blocks.AIR.defaultBlockState(), 2);               // the far end opened
            level.setBlock(at.offset(8, 1, 0), Blocks.AIR.defaultBlockState(), 2);
            var foe = foeAt(level, at, false);
            Settlement tunnels = city(middle, middle, new ArrayList<>()).settlement();
            int deep = asked(level, tunnels, foe, 8 * ASKS, true, c -> in(c, at, 4, 1));
            h.assertTrue(deep > 0, "no defender came up in the blind half of a tunnel open at its far end in " + 8 * ASKS + " asks");
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A floor cell whose four neighbours are half blocks is a place to come up in: a settler steps up onto a slab, a carpet or a stair, so it
     * is not shut in (Muster#opens counted every neighbour with a collision shape a wall, and refused it). The cell is on a stone floor under
     * a stone roof, with a slab to the north, a stair east, a carpet south and a stair west, the foe in it, and asked for until it comes up.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDefenderComesUpOnAFloorCellRingedBySlabsAndStairs(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    level.setBlock(at.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);   // the floor
                    level.setBlock(at.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), 2);    // the roof: three clear over the floor, for a settler on a slab
                }
            }
            level.setBlock(at.north(), Blocks.STONE_SLAB.defaultBlockState(), 2);
            level.setBlock(at.east(), Blocks.STONE_STAIRS.defaultBlockState(), 2);
            level.setBlock(at.south(), Blocks.WHITE_CARPET.defaultBlockState(), 2);
            level.setBlock(at.west(), Blocks.STONE_STAIRS.defaultBlockState(), 2);
            var foe = foeAt(level, at, false);
            Settlement tunnels = city(middle, middle, new ArrayList<>()).settlement();
            h.assertTrue(SettlerMob.standable(level, at) && !SettlerMob.underOpenSky(level, at), "the cell ringed by half blocks is not a standable one under cover");
            int ringed = asked(level, tunnels, foe, 8 * ASKS, true, at::equals);
            h.assertTrue(ringed > 0, "no defender came up in a floor cell ringed by slabs, stairs and a carpet in " + 8 * ASKS + " asks, at " + at.toShortString());
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * The foot of a shaft is no place to come up in, as before: the cell at the bottom of a shaft one wide, two deeper than the passage that
     * leaves it near the top for the open air, has nothing walkable at its side and its way out is a climb (only the flood of walkable cells
     * is asked, so a shaft section is refused, as Muster#opens refused it; the passage's floor is a cell to come up in all the same). Opened
     * on one side (the control) it is asked for.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDefenderIsNotBroughtUpAtTheFootOfAShaft(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos foot = middle.offset(-1, 3, 4);
            for (int dx = -1; dx <= 6; dx++) {
                for (int dy = -1; dy <= 4; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        boolean shaft = dx == 0 && dz == 0 && dy >= 0 && dy <= 3;
                        boolean passage = dx >= 1 && dz == 0 && (dy == 2 || dy == 3);           // off the top of the shaft, its far end open
                        level.setBlock(foot.offset(dx, dy, dz), shaft || passage ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
                    }
                }
            }
            var foe = foeAt(level, foot, false);
            Settlement tunnels = city(middle, middle, new ArrayList<>()).settlement();
            int shut = asked(level, tunnels, foe, 2 * ASKS, false, foot::equals);
            h.assertTrue(shut == 0, "a defender was to come up " + shut + " times of " + 2 * ASKS + " at the foot of a shaft, at " + foot.toShortString());
            level.setBlock(foot.west(), Blocks.AIR.defaultBlockState(), 2);                       // the control: a way out at its side
            level.setBlock(foot.west().above(), Blocks.AIR.defaultBlockState(), 2);
            int opened = asked(level, tunnels, foe, 16 * ASKS, true, foot::equals);
            h.assertTrue(opened > 0, "the foot of the shaft with a way out at its side was never asked for in " + 16 * ASKS + " asks: the ground gives none a cell");
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * The muster counts only the foes with a way out (Muster#shutIn). A foe sealed in stone, which no rat can come at and none sees, kept the alarm up
     * for as long as it lived, with defenders up for it and no all-clear. A foe on its feet in a sealed hollow of 1 by 1 and of 2 by 2 cells raises no
     * alarm in a settlement that musters under the sky (before: STEP defenders were asked up on the open ground between it and the heart); the same foe
     * with a wall of its hollow opened does, so the quiet is the rule's doing.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aFoeShutInStoneDoesNotRaiseTheAlarm(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            for (int[] size : new int[][]{{1, 1}, {2, 2}}) {
                hollow(level, at, size[0], size[1]);
                var foe = foeAt(level, at, true);
                foe.setOnGround(true);
                List<BlockPos> shutAsked = new ArrayList<>();
                Muster shut = new Muster();
                shut.second(level, city(middle, middle, shutAsked).is("mustersUnderSky", true).settlement());
                h.assertTrue(foe.isAlive(), "the foe was removed from the world");
                h.assertTrue(!shut.alarmed() && shut.lastSeen == 0 && shutAsked.isEmpty(), "a foe sealed in a hollow of " + size[0] + " by " + size[1]
                        + " raised the alarm (" + shut.alarmed() + "), was seen " + shut.lastSeen + " times and had " + shutAsked.size() + " defenders asked up");
                level.setBlock(at.west(), Blocks.AIR.defaultBlockState(), 2);                      // the control: a way out
                level.setBlock(at.west().above(), Blocks.AIR.defaultBlockState(), 2);
                List<BlockPos> openAsked = new ArrayList<>();
                Muster open = new Muster();
                open.second(level, city(middle, middle, openAsked).is("mustersUnderSky", true).settlement());
                h.assertTrue(open.alarmed() && open.lastSeen == 1 && openAsked.size() == Muster.STEP, "the same foe with a way out of its hollow of " + size[0] + " by "
                        + size[1] + ": alarm " + open.alarmed() + ", seen " + open.lastSeen + ", " + openAsked.size() + " defenders asked up, not " + Muster.STEP);
                foe.discard();
                unhollow(level, at, size[0], size[1]);
            }
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * The alarm stands down while a foe shut in stone still lives (Muster#shutIn): a foe in the open raises it and is the only one seen, the foe sealed
     * in the stone block not counted with it (one seen, not two: a defender for each); and when the foe in the open is gone the alarm ends within
     * ALL_CLEAR seconds, as it would with no foe about, the sealed one living (before: it stood for as long as the sealed foe lived).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void theAlarmStandsDownWhileAFoeShutInStoneStillLives(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            hollow(level, at, 2, 1);
            var shut = foeAt(level, at, true);
            shut.setOnGround(true);
            var loose = foeAt(level, middle.offset(10, 0, 0), true);
            loose.setOnGround(true);
            Muster muster = new Muster();
            Settlement surface = city(middle, middle, new ArrayList<>()).is("mustersUnderSky", true).settlement();
            muster.second(level, surface);
            h.assertTrue(muster.alarmed() && muster.lastSeen == 1, "the alarm for a foe in the open: " + muster.alarmed() + ", " + muster.lastSeen
                    + " foes seen, not 1 (the foe sealed in stone is not one)");
            loose.discard();
            for (int i = 0; i < Muster.ALL_CLEAR; i++) {
                muster.second(level, surface);
            }
            h.assertTrue(shut.isAlive(), "the foe in the stone was removed from the world");
            h.assertTrue(!muster.alarmed(), "the alarm was still up " + Muster.ALL_CLEAR + " seconds after the last foe it could be asked up for was gone, a foe sealed in stone alive");
            shut.discard();
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * The flood for a foe is for a body of its own height: a spider (0.9 high) sealed in a gap one cell high, where no settler fits, is shut in, and
     * raises no alarm; with the wall of the gap opened it does. (A flood for a body two high found no room for it, and counted it.)
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSmallFoeShutInAGapOneCellHighDoesNotRaiseTheAlarm(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            hollow(level, at, 1, 1);
            level.setBlock(at.above(), Blocks.STONE.defaultBlockState(), 2);                         // a gap one high
            Spider spider = EntityType.SPIDER.create(level);
            spider.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            spider.setNoAi(true);
            spider.setPersistenceRequired();
            level.addFreshEntity(spider);
            spider.setOnGround(true);
            Muster shut = new Muster();
            shut.second(level, city(middle, middle, new ArrayList<>()).is("mustersUnderSky", true).settlement());
            h.assertTrue(spider.isAlive(), "the spider was removed from the world");
            h.assertTrue(!shut.alarmed() && shut.lastSeen == 0, "a spider sealed in a gap one high raised the alarm (" + shut.alarmed() + "), seen " + shut.lastSeen + " times");
            level.setBlock(at.west(), Blocks.AIR.defaultBlockState(), 2);                            // the control: a way out for it
            Muster open = new Muster();
            open.second(level, city(middle, middle, new ArrayList<>()).is("mustersUnderSky", true).settlement());
            h.assertTrue(open.alarmed() && open.lastSeen == 1, "the spider with a way out of its gap: alarm " + open.alarmed() + ", seen " + open.lastSeen + ", not 1");
            spider.discard();
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A foe on its feet in a tall hollow shut in on every side raises no alarm, however many cells of air the hollow has over its floor (Footing's
     * limit counted every cell of air a body fits in, so a sealed hollow of 3 by 3 by 4, 4 by 3 by 3, 2 by 2 by 8 or 1 by 12 by 3 cells, 24 or more
     * of air and under 24 to stand in, passed as open: the foe was counted, and its alarm raised: the review of 18f90e3). With a wall of the
     * hollow opened (the control) the same foe is seen and raises it, so the quiet is the rule's doing.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aFoeShutInATallSealedHollowDoesNotRaiseTheAlarm(GameTestHelper h) {
        BlockPos middle = openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            for (int[] size : TALL) {
                String name = size[0] + " by " + size[1] + " by " + size[2];
                hollow(level, at, size[0], size[1], size[2]);
                var foe = foeAt(level, at, true);
                foe.setOnGround(true);
                Muster shut = new Muster();
                shut.second(level, city(middle, middle, new ArrayList<>()).is("mustersUnderSky", true).settlement());
                h.assertTrue(foe.isAlive(), "the foe was removed from the world");
                h.assertTrue(!shut.alarmed() && shut.lastSeen == 0, "a foe sealed in a hollow of " + name + " cells raised the alarm (" + shut.alarmed()
                        + "), and was seen " + shut.lastSeen + " times");
                level.setBlock(at.west(), Blocks.AIR.defaultBlockState(), 2);                      // the control: a way out
                level.setBlock(at.west().above(), Blocks.AIR.defaultBlockState(), 2);
                Muster open = new Muster();
                open.second(level, city(middle, middle, new ArrayList<>()).is("mustersUnderSky", true).settlement());
                h.assertTrue(open.alarmed() && open.lastSeen == 1, "the same foe with a way out of its hollow of " + name + ": alarm " + open.alarmed()
                        + ", seen " + open.lastSeen + ", not 1");
                foe.discard();
                unhollow(level, at, size[0], size[1], size[2]);
            }
        } finally {
            clearGround(h, middle);
        }
        h.succeed();
    }
}
