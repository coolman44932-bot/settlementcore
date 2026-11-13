package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
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

    /**
     * Carved down in the rock (Founding#beginCarved, 0.5.7; the user, 2026-11-06: "dwarves cant build underground?"): the god cuts the hall
     * out of the dig site's rock as a dig cuts a room, a save and a load half way keep it a carving, and its heart takes it as a raised one's.
     * The rock round it is left.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aFoundingHallIsCarvedOutOfTheRock(GameTestHelper h) {
        Rooms.Room room = hall();
        var level = h.getLevel();
        BlockPos door = h.absolutePos(new BlockPos(10, 5, 20));
        PlanPlacement pl = PlanPlacement.compute(room, door, Direction.EAST);
        h.assertTrue(!level.getBlockState(pl.world(new BlockPos(4, 3, 4))).isAir() && !level.getBlockState(pl.world(new BlockPos(9, 2, 4))).isAir(),
                "the test needs the hall's place and beyond it in rock");
        Founding f = Founding.of(level);
        UUID god = new UUID(0xf00dL, 7L);
        BlockPos chest = pl.world(new BlockPos(6, 2, 6));
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        String hoard = f.beginCarved(level, pl, god);
        h.assertTrue(hoard != null && hoard.contains(chest.getX() + " " + chest.getY() + " " + chest.getZ()) && !f.rising(pl.door()),
                "a chest in the hall's way wasn't refused: " + hoard);
        level.setBlockAndUpdate(chest, Blocks.STONE.defaultBlockState());
        String no = f.beginCarved(level, pl, god);
        h.assertTrue(no == null, "the carved hall was refused: " + no);
        h.assertTrue(f.beginCarved(level, pl, god) != null && f.begin(level, pl, god) != null, "a second hall at the same place wasn't refused");
        h.assertTrue(f.carving(pl.door()) && f.rising(pl.door()), "the hall isn't being carved");
        f.second(level, 40);                                     // a little of it cut, then saved and read back, as a restart would
        var saved = Founding.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, f).getOrThrow();
        Founding back = Founding.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, saved).getOrThrow();
        h.assertTrue(back.carving(pl.door()), "the carving read back as a raising");
        for (int sec = 0; sec < 100 && back.rising(pl.door()); sec++) {
            back.second(level, Founding.PER_SECOND);
        }
        h.assertTrue(!back.rising(pl.door()), "the hall was never carved through");
        h.assertTrue(level.getBlockState(pl.world(new BlockPos(4, 1, 4))).is(Blocks.CHISELED_STONE_BRICKS)
                        && level.getBlockState(pl.world(new BlockPos(8, 2, 2))).is(Blocks.STONE_BRICKS)
                        && level.getBlockState(pl.world(new BlockPos(4, 3, 4))).isAir(),
                "the hall wasn't carved as its template: its dais, its back wall, its open space");
        h.assertTrue(!level.getBlockState(pl.world(new BlockPos(9, 2, 4))).isAir(), "the rock beyond the hall was cut");
        BlockPos spot = Founding.heartSpot(pl);
        h.assertTrue(back.spot(spot, god), "the carved hall's spot doesn't wait for its heart");
        Building b = back.claim(spot, god);
        h.assertTrue(b != null && b.box().equals(pl.box()), "the god's heart didn't take the carved hall: " + b);
        f.forget(pl.door());
        h.succeed();
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

    // ------------------------------------------------------------------ a hall whose front is open terrace, carved or dug

    static final String TERRACE = "founding_test_terrace";

    /**
     * A 7 x 5 x 11 hall whose whole front face (x 0, z 1..9, y 1..3) is open, as a gate's forecourt is: walls at the sides, back and roof,
     * its door's middle at (0,1,5), its heart's spot on the floor in the middle.
     */
    static synchronized Rooms.Room terrace() {
        if (!RoomTemplates.has(TERRACE)) {
            List<RoomTemplates.Entry> cells = new ArrayList<>();
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 11; z++) {
                    for (int x = 0; x < 7; x++) {
                        boolean wall = y == 0 || y == 4 || z == 0 || z == 10 || x == 6;
                        cells.add(new RoomTemplates.Entry(new BlockPos(x, y, z),
                                (wall ? Blocks.STONE_BRICKS : Blocks.AIR).defaultBlockState(), null));
                    }
                }
            }
            RoomTemplates.put(TERRACE, cells);
        }
        if (Rooms.get(TERRACE) == null) {
            Rooms.Room r = new Rooms.Room(TERRACE, "Test Terrace Hall", 0, Rooms.Function.of("FOUNDING_TERRACE_TEST"), "A founding hall open across its front.",
                    7, 5, 11, 0, 1, 5, List.of(), List.of(), List.of(new Rooms.Station(Founding.HEART, 3, 1, 5, Direction.WEST)));
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(), List.of(), java.util.Set.of(), 0, List.of(r)));
        }
        return Rooms.get(TERRACE);
    }

    /** Cave cells in the rock just in front of the terrace hall's face, as (y, z) in its frame: a way in at the door's middle, and caves beside it. */
    private static final int[][] OPEN_BY_THE_DOOR = {{1, 5}, {2, 6}, {1, 3}};     // within 2 of the door's middle, and 4 high
    private static final int[][] CAVE_BESIDE = {{1, 8}, {2, 2}, {3, 9}, {1, 1}};   // 3 and 4 off it

    private static String cavesAfter(GameTestHelper h, PlanPlacement pl, int[][] cells) {
        StringBuilder sb = new StringBuilder();
        for (int[] c : cells) {
            BlockPos p = pl.world(new BlockPos(-1, c[0], c[1]));
            sb.append(" (y").append(c[0]).append(" z").append(c[1]).append(") ").append(p.toShortString()).append(" = ")
                    .append(h.getLevel().getBlockState(p).getBlock().getDescriptionId()).append(';');
        }
        return sb.toString();
    }

    private static PlanPlacement terraceInRock(GameTestHelper h) {
        Rooms.Room room = terrace();
        PlanPlacement pl = PlanPlacement.compute(room, h.absolutePos(new BlockPos(10, 5, 20)), Direction.EAST);
        var level = h.getLevel();
        h.assertTrue(!level.getBlockState(pl.world(new BlockPos(-1, 2, 5))).isAir() && !level.getBlockState(pl.world(new BlockPos(3, 2, 5))).isAir(),
                "the test needs the hall's place, and the rock before its face, in rock");
        for (int[][] set : new int[][][]{OPEN_BY_THE_DOOR, CAVE_BESIDE}) {
            for (int[] c : set) {
                level.setBlock(pl.world(new BlockPos(-1, c[0], c[1])), Blocks.CAVE_AIR.defaultBlockState(), 3);
            }
        }
        return pl;
    }

    /**
     * The user's WALLS rule for a CARVED hold gate (2026-11-13, "Yes, wall them"; the real-rock run of khazuk 353e8cc found six cave cells 4 to
     * 6 beside a carved gate's door, open into it): a founding hall carved out of the rock with an open front face is walled on the face
     * but for its door's opening. Caves in the rock just before the face, one cell each, are in the way the god dug (within 2 of the door's
     * middle, 4 high: they stay) or beside it (3 and 4 off the middle: sealed with the hall's wall). The old shoring took the 64 open
     * face cells nearest the door for its doorway (RoomShoring#doorways), and the face here is 27 of them: every cave on it was left open.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aCarvedHallIsWalledOnItsOpenFaceButForItsDoor(GameTestHelper h) {
        PlanPlacement pl = terraceInRock(h);
        var level = h.getLevel();
        Founding f = Founding.of(level);
        String no = f.beginCarved(level, pl, new UUID(0xf00dL, 8L));
        h.assertTrue(no == null, "the carved terrace hall was refused: " + no);
        for (int sec = 0; sec < 100 && f.rising(pl.door()); sec++) {
            f.second(level, Founding.PER_SECOND);
        }
        h.assertTrue(!f.rising(pl.door()), "the terrace hall was never carved through");
        h.assertTrue(level.getBlockState(pl.world(new BlockPos(0, 1, 5))).isAir() && level.getBlockState(pl.world(new BlockPos(0, 3, 9))).isAir(),
                "the hall's open space wasn't carved");
        for (int[] c : OPEN_BY_THE_DOOR) {
            h.assertTrue(level.getBlockState(pl.world(new BlockPos(-1, c[0], c[1]))).isAir(),
                    "a cave cell in the door's opening was walled (the god's way in must stay open):" + cavesAfter(h, pl, OPEN_BY_THE_DOOR));
        }
        for (int[] c : CAVE_BESIDE) {
            h.assertTrue(level.getBlockState(pl.world(new BlockPos(-1, c[0], c[1]))).is(Blocks.STONE_BRICKS),
                    "a cave beside the door's opening was left open into the carved hall (the old doorway patch is the 64 nearest face cells, "
                            + "here the whole face):" + cavesAfter(h, pl, CAVE_BESIDE));
        }
        f.forget(pl.door());
        h.succeed();
    }

    /**
     * The control: the same hall DUG (an ordinary dig from a door, as every room but a carved founding hall) keeps its joining patch as it was,
     * so caves along its open face stay open into it as the space it was dug from.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aDugHallStillJoinsTheCavesAlongItsFace(GameTestHelper h) {
        PlanPlacement pl = terraceInRock(h);
        var level = h.getLevel();
        claude.settlementcore.room.DigJob job = new claude.settlementcore.room.DigJob(TERRACE, pl.door(), pl.facing());
        for (int i = 0; i < 100 && !job.isDone(); i++) {
            job.gnawInOrder(level, 500, new ArrayList<>());
        }
        h.assertTrue(job.isDone(), "the dug terrace hall was never dug through");
        h.assertTrue(level.getBlockState(pl.world(new BlockPos(0, 1, 5))).isAir(), "the hall's open space wasn't dug");
        for (int[][] set : new int[][][]{OPEN_BY_THE_DOOR, CAVE_BESIDE}) {
            for (int[] c : set) {
                h.assertTrue(level.getBlockState(pl.world(new BlockPos(-1, c[0], c[1]))).isAir(),
                        "a dug hall's doorway patch was walled (dug halls join the space they were dug from):" + cavesAfter(h, pl, set));
            }
        }
        h.succeed();
    }
}
