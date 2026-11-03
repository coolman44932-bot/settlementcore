package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.Founding;
import claude.settlementcore.test.SitingGameTests.Site;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Founding halls (settlement/Founding; the user's "a heart hall ... you can place that first ... then you can place the heart"): a raised
 * building with a heart spot is raised by its god before any settlement exists, its cells in order, the land sited as any raised building's;
 * risen, it waits for a heart on its spot, and only its own god's heart there takes it.
 */
@GameTestHolder(Core.MODID)
public class FoundingGameTests {

    static final String HALL = "founding_test_hall";

    /** A 9 x 5 x 9 hall: stone-brick walls round it, a door gap at (0,1,4)-(0,2,4), a dais in the middle with the heart's spot on it. */
    static synchronized Rooms.Room hall() {
        if (!RoomTemplates.has(HALL)) {
            List<RoomTemplates.Entry> cells = new ArrayList<>();
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 9; z++) {
                    for (int x = 0; x < 9; x++) {
                        BlockState s = Blocks.AIR.defaultBlockState();
                        boolean edge = x == 0 || x == 8 || z == 0 || z == 8;
                        if (y == 0 || edge && y <= 3) {
                            s = Blocks.STONE_BRICKS.defaultBlockState();
                        }
                        if (x == 0 && z == 4 && (y == 1 || y == 2)) {
                            s = Blocks.AIR.defaultBlockState();
                        }
                        if (x == 4 && z == 4 && y == 1) {
                            s = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();   // the dais; the heart stands on it, at y 2
                        }
                        cells.add(new RoomTemplates.Entry(new BlockPos(x, y, z), s, null));
                    }
                }
            }
            RoomTemplates.put(HALL, cells);
        }
        if (Rooms.get(HALL) == null) {
            Rooms.Room r = new Rooms.Room(HALL, "Test Founding Hall", 0, Rooms.Function.of("FOUNDING_TEST"), "A founding hall of the engine's tests.",
                    9, 5, 9, 0, 1, 4, List.of(), List.of(), List.of(new Rooms.Station(Founding.HEART, 4, 2, 4, Direction.WEST)));
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(), List.of(), java.util.Set.of(), 0, List.of(r)));
        }
        return Rooms.get(HALL);
    }

    @GameTest(template = "digsite", timeoutTicks = 400, batch = "settlement")
    public static void aFoundingHallIsRaisedByItsGodAndTakenByItsHeart(GameTestHelper h) {
        Rooms.Room room = hall();
        h.assertTrue(Founding.isFoundingHall(room), "the test hall isn't a founding hall");
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            Founding f = Founding.of(s.level);
            int before = f.count();
            Founding live = f;
            UUID god = new UUID(0xf00dL, 1L), other = new UUID(0xf00dL, 2L);
            String no = f.begin(s.level, s.pl, god);
            h.assertTrue(no == null, "the founding hall was refused: " + no);
            h.assertTrue(f.begin(s.level, s.pl, god) != null, "a second hall at the same place wasn't refused");
            BlockPos spot = Founding.heartSpot(s.pl);
            h.assertTrue(!f.spot(spot, god), "the heart's spot was open before the hall had risen");
            f.second(s.level, 40);                               // a little of it up, then saved and read back, as a restart would
            var saved = Founding.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, f).getOrThrow();
            Founding back = Founding.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, saved).getOrThrow();
            h.assertTrue(back.count() == f.count() && back.rising(s.pl.door()), "the rising hall didn't survive a save and a load");
            for (int sec = 0; sec < 100 && back.rising(s.pl.door()); sec++) {
                back.second(s.level, Founding.PER_SECOND);       // the read-back copy raises the rest
            }
            h.assertTrue(!back.rising(s.pl.door()), "the read-back hall never finished rising");
            f = back;
            h.assertTrue(!f.rising(s.pl.door()), "the hall never finished rising");
            h.assertTrue(s.level.getBlockState(s.pl.world(new BlockPos(4, 1, 4))).is(Blocks.CHISELED_STONE_BRICKS), "the hall's dais isn't in");
            h.assertTrue(s.level.getBlockState(s.pl.world(new BlockPos(8, 2, 2))).is(Blocks.STONE_BRICKS), "the hall's back wall isn't in");
            h.assertTrue(f.spot(spot, god) && !f.spot(spot, other) && !f.spot(spot.above(), god),
                    "the heart's spot is wrong: its god's there, nobody else's, nowhere else");
            h.assertTrue(f.claim(spot, other) == null, "another's heart took the hall");
            Building b = f.claim(spot, god);
            h.assertTrue(b != null && b.room().equals(HALL) && b.box().equals(s.pl.box()), "the god's heart didn't take the hall: " + b);
            h.assertTrue(f.count() == before && f.claim(spot, god) == null, "the hall was taken twice, or not forgotten");
            h.assertTrue(live.forget(s.pl.door()), "the level's own record of the hall (raised by the read-back copy) wasn't there to forget");
            s.clear();
            h.succeed();
        }));
    }

    /**
     * Raised round a heart that already stands (Founding#beginRound): the heart (here a chest, a block entity, as every heart is) is spared
     * by the siting (never "something stands on the site") and by the raising, the hall rising round it on its spot.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "settlement")
    public static void aFoundingHallIsRaisedRoundAHeartThatStands(GameTestHelper h) {
        Rooms.Room room = hall();
        Site s = new Site(h, SitingGameTests.UP, room);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            Founding f = Founding.of(s.level);
            BlockPos heart = Founding.heartSpot(s.pl);
            s.level.setBlockAndUpdate(heart, Blocks.CHEST.defaultBlockState());
            String no = f.beginRound(s.level, room, heart, Direction.EAST, new UUID(0xf00dL, 3L));
            h.assertTrue(no == null, "the hall was refused round its heart: " + no);
            for (int sec = 0; sec < 100 && f.rising(s.pl.door()); sec++) {
                f.second(s.level, Founding.PER_SECOND);
            }
            h.assertTrue(!f.rising(s.pl.door()), "the hall never finished rising round its heart");
            h.assertTrue(s.level.getBlockState(heart).is(Blocks.CHEST), "the heart didn't stand through the raising");
            h.assertTrue(s.level.getBlockState(s.pl.world(new BlockPos(4, 1, 4))).is(Blocks.CHISELED_STONE_BRICKS), "the hall's dais isn't in under it");
            s.clear();
            h.succeed();
        }));
    }
}
