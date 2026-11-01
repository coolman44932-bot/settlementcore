package claude.settlementcore.client;

import claude.settlementcore.Core;
import claude.settlementcore.room.Reshape;
import claude.settlementcore.room.Siting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The outline of a plan held in the hand (docs/plans/greatplan-first-spawning.md, section B item 9): where the building would stand and
 * why it would be refused. Race-free: a race's client calls {@link #show} each tick the player holds one of its plans, with
 * {@link Siting#preview} of the ground the player aims at (on the client's own level), and the engine draws it, in the world, after the
 * translucent blocks:
 * <ul>
 *   <li>the building's box, green where the site would be taken and red where it would be refused;</li>
 *   <li>the door's cell in yellow and its landing, the pad's flat 3 by 2 in front of it;</li>
 *   <li>the plinth, column by column, from the ground up to the floor, shaded by how deep it goes ({@link OutlineShade}: blue for a thin
 *   one, yellow at {@link Siting#SKIRT_MAX}) and red past it;</li>
 *   <li>the refusal's spot, a red box with a beam over it, and the refusal's words are the race's to say (the preview has them).</li>
 * </ul>
 * What it draws is shown for a moment after the last {@link #show}, so a held plan that is put away takes its outline with it, and
 * only what is within {@link #RANGE} blocks of the camera is drawn.
 *
 * <p>Lines on 26.1 are widened on screen, so a line that runs behind the camera is cut at its near end first, as vanilla's own
 * debug lines are (DrawableGizmoPrimitives).
 */
@EventBusSubscriber(modid = Core.MODID, value = Dist.CLIENT)
public final class PlanOutline {

    /** How long, in milliseconds, an outline stays after the last {@link #show}. */
    private static final long LINGER = 150;
    /** The farthest column drawn from the camera, in blocks. */
    public static final double RANGE = 64;
    /** How far in front of the eye (view space) a line is cut. */
    private static final float NEAR = -0.05f;

    @Nullable
    private static Siting.Preview shown;
    private static long shownAt;

    // scratch space: rendering is single-threaded
    private static final Vector4f A = new Vector4f();
    private static final Vector4f B = new Vector4f();
    private static final Vector4f VA = new Vector4f();
    private static final Vector4f VB = new Vector4f();

    private PlanOutline() {
    }

    /** Draw {@code preview} from now on, until {@link #LINGER} passes without another. */
    public static void show(Siting.Preview preview) {
        shown = preview;
        shownAt = Util.getMillis();
    }

    /** Draw nothing. */
    public static void clear() {
        shown = null;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent.AfterTranslucentBlocks e) {
        Siting.Preview preview = shown;
        if (preview == null) {
            return;
        }
        if (Util.getMillis() - shownAt > LINGER) {
            shown = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 cam = e.getLevelRenderState().cameraRenderState.pos;
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Lines lines = new Lines(e.getPoseStack().last(), buffers.getBuffer(RenderTypes.lines()), e.getModelViewMatrix(), cam,
                mc.gameRenderer.getGameRenderState().windowRenderState.appropriateLineWidth);
        draw(lines, preview, cam);
        buffers.endBatch(RenderTypes.lines());
    }

    /** The boxes of {@code preview}. */
    private static void draw(Lines lines, Siting.Preview preview, Vec3 cam) {
        boolean refused = preview.refusal() != null;
        lines.box(AABB.of(preview.box()), refused ? 1f : 0.35f, refused ? 0.25f : 1f, refused ? 0.25f : 0.3f, 1f);
        BlockPos door = preview.door();
        lines.box(new AABB(door).inflate(0.02), 1f, 0.9f, 0.2f, 1f);
        Reshape.Rect landing = preview.landing();
        int floor = preview.floor();
        lines.box(new AABB(landing.x0(), floor + 1, landing.z0(), landing.x1() + 1, floor + 1.05, landing.z1() + 1), 1f, 0.9f, 0.2f, 0.7f);
        double range = RANGE * RANGE;
        for (Siting.Column c : preview.columns()) {
            float[] shade = OutlineShade.of(c.depth());
            if (shade == null || cam.distanceToSqr(c.x() + 0.5, floor, c.z() + 0.5) > range) {
                continue;
            }
            lines.box(new AABB(c.x() + 0.02, c.ground() + 1, c.z() + 0.02, c.x() + 0.98, floor, c.z() + 0.98), shade[0], shade[1], shade[2], shade[3]);
        }
        Siting.Refusal no = preview.refusal();
        if (no != null) {
            BlockPos spot = no.spot();
            lines.box(new AABB(spot).inflate(0.1), 1f, 0.1f, 0.1f, 1f);
            lines.box(new AABB(spot.getX() + 0.4, spot.getY() + 1, spot.getZ() + 0.4, spot.getX() + 0.6, spot.getY() + 9, spot.getZ() + 0.6),
                    1f, 0.1f, 0.1f, 1f);
        }
    }

    /**
     * Line boxes into a lines buffer, in camera-relative world space (worked out in doubles, so they hold still far from the world's
     * middle), each edge cut where it passes behind the eye.
     */
    private record Lines(PoseStack.Pose pose, VertexConsumer buffer, Matrix4fc modelView, Vec3 cam, float width) {

        /** The twelve edges of {@code box} (vanilla's old LevelRenderer.renderLineBox). */
        void box(AABB box, float r, float g, float b, float a) {
            int argb = ARGB.colorFromFloat(a, r, g, b);
            float x0 = (float) (box.minX - cam.x), y0 = (float) (box.minY - cam.y), z0 = (float) (box.minZ - cam.z);
            float x1 = (float) (box.maxX - cam.x), y1 = (float) (box.maxY - cam.y), z1 = (float) (box.maxZ - cam.z);
            line(x0, y0, z0, x1, y0, z0, argb);
            line(x0, y0, z0, x0, y1, z0, argb);
            line(x0, y0, z0, x0, y0, z1, argb);
            line(x1, y0, z0, x1, y1, z0, argb);
            line(x1, y1, z0, x0, y1, z0, argb);
            line(x0, y1, z0, x0, y1, z1, argb);
            line(x0, y1, z1, x0, y0, z1, argb);
            line(x0, y0, z1, x1, y0, z1, argb);
            line(x1, y0, z1, x1, y0, z0, argb);
            line(x0, y1, z1, x1, y1, z1, argb);
            line(x1, y0, z1, x1, y1, z1, argb);
            line(x1, y1, z0, x1, y1, z1, argb);
        }

        private void line(float ax, float ay, float az, float bx, float by, float bz, int argb) {
            A.set(ax, ay, az, 1f);
            B.set(bx, by, bz, 1f);
            A.mul(modelView, VA);
            B.mul(modelView, VB);
            boolean aBehind = VA.z > NEAR;
            boolean bBehind = VB.z > NEAR;
            if (aBehind && bBehind) {
                return;
            }
            if (aBehind || bBehind) {
                float t = (NEAR - VA.z) / (VB.z - VA.z);
                if (aBehind) {
                    A.lerp(B, t);
                } else {
                    B.set(A.x + (B.x - A.x) * t, A.y + (B.y - A.y) * t, A.z + (B.z - A.z) * t, 1f);
                }
            }
            float nx = B.x - A.x, ny = B.y - A.y, nz = B.z - A.z;
            buffer.addVertex(pose, A.x, A.y, A.z).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
            buffer.addVertex(pose, B.x, B.y, B.z).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(width);
        }
    }
}
