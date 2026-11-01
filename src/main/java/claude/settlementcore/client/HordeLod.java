package claude.settlementcore.client;

import claude.settlementcore.CoreConfig;
import claude.settlementcore.unit.GeoEvents;
import claude.settlementcore.unit.SettlerMob;
import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Drawing the horde cheaply. Profiled (FpsBench + JFR, 2026-09-24): with hundreds of rats in view the frame
 * goes on the CPU, in GeckoLib: about two thirds building every rat's vertices (every face of every cube,
 * with a new matrix per cube and a new vector per vertex) and a third working out every rat's animation,
 * every frame. For the horde's rats (and the Rat Ogre's faces):
 * <ul>
 *   <li><b>Hidden faces</b>: a face turned away from the camera is never built. A closed cube's far side
 *   is always behind its near side, so this changes nothing on screen; flat plates (ears) keep both sides,
 *   and a cube whose texture has see-through pixels keeps its far side, which shows through them.</li>
 *   <li><b>Far animation</b>: rats beyond 12 blocks work out their pose at most 30, 20 or 12 times a second
 *   (by distance) and hold it in between. The pose is exact whenever it is worked out: GeckoLib animates
 *   from each rat's clock, not frame by frame. On GeckoLib 5 working out a pose is two halves, the
 *   controllers' state (taken with the render state) and the bones' values from it (at draw time); a held
 *   frame skips both and puts back the bones' values as the last fresh frame left them.</li>
 *   <li><b>Far detail</b>: beyond 24 blocks, cubes under 3.5 cubic model pixels (buckles, blade and shield
 *   trim; smaller than a screen pixel out there) and the item in hand are not drawn; beyond 40 blocks, where
 *   a rat is some 18 pixels tall, neither are cubes under 12 (the ears, the thin tail).</li>
 * </ul>
 * Each can be switched off in the engine's client config (settlementcore-client.toml, [crowd_rendering]), and for A/B benchmark runs with
 * {@code -Pbenchflags=nocull,noanim,nodetail}.
 */
public final class HordeLod {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String BENCH_FLAGS = System.getProperty("settlementcore.fpsbench.flags", "");
    /** Bench-only experiments (-Pbenchflags): draw no rat beyond 12 blocks; draw no held items. */
    static final boolean BENCH_SKIP_FAR = BENCH_FLAGS.contains("skipfar");
    static final boolean BENCH_NO_ITEMS = BENCH_FLAGS.contains("noitems");
    /** Bench-only: far rats go through everything but emit no vertices / work out no animation. */
    static final boolean BENCH_NO_VERTS_FAR = BENCH_FLAGS.contains("novertsfar");
    static final boolean BENCH_NO_ANIM_FAR = BENCH_FLAGS.contains("noanimfar");
    /** Bench-only: kept models at every distance, held a second (the close-up then shows them full size). */
    static final boolean BENCH_MESH_NEAR = BENCH_FLAGS.contains("meshnear");

    /** Rats nearer than this work out their pose every frame; further off, at most POSE_RATES times a second. */
    public static final double ANIM_FULL = 12 * 12;
    private static final double[] POSE_BANDS = {24 * 24, 40 * 40};
    private static final long[] POSE_NANOS = {1_000_000_000L / 30, 1_000_000_000L / 20, 1_000_000_000L / 12};
    /** Beyond this, small cubes and held items are left out; beyond FAR_DETAIL, the ears and thin tail too. */
    public static final double DETAIL = 24 * 24;
    public static final double FAR_DETAIL = 40 * 40;
    /** A cube smaller than this (cubic model pixels) is a detail; smaller than FAR_DETAIL_VOLUME, a far detail. */
    public static final double DETAIL_VOLUME = 3.5;
    public static final double FAR_DETAIL_VOLUME = 12;
    /** Alpha below which the cutout shader discards a pixel (0.1). */
    private static final int HOLE_ALPHA = 26;
    /** A bone in a saved pose: rotation, translation, scale, hidden, children hidden, touched by the animation. */
    private static final int BONE = 12;

