package claude.settlementcore.client;

import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.CoreConfig;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.util.RenderUtil;

import java.io.InputStream;
import java.util.Collection;
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
 *   from each rat's clock, not frame by frame.</li>
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

    private static final Map<Identifier, Map<GeoCube, Boolean>> SEALED = new HashMap<>();
    private static final Map<Identifier, NativeImage> IMAGES = new HashMap<>();
    private static final Set<Identifier> UNREADABLE = new java.util.HashSet<>();

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

    /** Resources reloaded: textures and models may have changed. */
    public static void clearCaches() {
        SEALED.clear();
        IMAGES.values().forEach(NativeImage::close);
        IMAGES.clear();
        UNREADABLE.clear();
    }

    // ------------------------------------------------------------------ far animation

    /** Squared distance from the camera. */
    public static double distSqr(net.minecraft.world.entity.Entity e) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(e.getX(), e.getY(), e.getZ());
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
        if (!throttleAnimations() || distSqr < ANIM_FULL || rat.lodPose == null) {
            return true;
        }
        int band = distSqr < POSE_BANDS[0] ? 0 : distSqr < POSE_BANDS[1] ? 1 : 2;
        return System.nanoTime() - rat.lodPoseAt >= POSE_NANOS[band];
    }

    /** The bones' pose (rotation, position, scale, hidden: 10 floats a bone) into {@code into} (reused if it fits). */
    public static float[] savePose(float[] into, GeoBone[] bones) {
        float[] p = into != null && into.length == bones.length * 10 ? into : new float[bones.length * 10];
        for (int i = 0; i < bones.length; i++) {
            GeoBone b = bones[i];
            int o = i * 10;
            p[o] = b.getRotX();
            p[o + 1] = b.getRotY();
            p[o + 2] = b.getRotZ();
            p[o + 3] = b.getPosX();
            p[o + 4] = b.getPosY();
            p[o + 5] = b.getPosZ();
            p[o + 6] = b.getScaleX();
            p[o + 7] = b.getScaleY();
            p[o + 8] = b.getScaleZ();
            p[o + 9] = b.isHidden() ? 1f : 0f;
        }
        return p;
    }

    /** Put a saved pose back on the (shared) bones. False if it doesn't fit these bones. */
    public static boolean restorePose(float[] p, GeoBone[] bones) {
        if (p == null || p.length != bones.length * 10) {
            return false;
        }
        for (int i = 0; i < bones.length; i++) {
            GeoBone b = bones[i];
            int o = i * 10;
            b.setRotX(p[o]);
            b.setRotY(p[o + 1]);
            b.setRotZ(p[o + 2]);
            b.setPosX(p[o + 3]);
            b.setPosY(p[o + 4]);
            b.setPosZ(p[o + 5]);
            b.setScaleX(p[o + 6]);
            b.setScaleY(p[o + 7]);
            b.setScaleZ(p[o + 8]);
            b.setHidden(p[o + 9] != 0f);
        }
        return true;
    }

    /** The registered bones as a stable array (rebuilt only when the set changes). */
    public static GeoBone[] boneArray(Collection<GeoBone> bones, GeoBone[] cached) {
        if (cached != null && cached.length == bones.size()) {
            return cached;
        }
        return bones.toArray(new GeoBone[0]);
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
                    if ((img.getPixelRGBA(x, y) >>> 24 & 0xFF) < HOLE_ALPHA) {
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
     * GeckoLib's renderCube without the waste: no matrix, quaternion or vector allocated per cube, quad or
     * vertex; an unrotated cube (most of them) skips the pivot-rotate-pivot that cancels out; only flat plates
     * get GeckoLib's flat-cube normal fix; and faces turned away from the camera are left out when the cube is
     * {@link #sealed}. Vertices are in camera-relative world space here (the view rotation is on the
     * model-view matrix), so a face points away from the camera when its normal points the same way as its
     * position. Leaves the pose stack as it found it.
     */
    public static void renderCube(PoseStack poseStack, GeoCube cube, VertexConsumer buffer, int packedLight, int packedOverlay,
                                  int colour, boolean cullBack) {
        net.minecraft.world.phys.Vec3 rot = cube.rotation();
        boolean rotated = rot.x != 0 || rot.y != 0 || rot.z != 0;
        if (rotated) {
            // GeckoLib's order: about the cube's pivot, Z then Y then X
            poseStack.pushPose();
            RenderUtil.translateToPivotPoint(poseStack, cube);
            poseStack.mulPose(TURN.rotationZ((float) rot.z));
            poseStack.mulPose(TURN.rotationY((float) rot.y));
            poseStack.mulPose(TURN.rotationX((float) rot.x));
            RenderUtil.translateAwayFromPivotPoint(poseStack, cube);
        }
        net.minecraft.world.phys.Vec3 size = cube.size();
        boolean flat = size.x == 0 || size.y == 0 || size.z == 0;
        Matrix3f normalMatrix = poseStack.last().normal();
        Matrix4f pose = poseStack.last().pose();
        HordeMesh keep = HordeMesh.capturing;
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) {
                continue;
            }
            normalMatrix.transform(quad.normal(), NORMAL);
            if (flat) {
                RenderUtil.fixInvertedFlatCube(cube, NORMAL);
            }
            GeoVertex[] verts = quad.vertices();
            if (keep != null && verts.length == 4) {
                // kept for the frames between poses (HordeMesh): every face, the turned-away ones too
                for (int v = 0; v < 4; v++) {
                    Vector3f p = verts[v].position();
                    pose.transform(p.x(), p.y(), p.z(), 1f, POS);
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
                Vector3f p = verts[0].position();
                pose.transform(p.x(), p.y(), p.z(), 1f, POS);
                if (NORMAL.x * POS.x + NORMAL.y * POS.y + NORMAL.z * POS.z > 0f) {
                    continue;                                    // facing away: the near side hides it
                }
            }
            for (GeoVertex v : verts) {
                Vector3f p = v.position();
                pose.transform(p.x(), p.y(), p.z(), 1f, POS);
                buffer.addVertex(POS.x, POS.y, POS.z, colour, v.texU(), v.texV(), packedOverlay, packedLight, NORMAL.x, NORMAL.y, NORMAL.z);
            }
        }
        if (rotated) {
            poseStack.popPose();
        }
    }
}
