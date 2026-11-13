package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.SettlementBlockEntity;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The old-save check (2026-11-13, ported from the Under-Empire's of 2026-09-26 to 26.1): a world saved by an older set of jars, read with this
 * build. Every block entity and entity of the engine and of every mod built on it ({@link MockPlayers#own}: no race is named) is read out of
 * the copy's region files, loaded by this code and saved again, and written as sorted SNBT so two builds can be compared line by line
 * (tools/savecheck.py): {@code in/} as the old jars saved it, {@code out/} after this build's load and save, then {@code summary.txt}, last.
 *
 * <p>What fails it:
 * <ul>
 * <li>anything that fails to load or save, or that the loader or saver reports a problem with (their ProblemReporter);</li>
 * <li>for a block entity, a key the old save had (with something in it) that this build's save no longer writes, or a list that came back
 * shorter: what the old jars knew and this build drops. A drop that is meant (a key retired on purpose) is allowed by name with
 * {@code -Psavecheckallow=settlement.old_key,...} ({@value #ALLOW}; {@code []} matches every index);</li>
 * <li>for a settlement ({@link SettlementBlockEntity}), its people, stores and plans not reading back the same: the roll, the reserve, the
 * currency, the ration, the spoils, the ledger's stores, the buildings, the digs and the sites being raised, the refund owed, the crews and
 * the alarm, compared between this build's load of the old save and its load of its own save; and the digs, sites, buildings, spoils and
 * ledger stores counted against the old save's own lists (a dig whose room this build no longer has is dropped on load);</li>
 * <li>a world copy with no settlement in it (nothing was checked).</li>
 * </ul>
 * An entity's dropped keys are listed as warnings only: vanilla's own save of a mob is not ours to hold to.
 *
 * <p>Run with {@code -Psavecheck=<folder>[;<folder>...]} ({@value #PROPERTY}; tools/savecheck.py run): each folder is
 * {@code runs/savecheck/<name>} with the world copy in {@code world/} (tools/savecheck.py prepare unzips it there). Nothing is placed in the
 * test world: block entities are made and loaded as the chunk loader does, entities as the entity storage does, and set into no level.
 * Without the property the test passes at once (a full run has no world to check). Only the mods this server has loaded can be read: a
 * race's world is checked in the all-mods run, where the race is ({@code runGameTestServerTogether}, tools/savecheck.py run --together),
 * and what belongs to a mod not loaded is listed as a warning. <b>Not covered:</b> the engine's saved data
 * ({@code data/}), attachments on players and on the level, and the live world (only a copy is read).
 */
@GameTestHolder(Core.MODID)
public class SaveCheckGameTests {

    static final String PROPERTY = "settlementcore.savecheck";
    static final String ALLOW = "settlementcore.savecheck.allowdropped";

    @GameTest(template = "digsite", timeoutTicks = 40, batch = "savecheck")
    public static void oldSavesLoadAndSaveAgainAsTheyWere(GameTestHelper h) {
        String prop = System.getProperty(PROPERTY, "").trim();
        if (prop.isEmpty()) {
            h.succeed();                                          // no world copy given: nothing to check in this run
            return;
        }
        Set<String> own = MockPlayers.own();
        List<String> allowed = Arrays.stream(System.getProperty(ALLOW, "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> failed = new ArrayList<>();
        for (String dir : prop.split(";")) {
            if (dir.isBlank()) {
                continue;
            }
            Path root = Path.of(dir.trim()).toAbsolutePath().normalize();
            String refused = refusal(root);
            h.assertTrue(refused == null, "the save check refuses " + root + ": " + refused);
            Path world = root.resolve("world");
            h.assertTrue(Files.isDirectory(world), "no world copy at " + world + " (tools/savecheck.py prepare)");
            Check c = new Check(h.getLevel(), root, own, allowed);
            try {
                c.clean();
                for (Map.Entry<String, Dimension> d : dimensions(world).entrySet()) {
                    c.dimension(d.getKey(), d.getValue());
                }
                if (c.settlements == 0) {
                    c.errors.add("no settlement block entity of " + own + " in " + world + ": nothing was checked" + (c.unloaded.isEmpty() ? ""
                            : "; the world holds mods this server has not loaded, " + c.unloaded + ": a race's world is checked in the all-mods run "
                            + "(tools/savecheck.py run --together)"));
                }
                c.summary();
            } catch (IOException | RuntimeException e) {
                throw h.assertionException("the save check of " + root + " could not read or write: " + e);
            }
            if (!c.errors.isEmpty()) {
                failed.add(root.getFileName() + ": " + c.errors.size() + " error(s), the first: " + String.join(" | ",
                        c.errors.subList(0, Math.min(8, c.errors.size()))));
            }
        }
        h.assertTrue(failed.isEmpty(), String.join("\n", failed));
        h.succeed();
    }

    /**
     * Why a folder must not be used as the check's (it deletes its in/ and out/ and writes summary.txt there), or null: it has to be the
     * tools' own place, runs/savecheck/<name>, and must look like neither a server's folder nor a game's saves.
     */
    static String refusal(Path root) {
        int n = root.getNameCount();
        if (n < 3 || !root.getName(n - 2).toString().equals("savecheck") || !root.getName(n - 3).toString().equals("runs")) {
            return "not under runs/savecheck/<name> (the folder tools/savecheck.py prepare makes)";
        }
        for (String marker : new String[]{"server.properties", "ops.json", "whitelist.json", "banned-players.json", "eula.txt", "options.txt"}) {
            for (Path where : new Path[]{root, root.resolve("world")}) {
                if (Files.exists(where.resolve(marker))) {
                    return where.resolve(marker) + " exists: this looks like a server's or a game's folder, not a copy of a world";
                }
            }
        }
        return null;
    }

    /** A dimension's folder (its region/ and entities/ inside) and its key. */
    record Dimension(Path folder, ResourceKey<Level> key) {
    }

    /**
     * The world's dimensions, by a slug: 26.1 keeps every one under {@code dimensions/<namespace>/<path>} (the overworld too); a world from
     * before that layout keeps the overworld at its root and the Nether and the End in DIM-1 and DIM1.
     */
    static Map<String, Dimension> dimensions(Path world) throws IOException {
        Map<String, Dimension> out = new TreeMap<>();
        if (Files.isDirectory(world.resolve("region"))) {
            out.put("overworld", new Dimension(world, Level.OVERWORLD));
        }
        if (Files.isDirectory(world.resolve("DIM-1"))) {
            out.put("the_nether", new Dimension(world.resolve("DIM-1"), Level.NETHER));
        }
        if (Files.isDirectory(world.resolve("DIM1"))) {
            out.put("the_end", new Dimension(world.resolve("DIM1"), Level.END));
        }
        Path dims = world.resolve("dimensions");
        if (Files.isDirectory(dims)) {
            try (Stream<Path> all = Files.walk(dims, 6)) {
                for (Path d : all.filter(Files::isDirectory).sorted().toList()) {
                    if (!Files.isDirectory(d.resolve("region")) && !Files.isDirectory(d.resolve("entities"))) {
                        continue;
                    }
                    Path rel = dims.relativize(d);
                    if (rel.getNameCount() < 2) {
                        continue;
                    }
                    String ns = rel.getName(0).toString();
                    String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
                    Identifier id = Identifier.tryBuild(ns, path);
                    if (id != null) {
                        out.put(ns + "_" + path.replace('/', '_'), new Dimension(d, ResourceKey.create(Registries.DIMENSION, id)));
                    }
                }
            }
        }
        return out;
    }

    /** One world copy's check. */
    static final class Check {
        final ServerLevel level;
        final HolderLookup.Provider registries;
        final Path root;
        final Path in;
        final Path out;
        final Set<String> own;
        final List<String> allowed;
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, Integer> counts = new TreeMap<>();
        final Set<String> used = new HashSet<>();
        final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        final Set<Integer> otherVersions = new java.util.TreeSet<>();
        /** Things of mods this server has not loaded, by namespace: not checked (they are no mod's here), and listed as warnings. */
        final Map<String, Integer> unloaded = new TreeMap<>();
        int settlements;

        Check(ServerLevel level, Path root, Set<String> own, List<String> allowed) {
            this.level = level;
            this.registries = level.registryAccess();
            this.root = root;
            this.in = root.resolve("in");
            this.out = root.resolve("out");
            this.own = own;
            this.allowed = allowed;
        }

        /**
         * A file name nobody else in this run has: the name itself, or with _2, _3 ... when it is taken (the same entity UUID in two chunks).
         * Every counted item gets a file of its own, so tools/savecheck.py can check the summary's counts against the files.
         */
        String unique(String name) {
            String n = name;
            for (int i = 2; !used.add(n); i++) {
                n = name + "_" + i;
            }
            return n;
        }

        boolean ours(String id) {
            int colon = id.indexOf(':');
            if (colon <= 0) {
                return false;
            }
            String ns = id.substring(0, colon);
            if (!ns.equals("minecraft") && !net.neoforged.fml.ModList.get().isLoaded(ns)) {
                unloaded.merge(ns, 1, Integer::sum);
            }
            return own.contains(ns);
        }

        void clean() throws IOException {
            for (Path p : new Path[]{in, out}) {
                if (Files.isDirectory(p)) {
                    try (Stream<Path> w = Files.walk(p)) {
                        for (Path f : w.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(f);
                        }
                    }
                }
                Files.createDirectories(p);
            }
            Files.deleteIfExists(root.resolve("summary.txt"));
        }

        void dimension(String slug, Dimension d) throws IOException {
            ServerLevel home = level.getServer().getLevel(d.key());   // null for a dimension the test server doesn't have
            for (Path region : files(d.folder().resolve("region"))) {
                read(region, d.key(), "chunk", tag -> {
                    version(tag);
                    for (Tag t : tag.getListOrEmpty("block_entities")) {
                        if (t instanceof CompoundTag be && ours(be.getStringOr("id", ""))) {
                            blockEntity(slug, be, home == null ? level : home);
                        }
                    }
                });
            }
            for (Path region : files(d.folder().resolve("entities"))) {
                read(region, d.key(), "entities", tag -> {
                    version(tag);
                    for (Tag t : tag.getListOrEmpty("Entities")) {
                        if (t instanceof CompoundTag e) {
                            entity(slug, e);
                        }
                    }
                });
            }
        }

        /** A chunk saved by another version of the game holds data its DataFixer would change first: compared raw, it would look lost. */
        void version(CompoundTag chunk) {
            int v = chunk.getIntOr("DataVersion", dataVersion);
            if (v != dataVersion && otherVersions.add(v)) {
                errors.add("chunks saved at data version " + v + ", not this game's " + dataVersion + ": open the world in this version once "
                        + "and save it, then copy it again (this check does not run the DataFixer)");
            }
        }

        interface ChunkReader {
            void read(CompoundTag tag) throws IOException;
        }

        static List<Path> files(Path dir) throws IOException {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
            }
        }

        void read(Path file, ResourceKey<Level> dim, String type, ChunkReader reader) throws IOException {
            String[] parts = file.getFileName().toString().split("\\.");
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            try (RegionFile rf = new RegionFile(new RegionStorageInfo("savecheck", dim, type), file, file.getParent(), false)) {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        ChunkPos pos = new ChunkPos(rx * 32 + x, rz * 32 + z);
                        try (DataInputStream s = rf.getChunkDataInputStream(pos)) {
                            if (s != null) {
                                reader.read(NbtIo.read(s));
                            }
                        } catch (IOException | RuntimeException e) {
                            errors.add(file.getFileName() + " " + pos + ": unreadable: " + e);
                        }
                    }
                }
            }
        }

        void blockEntity(String dim, CompoundTag tag, ServerLevel home) throws IOException {
            String id = tag.getStringOr("id", "");
            BlockPos pos = new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
            String name = unique(dim + "/" + slug(id) + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ());
            count("block_entity " + id);
            write(in, name, tag);
            Identifier rl = Identifier.tryParse(id);
            BlockEntityType<?> type = rl == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(rl);
            Block block = type == null ? null : blockOf(type, rl);
            if (type == null || block == null) {
                errors.add(name + ": no block entity type " + id + " (or no block for it) in this build");
                return;
            }
            try {
                BlockEntity first = load(type, pos, block, tag, home, name, "load");
                if (first == null) {
                    return;
                }
                CompoundTag saved = save(first, name);
                write(out, name, saved);
                List<String> lost = new ArrayList<>();
                dropped(tag, saved, "", lost);
                for (String l : lost) {
                    if (!allowed(l)) {
                        errors.add(name + ": the old save's " + l + " is not in this build's save");
                    }
                }
                if (first instanceof SettlementBlockEntity s1) {
                    settlements++;
                    count("settlement " + id);
                    BlockEntity again = load(type, pos, block, saved, home, name, "load of its own save");
                    if (again instanceof SettlementBlockEntity s2) {
                        Snap a = Snap.of(s1);
                        Snap b = Snap.of(s2);
                        if (!a.equals(b)) {
                            errors.add(name + ": the settlement reads back different after a save: " + a.differences(b));
                        }
                        a.againstSave(tag.getCompoundOrEmpty(SettlementBlockEntity.SAVE_KEY), name, errors);
                    } else {
                        errors.add(name + ": its own save loads as " + again);
                    }
                }
            } catch (RuntimeException e) {
                errors.add(name + ": " + e);
            }
        }

        /** {@code type}'s block: the block of its own id if it is one of the type's, else the first of them by id. */
        static Block blockOf(BlockEntityType<?> type, Identifier id) {
            Block same = BuiltInRegistries.BLOCK.getValue(id);
            if (same != null && type.getValidBlocks().contains(same)) {
                return same;
            }
            return type.getValidBlocks().stream().min(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString())).orElse(null);
        }

        /** A block entity made and loaded from {@code tag} as the chunk loader does, then set in {@code home} so it can save; null on a failure. */
        BlockEntity load(BlockEntityType<?> type, BlockPos pos, Block block, CompoundTag tag, ServerLevel home, String name, String what) {
            BlockEntity be = type.create(pos, block.defaultBlockState());
            if (be == null) {
                errors.add(name + ": " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type) + " made no block entity");
                return null;
            }
            ProblemReporter.Collector problems = new ProblemReporter.Collector();
            be.loadWithComponents(TagValueInput.create(problems, registries, tag));
            if (!problems.isEmpty()) {
                errors.add(name + ": problems in the " + what + ": " + problems.getReport().replace('\n', ' '));
            }
            be.setLevel(home);                                    // in its world, as when the game saves it: a settlement saves only then
            return be;
        }

        CompoundTag save(BlockEntity be, String name) {
            ProblemReporter.Collector problems = new ProblemReporter.Collector();
            TagValueOutput o = TagValueOutput.createWithContext(problems, registries);
            be.saveWithFullMetadata(o);
            if (!problems.isEmpty()) {
                errors.add(name + ": problems in the save: " + problems.getReport().replace('\n', ' '));
            }
            return o.buildResult();
        }

        void entity(String dim, CompoundTag tag) throws IOException {
            CompoundTag copy = tag.copy();
            copy.remove("Passengers");                             // each rider is checked on its own
            String id = copy.getStringOr("id", "");
            if (ours(id)) {
                int[] uuid = copy.getIntArray("UUID").orElse(new int[0]);
                // the whole UUID, each int padded to 8 hex digits; unique() for the rest: the same UUID twice, or none
                String name = unique(dim + "/" + slug(id) + "_" + (uuid.length == 4
                        ? String.format("%08x%08x%08x%08x", uuid[0], uuid[1], uuid[2], uuid[3]) : "nouuid"));
                count("entity " + id);
                write(in, name, copy);
                try {
                    ProblemReporter.Collector problems = new ProblemReporter.Collector();
                    Entity e = EntityType.create(TagValueInput.create(problems, registries, copy), level, EntitySpawnReason.LOAD).orElse(null);
                    if (e == null) {
                        errors.add(name + ": " + id + " did not load");
                    } else {
                        TagValueOutput o = TagValueOutput.createWithContext(problems, registries);
                        if (!e.save(o)) {
                            o.putString("id", id);
                            e.saveWithoutId(o);
                        }
                        CompoundTag saved = o.buildResult();
                        saved.remove("Passengers");
                        write(out, name, saved);
                        e.discard();                              // it was never added to the level
                        if (!problems.isEmpty()) {
                            errors.add(name + ": problems in its load or save: " + problems.getReport().replace('\n', ' '));
                        }
                        List<String> lost = new ArrayList<>();
                        dropped(copy, saved, "", lost);
                        lost.stream().filter(l -> !allowed(l)).forEach(l -> warnings.add(name + ": the old save's " + l + " is not in this build's save"));
                    }
                } catch (RuntimeException e) {
                    errors.add(name + ": " + e);
                }
            }
            for (Tag t : tag.getListOrEmpty("Passengers")) {
                if (t instanceof CompoundTag p) {
                    entity(dim, p);
                }
            }
        }

        boolean allowed(String path) {
            String general = path.replaceAll("\\[\\d+]", "[]");
            int cut = general.indexOf(' ');
            String bare = cut < 0 ? general : general.substring(0, cut);
            for (String a : allowed) {
                if (bare.equals(a) || bare.startsWith(a + ".") || bare.startsWith(a + "[")) {
                    return true;
                }
            }
            return false;
        }

        void count(String key) {
            counts.merge(key, 1, Integer::sum);
        }

        /** summary.txt, last: the counts, then every error and warning, so tools/savecheck.py knows a run that finished from one that did not. */
        void summary() throws IOException {
            StringBuilder b = new StringBuilder();
            counts.forEach((k, v) -> b.append(k).append(' ').append(v).append('\n'));
            b.append("settlements ").append(settlements).append('\n');
            b.append("errors ").append(errors.size()).append('\n');
            errors.forEach(e -> b.append("  ").append(e.replace('\n', ' ')).append('\n'));
            unloaded.forEach((ns, n) -> warnings.add("not loaded here, so not checked: " + n + " block entities and entities of " + ns));
            b.append("warnings ").append(warnings.size()).append('\n');
            warnings.forEach(w -> b.append("  ").append(w.replace('\n', ' ')).append('\n'));
            Files.writeString(root.resolve("summary.txt"), b.toString(), StandardCharsets.UTF_8);
        }

        static String slug(String id) {
            return id.replace(':', '_').replace('/', '_');
        }

        static void write(Path base, String name, CompoundTag tag) throws IOException {
            Path p = base.resolve(name + ".snbt");
            Files.createDirectories(p.getParent());
            StringBuilder b = new StringBuilder();
            snbt(b, tag, 0);
            Files.writeString(p, b.append('\n').toString(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ what a save dropped

    /** Lists whose order means nothing: vanilla saves a mob's attributes, their modifiers and its effects out of hash maps. */
    static final Set<String> UNORDERED = Set.of("attributes", "modifiers", "active_effects");

    /**
     * Adds to {@code lost} every key of {@code was} with something in it that {@code now} does not have, every list or array that came back
     * shorter, and every value whose kind changed, by its path (settlement.works.digs[2]).
     */
    static void dropped(Tag was, Tag now, String path, List<String> lost) {
        if (was instanceof CompoundTag c) {
            if (!(now instanceof CompoundTag d)) {
                lost.add(at(path) + " (a compound, now " + kind(now) + ")");
                return;
            }
            for (String k : c.keySet()) {
                Tag v = c.get(k);
                String p = path.isEmpty() ? k : path + "." + k;
                if (!d.contains(k)) {
                    if (!blank(v)) {
                        lost.add(p);
                    }
                } else if (!UNORDERED.contains(k)) {
                    dropped(v, d.get(k), p, lost);
                }
            }
        } else if (was instanceof ListTag l) {
            if (!(now instanceof ListTag m)) {
                lost.add(at(path) + " (a list, now " + kind(now) + ")");
            } else if (m.size() < l.size()) {
                lost.add(at(path) + " (" + l.size() + " entries, now " + m.size() + ")");
            } else if (m.size() == l.size()) {
                for (int i = 0; i < l.size(); i++) {
                    dropped(l.get(i), m.get(i), path + "[" + i + "]", lost);
                }
            }
        } else if (was instanceof LongArrayTag || was instanceof IntArrayTag || was instanceof ByteArrayTag) {
            int a = ((net.minecraft.nbt.CollectionTag) was).size();
            if (!(now instanceof net.minecraft.nbt.CollectionTag n) || now instanceof ListTag) {
                lost.add(at(path) + " (an array, now " + kind(now) + ")");
            } else if (n.size() < a) {
                lost.add(at(path) + " (" + a + " entries, now " + n.size() + ")");
            }
        }
    }

    private static String at(String path) {
        return path.isEmpty() ? "(the whole tag)" : path;
    }

    private static String kind(Tag t) {
        return t == null ? "nothing" : t.getClass().getSimpleName();
    }

    /** A value whose absence loses nothing: an empty compound, list, array or string, a 0 or false. */
    static boolean blank(Tag t) {
        if (t instanceof CompoundTag c) {
            return c.isEmpty();
        }
        if (t instanceof net.minecraft.nbt.CollectionTag c) {
            return c.size() == 0;
        }
        if (t instanceof StringTag s) {
            return s.value().isEmpty();
        }
        if (t instanceof NumericTag n) {
            return n.doubleValue() == 0;
        }
        return false;
    }

    // ------------------------------------------------------------------ a settlement's people, stores and plans

    /** What a settlement holds that a player would miss: read off a loaded settlement, with no level needed. */
    record Snap(String owner, String name, int currency, List<Integer> ration, List<UUID> roll, int reserveWorkers, int reserveSoldiers,
                List<String> buildings, List<String> digs, List<String> sites, List<String> spoils, List<Long> ledgerStores, int refundDue,
                int owedLines, List<UUID> crews, boolean alarmed) {

        static Snap of(SettlementBlockEntity s) {
            List<String> buildings = s.buildings().stream().map(Snap::building).toList();
            List<String> digs = s.works().digs().stream().map(d -> d.job().roomId + "@" + d.job().door.toShortString()).sorted().toList();
            List<String> sites = s.works().sites().stream().map(d -> d.job().roomId + "@" + d.job().door.toShortString()).sorted().toList();
            List<String> spoils = s.spoils().stream().map(Snap::stack).toList();
            List<Long> ledger = Arrays.stream(s.ledgerStores().toLongArray()).sorted().boxed().toList();
            List<UUID> crews = s.works().crewIds().stream().sorted().toList();
            return new Snap(Objects.toString(s.owner(), ""), s.settlementName(), s.currency(), Arrays.stream(s.ration().grades()).boxed().toList(),
                    List.copyOf(s.roll().ids()), s.reserveWorkers(), s.reserveSoldiers(), buildings, digs, sites, spoils, ledger,
                    s.works().refundDue(), s.works().owedLines(), crews, s.muster().alarmed());
        }

        static String building(Building b) {
            return b.room() + " " + b.box() + (b.door() == null ? "" : " door " + b.door().toShortString() + " " + b.facing());
        }

        static String stack(ItemStack s) {
            return BuiltInRegistries.ITEM.getKey(s.getItem()) + " x" + s.getCount() + (s.getComponentsPatch().isEmpty() ? "" : " " + s.getComponentsPatch());
        }

        /** The fields of {@code b} that differ from this one's, as "field: this / that". */
        String differences(Snap b) {
            List<String> out = new ArrayList<>();
            var comps = Snap.class.getRecordComponents();
            for (var c : comps) {
                try {
                    Object x = c.getAccessor().invoke(this);
                    Object y = c.getAccessor().invoke(b);
                    if (!Objects.equals(x, y)) {
                        out.add(c.getName() + ": " + x + " / " + y);
                    }
                } catch (ReflectiveOperationException e) {
                    out.add(c.getName() + ": unreadable (" + e + ")");
                }
            }
            return String.join("; ", out);
        }

        /** This (the load of the old save) against the old save's own lists: what the load skipped is gone at the next save. */
        void againstSave(CompoundTag saved, String name, List<String> errors) {
            CompoundTag works = saved.getCompoundOrEmpty("works");
            check(name, "digs", works.getListOrEmpty("digs").size(), digs.size(), errors, "a dig whose room this build has not, or a second at one door");
            check(name, "sites", works.getListOrEmpty("sites").size(), sites.size(), errors, "a site whose plan or room can't be read: its price is refunded");
            check(name, "buildings", saved.getListOrEmpty("buildings").size(), buildings.size(), errors, "a building without its six-number box");
            check(name, "spoils", saved.getListOrEmpty("spoils").size(), spoils.size(), errors, "a stack whose item this build can't read");
            check(name, "ledger stores", saved.getLongArray("ledger_stores").orElse(new long[0]).length, ledgerStores.size(), errors, "the same store twice");
        }

        private static void check(String name, String what, int inSave, int loaded, List<String> errors, String likely) {
            if (inSave != loaded) {
                errors.add(name + ": the old save holds " + inSave + " " + what + ", this build reads " + loaded + " (likely " + likely + ")");
            }
        }
    }

    // ------------------------------------------------------------------ sorted SNBT

    /** A tag as SNBT with its keys sorted, its order-free lists sorted by id and one entry a line, so two saves diff line by line. */
    static void snbt(StringBuilder b, Tag t, int depth) {
        String pad = "  ".repeat(depth + 1);
        if (t instanceof CompoundTag c) {
            if (c.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            List<String> keys = new ArrayList<>(c.keySet());
            keys.sort(null);
            for (int i = 0; i < keys.size(); i++) {
                b.append(pad).append(keys.get(i)).append(": ");
                Tag v = c.get(keys.get(i));
                if (UNORDERED.contains(keys.get(i)) && v instanceof ListTag l && l.stream().allMatch(e -> e instanceof CompoundTag)) {
                    List<Tag> sorted = new ArrayList<>(l);
                    sorted.sort(Comparator.comparing(e -> ((CompoundTag) e).getStringOr("id", "")));
                    ListTag s = new ListTag();
                    s.addAll(sorted);
                    v = s;
                }
                snbt(b, v, depth + 1);
                b.append(i < keys.size() - 1 ? ",\n" : "\n");
            }
            b.append("  ".repeat(depth)).append('}');
        } else if (t instanceof ListTag l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                b.append(pad);
                snbt(b, l.get(i), depth + 1);
                b.append(i < l.size() - 1 ? ",\n" : "\n");
            }
            b.append("  ".repeat(depth)).append(']');
        } else {
            b.append(t);
        }
    }
}
