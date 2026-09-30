package claude.underempire.core.client;

import claude.underempire.core.unit.SettlerMob;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * A horde creature's model, with far animation throttled (client/HordeLod): a far rat works out its pose
 * only every few frames and holds it in between.
 *
 * <p>GeckoLib's bones are shared by every rat of a kind, so each rat's pose has to be put back on them
 * before it is drawn. The pose is saved as the animation left it, before the per-rat touches in
 * {@link #customAnimations} (the look, which kit bones show), and on a held frame it is restored and
 * those touches are made again: they may be additive (the Rat Ogre's look) and must not stack.
 * Subclasses override {@link #customAnimations}, never {@link #setCustomAnimations}.
 */
public abstract class HordeGeoModel<T extends SettlerMob & software.bernie.geckolib.animatable.GeoEntity> extends DefaultedEntityGeoModel<T> {

    private GeoBone[] bones;

    protected HordeGeoModel(ResourceLocation assetSubpath, boolean turnsHead) {
        super(assetSubpath, turnsHead);
    }

    @Override
    public void handleAnimations(T rat, long instanceId, AnimationState<T> state, float partialTick) {
        if (HordeLod.BENCH_NO_ANIM_FAR && HordeLod.distSqr(rat) > HordeLod.ANIM_FULL && rat.lodPose != null) {
            return;
        }
        bones = HordeLod.boneArray(getAnimationProcessor().getRegisteredBones(), bones);
        if (!HordeLod.animateThisFrame(rat, HordeLod.distSqr(rat)) && HordeLod.restorePose(rat.lodPose, bones)) {
            customAnimations(rat, instanceId, state);           // held pose; the look and kit stay live
            return;
        }
        super.handleAnimations(rat, instanceId, state, partialTick);
    }

    @Override
    public final void setCustomAnimations(T rat, long instanceId, AnimationState<T> state) {
        if (bones != null) {
            rat.lodPose = HordeLod.savePose(rat.lodPose, bones);    // as the animation left it
        }
        customAnimations(rat, instanceId, state);
    }

    /** Per-rat touches on top of the animation (GeckoLib's head turn by default). */
    protected void customAnimations(T rat, long instanceId, AnimationState<T> state) {
        super.setCustomAnimations(rat, instanceId, state);
    }
}
