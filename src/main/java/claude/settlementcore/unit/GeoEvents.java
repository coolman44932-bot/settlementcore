package claude.settlementcore.unit;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animation.state.AnimationPoint;
import com.geckolib.animation.state.KeyFrameEvent;
import com.geckolib.cache.animation.keyframeevent.ParticleKeyframeData;
import com.geckolib.cache.animation.keyframeevent.SoundKeyframeData;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sound and particle keyframes from the Blockbench session's animation files (Verminlord, Rat Ogre),
 * played client-side. Sounds are "vanilla_id|pitch|volume". Particles carry the effect ("warp":
 * green warpstone dust; "warpfire": a cone of green fire along the entity's facing; otherwise a
 * vanilla simple particle id), a count in the locator field, and the spawn point in the script
 * field as "right,up,forward" blocks from the entity origin (Blockbench appends ";"). GeckoLib 5
 * no longer keeps a particle keyframe's script when it bakes an animation, so the scripts are read
 * from the animation file itself ({@link Scripts}).
 */
public final class GeoEvents {

    /** The warpstone green, as the 0xRRGGBB a dust particle takes (0.45, 1.0, 0.2). */
    public static final int WARP_GREEN = 0x73FF33;

    private GeoEvents() {
    }

    public static <T extends LivingEntity & GeoEntity> void playSound(KeyFrameEvent<T, SoundKeyframeData> event) {
        T e = event.animatable();
        Sound cue = sound(event.keyframeData().getSound());
        if (cue == null) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(cue.id());
        if (sound == null) {
            return;
        }
        e.level().playLocalSound(e.getX(), e.getY(), e.getZ(), sound, SoundSource.HOSTILE, cue.volume(), cue.pitch(), false);
    }

    // ------------------------------------------------------------------ reading the keyframes' strings
    // A keyframe plays on the render thread, every time its animation passes it: a string the animation file got wrong must
    // not crash the client. Each of these is null for one it can't read (and the keyframe is skipped), and says so in the
    // log once per string, not every frame.

    /** A sound keyframe, read: the sound's id, its pitch and its volume. */
    public record Sound(Identifier id, float pitch, float volume) {
    }

    private static final Logger LOG = LogUtils.getLogger();
    /** The bad strings warned of already (at most 256, so a generated file can't grow it without end). */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** A sound keyframe's "vanilla_id|pitch|volume" (pitch and volume optional, 1 each), or null if it can't be read. */
    @Nullable
    public static Sound sound(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|");
        Identifier id = parts.length == 0 || parts[0].isBlank() ? null : Identifier.tryParse(parts[0].trim());
        float pitch = parts.length > 1 ? (float) number(parts[1]) : 1.0F;
        float volume = parts.length > 2 ? (float) number(parts[2]) : 1.0F;
        if (id == null || !Float.isFinite(pitch) || !Float.isFinite(volume)) {
            warnOnce("sound", raw);
            return null;
        }
        return new Sound(id, pitch, volume);
    }

    /** A particle keyframe's effect as a particle id, or null if it is no id (the warp effects are matched before this). */
    @Nullable
    public static Identifier particleId(@Nullable String effect) {
        Identifier id = effect == null || effect.isBlank() ? null : Identifier.tryParse(effect.trim());
        if (id == null) {
            warnOnce("particle", effect);
        }
        return id;
    }

    /** No offset: the particles spawn at the middle of the entity. */
    public static final double[] NO_OFFSET = new double[0];

