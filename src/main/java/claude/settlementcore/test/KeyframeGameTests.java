package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.unit.GeoEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

/**
 * The animation files' sound and particle keyframe strings (unit/GeoEvents), read as they are on the render thread: one the
 * file got wrong is skipped, never thrown. To 0.5.5 a bad id or number crashed the client every time the keyframe played.
 */
@GameTestHolder(Core.MODID)
public class KeyframeGameTests {

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aGoodSoundKeyframeIsRead(GameTestHelper h) {
        GeoEvents.Sound s = GeoEvents.sound("minecraft:entity.ravager.roar|0.8|1.5");
        h.assertTrue(s != null && s.id().equals(Identifier.parse("minecraft:entity.ravager.roar")) && s.pitch() == 0.8f && s.volume() == 1.5f,
                "read as " + s);
        GeoEvents.Sound plain = GeoEvents.sound("entity.rat.squeak");
        h.assertTrue(plain != null && plain.pitch() == 1f && plain.volume() == 1f, "a sound with no pitch or volume read as " + plain);
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aBadSoundKeyframeIsSkippedNotThrown(GameTestHelper h) {
        for (String bad : new String[]{"Minecraft:Bad Id|1|1", "minecraft:entity.ravager.roar|loud", "minecraft:entity.ravager.roar| |1",
                "minecraft:entity.ravager.roar|NaN", "minecraft:entity.ravager.roar|1|Infinity", "", "|", "a:b:c", null}) {
            GeoEvents.Sound s;
            try {
                s = GeoEvents.sound(bad);
                GeoEvents.sound(bad);                            // and again, as the next frame plays it (warned of once)
            } catch (RuntimeException ex) {
                h.fail("the sound keyframe \"" + bad + "\" threw " + ex);
                return;
            }
            h.assertTrue(s == null, "the sound keyframe \"" + bad + "\" read as " + s + ", not skipped");
        }
        h.succeed();
    }

    @GameTest(template = "digsite", timeoutTicks = 20, batch = "unitlogic")
    public static void aBadParticleKeyframeIsSkippedNotThrown(GameTestHelper h) {
        h.assertTrue(Identifier.parse("minecraft:flame").equals(GeoEvents.particleId(" minecraft:flame ")), "a good particle id isn't read");
        double[] good = GeoEvents.offset("0.5,1.25,-2;");
        h.assertTrue(Arrays.equals(good, new double[]{0.5, 1.25, -2}), "the spawn point 0.5,1.25,-2 read as " + Arrays.toString(good));
        h.assertTrue(GeoEvents.offset(null) == GeoEvents.NO_OFFSET && GeoEvents.offset("1,2") == GeoEvents.NO_OFFSET,
                "no spawn point (or one not of three parts) isn't the entity's middle, as before");
        for (String bad : new String[]{"Not A Particle!", "", " ", "a:b:c", null}) {
            Identifier id;
            try {
                id = GeoEvents.particleId(bad);
            } catch (RuntimeException ex) {
                h.fail("the particle keyframe \"" + bad + "\" threw " + ex);
                return;
            }
            h.assertTrue(id == null, "the particle keyframe \"" + bad + "\" read as " + id + ", not skipped");
        }
        for (String bad : new String[]{"a,b,c", "1,,2", "1,2,NaN;", "1,Infinity,0", " , , "}) {
            double[] off;
            try {
                off = GeoEvents.offset(bad);
            } catch (RuntimeException ex) {
                h.fail("the spawn point \"" + bad + "\" threw " + ex);
                return;
            }
            h.assertTrue(off == null, "the spawn point \"" + bad + "\" read as " + Arrays.toString(off) + ", not skipped");
        }
        h.succeed();
    }
}
