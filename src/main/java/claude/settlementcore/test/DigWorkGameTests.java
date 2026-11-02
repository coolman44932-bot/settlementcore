package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.work.DigWork;
import claude.settlementcore.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The 26.1 engine's dig side ({@link Works#dig}, {@link DigWork}): a dug room is refused where it can't be begun (a raised building, a door
 * outside the settlement, a door with no open space before it, a second dig at the same door), and begun, it is dug out cell by cell
 * (here by the settlement itself, its reserve empty) and handed over: the room's air open, its furnishing in, and the settlement has it.
 * In the digsite (solid stone, 44 x 20 x 44).
 */
@GameTestHolder(Core.MODID)
public class DigWorkGameTests {

    static final String DUG = "dig_test_hall";

    /** A 7 x 5 x 7 hall: its floor of stone bricks, a lantern on a pillar in the middle, open otherwise; door (0, 1, 3). */
    static synchronized Rooms.Room hall() {
        if (!RoomTemplates.has(DUG)) {
            List<RoomTemplates.Entry> cells = new ArrayList<>();
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 7; z++) {
                    for (int x = 0; x < 7; x++) {
                        BlockState s = y == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        if (x == 3 && z == 3 && y >= 1 && y <= 2) {
                            s = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
                        } else if (x == 3 && z == 3 && y == 3) {
                            s = Blocks.LANTERN.defaultBlockState();
                        }
                        cells.add(new RoomTemplates.Entry(new BlockPos(x, y, z), s, null));
                    }
                }
            }
            RoomTemplates.put(DUG, cells);
        }
        if (Rooms.get(DUG) == null) {
            Rooms.Room r = new Rooms.Room(DUG, "Test Dug Hall", 5, Rooms.Function.HALL, "A dug hall of the engine's tests.", 7, 5, 7, 0, 1, 3,
                    List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(r), List.of(), List.of(), java.util.Set.of(), 0));
        }
        return Rooms.get(DUG);
    }

    @GameTest(template = "digsite", timeoutTicks = 200, batch = "settlement")
    public static void aRoomIsDugOutOffOpenSpaceAndHandedOver(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room room = hall();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        Works works = t.works();
        BlockPos door = h.absolutePos(new BlockPos(10, 5, 20));
        PlanPlacement pl = PlanPlacement.compute(room, door, Direction.EAST);

        String outside = works.dig(level, pl, true);
        h.assertTrue(outside != null && outside.contains("outside the settlement"), "a door outside the settlement was not refused: " + outside);
        t.holds = true;
        String shut = works.dig(level, pl, true);
        h.assertTrue(shut != null && shut.contains("Aim the plan"), "a door with rock before it was not refused: " + shut);
        BlockPos before = door.west();
        level.setBlockAndUpdate(before, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(before.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(before.below(), Blocks.STONE.defaultBlockState());
        String begun = works.dig(level, pl, true);
        h.assertTrue(begun == null, "the dig was refused with open space before its door: " + begun);
        String twice = works.dig(level, pl, true);
        h.assertTrue(twice != null && twice.contains("already"), "a second dig at the same door was not refused: " + twice);
        h.assertTrue(works.digs().size() == 1, "not one dig: " + works.digs().size());

        for (int sec = 0; sec < 200 && !works.digs().isEmpty(); sec++) {
            works.second(level, sec);                            // its reserve empty: the settlement digs it out itself
        }
        h.assertTrue(works.digs().isEmpty(), "the hall is still being dug: " + works.digs().stream().map(d -> d.job().progress()).toList());
        boolean has = false;
        for (Building b : t.buildings()) {
            has |= b.room().equals(DUG) && door.equals(b.door());
        }
        h.assertTrue(has, "the dug hall wasn't handed over to the settlement");
        h.assertTrue(level.getBlockState(pl.world(new BlockPos(1, 2, 1))).isAir(), "the hall's space is still rock");
        h.assertTrue(level.getBlockState(pl.world(new BlockPos(3, 3, 3))).is(Blocks.LANTERN), "the hall's lantern isn't in");
        h.assertTrue(level.getBlockState(pl.world(new BlockPos(5, 0, 5))).is(Blocks.STONE_BRICKS), "the hall's floor isn't laid");
        h.assertTrue(t.heard.stream().anyMatch(l -> l.contains("is dug out")), "nobody was told the hall is dug out: " + t.heard);
        h.succeed();
    }
}