    /**
     * A particle keyframe's spawn point, "right,up,forward" blocks from the entity (with the ";" Blockbench appends): the
     * three, {@link #NO_OFFSET} for no script or one that isn't three parts (spawned at the entity's middle, as before), or
     * null for three parts that aren't all numbers (the keyframe is skipped).
     */
    @Nullable
    public static double[] offset(@Nullable String script) {
        if (script == null) {
            return NO_OFFSET;
        }
        String[] off = script.replace(";", "").split(",");
        if (off.length != 3) {
            return NO_OFFSET;
        }
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            v[i] = number(off[i]);
            if (Double.isNaN(v[i])) {
                warnOnce("particle offset", script);
                return null;
            }
        }
        return v;
    }

    /** A finite number, else NaN. */
    private static double number(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d) ? d : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static void warnOnce(String what, @Nullable String raw) {
        if (WARNED.size() < 256 && WARNED.add(what + "|" + raw)) {
            LOG.warn("GeoEvents: a {} keyframe that can't be read is skipped: \"{}\"", what, raw);
        }
    }

    public static <T extends LivingEntity & GeoEntity> void spawnParticles(KeyFrameEvent<T, ParticleKeyframeData> event) {
        T e = event.animatable();
        Level level = e.level();
        ParticleKeyframeData data = event.keyframeData();
        String effect = data.getEffect() == null ? "" : data.getEffect().trim();
        int count = 8;
        try {
            count = Integer.parseInt(data.getLocatorName() == null ? "" : data.getLocatorName().trim());
        } catch (NumberFormatException ignored) {
        }
        float yaw = e.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
        Vec3 at = e.position().add(0, e.getBbHeight() * 0.5, 0);
        AnimationPoint playing = event.controller().getCurrentAnimationPoint();
        String script = Scripts.of(e, playing == null ? null : playing.animation().name(), data);
        double[] off = offset(script);
        if (off == null) {
            return;                                              // a spawn point it can't read (warned once): this keyframe is skipped
        }
        if (off.length == 3) {
            at = e.position().add(right.scale(off[0])).add(forward.scale(off[2])).add(0, off[1], 0);
        }
        RandomSource rand = e.getRandom();
        if ("warpfire".equals(effect)) {
            // a cone of green fire, laid out along the facing (particle velocity alone won't carry dust)
            DustParticleOptions fire = new DustParticleOptions(WARP_GREEN, 2.0F);
            for (int i = 0; i < count * 3; i++) {
                double d = rand.nextDouble() * 5.0;
                Vec3 p = at.add(forward.scale(d)).add(right.scale(rand.nextGaussian() * d * 0.22)).add(0, rand.nextGaussian() * d * 0.15, 0);
                level.addParticle(i % 4 == 0 ? ParticleTypes.FLAME : fire, p.x, p.y, p.z,
                        forward.x * 0.05, 0.02, forward.z * 0.05);
            }
            return;
        }
        ParticleOptions particle;
        if ("warp".equals(effect)) {
            particle = new DustParticleOptions(WARP_GREEN, 1.6F);
        } else if (particleId(effect) instanceof Identifier id && BuiltInRegistries.PARTICLE_TYPE.getValue(id) instanceof SimpleParticleType simple) {
            particle = simple;
        } else {
            return;
        }
        for (int i = 0; i < count; i++) {
            level.addParticle(particle, at.x + rand.nextGaussian() * 0.25, at.y + rand.nextGaussian() * 0.25, at.z + rand.nextGaussian() * 0.25,
                    rand.nextGaussian() * 0.04, rand.nextDouble() * 0.06, rand.nextGaussian() * 0.04);
        }
    }

    /** Resources reloaded: the animation files may have changed (client/HordeLod#clearCaches). */
    public static void clearScripts() {
        Scripts.FILES.clear();
    }

    /**
     * The particle keyframes' scripts ("pre_effect_script"), read once per animation file from the entity's GeckoLib model.
     * Client only (it is reached only from a keyframe, which plays on the client), so it is the one place here that names client classes.
     */
    private static final class Scripts {

        /** By animation file: by animation name, the scripts by "time|effect|locator". */
        static final Map<Identifier, Map<String, Map<String, String>>> FILES = new HashMap<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Nullable
        static String of(LivingEntity e, @Nullable String animation, ParticleKeyframeData data) {
            if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e) instanceof GeoRenderer<?, ?, ?> renderer)) {
                return null;
            }
            Identifier file = ((GeoModel) renderer.getGeoModel()).getAnimationResource((com.geckolib.animatable.GeoAnimatable) e);
            Map<String, Map<String, String>> byAnimation = FILES.computeIfAbsent(file, Scripts::read);
            String key = key(data.getTime(), data.getEffect(), data.getLocatorName());
            Map<String, String> scripts = animation == null ? null : byAnimation.get(animation);
            if (scripts != null && scripts.containsKey(key)) {
                return scripts.get(key);
            }
            for (Map<String, String> other : byAnimation.values()) {
                if (other.containsKey(key)) {
                    return other.get(key);                       // played from a stage other than the controller's current one
                }
            }
            return null;
        }

        private static String key(double time, String effect, @Nullable String locator) {
            return time + "|" + effect + "|" + (locator == null ? "" : locator);
        }

        private static Map<String, Map<String, String>> read(Identifier file) {
            Map<String, Map<String, String>> byAnimation = new HashMap<>();
            try (Reader in = Minecraft.getInstance().getResourceManager().openAsReader(file)) {
                JsonObject animations = GsonHelper.getAsJsonObject(GsonHelper.parse(in), "animations");
                for (Map.Entry<String, JsonElement> anim : animations.entrySet()) {
                    JsonObject effects = anim.getValue().isJsonObject() ? GsonHelper.getAsJsonObject(anim.getValue().getAsJsonObject(), "particle_effects", null) : null;
                    if (effects == null) {
                        continue;
                    }
                    Map<String, String> scripts = new HashMap<>();
                    for (Map.Entry<String, JsonElement> at : effects.entrySet()) {
                        double time = Double.parseDouble(at.getKey());
                        Iterable<JsonElement> each = at.getValue().isJsonArray() ? at.getValue().getAsJsonArray() : java.util.List.of(at.getValue());
                        for (JsonElement fx : each) {
                            JsonObject o = fx.getAsJsonObject();
                            String script = GsonHelper.getAsString(o, "pre_effect_script", null);
                            if (script != null) {
                                scripts.put(key(time, GsonHelper.getAsString(o, "effect"), GsonHelper.getAsString(o, "locator", null)), script);
                            }
                        }
                    }
                    byAnimation.put(anim.getKey(), scripts);
                }
            } catch (Exception ex) {
                LOG.warn("GeoEvents: cannot read the particle scripts of {}; its particles spawn at the middle of the entity", file, ex);
            }
            return byAnimation;
        }
    }
}
