package claude.settlementcore.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A {@code public static void (ServerLevel)} run as the batch named starts, before its tests' first tick. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BeforeBatch {
    String batch();
}
