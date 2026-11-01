package claude.settlementcore.client;

import claude.settlementcore.unit.SettlerMob;
import com.geckolib.animatable.GeoEntity;
import com.geckolib.constant.DefaultAnimations;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;

/**
 * A horde creature's model, drawn by a {@link HordeRenderer} (which throttles far animation, client/HordeLod: a far
 * rat works out its pose only every few frames and holds it in between).
 *
 * <p>GeckoLib 5 poses a model at draw time from what was taken off the rat into its render state, so the
 * per-rat touches on top of the animation (the look, which kit bones show) are made in
 * {@link #customAnimations}, from the render state, every frame, after the animation's pose (worked out
 * fresh or held): they may be additive (the Rat Ogre's look) and never stack, as each frame's bone values
 * start from nothing. What they need off the rat goes into the render state in {@link #addAdditionalStateData}.
 */
public abstract class HordeGeoModel<T extends SettlerMob & GeoEntity> extends DefaultedEntityGeoModel<T> {

    private final boolean turnsHead;

    protected HordeGeoModel(Identifier assetSubpath, boolean turnsHead) {
        super(assetSubpath);
        this.turnsHead = turnsHead;
    }

    /** Whether the "head" bone follows where the rat looks (GeckoLib 4's DefaultedEntityGeoModel did this itself). */
    public boolean turnsHead() {
        return turnsHead;
    }

    /**
     * Per-rat touches on top of the animation, through {@code snapshots}; by default the head turn, which
     * sets the "head" bone's turn to where the rat looks (overriding the animation's), as GeckoLib 4 did.
     */
    public <R extends LivingEntityRenderState & GeoRenderState> void customAnimations(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        if (turnsHead) {
            DefaultAnimations.hardcodedHeadRotation(info, snapshots, "head");
        }
    }
}
