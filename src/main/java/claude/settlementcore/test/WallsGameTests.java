package claude.settlementcore.test;

import claude.settlementcore.defence.Muster;
import claude.settlementcore.room.DigJob;
import claude.settlementcore.room.WallJob;
import claude.settlementcore.room.WallStyle;
import claude.settlementcore.settlement.SettlementSpawns;
import claude.settlementcore.settlement.Walls;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A settlement's walls (settlement/Walls, docs/plans/walls.md, E1), on the engine's plot (SitingGameTests.Site: flat grass at +360): a
 * square ring of stone round the heart, 13 across. Two high, it walls the settlement in; one high, mobs step over it; a gap opens it; a
 * gate (open) is still wall. Walled in, a foe outside the wall is outside for the muster even where the race's land says otherwise, the
 * walls keep monsters from spawning beyond the race's land, and a broken wall is noticed.
 */
@GameTestHolder("settlementcore")
public class WallsGameTests {

    private static final int R = 6;
    /** The walk's reach here: inside the plot (22 out from its middle), so its edge, a cliff, never closes a ring. */
    private static final int WALK = 15;

    /** The plot laid flat, and the heart's spot in its middle (absolute). */
    private static BlockPos plot(SitingGameTests.Site s) {
        s.lay((x, z) -> s.floor);
        return new BlockPos((s.px0 + s.px1) / 2, s.floor + 1, (s.pz0 + s.pz1) / 2);
    }

