package claude.settlementcore.test;

import claude.settlementcore.Core;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchListener;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.objectweb.asm.Type;
import org.slf4j.Logger;

import java.lang.annotation.ElementType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Registers a mod's {@link GameTest}s with 26.1's GameTest registries (2026-10-31, the move from 1.21.1). Since 1.21.5 a test is
 * three registry entries: its function (the static method), its instance (structure, timeout, attempts, sky) and an environment,
 * which is also its batch. This reads the annotations the tests were written with and makes those entries, so every race's
 * tests are written the way they always were: a race calls {@link #register} from its mod's constructor, and the engine does
 * for its own.
 *
 * <ul>
 * <li>A test's id is {@code <namespace>:<class>/<method>} in snake case (settlementcore:harness_game_tests/a_lost_light_update_is_set_going_again).
 * <li>A batch is an environment, {@code <namespace>:<batch>}, of the engine's own type ({@link BatchEnvironment}): the runner
 * starts one batch per environment (50 tests at most), and the environment runs the batch's {@link BeforeBatch} hooks as it
 * starts and its {@link AfterBatch} hooks the moment its last test is done.
 * <li>{@code -Ponly=a,b} (settlementcore.only): only the tests whose batch, method, snake-case name or class is named are
 * registered at all, to run a flaky one again and again; {@code -Ptestns=a,b} (settlementcore.testns): only those mods' tests.
 * </ul>
 *
 * Only where GameTests are on (a dev run or the GameTest server): a player's game registers none of it.
 */
public final class GameTests {

    private static final Logger LOG = LogUtils.getLogger();

    private static final DeferredRegister<MapCodec<? extends TestEnvironmentDefinition<?>>> ENVIRONMENT_TYPES =
            DeferredRegister.create(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Core.MODID);

    static {
        ENVIRONMENT_TYPES.register("batch", () -> BatchEnvironment.CODEC);
    }

    /** Each batch's hooks, by its environment's id. */
    private static final Map<Identifier, List<Method>> BEFORE = new LinkedHashMap<>();
    private static final Map<Identifier, List<Method>> AFTER = new LinkedHashMap<>();
    /** The runners already told about batches ending. */
    private static final Set<GameTestRunner> HOOKED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Field TICKER_RUNNER = runnerField();

    private static boolean engineRegistered;

    private GameTests() {
    }

    /** The engine's own part: its environment type. Settlement Core's constructor calls it, once. */
    public static void registerEngine(IEventBus modBus) {
        if (!GameTestHooks.isGametestEnabled() || engineRegistered) {
            return;
        }
        engineRegistered = true;
        ENVIRONMENT_TYPES.register(modBus);
    }

    /** Registers every {@link GameTest} in {@code mod}'s classes marked {@link GameTestHolder}, from its constructor. */
    public static void register(ModContainer mod, IEventBus modBus) {
        if (!GameTestHooks.isGametestEnabled()) {
            return;
        }
        Set<String> only = list(Core.MODID + ".only");
        Set<String> namespaces = list(Core.MODID + ".testns");
        if (!namespaces.isEmpty() && !namespaces.contains(mod.getModId())) {
            return;
        }
        List<Class<?>> holders = holders(mod);
        Map<String, DeferredRegister<Consumer<GameTestHelper>>> functions = new LinkedHashMap<>();
        List<Planned> planned = new ArrayList<>();
        for (Class<?> holder : holders) {
            String ns = holder.getAnnotation(GameTestHolder.class).value();
            DeferredRegister<Consumer<GameTestHelper>> fns = functions.computeIfAbsent(ns, n -> DeferredRegister.create(Registries.TEST_FUNCTION, n));
            List<Method> methods = new ArrayList<>(Arrays.asList(holder.getDeclaredMethods()));
            methods.sort(Comparator.comparing(Method::getName));
            for (Method m : methods) {
                hook(m, ns);
                GameTest t = m.getAnnotation(GameTest.class);
                if (t == null) {
                    continue;
                }
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || m.getParameterTypes()[0] != GameTestHelper.class) {
                    throw new IllegalStateException("a @GameTest must be static and take one GameTestHelper: " + holder.getName() + "#" + m.getName());
                }
                String path = snake(holder.getSimpleName()) + "/" + snake(m.getName());
                if (!only.isEmpty() && !only.contains(t.batch().toLowerCase(Locale.ROOT)) && !only.contains(m.getName().toLowerCase(Locale.ROOT))
                        && !only.contains(snake(m.getName())) && !only.contains(holder.getSimpleName().toLowerCase(Locale.ROOT))
                        && !only.contains(snake(holder.getSimpleName()))) {
                    continue;
                }
                m.setAccessible(true);
                fns.register(path, () -> helper -> invoke(m, helper));
                planned.add(new Planned(Identifier.fromNamespaceAndPath(ns, path), Identifier.fromNamespaceAndPath(ns, t.batch()), t));
            }
        }
        functions.values().forEach(r -> r.register(modBus));
        if (!only.isEmpty()) {
            LOG.info("{}.only={}: {} of {}'s tests registered", Core.MODID, only, planned.size(), mod.getModId());
        }
        modBus.addListener(RegisterGameTestsEvent.class, e -> {
            Map<Identifier, Holder<TestEnvironmentDefinition<?>>> envs = new LinkedHashMap<>();
            for (Planned p : planned) {
                Holder<TestEnvironmentDefinition<?>> env = envs.computeIfAbsent(p.batch, b -> e.registerEnvironment(b, new BatchEnvironment(b)));
                GameTest t = p.test;
                Identifier structure = Identifier.fromNamespaceAndPath(p.id.getNamespace(), t.template());
                TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(env, structure, t.timeoutTicks(), t.setupTicks(),
                        t.required(), Rotation.NONE, false, t.attempts(), t.requiredSuccesses(), t.skyAccess(), 0);
                e.registerTest(p.id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, p.id), data));
            }
        });
    }

    /** A comma-separated system property, lower-cased; empty if unset. */
    private static Set<String> list(String property) {
        return Arrays.stream(System.getProperty(property, "").split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private record Planned(Identifier id, Identifier batch, GameTest test) {
    }

    /** The classes of {@code mod}'s jar marked {@link GameTestHolder}, from the loader's scan (none are loaded until here). */
    private static List<Class<?>> holders(ModContainer mod) {
        Type marker = Type.getType(GameTestHolder.class);
        List<Class<?>> out = new ArrayList<>();
        mod.getModInfo().getOwningFile().getFile().getScanResult().getAnnotatedBy(GameTestHolder.class, ElementType.TYPE)
                .filter(a -> a.annotationType().equals(marker))
                .map(a -> a.clazz().getClassName())
                .sorted()
                .forEach(name -> {
                    try {
                        out.add(Class.forName(name, true, GameTests.class.getClassLoader()));
                    } catch (ClassNotFoundException ex) {
                        throw new IllegalStateException("cannot load GameTest holder " + name, ex);
                    }
                });
        return out;
    }

    private static void hook(Method m, String ns) {
        BeforeBatch before = m.getAnnotation(BeforeBatch.class);
        AfterBatch after = m.getAnnotation(AfterBatch.class);
        if (before == null && after == null) {
            return;
        }
        if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || m.getParameterTypes()[0] != ServerLevel.class) {
            throw new IllegalStateException("a batch hook must be static and take one ServerLevel: " + m.getDeclaringClass().getName() + "#" + m.getName());
        }
        m.setAccessible(true);
        if (before != null) {
            BEFORE.computeIfAbsent(Identifier.fromNamespaceAndPath(ns, before.batch()), b -> new ArrayList<>()).add(m);
        }
        if (after != null) {
            AFTER.computeIfAbsent(Identifier.fromNamespaceAndPath(ns, after.batch()), b -> new ArrayList<>()).add(m);
        }
    }

    private static void invoke(Method m, Object arg) {
        try {
            m.invoke(null, arg);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** aLostLightUpdateIsSetGoingAgain to a_lost_light_update_is_set_going_again. */
    static String snake(String camel) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && camel.charAt(i - 1) != '_') {
                    b.append('_');
                }
                b.append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static Field runnerField() {
        try {
            Field f = GameTestTicker.class.getDeclaredField("runner");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException ex) {
            LOG.error("cannot reach GameTestTicker.runner: @AfterBatch hooks will run late, at the next batch's start", ex);
            return null;
        }
    }

    private static GameTestRunner runner() {
        try {
            return TICKER_RUNNER == null ? null : (GameTestRunner) TICKER_RUNNER.get(GameTestTicker.SINGLETON);
        } catch (IllegalAccessException ex) {
            return null;
        }
    }

    static void runHooks(Map<Identifier, List<Method>> hooks, Identifier batch, ServerLevel level) {
        for (Method m : hooks.getOrDefault(batch, List.of())) {
            try {
                invoke(m, level);
            } catch (RuntimeException ex) {
                LOG.error("batch hook {}#{} for '{}' failed", m.getDeclaringClass().getSimpleName(), m.getName(), batch, ex);
            }
        }
    }

    /**
     * A batch, as an environment: it starts as the runner starts the batch (its structures placed, its tests not yet ticked),
     * and the runner is told to call it back as the batch's last test is done, before the forced chunks go: the vanilla
     * teardown only comes once the next batch's structures are placed.
     */
    public record BatchEnvironment(Identifier batch) implements TestEnvironmentDefinition<BatchEnvironment.Run> {

        public static final MapCodec<BatchEnvironment> CODEC = Identifier.CODEC.fieldOf("batch").xmap(BatchEnvironment::new, BatchEnvironment::batch);

        /** One run of the batch: whether its after-hooks have run. */
        public static final class Run {
            boolean ended;
        }

        @Override
        public Run setup(ServerLevel level) {
            Run run = new Run();
            GameTestRunner runner = runner();
            if (runner != null && HOOKED.add(runner)) {
                runner.addListener(new Ends(level));
            }
            CURRENT.put(level, new Current(batch, run));
            runHooks(BEFORE, batch, level);
            return run;
        }

        @Override
        public void teardown(ServerLevel level, Run run) {
            if (!run.ended) {
                run.ended = true;
                runHooks(AFTER, batch, level);
            }
        }

        @Override
        public MapCodec<BatchEnvironment> codec() {
            return CODEC;
        }
    }

    private record Current(Identifier batch, BatchEnvironment.Run run) {
    }

    private static final Map<ServerLevel, Current> CURRENT = new WeakHashMap<>();

    /** Calls the running batch's after-hooks the moment its last test is done (the runner's own batch listener). */
    private record Ends(ServerLevel level) implements GameTestBatchListener {
        @Override
        public void testBatchStarting(GameTestBatch batch) {
            TestWatchdog.batchStarting(level, batch);
        }

        @Override
        public void testBatchFinished(GameTestBatch batch) {
            Current c = CURRENT.get(level);
            if (c != null && !c.run.ended && batch.environment().unwrapKey().map(k -> k.identifier().equals(c.batch)).orElse(false)) {
                c.run.ended = true;
                runHooks(AFTER, c.batch, level);
            }
            TestWatchdog.batchFinished(level, batch);
        }
    }
}
