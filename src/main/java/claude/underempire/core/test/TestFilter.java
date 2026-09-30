package claude.underempire.core.test;

import claude.underempire.core.Core;
import com.mojang.logging.LogUtils;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ./gradlew runGameTestServer -Ponly=works,deeps : run just those batches or tests (by batch name or test
 * name), to run a flaky one again and again without the whole suite. The GameTestServer copies the
 * registered tests into its own list and makes its batches from that list only after this event, so
 * the tests not named are taken out of it here.
 */
@EventBusSubscriber(modid = Core.MODID)
final class TestFilter {

    @SubscribeEvent
    @SuppressWarnings("unchecked")
    static void aboutToStart(ServerAboutToStartEvent e) {
        String only = System.getProperty("underempire.only", "");
        if (only.isBlank() || !(e.getServer() instanceof GameTestServer server)) {
            return;
        }
        Set<String> names = Arrays.stream(only.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        try {
            var field = GameTestServer.class.getDeclaredField("testFunctions");
            field.setAccessible(true);
            List<TestFunction> tests = (List<TestFunction>) field.get(server);
            tests.removeIf(t -> !names.contains(t.batchName().toLowerCase(Locale.ROOT)) && !names.contains(t.testName().toLowerCase(Locale.ROOT)));
            LogUtils.getLogger().info("underempire.only={}: running {} tests", only, tests.size());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("underempire.only: cannot filter the GameTestServer's tests", ex);
        }
    }

    private TestFilter() {
    }
}
