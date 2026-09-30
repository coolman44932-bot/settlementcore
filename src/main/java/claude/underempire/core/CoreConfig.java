package claude.underempire.core;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The engine's settings, in files of their own: config/settlementcore-common.toml and settlementcore-client.toml
 * (docs/plans/core-step2.md, C6). Until the engine is its own mod, the Under-Empire registers these specs under those
 * names; after, the engine's own mod does, and the files stay the same. The race mod that owned the values before moves
 * the player's own into the new file once, before configs load ({@link #adoptLegacy}).
 *
 * <p>Each setting is read through a supplier (the engine's code calls these), which gives its default until the file has
 * loaded.
 */
public final class CoreConfig {

    private static final Logger LOG = LogUtils.getLogger();

    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue FOES_HUNT_SETTLERS;
    static final ModConfigSpec.BooleanValue DEFENDS_ITSELF;
    static final ModConfigSpec.IntValue DEFENDERS_MAX;
    static final ModConfigSpec.IntValue DEFENDERS_PER_FOE;
    static final ModConfigSpec.IntValue DEEP_LEVELS;
    static final ModConfigSpec.BooleanValue ROOMS_ORDER_GEAR;

    public static final ModConfigSpec CLIENT_SPEC;
    static final ModConfigSpec.BooleanValue CULL_HIDDEN_FACES;
    static final ModConfigSpec.BooleanValue THROTTLE_FAR_ANIMATIONS;
    static final ModConfigSpec.BooleanValue SIMPLIFY_FAR_UNITS;
    static final ModConfigSpec.BooleanValue REUSE_FAR_UNITS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("defence");
        FOES_HUNT_SETTLERS = b.comment("Raiders and the undead (other mods' raiders and zombies too, such as Undead Nights' hordes) go after a",
                        "settlement's people on sight, as they go after villagers, not only after its leader. Off: only when struck.")
                .define("foesHuntSettlers", true);
        DEFENDS_ITSELF = b.comment("A settlement defends itself: when foes get inside it, defenders rise out of its reserve to meet them,",
                        "everyone in it turns on them, and they fight harder in their own tunnels. Whether or not its leader is there.")
                .define("settlementDefendsItself", true);
        DEFENDERS_MAX = b.comment("Defenders up out of the reserve at once, at most.")
                .defineInRange("defendersMax", 40, 0, 400);
        DEFENDERS_PER_FOE = b.comment("Defenders sent up for every foe inside the settlement.")
                .defineInRange("defendersPerFoe", 3, 1, 20);
        b.pop();
        b.push("digging");
        DEEP_LEVELS = b.comment("Levels dug beneath a settlement, each 24 blocks under the last. 0: none.")
                .defineInRange("deepLevels", 2, 0, 6);
        b.pop();
        b.push("work");
        ROOMS_ORDER_GEAR = b.comment("The rooms order their own gear from the workshop (weapons for the guard and the ranks they keep).",
                        "Off: only what you order yourself is made.")
                .define("roomsOrderTheirGear", true);
        b.pop();
        SPEC = b.build();

        ModConfigSpec.Builder c = new ModConfigSpec.Builder();
        c.push("crowd_rendering");
        CULL_HIDDEN_FACES = c.comment("Don't build the faces of a unit's cubes that are turned away from you (they are hidden behind the",
                        "near faces anyway). Changes nothing on screen; the biggest saving.")
                .define("cullHiddenFaces", true);
        THROTTLE_FAR_ANIMATIONS = c.comment("Units beyond 12 blocks work out their pose at most 30, 20 or 12 times a second (by distance) and",
                        "hold it in between.")
                .define("throttleFarAnimations", true);
        SIMPLIFY_FAR_UNITS = c.comment("Beyond 24 blocks, leave out a unit's smallest cubes and the item in its hand; beyond 8, draw the item",
                        "in its hand as a flat picture.")
                .define("simplifyFarUnits", true);
        REUSE_FAR_UNITS = c.comment("Beyond 12 blocks, keep each unit's finished model when it works out a pose and draw it again until the",
                        "next, instead of building it afresh every frame.")
                .define("reuseFarUnits", true);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    /** Raiders and the undead hunt the settlement's people on sight, not only its leader (defence/Foes). */
    public static BooleanSupplier foesHuntSettlers = () -> common(FOES_HUNT_SETTLERS, true);
    /** The settlement defends itself: defenders rise out of the reserve against foes inside it (defence/Muster). */
    public static BooleanSupplier muster = () -> common(DEFENDS_ITSELF, true);
    /** Defenders up at once, at most. */
    public static IntSupplier musterMax = () -> common(DEFENDERS_MAX, 40);
    /** Defenders sent up for each foe. */
    public static IntSupplier musterPerFoe = () -> common(DEFENDERS_PER_FOE, 3);
    /** Levels dug beneath a settlement, each a way down deeper than the last (room/DeepLevels). */
    public static IntSupplier deepLevels = () -> common(DEEP_LEVELS, 2);
    /** The rooms order their own gear from the workshop (work/Works#gear). */
    public static BooleanSupplier roomsOrderGear = () -> common(ROOMS_ORDER_GEAR, true);

    // crowd rendering (client/HordeLod), client side
    public static BooleanSupplier cullHiddenFaces = () -> client(CULL_HIDDEN_FACES, true);
    public static BooleanSupplier throttleFarAnimations = () -> client(THROTTLE_FAR_ANIMATIONS, true);
    public static BooleanSupplier simplifyFarUnits = () -> client(SIMPLIFY_FAR_UNITS, true);
    public static BooleanSupplier reuseFarUnits = () -> client(REUSE_FAR_UNITS, true);

    /** The files' names: the engine's own mod's default names, so they don't change when it becomes its own mod. */
    public static final String COMMON_FILE = Core.NAMESPACE + "-common.toml", CLIENT_FILE = Core.NAMESPACE + "-client.toml";

    private CoreConfig() {
    }

    private static boolean common(ModConfigSpec.BooleanValue v, boolean fallback) {
        return SPEC.isLoaded() ? v.getAsBoolean() : fallback;
    }

    private static int common(ModConfigSpec.IntValue v, int fallback) {
        return SPEC.isLoaded() ? v.getAsInt() : fallback;
    }

    private static boolean client(ModConfigSpec.BooleanValue v, boolean fallback) {
        return CLIENT_SPEC.isLoaded() ? v.getAsBoolean() : fallback;
    }

    /**
     * The player's own values of these settings, from the file a race mod kept them in before, into the engine's new file:
     * once, from the race mod's constructor (configs load after every mod is built). {@code oldToNew} maps dotted keys
     * ("foes.defendersMax" to "defence.defendersMax"), written in the map's order (give it the new file's own order: the
     * player may open it). Nothing happens if the new file is there already or the old one isn't (a dedicated server has no
     * client file); NeoForge then fills in the rest of the new file and drops the moved keys from the old one, if that is
     * still registered (keeping a .bak). Returns how many values moved. Never throws: a failed move leaves the defaults.
     */
    public static int adoptLegacy(String legacyFile, String newFile, Map<String, String> oldToNew) {
        return adoptLegacy(FMLPaths.CONFIGDIR.get(), legacyFile, newFile, oldToNew);
    }

    /** {@link #adoptLegacy(String, String, Map)} in {@code dir} (tests use a folder of their own). */
    public static int adoptLegacy(Path dir, String legacyFile, String newFile, Map<String, String> oldToNew) {
        Path moving = null;
        try {
            Path legacy = dir.resolve(legacyFile), target = dir.resolve(newFile);
            if (Files.exists(target) || !Files.exists(legacy)) {
                return 0;
            }
            moving = dir.resolve(newFile + ".moving");
            CommentedConfig old;
            try (Reader r = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
                old = new TomlParser().parse(r);
            }
            CommentedConfig out = TomlFormat.instance().createConfig(LinkedHashMap::new);   // in order, not hashed
            int n = 0;
            for (Map.Entry<String, String> e : oldToNew.entrySet()) {
                Object v = old.get(e.getKey());
                if (v != null) {
                    out.set(e.getValue(), v);
                    n++;
                }
            }
            if (n == 0) {
                return 0;
            }
            try (Writer w = Files.newBufferedWriter(moving, StandardCharsets.UTF_8)) {
                new TomlWriter().write(out, w);
            }
            Files.move(moving, target, StandardCopyOption.ATOMIC_MOVE);     // whole or not at all: a stub would block the next try
            LOG.info("Settlement Core: moved {} setting(s) from {} into {}", n, legacyFile, newFile);
            return n;
        } catch (IOException | RuntimeException | LinkageError ex) {    // a mod constructor must not fail over this
            LOG.warn("Settlement Core: could not move the settings from {} into {} ({}); they start at their defaults", legacyFile, newFile,
                    ex.toString());
            if (moving != null) {
                try {
                    Files.deleteIfExists(moving);
                } catch (IOException | RuntimeException ignored) {
                }
            }
            return 0;
        }
    }
}
