package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.CoreConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's settings move (CoreConfig#adoptLegacy), for any race that kept them before: with a key map of the test's
 * own, not a race's (the Skaven's own map is checked in their SeamGameTests). Each in a folder of its own: the real config
 * folder is never touched.
 */
@GameTestHolder(Core.MODID)
public class ConfigGameTests {

    /** A race's old keys, in the engine's order, to the engine's keys: one of each section and type. */
    private static Map<String, String> moved() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("old.hunt", "defence.foesHuntSettlers");
        m.put("old.most", "defence.defendersMax");
        m.put("old.levels", "digging.deepLevels");
        m.put("old.gear", "work.roomsOrderTheirGear");
        return m;
    }

    private static final String OLD = String.join(System.lineSeparator(), "[old]", "hunt = false", "most = 9", "levels = 5",
            "gear = false", "unmoved = 1", "");

    /** Values off their defaults come across under keys the spec has, in its order, and survive NeoForge's correction. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "coreconfig")
    public static void aRacesOldSettingsMoveIntoTheEnginesFile(GameTestHelper h) {
        inFolder(h, dir -> {
            Files.writeString(dir.resolve("race-common.toml"), OLD);
            int n = CoreConfig.adoptLegacy(dir, "race-common.toml", "engine-common.toml", moved());
            h.assertTrue(n == 4, n + " settings moved, not 4");
            CommentedConfig cfg = read(dir.resolve("engine-common.toml"));
            h.assertTrue(Boolean.FALSE.equals(cfg.get("defence.foesHuntSettlers")) && Integer.valueOf(9).equals(cfg.get("defence.defendersMax"))
                    && Integer.valueOf(5).equals(cfg.get("digging.deepLevels")) && Boolean.FALSE.equals(cfg.get("work.roomsOrderTheirGear")),
                    "the moved values are wrong: " + cfg);
            h.assertTrue(keysInOrder(dir.resolve("engine-common.toml")).equals(new ArrayList<>(moved().values())),
                    "written in the order " + keysInOrder(dir.resolve("engine-common.toml")) + ", not the spec's");
            CoreConfig.SPEC.correct(cfg);
            h.assertTrue(Integer.valueOf(9).equals(cfg.get("defence.defendersMax")) && Integer.valueOf(5).equals(cfg.get("digging.deepLevels")),
                    "the values did not survive the spec's correction: " + cfg);
        });
    }

    /**
     * A move that fails leaves no file behind (a stub would block every later try, and NeoForge would fill it with
     * defaults), and the next launch's try moves the values; an engine file already there is never overwritten.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "coreconfig")
    public static void aFailedMoveLeavesNothingAndTheNextTryMoves(GameTestHelper h) {
        inFolder(h, dir -> {
            Files.writeString(dir.resolve("race-common.toml"), OLD);
            Path blocker = dir.resolve("engine-common.toml.moving");
            Files.createDirectories(blocker.resolve("in-the-way"));          // the temporary file can't be written
            int n = CoreConfig.adoptLegacy(dir, "race-common.toml", "engine-common.toml", moved());
            h.assertTrue(n == 0 && !Files.exists(dir.resolve("engine-common.toml")),
                    "a failed move reported " + n + " and left " + (Files.exists(dir.resolve("engine-common.toml")) ? "a stub" : "nothing"));
            wipe(blocker);
            n = CoreConfig.adoptLegacy(dir, "race-common.toml", "engine-common.toml", moved());
            h.assertTrue(n == 4 && Integer.valueOf(9).equals(read(dir.resolve("engine-common.toml")).get("defence.defendersMax")),
                    "the next try moved " + n + ", not 4");
            h.assertTrue(!Files.exists(dir.resolve("engine-common.toml.moving")), "the move left its temporary file");
            Files.writeString(dir.resolve("race-common.toml"), String.join(System.lineSeparator(), "[old]", "most = 3", ""));
            n = CoreConfig.adoptLegacy(dir, "race-common.toml", "engine-common.toml", moved());
            h.assertTrue(n == 0 && Integer.valueOf(9).equals(read(dir.resolve("engine-common.toml")).get("defence.defendersMax")),
                    "an engine file already there was overwritten");
        });
    }

    private interface InFolder {
        void run(Path dir) throws IOException;
    }

    private static void inFolder(GameTestHelper h, InFolder body) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("settlementcore_configtest_");
            body.run(dir);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            if (dir != null) {
                wipe(dir);
            }
        }
        h.succeed();
    }

    private static void wipe(Path p) {
        try (var files = Files.walk(p)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    private static CommentedConfig read(Path p) throws IOException {
        try (var r = Files.newBufferedReader(p)) {
            return new TomlParser().parse(r);
        }
    }

    /** section.key of every value line, in the file's own order (a parsed config is hashed). */
    private static List<String> keysInOrder(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        String section = "";
        for (String line : Files.readAllLines(p)) {
            line = line.strip();
            if (line.startsWith("[")) {
                section = line.substring(1, line.indexOf(']')).strip();
            } else if (line.contains("=")) {
                out.add(section + "." + line.substring(0, line.indexOf('=')).strip());
            }
        }
        return out;
    }
}
