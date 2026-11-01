package claude.settlementcore.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code public static void (ServerLevel)} run as the batch named ends: once its last test is done, before the runner
 * un-forces the forced chunks and places the next batch.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AfterBatch {
    String batch();
}