    /** A square ring of {@code block}, {@code high} blocks high, {@code r} out from {@code c}. */
    private static void ring(SitingGameTests.Site s, BlockPos c, int r, int high, BlockState block) {
        for (int i = -r; i <= r; i++) {
            for (int y = 0; y < high; y++) {
                for (BlockPos p : new BlockPos[]{c.offset(i, y, -r), c.offset(i, y, r), c.offset(-r, y, i), c.offset(r, y, i)}) {
                    s.level.setBlock(p, block, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    @GameTest(template = "digsite", timeoutTicks = 60, batch = "walls")
    public static void aTwoHighRingWallsTheSettlementAndAGapOpensIt(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            ring(s, c, R, 2, Blocks.STONE.defaultBlockState());
            Walls.Scan in = Walls.walk(s.level, c, WALK);
            h.assertTrue(in.walled(), "a 2-high ring didn't wall it in (out at " + in.leak() + ")");
            h.assertTrue(in.columns().size() == (2 * R - 1) * (2 * R - 1), "inside the walls are " + in.columns().size() + " columns, not " + (2 * R - 1) * (2 * R - 1));
            s.level.setBlock(c.offset(R, 0, 2), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            s.level.setBlock(c.offset(R, 1, 2), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            Walls.Scan gap = Walls.walk(s.level, c, WALK);
            h.assertTrue(!gap.walled() && gap.leak() != null, "a gap in the ring didn't open it");
            s.clear();
            h.succeed();
        }));
    }

    @GameTest(template = "digsite", timeoutTicks = 60, batch = "walls")
    public static void aOneHighRingIsSteppedOver(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            ring(s, c, R, 1, Blocks.STONE.defaultBlockState());
            h.assertTrue(!Walls.walk(s.level, c, WALK).walled(), "a 1-high ring walled it in: a mob steps over one block");
            ring(s, c, R, 1, Blocks.AIR.defaultBlockState());
            ring(s, c, 3, 2, Blocks.STONE.defaultBlockState());   // 5 by 5 inside: a hut round the heart, not walls (the review of b8c9e8b)
            h.assertTrue(!Walls.walk(s.level, c, WALK).walled(), "a ring 5 across inside walled it in: under " + Walls.MIN_COLUMNS + " columns is a hut");
            s.clear();
            h.succeed();
        }));
    }

    @GameTest(template = "digsite", timeoutTicks = 60, batch = "walls")
    public static void anOpenGateIsStillWall(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            ring(s, c, R, 2, Blocks.STONE.defaultBlockState());
            s.level.setBlock(c.offset(-R, 0, 0), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true), Block.UPDATE_CLIENTS);
            s.level.setBlock(c.offset(-R, 1, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            h.assertTrue(Walls.walk(s.level, c, WALK).walled(), "an open gate in the ring opened it: the settlement's own gate never breaks its wall");
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Walled in, the muster defends only inside the walls (a foe outside the ring, though the race's land covers it, is outside), the walls
     * keep monsters from spawning even where the race's land doesn't reach, and when the wall breaks the scan says where.
     */
    @GameTest(template = "digsite", timeoutTicks = 60, batch = "walls")
    public static void walledInOutsideIsOutside(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            ring(s, c, R, 2, Blocks.STONE.defaultBlockState());
            var t = new SettlementBlockEntityGameTests.TestSettlement(c, Blocks.CHEST.defaultBlockState());
            t.setLevel(s.level);
            t.claim(h.makeMockServerPlayerInLevel(), "The Walled Test");
            t.holds = true;                                      // the race's own land: everything
            try {
                h.assertTrue(t.walls().scan(s.level, c, WALK) == Walls.Change.NONE && t.walled(), "the settlement didn't find its wall");
                var ring = Muster.ring(t);
                BlockPos inside = c.offset(2, 0, 2), outside = c.offset(R + 3, 0, 0);
                h.assertTrue(Muster.holds(t, ring, inside), "a foe inside the walls is outside the muster's ground");
                h.assertTrue(!Muster.holds(t, ring, outside), "a foe outside the walls is inside the muster's ground, though the wall stands between");
                t.holds = false;                                 // the race's land now nothing: the walls alone keep spawns out
                SettlementSpawns.add(s.level, t);
                h.assertTrue(SettlementSpawns.inAnyWarren(s.level, inside), "a monster could spawn inside the walls");
                h.assertTrue(!SettlementSpawns.inAnyWarren(s.level, outside), "the walls kept spawns out past themselves");
                s.level.setBlock(c.offset(0, 0, R), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                s.level.setBlock(c.offset(0, 1, R), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                h.assertTrue(t.walls().scan(s.level, c, WALK) == Walls.Change.BROKEN && !t.walled() && t.walls().leak() != null, "the broken wall wasn't noticed");
                h.assertTrue(t.wallBrokenLine("1 2").contains("broken at 1 2"), "the broken line: " + t.wallBrokenLine("1 2"));
            } finally {
                SettlementSpawns.remove(s.level, t);
                t.setRemoved();
            }
            s.clear();
            h.succeed();
        }));
    }

    // --- E2: settlers raise walls ---------------------------------------------------------------------------------------

    /** The engine's own test wall: 3 of cobblestone, a gate (a fence gate, an opening over it, a lintel), 2 a column. */
    static final WallStyle STONE = new WallStyle("test_stone", "the test wall", 3, 2, 1,
            (i, k, height, across) -> Blocks.COBBLESTONE.defaultBlockState(),
            (i, k, height, across) -> k == 0 ? Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, across)
                    : k == height - 1 ? Blocks.COBBLESTONE.defaultBlockState() : null);

    static {
        WallStyle.register("settlementcore", STONE);
    }

    /** The land the line runs over: flat to the west, a step of 1 at the heart's x, another of 1 three further east. */
    private static int ground(SitingGameTests.Site s, int cx, int x) {
        return s.floor + (x - cx >= 3 ? 2 : x - cx >= 0 ? 1 : 0);
    }

    /** A wall's job laid by hand, as a builder's swings lay it (RaiseJobGameTests' loop); saved and loaded back half-way. Returns the job that finished. */
    private static WallJob layByHand(GameTestHelper h, SitingGameTests.Site s, WallJob job) {
        job.ensureLoaded(s.level);
        for (int k = 0; job.clearing(); k++) {
            h.assertTrue(k < 64, "the wall's clearing never ends: " + job.state());
            job.clear(s.level, claude.settlementcore.work.SiteWork.CLEAR_PER_SECOND, new ArrayList<>());
        }
        UUID builder = new UUID(0x3a11L, 1L);
        BlockPos near = job.foot(0);
        boolean reloaded = false;
        for (int round = 0; !job.isDone(); round++) {
            h.assertTrue(round < 4000, "no end to the wall's claims: " + job.state() + ", " + job.finishedCount() + " of " + job.taskList().size());
            BlockPos anchor = job.claim(s.level, builder, claude.settlementcore.work.SiteWork.BATCH, s.level.getGameTime(), near);
            h.assertTrue(anchor != null, "nothing of the wall could be handed out, " + (job.taskList().size() - job.finishedCount()) + " cells left, "
                    + job.state() + " (" + job.openNow() + " open now)");
            List<DigJob.Task> batch = job.batch(builder);
            for (int k = 0; k < batch.size(); k++) {
                job.gnawSome(s.level, builder, (k + 0.5f) / batch.size());
            }
            job.complete(s.level, builder);
            near = anchor;
            if (!reloaded && job.finishedCount() * 2 >= job.taskList().size()) {
                reloaded = true;
                CompoundTag saved = SitingGameTests.roundTrip(job.save());
                h.assertTrue(WallJob.isWall(saved), "the half-raised wall saves as " + saved.keySet());
                WallJob back = WallJob.loadWall(saved);
                h.assertTrue(back != null, "the half-raised wall didn't load back");
                back.ensureLoaded(s.level);
                h.assertTrue(back.taskList().equals(job.taskList()), "loaded back, the wall's task list differs");
                h.assertTrue(back.finishedCount() == job.finishedCount(), "loaded back, " + back.finishedCount() + " done, not " + job.finishedCount());
                job = back;
            }
        }
        h.assertTrue(reloaded, "the wall was never saved half-way");
        return job;
    }

    /**
     * A wall along a line over stepped ground, round a corner and off on a slant: every column side by side with the last (nothing
     * slips through a corner), on its own ground, three high; the tree in its way felled whole, first; the gate at the corner post a
     * fence gate facing across; its task list the same after a save and load half-way.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "walls")
    public static void aWallFollowsTheGroundAndFellsATreeInItsWay(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        int cx = (s.px0 + s.px1) / 2, cz = (s.pz0 + s.pz1) / 2;
        s.lay((x, z) -> ground(s, cx, x));
        int wz = cz - 6;
        int tx = cx - 4;
        BlockPos trunk = new BlockPos(tx, ground(s, cx, tx) + 1, wz);
        h.runAfterDelay(20, () -> s.guard(() -> {
            for (int y = 0; y < 5; y++) {
                s.level.setBlock(trunk.above(y), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 3; dy <= 5; dy++) {
                        BlockPos l = trunk.offset(dx, dy, dz);
                        if (s.level.getBlockState(l).isAir()) {
                            s.level.setBlock(l, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false), Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }
            BlockPos heart = new BlockPos(cx, s.floor + 2, cz);
            List<BlockPos> posts = List.of(new BlockPos(cx - 8, 0, wz), new BlockPos(cx + 8, 0, wz), new BlockPos(cx + 12, 0, wz + 5));
            WallJob.Line line = WallJob.line(s.level, heart, posts, false, List.of(1), STONE);
            h.assertTrue(line.why() == null, "the line was refused: " + line.why());
            long[] feet = line.feet();
            for (int i = 1; i < feet.length; i++) {
                BlockPos a = BlockPos.of(feet[i - 1]), b = BlockPos.of(feet[i]);
                h.assertTrue(Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) == 1,
                        "the columns " + a.toShortString() + " and " + b.toShortString() + " aren't side by side");
            }
            h.assertTrue(feet.length == 17 + 9, "the line is " + feet.length + " columns, not " + (17 + 9));
            WallJob job = layByHand(h, s, new WallJob(STONE, line, 0));
            h.assertTrue(job.spared() == 0, "the wall left " + job.spared() + " cells standing as they were");
            for (int i = 0; i < job.columns(); i++) {
                BlockPos f = job.foot(i);
                h.assertTrue(f.getY() == ground(s, cx, f.getX()) + 1, "the column at " + f.getX() + " " + f.getZ() + " stands at " + f.getY()
                        + ", not on its ground at " + ground(s, cx, f.getX()));
                for (int k = 0; k < 3; k++) {
                    BlockState now = s.level.getBlockState(f.above(k));
                    if (job.gate(i)) {
                        h.assertTrue(k != 0 || now.getBlock() instanceof FenceGateBlock && now.getValue(FenceGateBlock.FACING).getAxis() == Direction.Axis.Z,
                                "the gate at " + f.toShortString() + " is " + now + ", not a fence gate facing across the wall");
                    } else {
                        h.assertTrue(now.is(Blocks.COBBLESTONE), "the wall's cell " + f.above(k).toShortString() + " is " + now);
                    }
                }
            }
            h.assertTrue(job.gate(16) && !job.gate(15) && !job.gate(17), "the gate isn't the corner post's column alone");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 0; dy <= 5; dy++) {
                        BlockState c = s.level.getBlockState(trunk.offset(dx, dy, dz));
                        h.assertTrue(!c.is(Blocks.OAK_LOG) && !c.is(Blocks.OAK_LEAVES), "the tree in the wall's way stands at " + trunk.offset(dx, dy, dz).toShortString());
                    }
                }
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A ring ordered round a settlement: refused while it can't pay (2 a column) or reaches too far, then raised, paid; saved and
     * loaded with the settlement's works; laid; and the settlement is walled in, its gate a gate. A second ring called off before a
     * cell of it is laid pays everything back.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "walls")
    public static void aRingIsOrderedPaidForAndWallsTheSettlementIn(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            var t = new SettlementBlockEntityGameTests.TestSettlement(c, Blocks.CHEST.defaultBlockState());
            t.setLevel(s.level);
            t.claim(h.makeMockServerPlayerInLevel(), "The Ringed Test");
            try {
                int r = 7;
                List<BlockPos> ring = List.of(c.offset(-r, 0, -r), c.offset(r, 0, -r), c.offset(r, 0, r), c.offset(0, 0, r), c.offset(-r, 0, r));
                int columns = 8 * r;
                String no = t.works().raiseWall(s.level, ring, STONE, true, List.of(3), false);
                h.assertTrue(no != null && no.contains("costs"), "a ring it can't pay for was begun: " + no);
                List<BlockPos> far = List.of(c.offset(-80, 0, 0), c.offset(0, 0, 0), c.offset(0, 0, 5));
                no = t.works().raiseWall(s.level, far, STONE, false, List.of(), true);
                h.assertTrue(no != null && no.contains("from the settlement"), "a wall 80 out was begun: " + no);
                t.addCurrency(2 * columns + 5, "Testing");
                no = t.works().raiseWall(s.level, ring, STONE, true, List.of(3), false);
                h.assertTrue(no == null, "the ring was refused: " + no);
                h.assertTrue(t.currency() == 5, "the ring of " + columns + " columns left " + t.currency() + " of " + (2 * columns + 5) + ", not 5");
                CompoundTag works = t.works().save(s.level.registryAccess());
                t.works().load(works, s.level.registryAccess());
                h.assertTrue(t.works().sites().size() == 1 && t.works().sites().get(0).job() instanceof WallJob w && w.columns() == columns && w.paid() == 2 * columns,
                        "the ring didn't come back with the settlement's works: " + t.works().sites());
                WallJob job = (WallJob) t.works().sites().get(0).job();
                job.ensureLoaded(s.level);
                h.assertTrue(!t.walls().walled(), "walled before a stone of the ring was laid");
                job.finishAll(s.level);
                h.assertTrue(t.walls().scan(s.level, c, WALK) == Walls.Change.NONE && t.walled(), "the ring didn't wall the settlement in: out at " + t.walls().leak());
                BlockState gate = s.level.getBlockState(c.offset(0, 0, r));
                h.assertTrue(gate.getBlock() instanceof FenceGateBlock, "the ring's gate is " + gate);
                // a second ring, called off before anything of it is laid: paid back whole
                t.addCurrency(2 * 4 * 4, "Testing");
                int before = t.currency();
                List<BlockPos> small = List.of(c.offset(-2, 0, -2), c.offset(2, 0, -2), c.offset(2, 0, 2), c.offset(-2, 0, 2));
                no = t.works().raiseWall(s.level, small, STONE, true, List.of(), false);
                h.assertTrue(no == null && t.currency() == before - 2 * 16, "the small ring: " + no + ", " + t.currency() + " left of " + before);
                var called = t.works().cancel(s.level, BlockPos.of(WallJob.line(s.level, c, small, true, List.of(), STONE).feet()[0]));
                h.assertTrue(called != null && called.atOnce() && t.currency() == before, "called off, the small ring paid back " + (t.currency() - before + 32)
                        + " of 32 (" + called + ")");
            } finally {
                t.setRemoved();
            }
            s.clear();
            h.succeed();
        }));
    }

    // --- E3: gates, manning the wall, patches ---------------------------------------------------------------------------

    /** A ring of the test wall round {@code c}, {@code r} out, its gate the middle of the south side, raised whole and recorded; the settlement walled in. */
    private static WallJob walledRing(GameTestHelper h, SitingGameTests.Site s, SettlementBlockEntityGameTests.TestSettlement t, BlockPos c, int r) {
        List<BlockPos> ring = List.of(c.offset(-r, 0, -r), c.offset(r, 0, -r), c.offset(r, 0, r), c.offset(0, 0, r), c.offset(-r, 0, r));
        String no = t.works().raiseWall(s.level, ring, STONE, true, List.of(3), true);
        h.assertTrue(no == null, "the ring was refused: " + no);
        WallJob job = (WallJob) t.works().sites().get(0).job();
        job.finishAll(s.level);
        t.works().second(s.level, 1);                            // handed over: recorded, as the game does it
        h.assertTrue(t.works().sites().isEmpty() && t.walls().built().size() == 1, "the finished ring wasn't handed over and recorded");
        t.walls().scan(s.level, c, WALK);
        h.assertTrue(t.walled(), "the ring didn't wall it in: out at " + t.walls().leak());
        return job;
    }

    /**
     * Walled in, a foe at the walls (2 out from the ring) shuts the gates and its owner is told (no alarm proper: nothing is in); one 12 out is nothing
     * to it. Once the foe is gone the all-clear opens them. The scan found the gate in the ring.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "walls")
    public static void aFoeAtTheWallsShutsTheGatesAndTheAllClearOpensThem(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            var t = new SettlementBlockEntityGameTests.TestSettlement(c, Blocks.CHEST.defaultBlockState());
            t.setLevel(s.level);
            t.claim(h.makeMockServerPlayerInLevel(), "The Gated Test");
            List<net.minecraft.world.entity.Entity> foes = new ArrayList<>();
            try {
                int r = 7;
                walledRing(h, s, t, c, r);
                BlockPos gate = c.offset(0, 0, r);
                h.assertTrue(t.walls().gates().contains(gate.asLong()), "the scan didn't find the ring's gate at " + gate.toShortString() + ": " + t.walls().gates().size());
                s.level.setBlock(gate, s.level.getBlockState(gate).setValue(FenceGateBlock.OPEN, true), Block.UPDATE_CLIENTS);   // open, as a palisade's are built
                BlockPos pen = c.offset(3, 0, 0);                // a pen's gate inside, shut: the all-clear never opens it (the review of b8c9e8b)
                s.level.setBlock(pen, Blocks.OAK_FENCE_GATE.defaultBlockState(), Block.UPDATE_CLIENTS);
                t.walls().scan(s.level, c, WALK);
                BlockPos far = c.offset(r + 12, 0, 0);
                h.assertTrue(!t.atWalls(far) && t.atWalls(c.offset(r + 2, 0, 0)) && !t.atWalls(c.offset(2, 0, 0)),
                        "at the walls: 12 out " + t.atWalls(far) + ", 2 out " + t.atWalls(c.offset(r + 2, 0, 0)) + ", inside " + t.atWalls(c.offset(2, 0, 0)));
                foes.add(MusterGameTests.foeAt(s.level, far, true));
                t.muster().second(s.level, t);
                h.assertTrue(!t.muster().alarmed() && s.level.getBlockState(gate).getValue(FenceGateBlock.OPEN), "a foe 12 out of the walls shut the gates");
                var at = MusterGameTests.foeAt(s.level, c.offset(0, 0, r + 2), true);
                foes.add(at);
                t.muster().second(s.level, t);
                h.assertTrue(t.muster().alarmed() && !s.level.getBlockState(gate).getValue(FenceGateBlock.OPEN), "a foe at the gate didn't shut it");
                h.assertTrue(t.heard.stream().anyMatch(l -> l.contains("at the walls")), "its owner wasn't told of the foe at the walls: " + t.heard);
                h.assertTrue(t.heard.stream().noneMatch(l -> l.contains("is attacked")), "a foe outside the walls raised the alarm proper: " + t.heard);
                at.discard();
                for (int k = 0; k < Muster.ALL_CLEAR + 1; k++) {
                    t.muster().second(s.level, t);
                }
                h.assertTrue(!t.muster().alarmed() && s.level.getBlockState(gate).getValue(FenceGateBlock.OPEN), "the all-clear didn't open the gate");
                h.assertTrue(!s.level.getBlockState(pen).getValue(FenceGateBlock.OPEN), "the all-clear opened the pen's gate, which was shut");
                h.assertTrue(t.heard.stream().noneMatch(l -> l.contains("krumped") || l.contains("stand down") || l.contains("All clear")),
                        "foes at the walls only, and the all-clear was told: " + t.heard);
            } finally {
                foes.forEach(net.minecraft.world.entity.Entity::discard);
                t.setRemoved();
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * A wall its settlers raised, with two blocks knocked out of it (as a creeper does), is patched: the builders get a free patch of just those two,
     * one patch at a time, and once laid the wall stands whole and the settlement is walled again. A block a player takes out of it stays out. Its
     * record of the wall saves and loads.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "walls")
    public static void aWallAFoeKnocksBlocksOutOfIsPatchedAndAPlayersGapIsLeft(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            var t = new SettlementBlockEntityGameTests.TestSettlement(c, Blocks.CHEST.defaultBlockState());
            t.setLevel(s.level);
            t.claim(h.makeMockServerPlayerInLevel(), "The Patched Test");
            try {
                int r = 7;
                walledRing(h, s, t, c, r);
                h.assertTrue(!t.mendWalls(s.level), "a whole wall was patched");
                BlockPos a = c.offset(-r, 0, 2), b = c.offset(-r, 1, 2);
                s.level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                s.level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                t.walls().scan(s.level, c, WALK);
                h.assertTrue(!t.walled(), "a hole 2 high didn't open the ring");
                h.assertTrue(t.mendWalls(s.level), "the knocked-out wall wasn't patched");
                h.assertTrue(!t.mendWalls(s.level), "a second patch was begun while the first is at work");
                WallJob patch = (WallJob) t.works().sites().get(0).job();
                patch.ensureLoaded(s.level);
                h.assertTrue(patch.patch() && patch.taskList().size() == 2 && patch.paid() == 0, "the patch lays " + patch.taskList().size() + " cells, paid "
                        + patch.paid());
                CompoundTag saved = SitingGameTests.roundTrip(patch.save());
                WallJob back = WallJob.loadWall(saved);
                h.assertTrue(back != null && back.patch(), "the patch didn't load back as one");
                back.ensureLoaded(s.level);
                h.assertTrue(back.taskList().equals(patch.taskList()), "loaded back, the patch's task list differs");
                patch.finishAll(s.level);
                t.works().second(s.level, 2);
                h.assertTrue(t.works().sites().isEmpty() && t.walls().built().size() == 1, "the finished patch wasn't handed over (or was recorded as a wall)");
                h.assertTrue(s.level.getBlockState(a).is(Blocks.COBBLESTONE) && s.level.getBlockState(b).is(Blocks.COBBLESTONE), "the patch didn't lay the hole");
                t.walls().scan(s.level, c, WALK);
                h.assertTrue(t.walled(), "patched, the ring still has a way out at " + t.walls().leak());
                BlockPos p = c.offset(r, 1, -3);
                t.wallCellBroken(p);                             // a player's pick (SettlementSpawns#broken)
                s.level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                h.assertTrue(!t.mendWalls(s.level), "the block a player took out was patched");
                Walls w = new Walls();
                w.load(SitingGameTests.roundTrip(t.walls().save()));
                h.assertTrue(w.built().size() == 1 && w.built().get(0).feet().length == 8 * r && w.missing(s.level) == null,
                        "the record loads back with " + w.built().size() + " walls, missing " + w.missing(s.level));
            } finally {
                t.setRemoved();
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Not walled, a foe a Watch-Post saw far out raises the alarm proper as ever, never "at the walls" (the review of b8c9e8b: it was told as foes
     * at the walls, with no bell, of a settlement with no walls).
     */
    @GameTest(template = "digsite", timeoutTicks = 60, batch = "walls")
    public static void aFoeAWatchPostSawIsTheAlarmProperOfASettlementWithNoWalls(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            var t = new SettlementBlockEntityGameTests.TestSettlement(c, Blocks.CHEST.defaultBlockState());
            t.setLevel(s.level);
            t.claim(h.makeMockServerPlayerInLevel(), "The Open Test");
            var foe = MusterGameTests.foeAt(s.level, c.offset(18, 0, 0), true);
            try {
                t.walls().scan(s.level, c, WALK);
                h.assertTrue(!t.walled(), "an open plot is walled");
                t.muster().sound(List.of(foe));
                t.muster().second(s.level, t);
                h.assertTrue(t.muster().alarmed(), "the foe the post saw raised no alarm");
                h.assertTrue(t.heard.stream().noneMatch(l -> l.contains("at the walls")), "a settlement with no walls was told of foes at its walls: " + t.heard);
            } finally {
                foe.discard();
                t.setRemoved();
            }
            s.clear();
            h.succeed();
        }));
    }
}
