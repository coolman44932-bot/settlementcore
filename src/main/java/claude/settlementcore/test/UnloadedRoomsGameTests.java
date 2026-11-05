package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.work.CookWork;
import claude.settlementcore.work.OrchardWork;
import claude.settlementcore.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * A room out of view loads nothing (the bug audit of 2026-11-04): in 26.1 a block read in a chunk that is not loaded loads it, on the
 * server thread, there and then. The haul asks every room for its bins every few seconds, loaded or not (Works#haul), and the Cook-Pits'
 * scan read its whole box unasked whether it was loaded: a kitchen out of view was loaded, and unloaded again, every ten seconds. Each
 * test puts a room of the settlement's 6000 blocks off, where nothing is loaded (it says so if something is), works it as the settlement
 * would, and asks its chunks again afterwards.
 */
@GameTestHolder(Core.MODID)
public class UnloadedRoomsGameTests {

    static final String KITCHEN = "unloaded_test_kitchen", ORCHARD = "unloaded_test_orchard";

    /** A room of the tests' own, registered (the Works make a room's work only for a room they know), 5 x 4 x 5, door (0, 1, 2). */
    static synchronized Rooms.Room room(String id, String name, Rooms.Function f, List<Rooms.Station> stations) {
        if (Rooms.get(id) == null) {
            Rooms.Room r = new Rooms.Room(id, name, 0, f, "A room of the unloaded-ground tests.", 5, 4, 5, 0, 1, 2, List.of(), List.of(), stations);
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(r), List.of(), java.util.Set.of(), 0));
        }
        return Rooms.get(id);
    }

    /** The chunks of {@code box} that are loaded, as "x,z" (none, for a room out of view). */
    static List<String> loaded(ServerLevel level, BoundingBox box) {
        List<String> out = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                if (level.hasChunk(cx, cz)) {
                    out.add(cx + "," + cz);
                }
            }
        }
        return out;
    }

    /** A room of the settlement's, far out: its placement, its building handed to the settlement, and the Works synced to it (a second). */
    static PlanPlacement farRoom(GameTestHelper h, TestSettlement t, Rooms.Room room, int dx, int dz) {
        ServerLevel level = h.getLevel();
        PlanPlacement pl = PlanPlacement.compute(room, h.absolutePos(new BlockPos(4, 5, 4)).offset(dx, 0, dz), Direction.EAST);
        h.assertTrue(loaded(level, pl.box()).isEmpty(), "the ground " + dx + ", " + dz + " off the plot is loaded already (chunks "
                + loaded(level, pl.box()) + "): the test shows nothing");
        t.addBuilding(new Building(room.id(), pl.box(), pl.door(), Direction.EAST), false);
        return pl;
    }

    /**
     * A Cook-Pits out of view: the settlement's second (a haul second: its haul asks the cooks for their Pantry and their Cooked Fare
     * barrels) loads none of its ground, and nor does anything else asked of the kitchen (its scan again, its supplies, needs, report).
     * Before the fix the scan read every cell of the box and the haul loaded the kitchen's chunk.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theHaulLoadsNoCookPitsOutOfView(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        try {
            Rooms.Room room = room(KITCHEN, "Test Far Cook-Pits", Rooms.Function.KITCHEN, List.of());
            PlanPlacement pl = farRoom(h, t, room, 6000, -6000);
            Works works = t.works();
            works.second(level, 0);                              // second 0: the haul's
            List<CookWork> cooks = works.all(CookWork.class);
            h.assertTrue(cooks.size() == 1, "the far Cook-Pits has no work of its own (" + cooks.size() + "): the haul never asked it, the test shows nothing");
            h.assertTrue(loaded(level, pl.box()).isEmpty(), "the haul loaded the far Cook-Pits' ground (chunks " + loaded(level, pl.box())
                    + "): the cooks' scan read blocks in a chunk not loaded");
            CookWork cook = cooks.get(0);
            cook.rescanSoon();
            h.assertTrue(cook.pantry(level).isEmpty() && cook.fareBins(level).isEmpty(), "the far kitchen found bins in ground not loaded");
            cook.supplies(level);
            List<String> needs = new ArrayList<>(), boosts = new ArrayList<>();
            cook.wants(level, needs, boosts);
            List<Component> lines = new ArrayList<>();
            cook.describe(level, lines);
            h.assertTrue(loaded(level, pl.box()).isEmpty(), "asking the far Cook-Pits loaded its ground (chunks " + loaded(level, pl.box()) + ")");
        } finally {
            t.setRemoved();
        }
        h.succeed();
    }

    /**
     * A Warp-Orchard out of view, with a tree bed: the haul, its second, its report, needs, trees and a night of its work load none of its
     * ground. Its beds are the room's own stations, known whether the ground is loaded or not, and they were read before they were
     * asked about (its second, its trees, needs and report, the night's shaking).
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void anOrchardOutOfViewLoadsNoneOfItsGround(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        try {
            Rooms.Room room = room(ORCHARD, "Test Far Orchard", Rooms.Function.ORCHARD, List.of(new Rooms.Station("tree", 2, 0, 2, Direction.NORTH)));
            PlanPlacement pl = farRoom(h, t, room, -6000, 6000);
            Works works = t.works();
            works.second(level, 0);
            List<OrchardWork> orchards = works.all(OrchardWork.class);
            h.assertTrue(orchards.size() == 1 && orchards.get(0).beds().size() == 1, "the far orchard has no work with its tree bed: the test shows nothing");
            h.assertTrue(loaded(level, pl.box()).isEmpty(), "the haul loaded the far orchard's ground (chunks " + loaded(level, pl.box()) + ")");
            OrchardWork o = orchards.get(0);
            o.second(level, 2);                                  // as if its crew were at work: the beds' growing
            h.assertTrue(loaded(level, pl.box()).isEmpty(), "the far orchard's second loaded its ground (chunks " + loaded(level, pl.box()) + ")");
            h.assertTrue(o.trees(level) == 0, "a tree was counted on a bed not loaded");
            List<String> needs = new ArrayList<>(), boosts = new ArrayList<>();
            o.wants(level, needs, boosts);
            List<Component> lines = new ArrayList<>();
            o.describe(level, lines);
            o.supplies(level);
            h.assertTrue(loaded(level, pl.box()).isEmpty(), "asking the far orchard (trees, needs, report) loaded its ground (chunks " + loaded(level, pl.box()) + ")");
            o.nightsWork(level, 600, lines);
            h.assertTrue(loaded(level, pl.box()).isEmpty(), "the far orchard's night loaded its ground (chunks " + loaded(level, pl.box()) + ")");
        } finally {
            t.setRemoved();
        }
        h.succeed();
    }
}
