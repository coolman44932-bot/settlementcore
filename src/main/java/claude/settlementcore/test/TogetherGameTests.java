package claude.settlementcore.test;

import claude.settlementcore.Core;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The all-mods run's own checks (2026-11-13): every mod the user's instance carries, loaded in one server with this engine, as the user plays
 * them (tools/together.py; the run gameTestServerTogether). Each mod's own tests run beside these; these check what no mod's tests can alone:
 * <ul>
 * <li>every jar put in the run's mods folder loaded, as the version it is, and every mod that carries GameTests registered some;</li>
 * <li>no two mods ship the same data file (a recipe, a loot table, a structure ...: the later pack silently wins), and no mod's tag file
 * {@code replace}s a tag another mod adds to, where one of the two is ours (the engine, its test race or a mod in the run's list); two other
 * mods' clashes are logged, not failed.</li>
 * </ul>
 * The list comes from the run ({@value #PROPERTY}: {@code modid@version,...}, {@value #TESTS}: the mods with tests, {@value #DIRS}: the data
 * folders the jars hold), which tools/together.py writes; outside that run the tests pass at once. No race is named here.
 */
@GameTestHolder(Core.MODID)
public class TogetherGameTests {

    private static final Logger LOG = LogUtils.getLogger();

    static final String PROPERTY = "settlementcore.together";
    static final String TESTS = "settlementcore.together.tests";
    static final String DIRS = "settlementcore.together.dirs";
    /** Vanilla's data folders whose files replace each other (tags merge, so they are looked at for {@code replace} instead). */
    static final List<String> DATA_DIRS = List.of("recipe", "loot_table", "advancement", "structure", "worldgen", "dimension", "dimension_type",
            "damage_type", "enchantment", "enchantment_provider", "item_modifier", "predicate", "trim_material", "trim_pattern", "banner_pattern",
            "painting_variant", "jukebox_song", "instrument", "chat_type", "test_instance", "test_environment");

    /** {@code modid -> version} of the run's list; empty outside the all-mods run. */
    static Map<String, String> expected() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String s : System.getProperty(PROPERTY, "").split(",")) {
            String e = s.trim();
            if (!e.isEmpty()) {
                int at = e.indexOf('@');
                out.put(at < 0 ? e : e.substring(0, at), at < 0 ? "" : e.substring(at + 1));
            }
        }
        return out;
    }

    static List<String> list(String property) {
        return Arrays.stream(System.getProperty(property, "").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @GameTest(template = "digsite", timeoutTicks = 40, batch = "together")
    public static void everyModInTheRunLoadedAndRegisteredItsTests(GameTestHelper h) {
        Map<String, String> want = expected();
        if (want.isEmpty()) {
            h.succeed();                                          // not the all-mods run
            return;
        }
        List<String> wrong = new ArrayList<>();
        want.forEach((id, version) -> {
            var mod = ModList.get().getModContainerById(id);
            if (mod.isEmpty()) {
                wrong.add(id + " is not loaded");
            } else if (!version.isEmpty() && !mod.get().getModInfo().getVersion().toString().equals(version)) {
                wrong.add(id + " loaded as " + mod.get().getModInfo().getVersion() + ", not the jar's " + version);
            }
        });
        boolean filtered = !System.getProperty(Core.MODID + ".only", "").isBlank();
        if (!filtered) {
            Map<String, Integer> tests = new LinkedHashMap<>();
            for (Identifier id : BuiltInRegistries.TEST_FUNCTION.keySet()) {
                tests.merge(id.getNamespace(), 1, Integer::sum);
            }
            for (String id : list(TESTS)) {
                if (tests.getOrDefault(id, 0) == 0) {
                    wrong.add(id + " carries GameTests but registered none (its registrar, or -Ptestns?)");
                }
            }
            LOG.info("TOGETHER tests by namespace: {}", tests);
        }
        h.assertTrue(wrong.isEmpty(), "the all-mods run: " + String.join("; ", wrong));
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 40, batch = "together")
    public static void noTwoModsShipTheSameDataFile(GameTestHelper h) {
        Map<String, String> want = expected();
        if (want.isEmpty()) {
            h.succeed();
            return;
        }
        Set<String> ours = new LinkedHashSet<>(want.keySet());
        ours.addAll(MockPlayers.own());
        ResourceManager rm = h.getLevel().getServer().getResourceManager();
        Set<String> dirs = new TreeSet<>(DATA_DIRS);
        dirs.addAll(list(DIRS));
        dirs.remove("tags");
        List<String> clashes = new ArrayList<>();
        List<String> others = new ArrayList<>();
        Set<String> seenPacks = new TreeSet<>();
        for (String dir : dirs) {
            for (Map.Entry<Identifier, List<Resource>> e : rm.listResourceStacks(dir, id -> true).entrySet()) {
                List<String> mods = modPacks(e.getValue(), seenPacks);
                if (mods.size() > 1) {
                    (touchesOurs(mods, ours) ? clashes : others).add(e.getKey() + " from " + mods + " (the last wins)");
                }
            }
        }
        for (Map.Entry<Identifier, List<Resource>> e : rm.listResourceStacks("tags", id -> id.getPath().endsWith(".json")).entrySet()) {
            List<String> mods = modPacks(e.getValue(), seenPacks);
            if (mods.size() < 2) {
                continue;
            }
            for (Resource r : e.getValue()) {
                if (r.sourcePackId().startsWith("mod/") && replaces(r)) {
                    (touchesOurs(mods, ours) ? clashes : others).add(e.getKey() + ": " + r.sourcePackId() + " replaces what " + mods + " add");
                }
            }
        }
        others.forEach(c -> LOG.warn("TOGETHER clash between two mods not ours: {}", c));
        // the check only works if each mod's files come from a pack of its own (NeoForge's mod/<modid>): say so if not, never pass blind
        LOG.info("TOGETHER data packs seen: {}; {} clash(es) of ours, {} of others", seenPacks, clashes.size(), others.size());
        h.assertTrue(clashes.isEmpty(), clashes.size() + " data file(s) shipped by two mods: " + String.join("; ", clashes.subList(0, Math.min(20, clashes.size()))));
        h.assertTrue(seenPacks.stream().anyMatch(p -> p.startsWith("mod/")),
                "no data file was seen coming from a mod's own pack (" + seenPacks + "): the clash check could not tell the mods apart");
        h.succeed();
    }

    /** The distinct mod packs ({@code mod/...}) a stack's files come from, in order; every pack seen is noted. */
    private static List<String> modPacks(List<Resource> stack, Set<String> seen) {
        List<String> out = new ArrayList<>();
        for (Resource r : stack) {
            String p = r.sourcePackId();
            seen.add(p);
            if (p.startsWith("mod/") && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean touchesOurs(List<String> packs, Set<String> ours) {
        for (String p : packs) {
            for (String id : p.substring("mod/".length()).split(",")) {
                if (ours.contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a tag file says {@code "replace": true}. */
    private static boolean replaces(Resource r) {
        try (Reader in = r.openAsReader()) {
            JsonElement j = JsonParser.parseReader(in);
            return j.isJsonObject() && j.getAsJsonObject().has("replace") && j.getAsJsonObject().get("replace").getAsBoolean();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
