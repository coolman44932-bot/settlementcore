package claude.settlementcore.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A class of {@link GameTest}s, registered under the namespace given (the mod's id): their tests' ids and their structures are its. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GameTestHolder {
    String value();
}
