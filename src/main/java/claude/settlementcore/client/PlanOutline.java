package claude.settlementcore.client;

import claude.settlementcore.Core;
import claude.settlementcore.room.Reshape;
import claude.settlementcore.room.Siting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

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
 */
@EventBusSubscriber(modid = Core.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PlanOutline {

    /** How long, in milliseconds, an outline stays after the last {@link #show}. */
    private static final long LINGER = 150;
    /** The farthest column drawn from the camera, in blocks. */
    public static final double RANGE = 64;

    @Nullable
    private static Siting.Preview shown;
    private static long shownAt;

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
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
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
        PoseStack pose = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        draw(pose, lines, preview, cam);
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** The boxes of {@code preview}, drawn into {@code lines} with the pose already translated to the camera's. */
    private static void draw(PoseStack pose, VertexConsumer lines, Siting.Preview preview, Vec3 cam) {
        boolean refused = preview.refusal() != null;
        LevelRenderer.renderLineBox(pose, lines, AABB.of(preview.box()), refused ? 1f : 0.35f, refused ? 0.25f : 1f, refused ? 0.25f : 0.3f, 1f);
        BlockPos door = preview.door();
        LevelRenderer.renderLineBox(pose, lines, new AABB(door).inflate(0.02), 1f, 0.9f, 0.2f, 1f);
        Reshape.Rect landing = preview.landing();
        int floor = preview.floor();
        LevelRenderer.renderLineBox(pose, lines, new AABB(landing.x0(), floor + 1, landing.z0(), landing.x1() + 1, floor + 1.05, landing.z1() + 1),
                1f, 0.9f, 0.2f, 0.7f);
        double range = RANGE * RANGE;
        for (Siting.Column c : preview.columns()) {
            float[] shade = OutlineShade.of(c.depth());
            if (shade == null || cam.distanceToSqr(c.x() + 0.5, floor, c.z() + 0.5) > range) {
                continue;
            }
            LevelRenderer.renderLineBox(pose, lines, new AABB(c.x() + 0.02, c.ground() + 1, c.z() + 0.02, c.x() + 0.98, floor, c.z() + 0.98),
                    shade[0], shade[1], shade[2], shade[3]);
        }
        Siting.Refusal no = preview.refusal();
        if (no != null) {
            BlockPos spot = no.spot();
            LevelRenderer.renderLineBox(pose, lines, new AABB(spot).inflate(0.1), 1f, 0.1f, 0.1f, 1f);
            LevelRenderer.renderLineBox(pose, lines, new AABB(spot.getX() + 0.4, spot.getY() + 1, spot.getZ() + 0.4, spot.getX() + 0.6, spot.getY() + 9,
                    spot.getZ() + 0.6), 1f, 0.1f, 0.1f, 1f);
        }
    }
}
