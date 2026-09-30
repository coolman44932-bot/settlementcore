package claude.underempire.core.client;

import claude.underempire.core.unit.SettlerMob;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * A horde creature's renderer (client/HordeLod): faces turned away from the camera are left out of every
 * sealed cube, and beyond 24 blocks the smallest cubes and the item in hand are too (beyond 40, a few more).
 * Beyond 8 blocks the item in hand is a flat picture of itself. Beyond 12, a renderer that
 * {@link #reusesFarModels} keeps each rat's finished model when it works out a pose and draws it again,
 * without GeckoLib, until the next (client/HordeMesh).
 */
public abstract class HordeRenderer<T extends SettlerMob & software.bernie.geckolib.animatable.GeoEntity> extends GeoEntityRenderer<T> {

    /** Beyond this (squared), a held item is drawn as its flat sprite. */
    public static final double ITEM_SPRITE = 8 * 8;
    private static final RenderType ITEM_SPRITES = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);

    private boolean far;
    private double distSqr;
    private boolean cull;
    private ResourceLocation texture;
    /** The model being kept this frame (HordeMesh), and where the rat stood and faced when it was. */
    private HordeMesh keeping;
    private final Matrix4f spot = new Matrix4f();
    private float heading;

    private static final float[] SPRITE = new float[20];
    private static final float[][] CORNERS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    private static final Vector3f P = new Vector3f();
    private static final Vector3f N = new Vector3f();

    protected HordeRenderer(EntityRendererProvider.Context ctx, HordeGeoModel<T> model) {
        super(ctx, model);
    }

    /** Whether far rats of this kind are drawn from their kept model (the rank and file; not the Rat Ogre, with its variants and glow). */
    protected boolean reusesFarModels() {
        return false;
    }

    @Override
    public void render(T rat, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        distSqr = HordeLod.distSqr(rat);
        if (HordeLod.BENCH_SKIP_FAR && distSqr > HordeLod.ANIM_FULL) {
            return;                                              // bench only: the ceiling for any far-rat saving
        }
        far = HordeLod.simplifyFar() && distSqr > HordeLod.DETAIL;
        cull = HordeLod.cullFaces();
        texture = getTextureLocation(rat);
        if (reusesFarModels() && HordeLod.reuseFar() && (distSqr > HordeLod.ANIM_FULL || HordeLod.BENCH_MESH_NEAR) && keepable(rat)) {
            HordeMesh mesh = rat.lodMesh instanceof HordeMesh m ? m : null;
            float headingNow = Mth.rotLerp(partialTick, rat.yBodyRotO, rat.yBodyRot);
            boolean due = HordeLod.BENCH_MESH_NEAR ? mesh == null || System.nanoTime() - mesh.keptAt > 1_000_000_000L
                    : HordeLod.poseDue(rat, distSqr);
            if (mesh != null && mesh.ready && texture.equals(mesh.texture) && !due) {
                drawKept(rat, mesh, headingNow, partialTick, poseStack, buffers, light);
                return;
            }
            if (mesh == null) {
                mesh = new HordeMesh();
                rat.lodMesh = mesh;
            }
            mesh.begin();
            mesh.texture = texture;
            keeping = mesh;
            spot.set(poseStack.last().pose());
            heading = headingNow;
            HordeMesh.capturing = mesh;
            try {
                super.render(rat, yaw, partialTick, poseStack, buffers, light);
            } finally {
                HordeMesh.capturing = null;
                keeping = null;
            }
            mesh.done();
            return;
        }
        super.render(rat, yaw, partialTick, poseStack, buffers, light);
    }

    /**
     * Whether a rat's kept model can stand in for it: only an upright, living, visible rat on its own feet,
     * turned no other way than by its heading (GeckoLib tilts the dying, the sleeping, the freezing).
     */
    private static boolean keepable(SettlerMob rat) {
        return rat.isAlive() && rat.deathTime == 0 && !rat.isInvisible() && !rat.isPassenger() && !rat.isFullyFrozen()
                && !rat.isSleeping() && !rat.isLeashed() && !rat.isAutoSpinAttack();
    }

    /** The kept model, where the rat stands and faces now, lit and flashing as it is now. */
    private void drawKept(T rat, HordeMesh mesh, float headingNow, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        RenderType type = getRenderType(rat, mesh.texture, buffers, partialTick);
        if (type == null) {
            return;
        }
        int overlay = getPackedOverlay(rat, 0, partialTick);
        int colour = getRenderColor(rat, partialTick, light).argbInt();
        mesh.place(poseStack, headingNow);
        mesh.drawBody(buffers.getBuffer(type), light, overlay, colour, cull);
        if (mesh.hasItem()) {
            mesh.drawItem(buffers.getBuffer(ITEM_SPRITES), light, overlay, 0xFFFFFFFF);
        }
        if (shouldShowName(rat)) {
            renderNameTag(rat, rat.getDisplayName(), poseStack, buffers, light, partialTick);
        }
    }

    @Override
    public void renderRecursively(PoseStack poseStack, T rat, GeoBone bone, RenderType renderType, MultiBufferSource buffers,
                                  VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        if (keeping != null && !keeping.rooted()) {
            keeping.root(poseStack.last(), spot, heading);       // the first bone GeckoLib draws: the model's root
        }
        super.renderRecursively(poseStack, rat, bone, renderType, buffers, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);
    }

    /** Whether the rat being drawn is far enough to leave out its details (and what it holds). */
    protected boolean drawingFar() {
        return far || HordeLod.BENCH_NO_ITEMS;
    }

    /**
     * A held item beyond {@link #ITEM_SPRITE}, drawn as the flat picture of itself: vanilla builds a held
     * item from a face per pixel edge (a pick has more faces than the slave holding it, and the picks cost
     * a sixth of the frame with 600 rats in view), and past 8 blocks its thickness is under a pixel.
     * {@code pose} is at the grip, as ItemRenderer gets it. False (and nothing drawn) near, or for anything
     * but a plain flat item.
     */
    protected boolean drawItemAsSprite(PoseStack pose, ItemStack stack, ItemDisplayContext context, boolean leftHand, T rat,
                                       MultiBufferSource buffers, int light, int overlay) {
        if (distSqr < ITEM_SPRITE && !HordeLod.BENCH_MESH_NEAR || !HordeLod.simplifyFar()) {
            return false;
        }
        BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(stack, rat.level(), rat, rat.getId());
        if (model.isGui3d() || model.isCustomRenderer() || stack.hasFoil()) {
            return false;
        }
        pose.pushPose();
        model = net.neoforged.neoforge.client.ClientHooks.handleCameraTransforms(pose, model, context, leftHand);
        pose.translate(-0.5f, -0.5f, -0.5f);
        TextureAtlasSprite sprite = model.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        n.transform(0f, 0f, 1f, N);
        // the generated item's front face, 16 x 16 pixels, through the middle of its thickness
        for (int v = 0; v < 4; v++) {
            m.transformPosition(CORNERS[v][0], CORNERS[v][1], 0.5f, P);
            SPRITE[v * 5] = P.x;
            SPRITE[v * 5 + 1] = P.y;
            SPRITE[v * 5 + 2] = P.z;
            SPRITE[v * 5 + 3] = Mth.lerp(CORNERS[v][0], sprite.getU0(), sprite.getU1());
            SPRITE[v * 5 + 4] = Mth.lerp(CORNERS[v][1], sprite.getV1(), sprite.getV0());
        }
        pose.popPose();
        VertexConsumer buffer = buffers.getBuffer(ITEM_SPRITES);
        for (int v = 0; v < 4; v++) {
            buffer.addVertex(SPRITE[v * 5], SPRITE[v * 5 + 1], SPRITE[v * 5 + 2], 0xFFFFFFFF, SPRITE[v * 5 + 3], SPRITE[v * 5 + 4],
                    overlay, light, N.x, N.y, N.z);
        }
        if (HordeMesh.capturing != null) {
            HordeMesh.capturing.quad(true, SPRITE, N.x, N.y, N.z, false);
        }
        return true;
    }

    @Override
    public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay, int colour) {
        if (bone.isHidden()) {
            return;
        }
        for (GeoCube cube : bone.getCubes()) {
            if (far && HordeLod.isDetail(cube, distSqr)) {
                continue;
            }
            if (HordeLod.BENCH_NO_VERTS_FAR && distSqr > HordeLod.ANIM_FULL) {
                continue;
            }
            HordeLod.renderCube(poseStack, cube, buffer, packedLight, packedOverlay, colour, cull && HordeLod.sealed(cube, texture));
        }
    }
}
