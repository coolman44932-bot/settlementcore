package claude.underempire.core.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The containers a work room runs on, found by name: a Furnace Chambers' "Ore Bin", "Fuel Bin" and
 * "Ingot Bin", an Ore Mine's "Ore Cart", the Hoard's "Metals &amp; Gems" and so on. The rooms come dug
 * with them (named in the template), and a Warlord can add more by naming a chest or barrel the same
 * in an anvil and setting it down in the room. Anything unnamed is left alone (it is the Warlord's),
 * except in the Hoard, where every container is the clan's.
 */
public final class Bins {

    private Bins() {
    }

    /** Every container block entity (not furnaces) in {@code box}, in loaded chunks, by position. */
    public static List<BlockPos> containers(ServerLevel level, BoundingBox box) {
        List<BlockPos> out = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (box.isInside(e.getKey()) && e.getValue() instanceof Container && !(e.getValue() instanceof AbstractFurnaceBlockEntity)) {
                        out.add(e.getKey().immutable());
                    }
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.asLong(), b.asLong()));
        return out;
    }

    /** Every furnace, blast furnace and smoker in {@code box}. */
    public static List<BlockPos> furnaces(ServerLevel level, BoundingBox box) {
        List<BlockPos> out = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (box.isInside(e.getKey()) && e.getValue() instanceof AbstractFurnaceBlockEntity) {
                        out.add(e.getKey().immutable());
                    }
                }
            }
        }
        out.sort((a, b) -> Long.compare(a.asLong(), b.asLong()));
        return out;
    }

    /** The name a container was given ("" for none). */
    public static String nameOf(ServerLevel level, BlockPos p) {
        return level.getBlockEntity(p) instanceof Nameable n && n.hasCustomName() && n.getCustomName() != null
                ? n.getCustomName().getString() : "";
    }

    /** The containers in {@code box} named {@code name} (any case). */
    public static List<BlockPos> named(ServerLevel level, BoundingBox box, String name) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : containers(level, box)) {
            if (nameOf(level, p).equalsIgnoreCase(name)) {
                out.add(p);
            }
        }
        return out;
    }

    @Nullable
    public static Container at(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockEntity(p) instanceof Container c ? c : null;
    }

    /** Put as much of {@code stack} as fits into {@code c}: onto like stacks first, then into empty slots. Returns what is left. */
    public static ItemStack insert(Container c, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack s = stack.copy();
        int max = Math.min(s.getMaxStackSize(), c.getMaxStackSize());
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack have = c.getItem(i);
            if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, s) && have.getCount() < max && c.canPlaceItem(i, s)) {
                int move = Math.min(s.getCount(), max - have.getCount());
                have.grow(move);
                s.shrink(move);
            }
        }
        for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
            if (c.getItem(i).isEmpty() && c.canPlaceItem(i, s)) {
                int move = Math.min(s.getCount(), max);
                c.setItem(i, s.split(move));
            }
        }
        if (s.getCount() != stack.getCount()) {
            c.setChanged();
        }
        return s;
    }

    /** Into the first of these containers with room, in order. Returns what is left. */
    public static ItemStack insert(ServerLevel level, List<BlockPos> into, ItemStack stack) {
        ItemStack s = stack;
        for (BlockPos p : into) {
            if (s.isEmpty()) {
                break;
            }
            Container c = at(level, p);
            if (c != null) {
                s = insert(c, s);
            }
        }
        return s;
    }

    /** Whether any of {@code stack} would go into these containers. */
    public static boolean hasRoom(ServerLevel level, List<BlockPos> into, ItemStack stack) {
        for (BlockPos p : into) {
            Container c = at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack have = c.getItem(i);
                if (have.isEmpty() || ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first stack in these containers {@code pick} accepts (a copy, nothing taken), or empty. */
    public static ItemStack peek(ServerLevel level, List<BlockPos> from, Predicate<ItemStack> pick) {
        for (BlockPos p : from) {
            Container c = at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack have = c.getItem(i);
                if (!have.isEmpty() && pick.test(have)) {
                    return have.copy();
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Take up to {@code n} items like the first one {@code pick} accepts, from these containers (all of one
     * kind, gathered across slots). Returns what was taken (empty: nothing there).
     */
    public static ItemStack take(ServerLevel level, List<BlockPos> from, Predicate<ItemStack> pick, int n) {
        ItemStack kind = peek(level, from, pick);
        if (kind.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int want = Math.min(n, kind.getMaxStackSize());
        ItemStack got = kind.copyWithCount(0);
        for (BlockPos p : from) {
            Container c = at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize() && got.getCount() < want; i++) {
                ItemStack have = c.getItem(i);
                if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, kind)) {
                    int move = Math.min(want - got.getCount(), have.getCount());
                    have.shrink(move);
                    got.grow(move);
                    if (have.isEmpty()) {
                        c.setItem(i, ItemStack.EMPTY);
                    }
                    c.setChanged();
                }
            }
        }
        return got;
    }

    /** How many items these containers hold that {@code pick} accepts. */
    public static int count(ServerLevel level, List<BlockPos> from, Predicate<ItemStack> pick) {
        int n = 0;
        for (BlockPos p : from) {
            Container c = at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack have = c.getItem(i);
                if (!have.isEmpty() && pick.test(have)) {
                    n += have.getCount();
                }
            }
        }
        return n;
    }

    /** Every stack in these containers (the containers' own, live: shrink them to take). */
    public static List<ItemStack> stacks(ServerLevel level, List<BlockPos> from) {
        List<ItemStack> out = new ArrayList<>();
        for (BlockPos p : from) {
            Container c = at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (!c.getItem(i).isEmpty()) {
                    out.add(c.getItem(i));
                }
            }
        }
        return out;
    }

    /**
     * A room dug before it had these named barrels (the Throne Hall's Armoury, the Fleshpits' Feeding Trough): set them
     * up at the room's {@code role} stations, where its template now has them, if the room has no container of that name
     * at all and the spot is clear. Returns how many went in.
     */
    public static int placeMissing(ServerLevel level, RoomWork w, String role, String name) {
        if (!named(level, w.box(), name).isEmpty()) {
            return 0;
        }
        int n = 0;
        for (RoomWork.Spot s : w.stations(role)) {
            BlockPos p = s.pos();
            if (!level.isLoaded(p) || !level.getBlockState(p).canBeReplaced()) {
                continue;
            }
            level.setBlock(p, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.BarrelBlock.FACING, s.facing()), net.minecraft.world.level.block.Block.UPDATE_ALL);
            if (level.getBlockEntity(p) instanceof BlockEntity be) {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                tag.putString("CustomName", "{\"text\":\"" + name + "\"}");
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
                n++;
            }
        }
        return n;
    }

    /** Mark these containers changed (after shrinking their live stacks). */
    public static void changed(ServerLevel level, List<BlockPos> ps) {
        for (BlockPos p : ps) {
            Container c = at(level, p);
            if (c != null) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (c.getItem(i).isEmpty() && c.getItem(i) != ItemStack.EMPTY) {
                        c.setItem(i, ItemStack.EMPTY);
                    }
                }
                c.setChanged();
            }
        }
    }
}
