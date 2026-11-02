package claude.settlementcore.settlement;

import claude.settlementcore.Nbt;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.RoomRelight;
import claude.settlementcore.room.RoomShoring;
import claude.settlementcore.room.RoomStacking;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.room.Rooms;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A settlement's buildings and what is kept about each: the list, which have been walled up, which have the floors between them
 * and the rooms dug over or under them laid, which lamps each has (the version of its room's relight), and where the settlement
 * has shored its rooms against caves, drops and the sky. Lifted from the Warren Heart (docs/plans/greatplan-first-spawning.md,
 * section B item 8): the same algorithms and the same save keys ({@code buildings} and {@code shored}), so the Heart's tag reads
 * back through here.
 *
 * <p>The upkeep drivers ({@link #wallUpRooms}, {@link #stackRooms}, {@link #relightRooms}) do each room's once, and are called
 * only by a settlement that wants them: a surface race adds each room it raises as already walled, and wants none of
 * the walling and relighting. They change what they place and report how many blocks went in, and leave telling the owner to the
 * settlement. The book changes nothing else: it calls its {@code changed} callback (the block entity's {@code setChanged}) after a
 * change, as the Heart does.
 */
public final class BuildingBook {

    /** A room that was relit: its lamps went from version {@code was} to its room's current one, and {@code blocks} went in. */
    @FunctionalInterface
    public interface Relit {
        void relit(Building b, int was, int blocks);
    }

    private final List<Building> buildings = new ArrayList<>();
    /** Where the settlement walled its rooms up against a cave, a drop or the sky: its own, cut by its later digs like rock. */
    private final LongOpenHashSet shored = new LongOpenHashSet();
    /** The rooms walled up (by the corner of their box): as they were dug, or since (a room dug before the shoring). */
    private final LongOpenHashSet walled = new LongOpenHashSet();
    /** The rooms whose floors with the rooms dug before them are laid. */
    private final LongOpenHashSet stacked = new LongOpenHashSet();
    /** The version of the rooms' lamps each room has, by the corner of its box. */
    private final Map<Long, Integer> relit = new HashMap<>();
    private final Runnable changed;

    /** @param changed what to do when the book changed and must be saved (the block entity's setChanged) */
    public BuildingBook(Runnable changed) {
        this.changed = changed;
    }

    /** A room's key in the book's sets: the corner of its box. */
    private static long key(Building b) {
        return BlockPos.asLong(b.box().minX(), b.box().minY(), b.box().minZ());
    }

    // ------------------------------------------------------------------ the list

    /** The buildings, in the order they were added: the list itself, as the Heart's is. */
    public List<Building> buildings() {
        return buildings;
    }

    /**
     * A room is finished. {@code walled}: whether it was walled up as it was dug (RoomShoring), as a raised building is. With a
     * {@code level} a room just made from today's template has today's lamps; without one it has none yet (the upkeep gives
     * them, if the settlement asks).
     */
    public void add(Building b, boolean walled, @Nullable ServerLevel level) {
        buildings.add(b);
        if (level != null) {
            relit.put(key(b), RoomRelight.get(level, b.room()).version());
        }
        if (walled) {
            this.walled.add(key(b));
        }
        changed.run();
    }

    /** A room is gone (a race's own reason: a heap of rubble that woke and walked off): out of the list and the book's sets. True if it was there. */
    public boolean remove(Building b) {
        if (!buildings.remove(b)) {
            return false;
        }
        long k = key(b);
        walled.remove(k);
        stacked.remove(k);
        relit.remove(k);
        changed.run();
        return true;
    }

    public boolean has(Rooms.Function f) {
        return count(f) > 0;
    }

    /** How many of its buildings are rooms of function {@code f}. */
    public int count(Rooms.Function f) {
        int n = 0;
        for (Building b : buildings) {
            Rooms.Room r = Rooms.get(b.room());
            if (r != null && r.function() == f) {
                n++;
            }
        }
        return n;
    }

    /** What the haul log calls the place at {@code p}: the room it is in, or "" if none. */
    public String placeOf(BlockPos p) {
        for (Building b : buildings) {
            if (b.box().isInside(p)) {
                return Settlement.roomName(b.room());
            }
        }
        return "";
    }

    /** Where a room was dug from its template (its door and the way it runs), or null for one dug before rooms kept their door. */
    @Nullable
    public static PlanPlacement placementOf(Building b) {
        Rooms.Room r = Rooms.get(b.room());
        return b.door() == null || b.facing() == null || r == null ? null : PlanPlacement.compute(r, b.door(), b.facing());
    }

    // ------------------------------------------------------------------ what is kept about each

    /** Whether the room has been walled up. */
    public boolean walled(Building b) {
        return walled.contains(key(b));
    }

    /** Whether the floors between the room and those dug before it have been laid. */
    public boolean stacked(Building b) {
        return stacked.contains(key(b));
    }

    /** The version of the rooms' lamps this room has (0: dug before any). */
    public int relitVersion(Building b) {
        return relit.getOrDefault(key(b), 0);
    }

    /** Tests: this room is as it was dug at the rooms' version {@code version}. */
    public void relitAt(Building b, int version) {
        relit.put(key(b), version);
    }

    /** Tests: this room is as it was dug before its lamps. */
    public void forgetLamps(Building b) {
        relit.remove(key(b));
    }

    // ------------------------------------------------------------------ the shoring (core/room/RoomShoring)

    public boolean shored(long pos) {
        return shored.contains(pos);
    }

    public void shore(long pos) {
        shored.add(pos);
        changed.run();
    }

    public void unshore(long pos) {
        if (shored.remove(pos)) {
            changed.run();
        }
    }

    /**
     * Every cell of the rooms in {@code area} but those of the one at {@code except}: the buildings (a room's template cells, or all
     * of its box for one dug before rooms kept their door) and the {@code digs} (rooms being dug or planned). The settlement adds
     * its heart's own cell.
     */
    public LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except, Collection<PlanPlacement> digs) {
        LongOpenHashSet out = new LongOpenHashSet();
        List<PlanPlacement> rooms = new ArrayList<>();
        for (Building b : buildings) {
            if (b.box().equals(except) || !b.box().intersects(area)) {
                continue;
            }
            PlanPlacement pl = placementOf(b);
            if (pl == null) {
                BoundingBox o = b.box();                           // dug before rooms kept their door: all its box is its
                for (BlockPos p : BlockPos.betweenClosed(Math.max(o.minX(), area.minX()), Math.max(o.minY(), area.minY()), Math.max(o.minZ(), area.minZ()),
                        Math.min(o.maxX(), area.maxX()), Math.min(o.maxY(), area.maxY()), Math.min(o.maxZ(), area.maxZ()))) {
                    out.add(p.asLong());
                }
                continue;
            }
            rooms.add(pl);
        }
        for (PlanPlacement dig : digs) {
            if (!dig.box().equals(except) && dig.box().intersects(area)) {
                rooms.add(dig);
            }
        }
        for (PlanPlacement pl : rooms) {
            for (RoomTemplates.Entry e : RoomTemplates.get(level, pl.room().id())) {
                BlockPos w = pl.world(e.local());
                if (area.isInside(w)) {
                    out.add(w.asLong());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the upkeep, once each room

    /**
     * Rooms dug before the shoring (or beside a chunk that was not loaded as they were dug) are walled up now, once each, where they
     * open onto a cave, a drop or the sky. A room whose chunks are not loaded is tried again later. Returns how many blocks went in.
     */
    public int wallUpRooms(ServerLevel level, RoomShoring.Warren warren) {
        int placed = 0;
        for (Building b : buildings) {
            if (walled.contains(key(b))) {
                continue;
            }
            int n = RoomShoring.wallUp(level, b, warren);
            if (n >= 0) {
                walled.add(key(b));
                placed += n;
                changed.run();
            }
        }
        return placed;
    }

    /**
     * Rooms dug over or under another before the rooms knew how to stack get the floor between them laid, once each. Returns how
     * many blocks went in.
     */
    public int stackRooms(ServerLevel level) {
        int placed = 0;
        for (int i = 0; i < buildings.size(); i++) {
            Building b = buildings.get(i);
            if (stacked.contains(key(b))) {
                continue;
            }
            PlanPlacement pl = placementOf(b);
            int n = 0;
            boolean loaded = true;
            for (int j = 0; j < i && pl != null; j++) {
                PlanPlacement earlier = placementOf(buildings.get(j));
                if (earlier == null || !earlier.box().intersects(pl.box())) {
                    continue;
                }
                int m = RoomStacking.mend(level, pl, earlier);
                if (m < 0) {
                    loaded = false;                              // tried again when its chunks are loaded
                    break;
                }
                n += m;
            }
            if (loaded) {
                stacked.add(key(b));
                placed += n;
                changed.run();
            }
        }
        return placed;
    }

    /** As {@link #relightRooms(ServerLevel, Relit)}, with no one told of each room. */
    public int relightRooms(ServerLevel level) {
        return relightRooms(level, (b, was, blocks) -> {
        });
    }

    /**
     * Rooms dug before they had their lamps get them now, once each (only where the world still has what the old room had there).
     * {@code each} hears of every room relit. Returns how many blocks went in.
     */
    public int relightRooms(ServerLevel level, Relit each) {
        int placed = 0;
        for (Building b : buildings) {
            int v = RoomRelight.get(level, b.room()).version();
            int was = relit.getOrDefault(key(b), 0);
            if (v == 0 || was >= v) {
                continue;
            }
            int n = RoomRelight.apply(level, b, was);
            if (n >= 0) {
                relit.put(key(b), v);
                placed += n;
                each.relit(b, was, n);
                changed.run();
            }
        }
        return placed;
    }

    // ------------------------------------------------------------------ saved

    /**
     * Written as the Heart writes them: {@code buildings}, each a compound of its {@code room}, {@code box} (six ints), {@code door}
     * and {@code facing} (when it has them), {@code relit}, {@code walled} and {@code stacked}; and {@code shored}, the cells as longs.
     */
    public void save(CompoundTag tag) {
        tag.putLongArray("shored", shored.toLongArray());
        ListTag list = new ListTag();
        for (Building b : buildings) {
            CompoundTag t = new CompoundTag();
            t.putString("room", b.room());
            t.putIntArray("box", new int[]{b.box().minX(), b.box().minY(), b.box().minZ(),
                    b.box().maxX(), b.box().maxY(), b.box().maxZ()});
            if (b.door() != null && b.facing() != null) {
                t.put("door", Nbt.pos(b.door()));
                t.putString("facing", b.facing().getSerializedName());
            }
            t.putInt("relit", relit.getOrDefault(key(b), 0));
            t.putBoolean("walled", walled.contains(key(b)));
            t.putBoolean("stacked", stacked.contains(key(b)));
            list.add(t);
        }
        tag.put("buildings", list);
    }

    /** Read from the keys {@link #save} writes (and the Heart's): the book is what the tag says. */
    public void load(CompoundTag tag) {
        shored.clear();
        shored.addAll(it.unimi.dsi.fastutil.longs.LongArrayList.wrap(tag.getLongArray("shored").orElse(new long[0])));
        buildings.clear();
        walled.clear();
        stacked.clear();
        relit.clear();
        for (Tag t : tag.getListOrEmpty("buildings")) {
            CompoundTag c = (CompoundTag) t;
            int[] b = c.getIntArray("box").orElse(new int[0]);
            if (b.length != 6) {
                continue;
            }
            BlockPos door = Nbt.readPos(c, "door").orElse(null);
            Direction facing = Direction.byName(c.getStringOr("facing", ""));
            Building built = new Building(c.getStringOr("room", ""), new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]), door, facing);
            buildings.add(built);
            relit.put(key(built), c.getIntOr("relit", 0));
            if (c.getBooleanOr("walled", false)) {
                walled.add(key(built));
            }
            if (c.getBooleanOr("stacked", false)) {
                stacked.add(key(built));
            }
        }
    }
}
