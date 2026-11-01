package claude.settlementcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * English for what the settlement says about things any mod may add: "a Horde Zombie", "an Elite Zombie", "3 Witches",
 * "2 Endermen". The alarm once said "The warren is attacked! a Horde Zombie in the tunnels: the horde rises to meet
 * them." (the user, 2026-09-27), and the Beast Pens "2 Cow", and a Watch-Post "3 Zombie".
 */
public final class Words {

    /** Names that are the same in the plural. */
    private static final List<String> SAME = List.of("sheep", "fish", "salmon", "cod", "deer", "moose", "bison", "bread", "kelp", "rice");
    /** Names ending in o that take -es. */
    private static final List<String> OES = List.of("potato", "tomato", "mango");

    /** "a Pig", "an Elite Zombie", "a Unicorn". */
    public static String a(String noun) {
        String s = noun.toLowerCase(Locale.ROOT);
        boolean vowel = !s.isEmpty() && "aeiou".indexOf(s.charAt(0)) >= 0
                && !s.startsWith("uni") && !s.startsWith("use") && !s.startsWith("eu") && !s.startsWith("one");
        return (vowel ? "an " : "a ") + noun;
    }

    /** "Pigs", "Witches", "Endermen", "Wolves", "Flies", "Sheep". */
    public static String plural(String noun) {
        String s = noun.toLowerCase(Locale.ROOT);
        for (String same : SAME) {
            if (s.endsWith(same)) {
                return noun;
            }
        }
        if (s.endsWith("man") && !s.endsWith("human") && !s.endsWith("shaman")) {
            return noun.substring(0, noun.length() - 2) + "en";
        }
        if (s.endsWith("wolf") || s.endsWith("elf")) {
            return noun.substring(0, noun.length() - 1) + "ves";
        }
        if (s.endsWith("mouse")) {
            return noun.substring(0, noun.length() - 4) + "ice";
        }
        for (String o : OES) {
            if (s.endsWith(o)) {
                return noun + "es";
            }
        }
        if (s.endsWith("ch") || s.endsWith("sh") || s.endsWith("s") || s.endsWith("x") || s.endsWith("z")) {
            return noun + "es";
        }
        if (s.endsWith("y") && s.length() > 1 && "aeiou".indexOf(s.charAt(s.length() - 2)) < 0) {
            return noun.substring(0, noun.length() - 1) + "ies";
        }
        return noun + "s";
    }

    /** "a Pig", "3 Pigs"; "1 Bread", "3 Bread". */
    public static String count(int n, String noun) {
        String s = noun.toLowerCase(Locale.ROOT);
        boolean mass = s.endsWith("bread") || s.endsWith("kelp") || s.endsWith("rice");
        return n == 1 && !mass ? a(noun) : n + " " + plural(noun);
    }

    /** "3 Zombies, a Witch and 2 Pillagers", in the map's order. */
    public static String list(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(count(v, k)));
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** The first letter up, as a sentence begins. */
    public static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private Words() {
    }
}
