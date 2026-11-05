package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.defence.Muster;
import claude.settlementcore.room.RoomCaches;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.SettlementSpawns;
import claude.settlementcore.settlement.Walls;
import claude.settlementcore.test.MusterSaveGameTests.Fake;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The robustness fixes of the 0.5.6 bug audit (2026-11-04), each failing before its fix: a full spoils pile destroyed what it had no room for;
 * a gate whose chunk was not loaded at the all-clear stayed shut for good; two hearts inside one ring, and the first one's all-clear opened the
 * gates while the second was still under attack; the room templates were never dropped at a /reload, and a template that failed to read was
 * kept as an empty one; and the muster, turned off or with no owner, never told a defender back in the world to go home.
 */
@GameTestHolder(Core.MODID)
public class AuditFixGameTests {

    private static final int R = 6;
    /** The walk's reach (WallsGameTests#WALK): inside the plot, so its edge never closes a ring. */
    private static final int WALK = 15;

    // --- 1: a full spoils pile -----------------------------------------------------------------------------------------------

    /**
     * A load onto a full spoils pile ({@link Settlement#MAX_SPOIL_STACKS} stacks) is not lost: the pile stays at its cap, and with no Hoard to
     * take it the load lies on the ground at the heart, whole. This is the give-back of a failed making (Crafter) and a meal's bowl (Larder#draw),
     * which call addSpoil with no check of their own; before, addSpoil dropped it from the game.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aLoadOntoAFullSpoilsPileIsNeverLost(GameTestHelper h) {
        TestSettlement s = TestSettlement.of(h, new BlockPos(3, 2, 3));
        ServerLevel level = h.getLevel();
        AABB round = new AABB(s.getBlockPos()).inflate(3);
        try {
            while (s.spoils().size() < Settlement.MAX_SPOIL_STACKS) {
                s.addSpoil(new ItemStack(Items.DIAMOND_SWORD));
            }
            s.addSpoil(new ItemStack(Items.EMERALD, 10));
            h.assertTrue(s.spoils().size() == Settlement.MAX_SPOIL_STACKS && s.spoils().stream().noneMatch(x -> x.is(Items.EMERALD)),
                    "the full pile took the emeralds, or grew past " + Settlement.MAX_SPOIL_STACKS + " stacks: " + s.spoils().size());
            int ground = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, round, e -> e.getItem().is(Items.EMERALD))) {
                ground += e.getItem().getCount();
            }
            h.assertTrue(ground == 10, ground + " of the 10 emeralds the full pile had no room for are on the ground at the heart: the rest were destroyed");
        } finally {
            level.getEntitiesOfClass(ItemEntity.class, round, e -> true).forEach(Entity::discard);
            s.setRemoved();
        }
        h.succeed();
    }

    // --- 2 and 3: gates ------------------------------------------------------------------------------------------------------

    /** The plot laid flat, and the heart's spot in its middle (absolute): WallsGameTests#plot. */
    private static BlockPos plot(SitingGameTests.Site s) {
        s.lay((x, z) -> s.floor);
        return new BlockPos((s.px0 + s.px1) / 2, s.floor + 1, (s.pz0 + s.pz1) / 2);
    }

