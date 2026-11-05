package claude.settlementcore.room;

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
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.resources.Identifier;
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
 * Loads room templates (data/&lt;the race's namespace&gt;/structure/room/&lt;id&gt;.nbt) straight from the server's
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

    /**
     * Ids whose template could not be read (no file, or one that would not read): said so in the log once, until the next reload or stop. A failed
     * read is never kept: it is tried again the next time it is asked for. Before, it was kept as an empty template until the tests cleared it,
     * so a dig of the room had no cells and was done at once, for as long as the server ran.
     */
    private static final Set<String> FAILED = new HashSet<>();

    /** The template of {@code id}, read from the server's resources the first time and kept (until {@link #reloaded} or {@link #clear}); a failed read is empty and not kept. */
    public static List<Entry> get(ServerLevel level, String id) {
        List<Entry> held = CACHE.get(id);
        if (held != null) {
            return held;
        }
        List<Entry> read = load(level, id);
        if (read == null) {
            return List.of();                                     // not kept: read again next time
        }
        CACHE.put(id, read);
        return read;
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
            String path = "data/" + ns + "/structure/room/" + id + ".nbt";
            var jar = mod.getFile().getContents();
            if (!jar.containsFile(path)) {
                continue;
            }
            try (InputStream in = jar.openFile(path)) {
                return entries(in, BuiltInRegistries.BLOCK);
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

    /** Every template dropped, the server's and a client's (and their prints): the server stopped (RoomCaches), or the tests. */
    public static void clear() {
        CACHE.clear();
        CLIENT_CACHE.clear();
        PRINTS.clear();
        FAILED.clear();
    }

    /**
     * The server's data was reloaded (/reload, RoomCaches): every template read from its resources is dropped, and read again when next asked, so a
     * datapack's override of a room's template comes in; one given by hand ({@link #put}) is no file's, and stays. A dig under way keeps the task
     * list it built: it reads the template again only when it is loaded, and finds from the print that the room changed (DigJob#ensureLoaded).
     */
    public static void reloaded() {
        CACHE.keySet().retainAll(HAND);
        PRINTS.keySet().retainAll(HAND);
        FAILED.clear();
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
        Integer held = PRINTS.get(id);
        if (held != null) {
            return held;
        }
        int h = 1;
        for (Entry e : get(level, id)) {
            h = 31 * h + Long.hashCode(e.local().asLong());
            h = 31 * h + net.minecraft.world.level.block.Block.getId(e.state());
        }
        h = h == 0 ? 1 : h;
        if (CACHE.containsKey(id)) {
            PRINTS.put(id, h);                                    // a failed read's print is not kept either
        }
        return h;
    }

    /** The template of {@code id} from the server's resources, or null when it can't be read (logged once, {@link #FAILED}). */
    private static @Nullable List<Entry> load(ServerLevel level, String id) {
        List<Entry> made = Rooms.madeCells(id);
        if (made != null) {
            return made;                                          // a room made in code (Rooms#family): no file to read
        }
        Identifier loc = null;
        Optional<Resource> res = Optional.empty();
        List<String> tried = Rooms.namespacesFor(id);
        for (String ns : tried) {                                 // the race that registered the room ships its template
            loc = Identifier.fromNamespaceAndPath(ns, "structure/room/" + id + ".nbt");
            res = level.getServer().getResourceManager().getResource(loc);
            if (res.isPresent()) {
                break;
            }
        }
        List<Entry> out = new ArrayList<>();
        if (res.isEmpty()) {
            if (FAILED.add(id)) {
                LOG.error("Settlement Core: missing room template structure/room/{}.nbt (looked in {}; it belongs in {}); it is looked for again "
                        + "each time it is asked for", id, tried, tried.get(0));
            }
            return null;
        }
        try (InputStream in = res.get().open()) {
            out.addAll(entries(in, level.holderLookup(Registries.BLOCK)));
        } catch (Exception e) {
            if (FAILED.add(id)) {
                LOG.error("Settlement Core: failed to read room template {}; it is read again each time it is asked for", loc, e);
            }
            return null;
        }
        return out;
    }

    /** A template file's cells, air and all, with their block states looked up in {@code blocks}. */
    private static List<Entry> entries(InputStream in, HolderGetter<Block> blocks) throws IOException {
        return entries(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()), blocks);
    }

    /**
     * A template's cells from its file's tag, brought up to this game's version first (also for the tests). A template saved on
     * an older Minecraft names its blocks as that game did, and a name this one no longer knows reads back as air, silently: on
     * 26.1 a 1.21.1 room's chains (now iron chains) were gone from it (the review of cccf720). Vanilla's structure loader runs the
     * same fixer; a tag with no DataVersion is from before they had one (500, as vanilla takes it).
     */
    public static List<Entry> entries(CompoundTag file, HolderGetter<Block> blocks) {
        List<Entry> out = new ArrayList<>();
        CompoundTag root = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), file, NbtUtils.getDataVersion(file, 500));
        ListTag palette = root.getListOrEmpty("palette");
        BlockState[] states = new BlockState[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            states[i] = NbtUtils.readBlockState(blocks, palette.getCompoundOrEmpty(i));
        }
        ListTag cells = root.getListOrEmpty("blocks");
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag b = cells.getCompoundOrEmpty(i);
            ListTag pos = b.getListOrEmpty("pos");
            BlockPos p = new BlockPos(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0));
            out.add(new Entry(p, states[b.getIntOr("state", 0)], b.contains("nbt") ? b.getCompoundOrEmpty("nbt") : null));
        }
        return out;
    }

    private RoomTemplates() {
    }
}
