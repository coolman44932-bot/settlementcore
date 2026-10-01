package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.PlanPlacement;
import claude.underempire.core.room.RoomRelight;
import claude.underempire.core.room.RoomShoring;
import claude.underempire.core.room.RoomTemplates;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.settlement.BuildingBook;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A settlement's buildings and what is kept about them (core/settlement/BuildingBook, docs/plans/greatplan-first-spawning.md
 * section B item 8): the Warren Heart's list of rooms, its walled, stacked and relit flags and its shoring lifted into a part
 * with no block entity. Checked as such: the list and its counts, the Heart's own save keys, the cells the other rooms hold
 * in an area, and each of the upkeep's drivers doing a room once. Two hand-built 3x3x3 rooms stand in for a race's: one with a
 * template of its own cells, one with relight plans of its own (data/settlementcore/room_relight/book_lamp_room.json: at version
 * 1 a glowstone at (1,1,1), at version 2 a sea lantern at (1,2,1), each where the room has air).
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class BuildingBookGameTests {

    static final String ROOM = "book_test_room", LAMPS = "book_lamp_room";
    /** The cells of ROOM's template, local: its door is (0,1,1), and it extends east. */
    private static final BlockPos[] CELLS = {new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 2, 2), new BlockPos(1, 1, 1)};
    /** Far enough off that no chunk is loaded there. */
    static final BlockPos FAR = new BlockPos(20_000_000, 70, 20_000_000);

    /** A 3x3x3 room of the tests' own, its template handed in whenever the cache lacks it (a server stop in the dev client drops it). */
    private static synchronized Rooms.Room room(String id, String function, List<RoomTemplates.Entry> template) {
        if (!RoomTemplates.has(id)) {
            RoomTemplates.put(id, template);
        }
        if (Rooms.get(id) == null) {
            Rooms.Room r = new Rooms.Room(id, "Test " + id, 0, Rooms.Function.of(function), "A hand-built test room for the building book.",
                    3, 3, 3, 0, 1, 1, List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(), List.of(), Set.of(), 0, List.of(r)));
        }
        return Rooms.get(id);
    }

    static Rooms.Room cellsRoom() {
        List<RoomTemplates.Entry> template = new ArrayList<>();
        for (BlockPos p : CELLS) {
            template.add(new RoomTemplates.Entry(p, Blocks.STONE.defaultBlockState(), null));
        }
        return room(ROOM, "BOOK_TEST", template);
    }

    static Rooms.Room lampsRoom() {
        return room(LAMPS, "BOOK_LAMPS", List.of());
    }

    /** {@code room} as dug with its door at {@code door}, extending east. */
    static Building dug(Rooms.Room room, BlockPos door) {
        PlanPlacement pl = PlanPlacement.compute(room, door, Direction.EAST);
        return new Building(room.id(), pl.box(), door, Direction.EAST);
    }

    /** The two lamps' cells of a lamp room dug with its door at {@code door}: the glowstone's, the sea lantern's. */
    static BlockPos[] lamps(BlockPos door) {
        return new BlockPos[]{door.offset(1, 0, 0), door.offset(1, 1, 0)};
    }

    /**
     * The book lists what it is given, in order, and says what is in it: 2 rooms of one function and 1 of another count so, a
     * function it has none of is neither had nor counted, a building of a room nobody registered counts for nothing; each add
     * is saved; one added walled is walled and the others are not; and a room added with a level has its room's version of the
     * lamps (the lamp room's 2), without one none. The place of a block is the room it is in, and nothing outside them.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aBookKeepsItsBuildingsAndCountsThemByFunction(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room cells = cellsRoom();
        Rooms.Room lamps = lampsRoom();
        int[] saves = {0};
        BuildingBook book = new BuildingBook(() -> saves[0]++);
        Building a = dug(cells, h.absolutePos(new BlockPos(2, 4, 2)));
        Building b = dug(lamps, h.absolutePos(new BlockPos(2, 4, 8)));
        Building c = new Building(ROOM, new BoundingBox(1000, 70, 1000, 1004, 74, 1004));
        Building ghost = new Building("a_room_nobody_registered", new BoundingBox(2000, 70, 2000, 2002, 72, 2002));
        book.add(a, true, null);
        book.add(b, false, level);
        book.add(c, false, null);
        book.add(ghost, false, level);
        h.assertTrue(saves[0] == 4 && book.buildings().equals(List.of(a, b, c, ghost)), "4 adds saved " + saves[0] + " times and listed " + book.buildings());
        h.assertTrue(book.count(Rooms.Function.of("BOOK_TEST")) == 2 && book.count(Rooms.Function.of("BOOK_LAMPS")) == 1 && book.has(Rooms.Function.of("BOOK_TEST")),
                "2 rooms of one function and 1 of another counted " + book.count(Rooms.Function.of("BOOK_TEST")) + " and " + book.count(Rooms.Function.of("BOOK_LAMPS")));
        h.assertTrue(!book.has(Rooms.Function.of("BOOK_NONE")) && book.count(Rooms.Function.of("BOOK_NONE")) == 0, "a function with no room was had");
        h.assertTrue(book.walled(a) && !book.walled(b) && !book.walled(c), "walled came out " + book.walled(a) + book.walled(b) + book.walled(c) + ", not true, false, false");
        h.assertTrue(book.relitVersion(a) == 0 && book.relitVersion(b) == 2 && book.relitVersion(c) == 0,
                "the lamps' versions were " + book.relitVersion(a) + ", " + book.relitVersion(b) + ", " + book.relitVersion(c) + ", not 0 (no level), 2, 0");
        h.assertTrue(RoomRelight.get(level, LAMPS).version() == 2 && book.relitVersion(ghost) == 0, "a room with no plans was given a version, or the lamp room's plans are not 2");
        h.assertTrue(book.placeOf(b.box().getCenter()).equals("Test " + LAMPS) && book.placeOf(new BlockPos(3000, 70, 3000)).isEmpty(),
                "the place of a block in the lamp room was '" + book.placeOf(b.box().getCenter()) + "'");
        h.succeed();
    }

    /**
     * The book writes what the Heart writes and reads what the Heart wrote: a tag shaped as the Heart's save is read (a room with its
     * door and facing, relit at 3, walled; a room with neither, stacked; two shored cells), writes back equal (the cells in any order),
     * and loading again empties what was there. The shoring is kept and let go by cell, saving on a change only.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aBookSavesUnderTheHeartsKeysAndReadsTheHeartsTags(GameTestHelper h) {
        CompoundTag heart = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag withDoor = new CompoundTag();
        withDoor.putString("room", "some_room");
        withDoor.putIntArray("box", new int[]{10, 20, 30, 16, 24, 36});
        withDoor.put("door", NbtUtils.writeBlockPos(new BlockPos(10, 21, 33)));
        withDoor.putString("facing", Direction.EAST.getSerializedName());
        withDoor.putInt("relit", 3);
        withDoor.putBoolean("walled", true);
        withDoor.putBoolean("stacked", false);
        list.add(withDoor);
        CompoundTag old = new CompoundTag();
        old.putString("room", "an_old_room");
        old.putIntArray("box", new int[]{-5, 0, -5, 0, 4, 0});
        old.putInt("relit", 0);
        old.putBoolean("walled", false);
        old.putBoolean("stacked", true);
        list.add(old);
        heart.put("buildings", list);
        heart.putLongArray("shored", new long[]{BlockPos.asLong(1, 2, 3), BlockPos.asLong(-4, 5, 6)});

        int[] saves = {0};
        BuildingBook book = new BuildingBook(() -> saves[0]++);
        book.add(new Building("stale", new BoundingBox(0, 0, 0, 1, 1, 1)), true, null);
        book.shore(7);
        book.load(heart);
        List<Building> read = book.buildings();
        h.assertTrue(read.size() == 2 && read.get(0).room().equals("some_room") && read.get(0).door().equals(new BlockPos(10, 21, 33))
                        && read.get(0).facing() == Direction.EAST && read.get(0).box().equals(new BoundingBox(10, 20, 30, 16, 24, 36)),
                "the Heart's first room read as " + read);
        h.assertTrue(read.get(1).room().equals("an_old_room") && read.get(1).door() == null && read.get(1).facing() == null, "the second room read as " + read.get(1));
        h.assertTrue(book.walled(read.get(0)) && !book.stacked(read.get(0)) && book.relitVersion(read.get(0)) == 3, "the first room's flags read wrong");
        h.assertTrue(!book.walled(read.get(1)) && book.stacked(read.get(1)) && book.relitVersion(read.get(1)) == 0, "the second room's flags read wrong");
        h.assertTrue(book.shored(BlockPos.asLong(1, 2, 3)) && book.shored(BlockPos.asLong(-4, 5, 6)) && !book.shored(7),
                "the shoring read wrong (a stale cell stayed, or a saved one was lost)");

        CompoundTag out = new CompoundTag();
        book.save(out);
        long[] shored = out.getLongArray("shored");
        long[] expected = heart.getLongArray("shored");
        Arrays.sort(shored);
        Arrays.sort(expected);
        out.remove("shored");
        heart.remove("shored");
        h.assertTrue(Arrays.equals(shored, expected) && out.equals(heart), "saved it is " + out + ", not the Heart's " + heart);
        h.assertTrue(saves[0] == 2, "adding and shoring saved " + saves[0] + " times, not 2 (loading is not a change)");
        book.unshore(999);
        h.assertTrue(saves[0] == 2, "letting go of a cell never shored saved");
        book.unshore(BlockPos.asLong(1, 2, 3));
        h.assertTrue(saves[0] == 3 && !book.shored(BlockPos.asLong(1, 2, 3)), "letting go of a shored cell did not save or keep it");
        book.load(new CompoundTag());
        h.assertTrue(book.buildings().isEmpty() && !book.shored(BlockPos.asLong(-4, 5, 6)), "loading an empty tag left " + book.buildings());
        h.succeed();
    }

    /**
     * What the other rooms hold in an area: a room's template cells (all 4 of ROOM's, where they stand in the area), all of the box of
     * a room dug before rooms kept their door, and the digs'; the room at {@code except} holds none (a dig's own room is not in its own
     * way); a room out of the area holds none; and a dig whose box is the {@code except} one is left out too.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aBooksRoomCellsKeepTheOtherRoomsCellsOutOfAnArea(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room room = cellsRoom();
        Building a = dug(room, h.absolutePos(new BlockPos(2, 4, 2)));
        BoundingBox plain = new BoundingBox(a.box().minX() + 10, a.box().minY(), a.box().minZ(), a.box().minX() + 12, a.box().minY() + 1, a.box().minZ() + 2);
        Building b = new Building("a_room_before_doors", plain);
        Building far = dug(room, FAR);
        BuildingBook book = new BuildingBook(() -> {
        });
        book.add(a, true, null);
        book.add(b, true, null);
        book.add(far, true, null);
        BoundingBox area = new BoundingBox(a.box().minX() - 1, a.box().minY() - 1, a.box().minZ() - 1, plain.maxX() + 1, plain.maxY() + 1, plain.maxZ() + 1);
        BoundingBox nothing = new BoundingBox(-9, -9, -9, -8, -8, -8);

        PlanPlacement pa = BuildingBook.placementOf(a);
        LongSet ofA = new LongOpenHashSet();
        for (BlockPos p : CELLS) {
            ofA.add(pa.world(p).asLong());
        }
        LongSet ofB = new LongOpenHashSet();
        for (BlockPos p : BlockPos.betweenClosed(plain.minX(), plain.minY(), plain.minZ(), plain.maxX(), plain.maxY(), plain.maxZ())) {
            ofB.add(p.asLong());
        }
        LongSet both = new LongOpenHashSet(ofA);
        both.addAll(ofB);
        LongSet cells = book.roomCells(level, area, nothing, List.of());
        h.assertTrue(cells.equals(both), "the rooms' cells in the area were " + cells.size() + ", not the " + both.size() + " of the 4 template cells and the whole plain room");
        h.assertTrue(book.roomCells(level, area, a.box(), List.of()).equals(ofB), "with the first room excepted its cells were still held");
        h.assertTrue(book.roomCells(level, area, b.box(), List.of()).equals(ofA), "with the plain room excepted its cells were still held");
        BoundingBox corner = new BoundingBox(plain.minX(), plain.minY(), plain.minZ(), plain.minX(), plain.minY(), plain.minZ());
        h.assertTrue(book.roomCells(level, corner, nothing, List.of()).equals(LongSets.singleton(BlockPos.asLong(plain.minX(), plain.minY(), plain.minZ()))),
                "an area of one cell held " + book.roomCells(level, corner, nothing, List.of()));
        BoundingBox farArea = new BoundingBox(FAR.getX() - 5, FAR.getY() - 5, FAR.getZ() - 5, FAR.getX() + 5, FAR.getY() + 5, FAR.getZ() + 5);
        h.assertTrue(book.roomCells(level, farArea, nothing, List.of()).size() == CELLS.length, "the far room held " + book.roomCells(level, farArea, nothing, List.of()).size() + " cells");

        PlanPlacement dig = PlanPlacement.compute(room, h.absolutePos(new BlockPos(2, 4, 12)), Direction.EAST);
        BoundingBox wide = new BoundingBox(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), dig.box().maxZ() + 1);
        LongSet withDig = book.roomCells(level, wide, nothing, List.of(dig));
        h.assertTrue(withDig.size() == both.size() + CELLS.length && withDig.contains(dig.world(CELLS[2]).asLong()), "a dig's cells were " + (withDig.size() - both.size()) + ", not 4");
        h.assertTrue(book.roomCells(level, wide, dig.box(), List.of(dig)).equals(both), "a dig whose box was excepted still held its cells");
        h.succeed();
    }

    /** A warren that holds nothing, for the shoring to ask. */
    private static final RoomShoring.Warren NO_WARREN = new RoomShoring.Warren() {
        @Override
        public LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except) {
            return new LongOpenHashSet();
        }

        @Override
        public boolean shored(long pos) {
            return false;
        }

        @Override
        public void shore(long pos) {
        }

        @Override
        public void unshore(long pos) {
        }
    };

    /**
     * The upkeep does each room once: rooms not yet walled are walled (one added walled is left alone) and say so, a second pass does
     * nothing and saves nothing, and a room whose chunks are not loaded is left for later, unwalled; rooms not yet stacked are
     * stacked once (including the one far off, with no earlier room over it) and not again.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theWallingAndStackingDoEachRoomOnceAndLeaveTheUnloadedForLater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room room = cellsRoom();
        int[] saves = {0};
        BuildingBook book = new BuildingBook(() -> saves[0]++);
        Building done = dug(room, h.absolutePos(new BlockPos(2, 4, 2)));
        Building near = dug(room, h.absolutePos(new BlockPos(2, 4, 8)));
        Building far = dug(room, FAR);
        book.add(done, true, null);
        book.add(near, false, null);
        book.add(far, false, null);
        saves[0] = 0;
        int placed = book.wallUpRooms(level, NO_WARREN);
        h.assertTrue(placed == 0 && book.walled(done) && book.walled(near) && !book.walled(far), "the first walling left walled: " + book.walled(done) + book.walled(near) + book.walled(far)
                + ", not true, true, false");
        h.assertTrue(saves[0] == 1, "the first walling saved " + saves[0] + " times, not once (for the one room it did)");
        book.wallUpRooms(level, NO_WARREN);
        h.assertTrue(saves[0] == 1 && !book.walled(far), "a second walling did a room again, or walled the unloaded one: saved " + saves[0]);

        saves[0] = 0;
        h.assertTrue(!book.stacked(done) && !book.stacked(near) && !book.stacked(far), "rooms were stacked before the stacking");
        book.stackRooms(level);
        h.assertTrue(book.stacked(done) && book.stacked(near) && book.stacked(far) && saves[0] == 3, "the first stacking left stacked: " + book.stacked(done) + book.stacked(near)
                + book.stacked(far) + ", saved " + saves[0] + " times, not true, true, true and 3");
        book.stackRooms(level);
        h.assertTrue(saves[0] == 3, "a second stacking did a room again: saved " + saves[0]);
        h.succeed();
    }

    /**
     * The lamps are brought up once each, by version: a room at none gets both steps (the glowstone and the sea lantern, where it was
     * air) and is at 2; one at 1 gets only the second step; one at 2, and one a pass has already done, nothing; one whose chunks are
     * not loaded is left at 0. The relight is told of each room it did, with the version it was at and the blocks it placed.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theRelightBringsEachRoomsLampsUpOnceByVersion(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Rooms.Room room = lampsRoom();
        BuildingBook book = new BuildingBook(() -> {
        });
        BlockPos doorA = h.absolutePos(new BlockPos(2, 4, 2)), doorB = h.absolutePos(new BlockPos(2, 4, 8)), doorC = h.absolutePos(new BlockPos(8, 4, 2));
        Building none = dug(room, doorA), one = dug(room, doorB), two = dug(room, doorC), away = dug(room, FAR);
        for (BlockPos door : new BlockPos[]{doorA, doorB, doorC}) {
            for (BlockPos p : lamps(door)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        book.add(none, true, null);
        book.add(one, true, null);
        book.add(two, true, null);
        book.add(away, true, null);
        book.relitAt(one, 1);
        book.relitAt(two, 2);
        List<String> heard = new ArrayList<>();
        int placed = book.relightRooms(level, (b, was, blocks) -> heard.add(b.door().getZ() - doorA.getZ() + ":" + was + ":" + blocks));
        h.assertTrue(placed == 3 && heard.equals(List.of("0:0:2", "6:1:1")), "the relight placed " + placed + " and was told " + heard + ", not 3 and [0:0:2, 6:1:1]");
        h.assertTrue(level.getBlockState(lamps(doorA)[0]).is(Blocks.GLOWSTONE) && level.getBlockState(lamps(doorA)[1]).is(Blocks.SEA_LANTERN),
                "the room at no version did not get both lamps");
        h.assertTrue(level.getBlockState(lamps(doorB)[0]).isAir() && level.getBlockState(lamps(doorB)[1]).is(Blocks.SEA_LANTERN),
                "the room at version 1 got " + level.getBlockState(lamps(doorB)[0]) + " and " + level.getBlockState(lamps(doorB)[1]) + ", not only the second step's lamp");
        h.assertTrue(level.getBlockState(lamps(doorC)[0]).isAir() && level.getBlockState(lamps(doorC)[1]).isAir(), "the room already at version 2 was given lamps");
        h.assertTrue(book.relitVersion(none) == 2 && book.relitVersion(one) == 2 && book.relitVersion(two) == 2 && book.relitVersion(away) == 0,
                "the versions after were " + book.relitVersion(none) + book.relitVersion(one) + book.relitVersion(two) + book.relitVersion(away) + ", not 2, 2, 2 and 0 for the room not loaded");
        heard.clear();
        h.assertTrue(book.relightRooms(level) == 0 && book.relightRooms(level, (b, was, blocks) -> heard.add("again")) == 0 && heard.isEmpty(), "a second pass relit a room again");
        book.forgetLamps(none);
        h.assertTrue(book.relitVersion(none) == 0, "a room whose lamps were forgotten is at " + book.relitVersion(none));
        for (BlockPos door : new BlockPos[]{doorA, doorB, doorC}) {
            for (BlockPos p : lamps(door)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        h.succeed();
    }
}
