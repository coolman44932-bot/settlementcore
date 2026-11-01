package claude.settlementcore.testrace.test;

import claude.settlementcore.test.GameTest;
import claude.settlementcore.test.GameTestHolder;
import claude.settlementcore.testrace.TestRace;
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
 * The test race's models and clips, read and baked by GeckoLib 5's own loader (the classes its resource reload uses), so a model
 * GeckoLib can't read fails here and not as an invisible unit in a client: Blockbench 5.2 stamps a geo format GeckoLib knows as no
 * version (the export sets 1.12.0), and a clip the controller asks for must be in the file.
 */
@GameTestHolder(TestRace.MODID)
public class TestRaceAssetGameTests {

    private static final List<String> BONES = List.of("root", "body", "neck", "head", "right_arm", "right_hand", "left_arm", "right_leg", "left_leg");
    private static final List<String> CLIPS = List.of("idle", "walk", "work", "attack");

    private static InputStreamReader open(GameTestHelper h, String path) throws Exception {
        var jar = ModList.get().getModFileById(TestRace.MODID).getFile().getContents();
        h.assertTrue(jar.containsFile(path), "the test race's jar has no " + path);
        InputStream in = jar.openFile(path);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    /** Both units' geo files read with a known format version, bake, and carry every bone the race's code and the renderer name. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "testrace")
    public static void theUnitsModelsBakeInGeckoLib(GameTestHelper h) throws Exception {
        for (String kind : List.of("worker", "guard")) {
            String path = "assets/testrace/geo/entity/" + kind + ".geo.json";
            Geometry geo;
            try (InputStreamReader r = open(h, path)) {
                geo = Geometry.GSON.fromJson(r, Geometry.class);
            }
            h.assertTrue(ModelFormatVersion.match(geo.formatVersion()) != null, kind + "'s geo format " + geo.formatVersion() + " is none GeckoLib knows");
            BakedGeoModel baked = geo.bake(Identifier.fromNamespaceAndPath(TestRace.MODID, "geo/entity/" + kind + ".geo.json"));
            for (String bone : BONES) {
                h.assertTrue(baked.getBone(bone).isPresent(), kind + "'s model has no bone " + bone);
            }
        }
        h.succeed();
    }

    /** Both units' animation files bake, with every clip the units' animation controller plays. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "testrace")
    public static void theUnitsClipsBakeInGeckoLib(GameTestHelper h) throws Exception {
        for (String kind : List.of("worker", "guard")) {
            String path = "assets/testrace/animations/entity/" + kind + ".animation.json";
            BakedAnimations baked;
            try (InputStreamReader r = open(h, path)) {
                baked = ActorAnimations.GSON.fromJson(r, ActorAnimations.class)
                        .bake(Identifier.fromNamespaceAndPath(TestRace.MODID, "animations/entity/" + kind + ".animation.json"), MathParser.create());
            }
            for (String clip : CLIPS) {
                h.assertTrue(baked.getAnimation("animation.testrace." + clip) != null, kind + "'s animations have no " + clip + ": " + baked.animations().keySet());
            }
        }
        h.succeed();
    }
}
