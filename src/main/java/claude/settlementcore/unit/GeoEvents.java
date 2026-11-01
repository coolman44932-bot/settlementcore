package claude.settlementcore.unit;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.keyframe.event.ParticleKeyframeEvent;
import software.bernie.geckolib.animation.keyframe.event.SoundKeyframeEvent;

/**
 * Sound and particle keyframes from the Blockbench session's animation files (Verminlord, Rat Ogre),
 * played client-side. Sounds are "vanilla_id|pitch|volume". Particles carry the effect ("warp":
 * green warpstone dust; "warpfire": a cone of green fire along the entity's facing; otherwise a
 * vanilla simple particle id), a count in the locator field, and the spawn point in the script
 * field as "right,up,forward" blocks from the entity origin (Blockbench appends ";").
 */
public final class GeoEvents {

    public static final Vector3f WARP_GREEN = new Vector3f(0.45F, 1.0F, 0.2F);

    private GeoEvents() {
    }

    public static <T extends LivingEntity & GeoEntity> void playSound(SoundKeyframeEvent<T> event) {
        T e = event.getAnimatable();
        String[] parts = event.getKeyframeData().getSound().split("\\|");
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(Identifier.parse(parts[0].trim()));
        if (sound == null) {
            return;
        }
        float pitch = parts.length > 1 ? Float.parseFloat(parts[1]) : 1.0F;
        float volume = parts.length > 2 ? Float.parseFloat(parts[2]) : 1.0F;
        e.level().playLocalSound(e.getX(), e.getY(), e.getZ(), sound, SoundSource.HOSTILE, volume, pitch, false);
    }

    public static <T extends LivingEntity & GeoEntity> void spawnParticles(ParticleKeyframeEvent<T> event) {
        T e = event.getAnimatable();
        Level level = e.level();
        String effect = event.getKeyframeData().getEffect().trim();
        int count = 8;
        try {
            count = Integer.parseInt(event.getKeyframeData().getLocator().trim());
        } catch (NumberFormatException ignored) {
        }
        float yaw = e.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
        Vec3 at = e.position().add(0, e.getBbHeight() * 0.5, 0);
        String[] off = event.getKeyframeData().script().replace(";", "").split(",");
        if (off.length == 3) {
            double r = Double.parseDouble(off[0].trim()), u = Double.parseDouble(off[1].trim()), f = Double.parseDouble(off[2].trim());
            at = e.position().add(right.scale(r)).add(forward.scale(f)).add(0, u, 0);
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
        } else if (BuiltInRegistries.PARTICLE_TYPE.get(Identifier.parse(effect)) instanceof SimpleParticleType simple) {
            particle = simple;
        } else {
            return;
        }
        for (int i = 0; i < count; i++) {
            level.addParticle(particle, at.x + rand.nextGaussian() * 0.25, at.y + rand.nextGaussian() * 0.25, at.z + rand.nextGaussian() * 0.25,
                    rand.nextGaussian() * 0.04, rand.nextDouble() * 0.06, rand.nextGaussian() * 0.04);
        }
    }
}
