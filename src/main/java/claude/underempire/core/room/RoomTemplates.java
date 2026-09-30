package claude.underempire.core.room;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads room templates (data/underempire/structure/room/&lt;id&gt;.nbt) straight from the server's
 * resources. Reading the file ourselves gives the full block list including air, which is what a
 * dig needs: air entries are the cells the rats gnaw out.
 */
public final class RoomTemplates {

    private static final Logger LOG = LogUtils.getLogger();

    public record Entry(BlockPos local, BlockState state, @Nullable CompoundTag nbt) {
    }

    private static final Map<String, List<Entry>> CACHE = new HashMap<>();

    public static List<Entry> get(ServerLevel level, String id) {
        return CACHE.computeIfAbsent(id, k -> load(level, k));
    }

    public static void clear() {
        CACHE.clear();
        PRINTS.clear();
    }

    /**
     * A room's template given by hand, for the tests: a small building no .nbt holds, read as a loaded one is (every cell, air
     * too) until {@link #clear}.
     */
    public static void put(String id, List<Entry> entries) {
        CACHE.put(id, List.copyOf(entries));
        PRINTS.remove(id);
    }

    private static final Map<String, Integer> PRINTS = new HashMap<>();

    /**
     * A fingerprint of a room's template (every cell and its block): a dig in progress keeps it, and finds from it
     * that the room was changed under it (DigJob#ensureLoaded).
     */
    public static int print(ServerLevel level, String id) {
        return PRINTS.computeIfAbsent(id, k -> {
            int h = 1;
            for (Entry e : get(level, k)) {
                h = 31 * h + Long.hashCode(e.local().asLong());
                h = 31 * h + net.minecraft.world.level.block.Block.getId(e.state());
            }
            return h == 0 ? 1 : h;
        });
    }

    private static List<Entry> load(ServerLevel level, String id) {
        ResourceLocation loc = null;
        Optional<Resource> res = Optional.empty();
        List<String> tried = Rooms.namespacesFor(id);
        for (String ns : tried) {                                 // the race that registered the room ships its template
            loc = ResourceLocation.fromNamespaceAndPath(ns, "structure/room/" + id + ".nbt");
            res = level.getServer().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                break;
            }
        }
        List<Entry> out = new ArrayList<>();
        if (res.isEmpty()) {
            LOG.error("Under-Empire: missing room template structure/room/{}.nbt (looked in {}; it belongs in {})", id, tried, tried.get(0));
            return out;
        }
        try (InputStream in = res.get().open()) {
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
            BlockState[] states = new BlockState[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                states[i] = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), palette.getCompound(i));
            }
            ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                ListTag pos = b.getList("pos", Tag.TAG_INT);
                BlockPos p = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                out.add(new Entry(p, states[b.getInt("state")], b.contains("nbt") ? b.getCompound("nbt") : null));
            }
        } catch (Exception e) {
            LOG.error("Under-Empire: failed to read room template {}", loc, e);
        }
        return out;
    }

    private RoomTemplates() {
    }
}
