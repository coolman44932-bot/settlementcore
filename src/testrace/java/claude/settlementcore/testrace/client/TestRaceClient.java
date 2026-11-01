package claude.settlementcore.testrace.client;

import claude.settlementcore.client.HordeGeoModel;
import claude.settlementcore.client.HordeLod;
import claude.settlementcore.client.HordeRenderer;
import claude.settlementcore.testrace.Guard;
import claude.settlementcore.testrace.TRRegistry;
import claude.settlementcore.testrace.TestRace;
import claude.settlementcore.testrace.Worker;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * The test race's look: its two units drawn by the engine's crowd renderer (HordeRenderer: far ones throttled, simplified and kept),
 * from the placeholder models tools/gen_testrace_assets.py writes; the engine's caches cleared when resources reload, as every race does.
 */
@EventBusSubscriber(modid = TestRace.MODID, value = Dist.CLIENT)
public final class TestRaceClient {

    private TestRaceClient() {
    }

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(TRRegistry.WORKER.get(), WorkerRenderer::new);
        e.registerEntityRenderer(TRRegistry.GUARD.get(), GuardRenderer::new);
    }

    @SubscribeEvent
    static void reload(AddClientReloadListenersEvent e) {
        e.addListener(Identifier.fromNamespaceAndPath(TestRace.MODID, "horde_caches"), (ResourceManagerReloadListener) rm -> HordeLod.clearCaches());
    }

    static final class WorkerModel extends HordeGeoModel<Worker> {
        WorkerModel() {
            super(Identifier.fromNamespaceAndPath(TestRace.MODID, "worker"), true);
        }
    }

    static final class GuardModel extends HordeGeoModel<Guard> {
        GuardModel() {
            super(Identifier.fromNamespaceAndPath(TestRace.MODID, "guard"), true);
        }
    }

    static final class WorkerRenderer extends HordeRenderer<Worker, LivingEntityRenderState> {
        WorkerRenderer(EntityRendererProvider.Context ctx) {
            super(ctx, new WorkerModel());
            this.shadowRadius = 0.35f;
        }
    }

    static final class GuardRenderer extends HordeRenderer<Guard, LivingEntityRenderState> {
        GuardRenderer(EntityRendererProvider.Context ctx) {
            super(ctx, new GuardModel());
            this.shadowRadius = 0.45f;
        }
    }
}
