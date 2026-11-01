package claude.settlementcore.testrace.test;

import claude.settlementcore.test.GameTest;
import claude.settlementcore.test.GameTestHolder;
import claude.settlementcore.testrace.TestRace;
import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.animation.BakedAnimations;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.loading.definition.animation.ActorAnimations;
import com.geckolib.loading.definition.geometry.Geometry;
import com.geckolib.loading.definition.geometry.object.ModelFormatVersion;
import com.geckolib.loading.math.MathParser;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModList;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The test race's models and clips, found where GeckoLib 5's resource reload looks and read and baked by its own loader, so a model
 * GeckoLib can't find or read fails here and not in a client: GeckoLib 5 reads only {@code assets/<ns>/geckolib/models} and
 * {@code geckolib/animations} (4.x's {@code geo/} and {@code animations/} load nothing, and the first unit drawn crashed the game),
 * Blockbench 5.2 stamps a geo format GeckoLib knows as no version (the export sets 1.12.0), and a clip the controller asks for must
 * be in the file.
 */
@GameTestHolder(TestRace.MODID)
public class TestRaceAssetGameTests {

    private static final List<String> BONES = List.of("root", "body", "neck", "head", "right_arm", "right_hand", "left_arm", "right_leg", "left_leg");
    private static final List<String> CLIPS = List.of("idle", "walk", "work", "attack");

    /**
     * The file GeckoLib's reload files under {@code id} (what DefaultedEntityGeoModel asks for, {@code testrace:entity/<kind>}):
     * a file in its folder whose name, stripped as GeckoLib strips it, is the id.
     */
    private static String path(GameTestHelper h, Identifier folder, Identifier id, String suffix) {
        String file = folder.getPath() + "/" + id.getPath() + suffix;
        Identifier filed = GeckoLibResources.stripPrefixAndSuffix(Identifier.fromNamespaceAndPath(id.getNamespace(), file));
        h.assertTrue(filed.equals(id), file + " is filed by GeckoLib as " + filed + ", not " + id);
        return "assets/" + id.getNamespace() + "/" + file;
    }

    private static Identifier unit(String kind) {
        return Identifier.fromNamespaceAndPath(TestRace.MODID, "entity/" + kind);
    }

    private static InputStreamReader open(GameTestHelper h, String path) throws Exception {
        var jar = ModList.get().getModFileById(TestRace.MODID).getFile().getContents();
        h.assertTrue(jar.containsFile(path), "the test race's jar has no " + path);
        InputStream in = jar.openFile(path);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    /** Both units' geo files are where GeckoLib looks, read with a known format version, bake, and carry every bone the race's code and the renderer name. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "testrace")
    public static void theUnitsModelsBakeInGeckoLib(GameTestHelper h) throws Exception {
        for (String kind : List.of("worker", "guard")) {
            String path = path(h, GeckoLibResources.MODELS_PATH, unit(kind), ".geo.json");
            Geometry geo;
            try (InputStreamReader r = open(h, path)) {
                geo = Geometry.GSON.fromJson(r, Geometry.class);
            }
            h.assertTrue(ModelFormatVersion.match(geo.formatVersion()) != null, kind + "'s geo format " + geo.formatVersion() + " is none GeckoLib knows");
            BakedGeoModel baked = geo.bake(unit(kind));
            for (String bone : BONES) {
                h.assertTrue(baked.getBone(bone).isPresent(), kind + "'s model has no bone " + bone);
            }
        }
        h.succeed();
    }

    /** Both units' animation files are where GeckoLib looks, bake, with every clip the units' animation controller plays. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "testrace")
    public static void theUnitsClipsBakeInGeckoLib(GameTestHelper h) throws Exception {
        for (String kind : List.of("worker", "guard")) {
            String path = path(h, GeckoLibResources.ANIMATIONS_PATH, unit(kind), ".animation.json");
            BakedAnimations baked;
            try (InputStreamReader r = open(h, path)) {
                baked = ActorAnimations.GSON.fromJson(r, ActorAnimations.class)
                        .bake(unit(kind), MathParser.create());
            }
            for (String clip : CLIPS) {
                h.assertTrue(baked.getAnimation("animation.testrace." + clip) != null, kind + "'s animations have no " + clip + ": " + baked.animations().keySet());
            }
        }
        h.succeed();
    }
}
