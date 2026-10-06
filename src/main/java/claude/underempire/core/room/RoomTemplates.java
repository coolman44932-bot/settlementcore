package claude.underempire.core.room;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** The ids {@link #put} was given: a hand-built room's, which survive {@link #clear} (the room stays registered). */
    private static final Set<String> HAND = new HashSet<>();

    /** What a client has read ({@link #lookup}): a render thread's own, apart from the server's {@link #CACHE}. */
    private static final Map<String, List<Entry>> CLIENT_CACHE = new ConcurrentHashMap<>();

    public static List<Entry> get(ServerLevel level, String id) {
        return CACHE.computeIfAbsent(id, k -> load(level, k));
    }

    /**
     * A room's template for a reader that may be a client's (Siting#preview): a server's level reads it as {@link #get} does, from the
     * server's resources; any other level reads it from the jar of the mod that registered the room, which a client has as well
     * ({@code data/<namespace>/structure/room/<id>.nbt}: a mod's data folder is not in a client's resource manager, and a dedicated
     * server's resources are not the client's). Empty when it is nowhere to be found.
     */
    public static List<Entry> lookup(Level level, String id) {
        if (level instanceof ServerLevel server) {
            return get(server, id);
        }
        return lookupFromMods(id);
    }

    /**
     * A client's {@link #lookup}: what the mods' jars hold of {@code id}, read once and remembered, until {@link #clear} or {@link #forget}. An
     * empty read is remembered too (a room with no template anywhere, or one that would not read, and said so in the log once): a plan held on
     * a client asks every tick, and a missing template must not go through ModList and the jars' files each time.
     */
    public static List<Entry> lookupFromMods(String id) {
        List<Entry> held = CLIENT_CACHE.get(id);
        if (held != null) {
            return held;
        }
        List<Entry> read = readFromMods(id);
        CLIENT_CACHE.put(id, read);
        return read;
    }

    private static final Map<String, Integer> MOD_READS = new ConcurrentHashMap<>();

    /** Tests: how many times {@link #readFromMods} has gone looking in the mods' jars for {@code id}. */
    public static int modReads(String id) {
        return MOD_READS.getOrDefault(id, 0);
    }

    /** The template of {@code id} from the data folder of the mod jar that registered the room, or empty. */
    public static List<Entry> readFromMods(String id) {
        List<Entry> made = Rooms.madeCells(id);
        if (made != null) {
            return made;
        }
        MOD_READS.merge(id, 1, Integer::sum);
        for (String ns : Rooms.namespacesFor(id)) {
            IModFileInfo mod = ModList.get().getModFileById(ns);
            if (mod == null) {
                continue;
            }
            Path path = mod.getFile().findResource("data", ns, "structure", "room", id + ".nbt");
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(path)) {
                return entries(in, BuiltInRegistries.BLOCK.asLookup());
            } catch (Exception e) {
                LOG.error("Under-Empire: failed to read room template {} from the mod {}", id, ns, e);
            }
        }
        return List.of();
    }

    /** Whether the template of {@code id} is in hand (read, or given by {@link #put}), until {@link #clear} or {@link #forget}. */
    public static boolean has(String id) {
        return CACHE.containsKey(id);
    }

    public static void clear() {
        CACHE.clear();
        CLIENT_CACHE.clear();
        PRINTS.clear();
    }

    /**
     * One room's template dropped, as {@link #clear} drops every one (the tests: the dev client drops them all at a server stop and
     * keeps the rooms registered, so a hand-built building's template has to be given again).
     */
    public static void forget(String id) {
        CACHE.remove(id);
        CLIENT_CACHE.remove(id);
        PRINTS.remove(id);
    }

    /**
     * A room's template given by hand, for the tests: a small building no .nbt holds, read as a loaded one is (every cell, air
     * too) until {@link #clear}. Refused (thrown) for the id of a registered room whose template is its own file's: this would
     * swap the template under every dig and raise of it. A hand-built building is given before it is registered, and again, once
     * {@link #clear} has dropped it, while it stays registered: an id already given by hand may be given again.
     */
    public static void put(String id, List<Entry> entries) {
        if (Rooms.get(id) != null && !HAND.contains(id)) {
            throw new IllegalStateException("Settlement Core: the room " + id + " is registered, so its template is its own file's: a template is "
                    + "given by hand only for a building of the tests' own");
        }
        HAND.add(id);
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
        List<Entry> made = Rooms.madeCells(id);
        if (made != null) {
            return made;                                          // a room made in code (Rooms#family): no file to read
        }
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
            out.addAll(entries(in, level.holderLookup(Registries.BLOCK)));
        } catch (Exception e) {
            LOG.error("Under-Empire: failed to read room template {}", loc, e);
        }
        return out;
    }

    /** A template file's cells, air and all, with their block states looked up in {@code blocks}. */
    private static List<Entry> entries(InputStream in, HolderGetter<Block> blocks) throws IOException {
        List<Entry> out = new ArrayList<>();
        CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            states[i] = NbtUtils.readBlockState(blocks, palette.getCompound(i));
        }
        ListTag cells = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag b = cells.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            BlockPos p = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
            out.add(new Entry(p, states[b.getInt("state")], b.contains("nbt") ? b.getCompound("nbt") : null));
        }
        return out;
    }

    private RoomTemplates() {
    }
}
