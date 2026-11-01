package claude.settlementcore.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * A far rat's finished model, kept and drawn again (user, 2026-09-25: "do the first 2", the second being
 * to stop rebuilding far rats' models from scratch every frame).
 *
 * <p>Measured (FpsBench, 600 rats in view, 2026-09-25): rats beyond 12 blocks cost some 5.6 ms of a 6.8 ms
 * frame, about half of it their vertices and half GeckoLib's own work per rat (render events, layers,
 * walking the bone tree with a matrix per bone), even on the frames their pose is only held (HordeLod
 * throttles far animation to 12-30 poses a second). So when a far rat works out a fresh pose, every face
 * GeckoLib builds for it (and its held item, as a flat sprite) is kept, in the model's own space; on the
 * frames in between it is drawn straight from that, under wherever the rat stands and faces now, without
 * GeckoLib at all. Faces turned away are still left out, tested against the camera as it is now; light,
 * the hurt flash and colour are the rat's own now.
 *
 * <p>Kept in the model's space means before the body turns: GeckoLib stands a model up as
 * scale, then turn about the vertical by the body's heading, then a hundredth of a block up, and the
 * turn commutes with both, so a rat that has turned since is drawn with the difference added.
 */
final class HordeMesh {

    // a quad: 4 corners of x, y, z, u, v, then its normal x, y, z and whether it may be left out facing away
    private static final int QUAD = 4 * 5 + 4;

    /** The rat's body (its own texture). */
    float[] body = new float[QUAD * 64];
    int bodyQuads;
    /** Its held item as a flat sprite (the block atlas). */
    float[] item = new float[QUAD * 2];
    int itemQuads;
    @Nullable Identifier texture;
    /** From the entity's spot to the model's space, when kept (scale, body turn, lift). */
    final Matrix4f root = new Matrix4f();
    /** Its turn alone (the root has the model's scale in it, and normals must stay unit length). */
    private final Matrix3f rootTurn = new Matrix3f();
    float bodyYaw;
    boolean ready;
    long keptAt;

    // capture: the model root's inverse, to put each face (built camera-relative) in the model's space
    private final Matrix4f toModel = new Matrix4f();
    private final Matrix3f toModelNormal = new Matrix3f();
    private boolean rooted;

    /** The mesh being kept while GeckoLib draws a rat, if any (rendering is single-threaded). */
    @Nullable static HordeMesh capturing;

    private static final Vector3f P = new Vector3f();
    private static final Vector3f N = new Vector3f();
    private static final Matrix4f M = new Matrix4f();
    private static final Matrix3f MN = new Matrix3f();

    void begin() {
        bodyQuads = 0;
        itemQuads = 0;
        rooted = false;
        ready = false;
    }

    /** The model root (the pose at the top of GeckoLib's bone tree) and the entity's spot it stands on. */
    void root(PoseStack.Pose modelRoot, Matrix4f entitySpot, float bodyYaw) {
        if (rooted) {
            return;
        }
        rooted = true;
        toModel.set(modelRoot.pose()).invert();
        toModelNormal.set(modelRoot.normal()).invert();
        root.set(entitySpot).invert().mul(modelRoot.pose());
        root.getScale(P);
        rootTurn.set(root).scale(1f / P.x, 1f / P.y, 1f / P.z);    // unit columns: the turn without the scale
        this.bodyYaw = bodyYaw;
    }

    boolean rooted() {
        return rooted;
    }

    /** Keep a quad as it was just built: {@code xyzuv} 4 corners of camera-relative x, y, z and u, v; its normal likewise. */
    void quad(boolean onItem, float[] xyzuv, float nx, float ny, float nz, boolean mayCull) {
        float[] a;
        int o;
        if (onItem) {
            item = grow(item, itemQuads);
            a = item;
            o = itemQuads++ * QUAD;
        } else {
            body = grow(body, bodyQuads);
            a = body;
            o = bodyQuads++ * QUAD;
        }
        for (int v = 0; v < 4; v++) {
            toModel.transformPosition(xyzuv[v * 5], xyzuv[v * 5 + 1], xyzuv[v * 5 + 2], P);
            a[o++] = P.x;
            a[o++] = P.y;
            a[o++] = P.z;
            a[o++] = xyzuv[v * 5 + 3];
            a[o++] = xyzuv[v * 5 + 4];
        }
        toModelNormal.transform(nx, ny, nz, N);
        N.normalize();
        a[o++] = N.x;
        a[o++] = N.y;
        a[o++] = N.z;
        a[o] = mayCull ? 1f : 0f;
    }

    private static float[] grow(float[] a, int quads) {
        return (quads + 1) * QUAD <= a.length ? a : Arrays.copyOf(a, a.length * 2);
    }

    void done() {
        ready = rooted && bodyQuads > 0;
        keptAt = System.nanoTime();
    }

    /**
     * Place it where the rat stands now, for {@link #drawBody} and {@link #drawItem}: {@code spot} is the
     * pose the renderer was handed (the entity's spot, camera-relative), {@code bodyYawNow} its heading now.
     */
    void place(PoseStack spot, float bodyYawNow) {
        // GeckoLib turns the model by (180 - heading) about the vertical: add how far it has turned since
        float turn = (float) Math.toRadians(bodyYaw - bodyYawNow);
        M.set(spot.last().pose()).mul(root).rotateY(turn);
        MN.set(spot.last().normal()).mul(rootTurn).rotateY(turn);
    }

    void drawBody(VertexConsumer buffer, int light, int overlay, int colour, boolean cull) {
        emit(body, bodyQuads, buffer, light, overlay, colour, cull);
    }

    /** Its held item, if it had one showing (ask for the item's buffer only after the body is drawn). */
    void drawItem(VertexConsumer buffer, int light, int overlay, int colour) {
        emit(item, itemQuads, buffer, light, overlay, colour, false);
    }

    boolean hasItem() {
        return itemQuads > 0;
    }

    private static void emit(float[] a, int quads, VertexConsumer buffer, int light, int overlay, int colour, boolean cull) {
        for (int q = 0; q < quads; q++) {
            int o = q * QUAD;
            MN.transform(a[o + 20], a[o + 21], a[o + 22], N);
            if (cull && a[o + 23] != 0f) {
                M.transformPosition(a[o], a[o + 1], a[o + 2], P);
                if (N.x * P.x + N.y * P.y + N.z * P.z > 0f) {
                    continue;                                    // facing away: its near side hides it
                }
            }
            for (int v = 0; v < 4; v++) {
                int i = o + v * 5;
                M.transformPosition(a[i], a[i + 1], a[i + 2], P);
                buffer.addVertex(P.x, P.y, P.z, colour, a[i + 3], a[i + 4], overlay, light, N.x, N.y, N.z);
            }
        }
    }
}
