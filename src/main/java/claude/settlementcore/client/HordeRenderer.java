package claude.settlementcore.client;

import claude.settlementcore.unit.SettlerMob;
import com.geckolib.animatable.GeoEntity;
import com.geckolib.animation.AnimationProcessor;
import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.constant.DataTickets;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.slf4j.Logger;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * A horde creature's renderer (client/HordeLod): faces turned away from the camera are left out of every
 * sealed cube, and beyond 24 blocks the smallest cubes and the item in hand are too (beyond 40, a few more).
 * Beyond 8 blocks the item in hand is a flat picture of itself. Beyond 12, a renderer that
 * {@link #reusesFarModels} keeps each rat's finished model when it works out a pose and draws it again,
 * without GeckoLib, until the next (client/HordeMesh).
 *
 * <p>GeckoLib 5 splits a frame in three: what is taken off the rat into its render state ({@link #fillRenderState},
 * where a far rat's pose is held or its kept model chosen), what is submitted from that ({@link #submit}), and the
 * model's vertices, built after every submission ({@link #submitRenderTasks}, where the cubes are walked here
 * instead of by GeckoLib). Anything a draw needs is carried in the render state, never in the renderer.
 * {@code R} is the render state (a {@link LivingEntityRenderState}, which GeckoLib's mixin makes a GeoRenderState).
 * A subclass that overrides {@link #adjustModelBonesForRender} calls super (the model's own touches).
 */
public abstract class HordeRenderer<T extends SettlerMob & GeoEntity, R extends LivingEntityRenderState & GeoRenderState>
        extends GeoEntityRenderer<T, R> {

    /** Beyond this (squared), a held item is drawn as its flat sprite. */
    public static final double ITEM_SPRITE = 8 * 8;

    /** The rat's pose store (HordeLod), and whether this frame holds the pose in it rather than working one out. */
    private static final DataTicket<float[]> POSE = DataTicket.create("settlementcore_horde_pose", float[].class);
    private static final DataTicket<Boolean> HELD = DataTicket.create("settlementcore_horde_held", Boolean.class);
    /** The kept model this frame is drawn from, or the one it is kept into (HordeMesh). */
    private static final DataTicket<HordeMesh> KEPT = DataTicket.create("settlementcore_horde_kept", HordeMesh.class);
    private static final DataTicket<HordeMesh> KEEP = DataTicket.create("settlementcore_horde_keep", HordeMesh.class);
    /** Bench only: drawn not at all. */
    private static final DataTicket<Boolean> SKIP = DataTicket.create("settlementcore_horde_skip", Boolean.class);

    private static final Logger LOG = LogUtils.getLogger();
    private static final ItemProbe PROBE = new ItemProbe();
    private static final float[] SPRITE = new float[20];
    private static final Vector3f P = new Vector3f();
    private static final Vector3f N = new Vector3f();

    private final HordeGeoModel<T> hordeModel;
    /** Whether this kind's missing clips were reported (once a kind, not every frame). */
    private boolean reportedMissingClips;

    protected HordeRenderer(EntityRendererProvider.Context ctx, HordeGeoModel<T> model) {
        super(ctx, model);
        this.hordeModel = model;
    }

    /** Whether far rats of this kind are drawn from their kept model (the rank and file; not the Rat Ogre, with its variants and glow). */
    protected boolean reusesFarModels() {
        return false;
    }

    // ------------------------------------------------------------------ taken off the rat

    /**
     * GeckoLib's own fill, cut short for a far rat: one drawn from its kept model takes nothing past what picks its
     * texture, and one whose pose is held this frame (HordeLod) skips the animation controllers and their Molang.
     */
    @Override
    public R fillRenderState(T rat, @Nullable Void none, R state, float partialTick) {
        captureDefaultRenderState(rat, none, state, partialTick);
        addRenderData(rat, none, state, partialTick);
        getGeoModel().addAdditionalStateData(rat, none, state);
        double distSqr = state.distanceToCameraSq;
        if (HordeLod.BENCH_SKIP_FAR && distSqr > HordeLod.ANIM_FULL) {
            state.addGeckolibData(SKIP, true);                   // bench only: the ceiling for any far-rat saving
            return state;
        }
        if (reusesFarModels() && HordeLod.reuseFar() && (distSqr > HordeLod.ANIM_FULL || HordeLod.BENCH_MESH_NEAR) && keepable(rat)) {
            HordeMesh mesh = rat.lodMesh instanceof HordeMesh m ? m : null;
            boolean due = HordeLod.BENCH_MESH_NEAR ? mesh == null || System.nanoTime() - mesh.keptAt > 1_000_000_000L
                    : HordeLod.poseDue(rat, distSqr);
            if (mesh != null && mesh.ready && getTextureLocation(state).equals(mesh.texture) && !due) {
                state.addGeckolibData(KEPT, mesh);
                return state;
            }
            if (mesh == null) {
                mesh = new HordeMesh();
                rat.lodMesh = mesh;
            }
            state.addGeckolibData(KEEP, mesh);
        }
        for (GeoRenderLayer<T, Void, R> layer : getRenderLayers()) {
            layer.addRenderData(rat, none, state, partialTick);
        }
        fireCompileRenderStateEvent(rat, none, state, partialTick);
        BakedGeoModel model = getGeoModel().getBakedModel(getGeoModel().getModelResource(state));
        float[] pose = HordeLod.poseStore(rat, model);
        boolean held = HordeLod.saved(pose)
                && (HordeLod.BENCH_NO_ANIM_FAR && distSqr > HordeLod.ANIM_FULL || !HordeLod.animateThisFrame(rat, distSqr));
        state.addGeckolibData(POSE, pose);
        state.addGeckolibData(HELD, held);
        if (!held) {
            setMolangQueryValues(rat, none, state, partialTick);
            try {
                AnimationProcessor.extractControllerStates(rat, state, getGeoModel());
            } catch (NoSuchElementException missing) {
                // GeckoLib 5.5.2 meets a clip it can't find (or a whole animation file it never loaded) with an empty
                // timeline and List.getLast, which crashed the client the first time a test race Guard was drawn
                // (its files were in 4.x's folders). The rat stands in its rest pose instead, and the kind is named once.
                if (!reportedMissingClips) {
                    reportedMissingClips = true;
                    LOG.error("{} asked GeckoLib for a clip that {} doesn't have (or GeckoLib never loaded that file: it reads "
                                    + "assets/<ns>/geckolib/animations/); drawn in its rest pose",
                            rat.getType(), getGeoModel().getAnimationResource(rat), missing);
                }
            }
        }
        return state;
    }

    /**
     * Whether a rat's kept model can stand in for it: only an upright, living, visible rat on its own feet,
     * turned no other way than by its heading (GeckoLib tilts the dying, the sleeping, the freezing).
     */
    private static boolean keepable(SettlerMob rat) {
        return rat.isAlive() && rat.deathTime == 0 && !rat.isInvisible() && !rat.isPassenger() && !rat.isFullyFrozen()
                && !rat.isSleeping() && !rat.isLeashed() && !rat.isAutoSpinAttack();
    }

    /** Whether the rat in {@code state} is far enough to leave out its details (and what it holds): for a layer's addRenderData. */
    protected boolean drawingFar(EntityRenderState state) {
        return HordeLod.simplifyFar() && state.distanceToCameraSq > HordeLod.DETAIL || HordeLod.BENCH_NO_ITEMS;
    }

    // ------------------------------------------------------------------ submitted

    @Override
    public void submit(R state, PoseStack poseStack, SubmitNodeCollector tasks, CameraRenderState camera) {
        if (state.hasGeckolibData(SKIP)) {
            return;
        }
        HordeMesh kept = state.getGeckolibData(KEPT);
        if (kept != null) {
            submitKept(state, kept, poseStack, tasks, camera);
            return;
        }
        HordeMesh keep = state.getGeckolibData(KEEP);
        if (keep != null) {
            keep.begin();
            keep.texture = getTextureLocation(state);
        }
        super.submit(state, poseStack, tasks, camera);
    }

    /** The kept model, where the rat stands and faces now, lit and flashing as it is now; then its leash and name, as vanilla's. */
    private void submitKept(R state, HordeMesh mesh, PoseStack poseStack, SubmitNodeCollector tasks, CameraRenderState camera) {
        RenderType type = getRenderType(state, mesh.texture);
        if (type == null) {
            return;
        }
        int light = state.getPackedLight();
        int overlay = state.getOrDefaultGeckolibData(DataTickets.PACKED_OVERLAY, OverlayTexture.NO_OVERLAY);
        int colour = state.getOrDefaultGeckolibData(DataTickets.RENDER_COLOR, 0xFFFFFFFF);
        float heading = state.bodyRot;
        boolean cull = HordeLod.cullFaces();
        tasks.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            mesh.place(pose, heading);
            mesh.drawBody(buffer, light, overlay, colour, cull);
        });
        if (mesh.hasItem()) {
            tasks.submitCustomGeometry(poseStack, RenderTypes.entityCutout(mesh.itemAtlas), (pose, buffer) -> {
                mesh.place(pose, heading);
                mesh.drawItem(buffer, light, overlay, 0xFFFFFFFF);
            });
        }
        if (state.leashStates != null) {
            for (EntityRenderState.LeashState leash : state.leashStates) {
                tasks.submitLeash(poseStack, leash);
            }
        }
        submitNameDisplay(state, poseStack, tasks, camera);
    }

    /** A held pose put back instead of the controllers' work, or the controllers' fresh pose saved as it is (HordeLod). */
    @Override
    public void applyAnimationControllers(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        float[] pose = info.getGeckolibData(POSE);
        if (pose == null) {
            super.applyAnimationControllers(info, snapshots);
            return;
        }
        GeoBone[] bones = HordeLod.bones(info.model());
        if (Boolean.TRUE.equals(info.getGeckolibData(HELD)) && HordeLod.restorePose(pose, bones, snapshots)) {
            return;
        }
        super.applyAnimationControllers(info, snapshots);
        HordeLod.savePose(pose, bones);                          // as the animation left it, before the per-rat touches
    }

    /** The model's per-rat touches (the look, the kit, the head turn), on the pose fresh or held. */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        hordeModel.customAnimations(info, snapshots);
    }

    // ------------------------------------------------------------------ the model's vertices

    /**
     * GeckoLib's model submission, with the cubes walked here (HordeLod#renderCube: hidden faces left out, and far
     * details). Also how a texture layer (the glow) draws the model again, so it is culled the same; only the
     * first pass of a rat being kept (its own texture) goes into its kept model.
     */
    @Override
    public void submitRenderTasks(RenderPassInfo<R> info, OrderedSubmitNodeCollector tasks, @Nullable RenderType type) {
        if (type == null) {
            return;
        }
        if (info.model().isMissingno()) {
            super.submitRenderTasks(info, tasks, type);
            return;
        }
        R state = info.renderState();
        HordeMesh keep = state.getGeckolibData(KEEP);
        if (keep != null && (keep.rooted() || !info.willRender())) {
            keep = null;                                         // a layer's pass over the model: not kept
        }
        if (keep != null) {
            keep.root(info.getModelRenderMatrixPose(), info.getPreRenderMatrixState(), state.bodyRot);
        }
        double distSqr = state.distanceToCameraSq;
        Pass pass = new Pass(HordeLod.simplifyFar() && distSqr > HordeLod.DETAIL, distSqr, HordeLod.cullFaces(), getTextureLocation(state),
                HordeLod.BENCH_NO_VERTS_FAR && distSqr > HordeLod.ANIM_FULL, keep);
        int light = info.packedLight();
        int overlay = info.packedOverlay();
        int colour = info.renderColor();
        tasks.submitCustomGeometry(info.poseStack(), type, (pose, buffer) -> {
            PoseStack poseStack = info.poseStack();
            poseStack.pushPose();
            poseStack.last().set(pose);
            info.renderPosed(() -> {
                for (GeoBone bone : info.model().topLevelBones()) {
                    drawBone(info, poseStack, bone, buffer, light, overlay, colour, pass);
                }
            });
            poseStack.popPose();
            if (pass.keep != null) {
                pass.keep.done();
            }
        });
    }

    /** What one pass of a rat's model is drawn with: worked out when it is submitted, as the draw comes after every rat's. */
    private record Pass(boolean far, double distSqr, boolean cull, Identifier texture, boolean noVerts, @Nullable HordeMesh keep) {
    }

    /** A bone and its children, as GeckoLib's GeoBone#positionAndRender, with the cubes drawn by HordeLod. */
    private void drawBone(RenderPassInfo<R> info, PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int light, int overlay, int colour,
                          Pass pass) {
        poseStack.pushPose();
        RenderUtil.prepMatrixForBoneAndUpdateListeners(poseStack, bone, info);
        BoneSnapshot snap = bone.frameSnapshot;
        if (!(bone instanceof CuboidGeoBone cuboid)) {
            bone.render(info, poseStack, buffer, light, overlay, colour);
        } else if ((snap == null || !snap.isHidden()) && !pass.noVerts) {
            for (GeoCube cube : cuboid.cubes) {
                if (pass.far && HordeLod.isDetail(cube, pass.distSqr)) {
                    continue;
                }
                HordeLod.renderCube(poseStack, cube, buffer, light, overlay, colour, pass.cull && HordeLod.sealed(cube, pass.texture), pass.keep);
            }
        }
        if (snap == null || !snap.areChildrenHidden()) {
            for (GeoBone child : bone.children()) {
                drawBone(info, poseStack, child, buffer, light, overlay, colour, pass);
            }
        }
        poseStack.popPose();
    }

    // ------------------------------------------------------------------ the item in hand

    /**
     * A held item beyond {@link #ITEM_SPRITE}, drawn as the flat picture of itself: vanilla builds a held
     * item from a face per pixel edge (a pick has more faces than the slave holding it, and the picks cost
     * a sixth of the frame with 600 rats in view), and past 8 blocks its thickness is under a pixel.
     * For a BlockAndItemGeoLayer's submitItemStackRender: {@code pose} is at the grip, as the item state gets it.
     * False (and nothing drawn) near, or for anything but a plain flat item (one generated layer, no glint,
     * tint or special model).
     */
    protected boolean drawItemAsSprite(PoseStack pose, ItemStackRenderState stack, R state, SubmitNodeCollector tasks, int light, int overlay) {
        if (state.distanceToCameraSq < ITEM_SPRITE && !HordeLod.BENCH_MESH_NEAR || !HordeLod.simplifyFar()) {
            return false;
        }
        if (stack.isEmpty() || stack.usesBlockLight()) {
            return false;                                        // lit from the side: a model in the round, such as a block
        }
        BakedQuad front = PROBE.look(stack, pose);
        if (front == null) {
            return false;
        }
        Identifier atlas = front.materialInfo().sprite().atlasLocation();
        HordeMesh keep = state.getGeckolibData(KEEP);
        if (keep != null && keep.rooted()) {
            // the generated item's front face, 16 x 16 pixels, through the middle of its thickness
            PROBE.pose.transformNormal(0f, 0f, 1f, N);
            for (int v = 0; v < 4; v++) {
                Vector3fc c = front.position(v);
                PROBE.pose.pose().transformPosition(c.x(), c.y(), 0.5f, P);
                long uv = front.packedUV(v);
                SPRITE[v * 5] = P.x;
                SPRITE[v * 5 + 1] = P.y;
                SPRITE[v * 5 + 2] = P.z;
                SPRITE[v * 5 + 3] = UVPair.unpackU(uv);
                SPRITE[v * 5 + 4] = UVPair.unpackV(uv);
            }
            keep.quad(true, SPRITE, N.x, N.y, N.z, false);
            keep.itemAtlas = atlas;
        }
        pose.pushPose();
        pose.last().set(PROBE.pose);
        tasks.submitCustomGeometry(pose, RenderTypes.entityCutout(atlas), (p, buffer) -> {
            p.transformNormal(0f, 0f, 1f, N);
            for (int v = 0; v < 4; v++) {
                Vector3fc c = front.position(v);
                p.pose().transformPosition(c.x(), c.y(), 0.5f, P);
                long uv = front.packedUV(v);
                buffer.addVertex(P.x, P.y, P.z, 0xFFFFFFFF, UVPair.unpackU(uv), UVPair.unpackV(uv), overlay, light, N.x, N.y, N.z);
            }
        });
        pose.popPose();
        return true;
    }

    /**
     * A stand-in collector a held item is submitted to, to see where it would be drawn and what of, without
     * drawing it: a plain flat item submits one list of quads, and anything else shows itself by submitting more.
     */
    private static final class ItemProbe implements SubmitNodeCollector {

        /** Where the item's quads would be drawn (its display transform on the grip). */
        final PoseStack.Pose pose = new PoseStack.Pose();
        @Nullable
        private BakedQuad front;
        private int items;
        private boolean other;

        /** The front face of {@code stack} if it is a plain flat item, else null; {@link #pose} is where it goes. */
        @Nullable
        BakedQuad look(ItemStackRenderState stack, PoseStack poseStack) {
            front = null;
            items = 0;
            other = false;
            stack.submit(poseStack, this, 0, OverlayTexture.NO_OVERLAY, 0);
            return items == 1 && !other ? front : null;
        }

        /** The generated item's front: facing south, across the whole square, at the near side of its thickness. */
        private static boolean isFront(BakedQuad q) {
            if (q.direction() != Direction.SOUTH) {
                return false;
            }
            float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
            for (int v = 0; v < 4; v++) {
                Vector3fc c = q.position(v);
                if (Math.abs(c.z() - 8.5f / 16f) > 1e-4f) {
                    return false;
                }
                x0 = Math.min(x0, c.x());
                x1 = Math.max(x1, c.x());
                y0 = Math.min(y0, c.y());
                y1 = Math.max(y1, c.y());
            }
            return x0 < 1e-4f && y0 < 1e-4f && x1 > 1 - 1e-4f && y1 > 1 - 1e-4f;
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor,
                               int[] tintLayers, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
            if (++items > 1 || foilType != ItemStackRenderState.FoilType.NONE || tintLayers.length > 0) {
                other = true;
                return;
            }
            for (BakedQuad q : quads) {
                if (isFront(q)) {
                    front = q;
                    pose.set(poseStack.last());
                    return;
                }
            }
            other = true;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        // anything else an item can submit makes it no plain flat item

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
            other = true;
        }

        @Override
        public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough,
                                  int lightCoords, double distanceToCameraSq, CameraRenderState camera) {
            other = true;
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode,
                               int lightCoords, int color, int backgroundColor, int outlineColor) {
            other = true;
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
            other = true;
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
            other = true;
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
                                    int tintedColor, @Nullable TextureAtlasSprite sprite, int outlineColor,
                                    ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
            other = true;
        }

        @Override
        public void submitModelPart(ModelPart modelPart, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
                                    @Nullable TextureAtlasSprite sprite, boolean sheeted, boolean hasFoil, int tintedColor,
                                    ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay, int outlineColor) {
            other = true;
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState) {
            other = true;
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords,
                                     int overlayCoords, int outlineColor) {
            other = true;
        }

        @Override
        public void submitMultiLayerBlockModel(PoseStack poseStack, List<BlockStateModelPart> modelParts, boolean translucent, int[] tintLayers,
                                               int lightCoords, int overlayCoords, int outlineColor) {
            other = true;
        }

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {
            other = true;
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, CustomGeometryRenderer customGeometryRenderer) {
            other = true;
        }

        @Override
        public void submitParticleGroup(ParticleGroupRenderer particleGroupRenderer) {
            other = true;
        }
    }
}
