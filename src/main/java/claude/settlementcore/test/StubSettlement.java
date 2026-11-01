package claude.settlementcore.test;

import claude.settlementcore.settlement.Settlement;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A {@link Settlement} made of what a test says of it, for the engine's own tests: the engine cannot place a block entity of
 * its own, and a settlement is about sixty methods. Each method a test answers is answered by name ({@link #answer}, {@link #is},
 * {@link #quiet}); a default method of the interface runs as written unless answered; any other call fails the test loudly, so
 * a stub is never asked something it was not told.
 */
public final class StubSettlement {

    private final Map<String, Function<Object[], Object>> answers = new HashMap<>();
    private final Settlement settlement;

    public StubSettlement() {
        InvocationHandler handler = (proxy, method, args) -> {
            Object[] a = args == null ? new Object[0] : args;
            Function<Object[], Object> answer = answers.get(method.getName());
            if (answer != null) {
                return answer.apply(a);
            }
            switch (method.getName()) {
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == a[0];
                }
                case "toString" -> {
                    return "StubSettlement";
                }
                default -> {
                }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, a);
            }
            throw new UnsupportedOperationException("the stub settlement was not told what " + method.getName() + " answers");
        };
        settlement = (Settlement) Proxy.newProxyInstance(Settlement.class.getClassLoader(), new Class<?>[]{Settlement.class}, handler);
    }

    /** What {@code method} answers, from the arguments it is called with (boxed). */
    public StubSettlement answer(String method, Function<Object[], Object> answer) {
        answers.put(method, answer);
        return this;
    }

    /** {@code method} always answers {@code value}. */
    public StubSettlement is(String method, Object value) {
        return answer(method, a -> value);
    }

    /** These methods do nothing (the ones that return nothing: setChanged, log). */
    public StubSettlement quiet(String... methods) {
        for (String m : methods) {
            answer(m, a -> null);
        }
        return this;
    }

    public Settlement settlement() {
        return settlement;
    }
}
