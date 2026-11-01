package claude.settlementcore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.Optional;
import java.util.UUID;

/**
 * The tag helpers 1.21.1 had and 26.1 dropped (2026-10-31): since 1.21.5 a tag reads and writes ids, positions and items through
 * their codecs. The forms on disk are 1.21.1's: a UUID and a position are int arrays, an item stack its codec's compound.
 */
public final class Nbt {

    private Nbt() {
    }

    public static void putUUID(CompoundTag t, String key, UUID id) {
        t.store(key, UUIDUtil.CODEC, id);
    }

    public static boolean hasUUID(CompoundTag t, String key) {
        return t.read(key, UUIDUtil.CODEC).isPresent();
    }

    /** The id under {@code key}, or null. */
    public static UUID getUUID(CompoundTag t, String key) {
        return t.read(key, UUIDUtil.CODEC).orElse(null);
    }

    public static Tag uuid(UUID id) {
        return UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, id).getOrThrow();
    }

    public static UUID uuid(Tag t) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, t).getOrThrow();
    }

    public static Tag pos(BlockPos p) {
        return BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, p).getOrThrow();
    }

    public static Optional<BlockPos> readPos(CompoundTag t, String key) {
        return t.read(key, BlockPos.CODEC);
    }

    public static Tag item(ItemStack s, HolderLookup.Provider registries) {
        return ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), s).getOrThrow();
    }

    public static Optional<ItemStack> parseItem(HolderLookup.Provider registries, Tag t) {
        return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), t).result();
    }

    /** A tag to load a block entity from, as a chunk's save gives one. */
    public static ValueInput input(CompoundTag t, HolderLookup.Provider registries) {
        return TagValueInput.create(ProblemReporter.DISCARDING, registries, t);
    }
}