    private static final Map<Identifier, Map<GeoCube, Boolean>> SEALED = new HashMap<>();
    private static final Map<Identifier, NativeImage> IMAGES = new HashMap<>();
    private static final Set<Identifier> UNREADABLE = new java.util.HashSet<>();
    private static final Map<BakedGeoModel, GeoBone[]> BONES = new IdentityHashMap<>();

    // scratch space: rendering is single-threaded, and these never escape a call
    private static final Vector3f NORMAL = new Vector3f();
    private static final Vector4f POS = new Vector4f();
    private static final org.joml.Quaternionf TURN = new org.joml.Quaternionf();
    private static final float[] QUAD = new float[20];

    private HordeLod() {
    }

    public static boolean cullFaces() {
        return !BENCH_FLAGS.contains("nocull") && CoreConfig.cullHiddenFaces.getAsBoolean();
    }

    public static boolean throttleAnimations() {
        return !BENCH_FLAGS.contains("noanim") && CoreConfig.throttleFarAnimations.getAsBoolean();
    }

    public static boolean simplifyFar() {
        return !BENCH_FLAGS.contains("nodetail") && CoreConfig.simplifyFarUnits.getAsBoolean();
    }

    public static boolean reuseFar() {
        return !BENCH_FLAGS.contains("nomesh") && CoreConfig.reuseFarUnits.getAsBoolean();
    }

    /** Resources reloaded: textures, models and animation files may have changed. */
    public static void clearCaches() {
        SEALED.clear();
        IMAGES.values().forEach(NativeImage::close);
        IMAGES.clear();
        UNREADABLE.clear();
        BONES.clear();
        GeoEvents.clearScripts();
    }

    // ------------------------------------------------------------------ far animation