    /** A square ring of stone, 2 high, {@code r} out from {@code c}. */
    private static void ring(SitingGameTests.Site s, BlockPos c, int r) {
        for (int i = -r; i <= r; i++) {
            for (int y = 0; y < 2; y++) {
                for (BlockPos p : new BlockPos[]{c.offset(i, y, -r), c.offset(i, y, r), c.offset(-r, y, i), c.offset(r, y, i)}) {
                    s.level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private static boolean open(ServerLevel level, BlockPos gate) {
        return level.getBlockState(gate).getValue(FenceGateBlock.OPEN);
    }

    /**
     * A gate it shut whose chunk is not loaded at the all-clear is kept as shut by it, and opened once its chunk is loaded; a loaded one is opened
     * at once. Before, the all-clear forgot every gate it had shut, so one out of the loaded ground stayed shut for good.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "walls")
    public static void aGateShutInAChunkNotLoadedOpensWhenItLoads(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            BlockPos near = c.offset(3, 0, 0);
            s.level.setBlock(near, Blocks.OAK_FENCE_GATE.defaultBlockState(), Block.UPDATE_CLIENTS);   // shut (by it, mid-alarm)
            BlockPos far = null;
            for (int k = 1; k <= 8 && far == null; k++) {
                BlockPos p = c.offset(4096 * k, 0, 0);
                if (!s.level.isLoaded(p)) {
                    far = p;
                }
            }
            h.assertTrue(far != null, "no ground out of the loaded chunks to put a gate in");
            Walls w = new Walls();
            CompoundTag t = new CompoundTag();
            t.putLongArray("shut", new long[]{near.asLong(), far.asLong()});
            w.load(t);
            int opened = w.gates(s.level, false);
            h.assertTrue(opened == 1 && open(s.level, near), "the all-clear opened " + opened + " gates, not the 1 loaded");
            h.assertTrue(w.anyShut(), "the gate whose chunk is not loaded was forgotten at the all-clear: it stays shut for good");
            long[] kept = w.save().getLongArray("shut").orElse(new long[0]);
            h.assertTrue(kept.length == 1 && kept[0] == far.asLong(), "the gates kept as shut are " + kept.length + ", not just the one not loaded");
            int cx = far.getX() >> 4, cz = far.getZ() >> 4;
            s.level.setChunkForced(cx, cz, true);
            BlockState was = null;
            try {
                s.level.getChunk(cx, cz);                        // loaded now, as when a player walks there
                h.assertTrue(s.level.isLoaded(far), "the far chunk didn't load");
                was = s.level.getBlockState(far);
                s.level.setBlock(far, Blocks.OAK_FENCE_GATE.defaultBlockState(), Block.UPDATE_CLIENTS);
                opened = w.gates(s.level, false);                // the next second's try (SettlementBlockEntity#second)
                h.assertTrue(opened == 1 && open(s.level, far) && !w.anyShut(), "with its chunk loaded the kept gate opened " + opened + ", "
                        + (w.anyShut() ? "and is still kept" : "and is let go"));
            } finally {
                if (was != null) {
                    s.level.setBlock(far, was, Block.UPDATE_CLIENTS);
                }
                s.level.setChunkForced(cx, cz, false);
            }
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Two hearts inside one ring: the first shuts the gate at a foe at the walls, the second finds it shut (so it shut none). The first one's
     * all-clear leaves the gate shut while the second is still alarmed, and it opens the second after the second stands down. Before, the first
     * one's all-clear opened it with the second still under attack.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "walls")
    public static void twoHeartsInOneRingOpenTheGateOnlyWhenBothAreClear(GameTestHelper h) {
        SitingGameTests.Site s = new SitingGameTests.Site(h);
        h.runAfterDelay(20, () -> s.guard(() -> {
            BlockPos c = plot(s);
            ring(s, c, R);
            BlockPos gate = c.offset(-R, 0, 0);
            s.level.setBlock(gate, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.OPEN, true), Block.UPDATE_CLIENTS);
            var a = new TestSettlement(c, Blocks.CHEST.defaultBlockState());
            var b = new TestSettlement(c.offset(3, 0, 3), Blocks.CHEST.defaultBlockState());
            List<Entity> foes = new ArrayList<>();
            try {
                for (TestSettlement t : List.of(a, b)) {
                    t.setLevel(s.level);
                    t.claim(h.makeMockServerPlayerInLevel(), t == a ? "The First Test" : "The Second Test");
                    t.walls().scan(s.level, t.getBlockPos(), WALK);
                    h.assertTrue(t.walled() && t.walls().gates().contains(gate.asLong()), "a heart didn't find its ring and the gate: out at " + t.walls().leak());
                    SettlementSpawns.add(s.level, t);            // loaded, as a ticking heart is
                }
                var foe = MusterGameTests.foeAt(s.level, c.offset(-R - 2, 0, 0), true);
                foes.add(foe);
                a.muster().second(s.level, a);
                b.muster().second(s.level, b);
                h.assertTrue(a.muster().alarmed() && b.muster().alarmed() && !open(s.level, gate), "a foe at the gate didn't alarm both hearts and shut it");
                h.assertTrue(a.walls().anyShut() && !b.walls().anyShut(), "the first heart didn't shut the gate, or the second shut it again");
                foe.discard();
                for (int k = 0; k < Muster.ALL_CLEAR + 1; k++) {
                    a.muster().second(s.level, a);               // the first hears the all-clear; the second is still alarmed (its foe not yet judged gone)
                }
                h.assertTrue(!a.muster().alarmed() && b.muster().alarmed(), "the first heart didn't stand down, or the second did with it");
                h.assertTrue(!open(s.level, gate), "the first heart's all-clear opened the gate while the second heart in the same ring is alarmed");
                h.assertTrue(a.walls().anyShut(), "the first heart let the gate go: nothing would open it once the second is clear");
                for (int k = 0; k < Muster.ALL_CLEAR + 1; k++) {
                    b.muster().second(s.level, b);
                }
                h.assertTrue(!b.muster().alarmed(), "the second heart didn't stand down");
                a.second(s.level);                               // the first heart's next second
                h.assertTrue(open(s.level, gate) && !a.walls().anyShut(), "with both hearts clear the gate stayed shut");
            } finally {
                foes.forEach(Entity::discard);
                for (TestSettlement t : List.of(a, b)) {
                    SettlementSpawns.remove(s.level, t);
                    t.setRemoved();
                }
            }
            s.clear();
            h.succeed();
        }));
    }

    // --- 4: the room templates' cache ----------------------------------------------------------------------------------------

    /**
     * The room templates read from the server's data are dropped at a /reload (RoomCaches) and read again when next asked, so a datapack's
     * override comes in; one given by hand stays. A template that can't be read is empty and not kept: it is looked for again next time. Before,
     * the cache was cleared only by the tests, and a failed read was kept as an empty template (a dig of it had no cells, done at once).
     * Its own batch: the reload drops the templates every other test reads.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "roomcaches")
    public static void aReloadDropsTheTemplatesReadAndAFailedReadIsNotKept(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room platform = SitingGameTests.platform();
        List<RoomTemplates.Entry> first = RoomTemplates.get(level, platform.id());
        int print = RoomTemplates.print(level, platform.id());
        h.assertTrue(first.size() == 9 * 5 * 9 && RoomTemplates.has(platform.id()), "the platform's template read " + first.size() + " cells");
        String hand = "audit_hand_built_template";
        h.assertTrue(Rooms.get(hand) == null, "the hand-built id is a registered room's");
        RoomTemplates.put(hand, List.of(new RoomTemplates.Entry(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), null)));
        try {
            RoomCaches.reloaded();
            h.assertTrue(!RoomTemplates.has(platform.id()), "a /reload kept the platform's template read before it: a datapack's override would never come in");
            List<RoomTemplates.Entry> again = RoomTemplates.get(level, platform.id());
            h.assertTrue(again != first && again.equals(first) && RoomTemplates.print(level, platform.id()) == print,
                    "after the reload the platform's template wasn't read again whole: " + again.size() + " cells");
            h.assertTrue(RoomTemplates.has(hand) && RoomTemplates.get(level, hand).size() == 1, "a /reload dropped a template given by hand, which no file holds");

            String missing = "no_such_room_for_a_server_read";
            RoomTemplates.forget(missing);
            List<RoomTemplates.Entry> none = RoomTemplates.get(level, missing);
            RoomTemplates.print(level, missing);
            h.assertTrue(none.isEmpty() && !RoomTemplates.has(missing), "a template that couldn't be read was kept as an empty one: a dig of it is done at once, for good");
        } finally {
            RoomTemplates.forget(hand);
        }
        h.succeed();
    }

    // --- 5: the muster turned off ------------------------------------------------------------------------------------------

    /**
     * A defender out of the world when an alarm stands down because its settlement has no owner (the muster's off branch), back in the world
     * later, is told to go home there. Before, the off branch never told the untold: it stood about, its buff still on, for good.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "settlement")
    public static void aDefenderBackInTheWorldIsSentHomeWhenTheMusterHasNoOwner(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        ServerLevel level = h.getLevel();
        List<Fake> fakes = new ArrayList<>();
        List<Entity> others = new ArrayList<>();
        TestSettlement s = new TestSettlement(middle, Blocks.CHEST.defaultBlockState());
        s.setLevel(level);
        s.dormant = true;
        try {
            s.claim(h.makeMockServerPlayerInLevel(), "Karak Untold");
            SettlementSpawns.add(level, s);
            others.add(MusterGameTests.foeAt(level, middle.offset(10, 0, 0), true));
            Settlement raiser = new StubSettlement()
                    .is("getBlockPos", middle)
                    .is("owner", UUID.randomUUID())
                    .is("settlementBoxes", List.of())
                    .answer("inSettlement", a -> near((BlockPos) a[0], middle))
                    .is("mustersUnderSky", true)
                    .is("placeOf", "")
                    .quiet("tellOwner")
                    .is("reserve", 6)
                    .is("reserveWorkers", 6)
                    .is("reserveSoldiers", 0)
                    .answer("riseFromReserve", a -> {
                        if (fakes.size() >= 2) {
                            return null;
                        }
                        Fake f = Fake.at(level, s, (BlockPos) a[1], null);
                        fakes.add(f);
                        return f;
                    })
                    .settlement();
            Muster m = s.muster();
            m.second(level, raiser);
            m.second(level, raiser);
            h.assertTrue(m.alarmed() && !fakes.isEmpty(), "the foe raised no defenders");
            Fake away = fakes.get(0);
            UUID awayId = away.getUUID();
            away.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);  // its chunk unloads
            Settlement ownerless = new StubSettlement()
                    .is("getBlockPos", middle)
                    .is("owner", null)
                    .is("allClearLine", "The test is clear.")
                    .quiet("tellOwner", "shutGates")
                    .settlement();
            m.second(level, ownerless);                            // no owner: the alarm stands down, the one away untold
            h.assertTrue(!m.alarmed() && m.homewardIds().contains(awayId), "with no owner the alarm didn't stand down with the one away homeward");
            Fake back = Fake.at(level, s, middle.offset(3, 0, 3), awayId);   // its chunk loads again
            fakes.add(back);
            m.second(level, ownerless);
            h.assertTrue(back.told, "a defender stood down out of the world, back with the muster off, was never told to go home");
        } finally {
            fakes.forEach(Entity::discard);
            others.forEach(Entity::discard);
            SettlementSpawns.remove(level, s);
            s.setRemoved();
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    private static boolean near(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 20 && Math.abs(a.getZ() - b.getZ()) <= 20 && Math.abs(a.getY() - b.getY()) <= 20;
    }
}