    /** Squared distance from the camera. */
    public static double distSqr(net.minecraft.world.entity.Entity e) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceToSqr(e.getX(), e.getY(), e.getZ());
    }

    /**
     * Whether this rat works out its pose this frame: always when near (or with throttling off); further off,
     * at most 30, 20 or 12 times a second by distance. A time budget, not a frame count: at a high frame rate
     * most frames reuse the pose, at a low one almost none do, so far rats never animate choppier than that.
     */
    public static boolean animateThisFrame(SettlerMob rat, double distSqr) {
        if (poseDue(rat, distSqr)) {
            rat.lodPoseAt = System.nanoTime();
            return true;
        }
        return false;
    }

    /** Whether this rat is due a fresh pose (animateThisFrame without taking it). */
    public static boolean poseDue(SettlerMob rat, double distSqr) {
        if (!throttleAnimations() || distSqr < ANIM_FULL || !saved(rat.lodPose)) {
            return true;
        }
        int band = distSqr < POSE_BANDS[0] ? 0 : distSqr < POSE_BANDS[1] ? 1 : 2;
        return System.nanoTime() - rat.lodPoseAt >= POSE_NANOS[band];
    }

    /**
     * The rat's pose store for {@code model}: its own if it fits the model's bones, else a new empty one
     * (which it keeps). Filled at draw time by {@link #savePose}; the render state carries it there.
     */
    public static float[] poseStore(SettlerMob rat, BakedGeoModel model) {
        int size = bones(model).length * BONE + 1;
        if (rat.lodPose == null || rat.lodPose.length != size) {
            rat.lodPose = new float[size];
        }
        return rat.lodPose;
    }

    /** Whether a pose store holds a pose (one has been drawn since it was made). */
    public static boolean saved(@Nullable float[] p) {
        return p != null && p.length > 0 && p[p.length - 1] != 0f;
    }

    /**
     * The bones' values for this frame as the animation left them, into {@code p}: called while GeckoLib is
     * working them out, so a bone the animation touched has its frame snapshot, and one it left alone has none.
     */
    public static void savePose(float[] p, GeoBone[] bones) {
        if (p.length != bones.length * BONE + 1) {
            return;
        }
        for (int i = 0; i < bones.length; i++) {
            BoneSnapshot s = bones[i].frameSnapshot;
            int o = i * BONE;
            if (s == null) {
                p[o + 11] = 0f;
                continue;
            }
            p[o] = s.getRotX();
            p[o + 1] = s.getRotY();
            p[o + 2] = s.getRotZ();
            p[o + 3] = s.getTranslateX();
            p[o + 4] = s.getTranslateY();
            p[o + 5] = s.getTranslateZ();
            p[o + 6] = s.getScaleX();
            p[o + 7] = s.getScaleY();
            p[o + 8] = s.getScaleZ();
            p[o + 9] = s.isHidden() ? 1f : 0f;
            p[o + 10] = s.areChildrenHidden() ? 1f : 0f;
            p[o + 11] = 1f;
        }
        p[p.length - 1] = 1f;
    }

    /** Put a saved pose back on this frame's snapshots. False if there is none, or it doesn't fit these bones. */
    public static boolean restorePose(@Nullable float[] p, GeoBone[] bones, BoneSnapshots snapshots) {
        if (!saved(p) || p.length != bones.length * BONE + 1) {
            return false;
        }
        for (int i = 0; i < bones.length; i++) {
            int o = i * BONE;
            if (p[o + 11] == 0f) {
                continue;
            }
            snapshots.get(bones[i])
                    .setRotation(p[o], p[o + 1], p[o + 2])
                    .setTranslation(p[o + 3], p[o + 4], p[o + 5])
                    .setScale(p[o + 6], p[o + 7], p[o + 8])
                    .skipRender(p[o + 9] != 0f)
                    .skipChildrenRender(p[o + 10] != 0f);
        }
        return true;
    }

    /** Every bone of a model as a stable array (made once per model). */
    public static GeoBone[] bones(BakedGeoModel model) {
        GeoBone[] b = BONES.get(model);
        if (b == null) {
            b = model.boneLookup().get().values().toArray(new GeoBone[0]);
            BONES.put(model, b);
        }
        return b;
    }

    // ------------------------------------------------------------------ far detail

    /** Whether a cube is left out of a rat this far off (squared distance; only called when far detail is on). */
    public static boolean isDetail(GeoCube cube, double distSqr) {
        double v = cube.size().x() * cube.size().y() * cube.size().z();
        return v < (distSqr > FAR_DETAIL ? FAR_DETAIL_VOLUME : DETAIL_VOLUME);
    }

    // ------------------------------------------------------------------ hidden faces

    /**
     * Whether the far side of {@code cube} can never be seen: it is a closed box (no flat side) and none of
     * its faces has a see-through pixel in {@code texture}.
     */
    public static boolean sealed(GeoCube cube, Identifier texture) {
        Map<GeoCube, Boolean> byCube = SEALED.computeIfAbsent(texture, t -> new IdentityHashMap<>());
        Boolean s = byCube.get(cube);
        if (s == null) {
            s = computeSealed(cube, texture);
            byCube.put(cube, s);
        }
        return s;
    }

    private static boolean computeSealed(GeoCube cube, Identifier texture) {
        if (cube.size().x() == 0 || cube.size().y() == 0 || cube.size().z() == 0) {
            return false;                                        // a flat plate: seen from both sides
        }
        NativeImage img = image(texture);
        if (img == null) {
            return false;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) {
                continue;
            }
            float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
            for (GeoVertex v : quad.vertices()) {
                u0 = Math.min(u0, v.texU());
                u1 = Math.max(u1, v.texU());
                v0 = Math.min(v0, v.texV());
                v1 = Math.max(v1, v.texV());
            }
            int x0 = Math.max(0, (int) Math.floor(u0 * w));
            int x1 = Math.min(w, (int) Math.ceil(u1 * w));
            int y0 = Math.max(0, (int) Math.floor(v0 * h));
            int y1 = Math.min(h, (int) Math.ceil(v1 * h));
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    if ((img.getPixel(x, y) >>> 24 & 0xFF) < HOLE_ALPHA) {
                        return false;                            // a hole: the far side shows through it
                    }
                }
            }
        }
        return true;
    }

    private static NativeImage image(Identifier texture) {
        NativeImage img = IMAGES.get(texture);
        if (img != null || UNREADABLE.contains(texture)) {
            return img;
        }
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(texture)) {
            img = NativeImage.read(in);
            IMAGES.put(texture, img);
        } catch (Exception e) {
            LOG.warn("HordeLod: cannot read {} to find see-through faces; its cubes keep both sides", texture);
            UNREADABLE.add(texture);
        }
        return img;
    }

    /**
     * GeckoLib's GeoCube.render without the waste: no matrix, quaternion or vector allocated per cube, quad or
     * vertex; an unrotated cube (most of them) skips the pivot-rotate-pivot that cancels out; only flat plates
     * get GeckoLib's flat-cube normal fix; and faces turned away from the camera are left out when the cube is
     * {@link #sealed}. Vertices are in camera-relative world space here (the view rotation is on the
     * model-view matrix), so a face points away from the camera when its normal points the same way as its
     * position. With {@code keep}, every face (the turned-away ones too) is also kept in it (HordeMesh).
     * Leaves the pose stack as it found it.
     */
    public static void renderCube(PoseStack poseStack, GeoCube cube, VertexConsumer buffer, int packedLight, int packedOverlay,
                                  int colour, boolean cullBack, @Nullable HordeMesh keep) {
        net.minecraft.world.phys.Vec3 rot = cube.rotation();
        boolean rotated = rot.x != 0 || rot.y != 0 || rot.z != 0;
        if (rotated) {
            // GeckoLib's order: about the cube's pivot, Z then Y then X
            poseStack.pushPose();
            cube.translateToPivotPoint(poseStack);
            poseStack.mulPose(TURN.rotationZ((float) rot.z));
            poseStack.mulPose(TURN.rotationY((float) rot.y));
            poseStack.mulPose(TURN.rotationX((float) rot.x));
            cube.translateAwayFromPivotPoint(poseStack);
        }
        net.minecraft.world.phys.Vec3 size = cube.size();
        boolean flat = size.x == 0 || size.y == 0 || size.z == 0;
        Matrix3f normalMatrix = poseStack.last().normal();
        Matrix4f pose = poseStack.last().pose();
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) {
                continue;
            }
            normalMatrix.transform(quad.normalX(), quad.normalY(), quad.normalZ(), NORMAL);
            if (flat) {
                RenderUtil.fixInvertedFlatCube(cube, NORMAL);
            }
            GeoVertex[] verts = quad.vertices();
            if (keep != null && verts.length == 4) {
                // kept for the frames between poses (HordeMesh): every face, the turned-away ones too
                for (int v = 0; v < 4; v++) {
                    pose.transform(verts[v].posX(), verts[v].posY(), verts[v].posZ(), 1f, POS);
                    QUAD[v * 5] = POS.x;
                    QUAD[v * 5 + 1] = POS.y;
                    QUAD[v * 5 + 2] = POS.z;
                    QUAD[v * 5 + 3] = verts[v].texU();
                    QUAD[v * 5 + 4] = verts[v].texV();
                }
                keep.quad(false, QUAD, NORMAL.x, NORMAL.y, NORMAL.z, cullBack);
                if (cullBack && NORMAL.x * QUAD[0] + NORMAL.y * QUAD[1] + NORMAL.z * QUAD[2] > 0f) {
                    continue;
                }
                for (int v = 0; v < 4; v++) {
                    buffer.addVertex(QUAD[v * 5], QUAD[v * 5 + 1], QUAD[v * 5 + 2], colour, QUAD[v * 5 + 3], QUAD[v * 5 + 4],
                            packedOverlay, packedLight, NORMAL.x, NORMAL.y, NORMAL.z);
                }
                continue;
            }
            if (cullBack) {
                GeoVertex v0 = verts[0];
                pose.transform(v0.posX(), v0.posY(), v0.posZ(), 1f, POS);
                if (NORMAL.x * POS.x + NORMAL.y * POS.y + NORMAL.z * POS.z > 0f) {
                    continue;                                    // facing away: the near side hides it
                }
            }
            for (GeoVertex v : verts) {
                pose.transform(v.posX(), v.posY(), v.posZ(), 1f, POS);
                buffer.addVertex(POS.x, POS.y, POS.z, colour, v.texU(), v.texV(), packedOverlay, packedLight, NORMAL.x, NORMAL.y, NORMAL.z);
            }
        }
        if (rotated) {
            poseStack.popPose();
        }
    }
}
