package claude.settlementcore.work;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines an owner who was away still owes hearing (Works#tell: a building came down, a load paid a site back, one was raised, with the owner off the server): kept
 * oldest first, said when they are about. Pure, so the rules are tested without a world:
 * <ul>
 *   <li>a line the same as one already kept (the same words and colour) is not kept again but counted, and said once with its count ("The Warp-Orchard is raised. (x2)"),
 *   in the place of the first;</li>
 *   <li>at most {@value #MAX} are kept, and a <b>routine</b> line ("X is raised.": good news with no gold in it) never pushes out one that isn't: when the queue is full the oldest
 *   routine line goes first, a new routine line is dropped when there is none to make room by, and only a full queue of lines that matter drops its oldest.</li>
 * </ul>
 * Saved as a list, and only while it holds any (Works writes it as {@code told}), so a settlement whose owner was never away saves as it always did.
 */
public final class OwedLines {

    /** The most lines kept. */
    public static final int MAX = 12;

    /** A line owed, how many times it was said while the owner was away, and whether it is routine. */
    public record Line(String text, ChatFormatting color, boolean routine, int count) {

        /** As it is said: the words, and the count after them when it was said more than once. */
        public String said() {
            return count > 1 ? text + " (x" + count + ")" : text;
        }
    }

    private final List<Line> lines = new ArrayList<>();

    /** Keeps a line for the owner. */
    public void add(String text, ChatFormatting color, boolean routine) {
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.text().equals(text) && l.color() == color && l.routine() == routine) {
                lines.set(i, new Line(text, color, routine, l.count() + 1));
                return;
            }
        }
        if (lines.size() >= MAX) {
            int drop = -1;
            for (int i = 0; i < lines.size() && drop < 0; i++) {
                drop = lines.get(i).routine() ? i : -1;
            }
            if (drop < 0 && routine) {
                return;                                          // a routine line never pushes out one that matters
            }
            lines.remove(Math.max(drop, 0));
        }
        lines.add(new Line(text, color, routine, 1));
    }

    /** Everything kept, oldest first; the queue is empty after. */
    public List<Line> take() {
        List<Line> out = new ArrayList<>(lines);
        lines.clear();
        return out;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    /** What is kept, oldest first (the tests). */
    public List<Line> lines() {
        return List.copyOf(lines);
    }

    public ListTag save() {
        ListTag out = new ListTag();
        for (Line l : lines) {
            CompoundTag c = new CompoundTag();
            c.putString("line", l.text());
            c.putString("color", l.color().getName());
            if (l.routine()) {
                c.putBoolean("routine", true);
            }
            if (l.count() > 1) {
                c.putInt("count", l.count());
            }
            out.add(c);
        }
        return out;
    }

    /** What was saved, as far as it can be read (a line with no words is let go); no more than {@value #MAX}. */
    public void load(ListTag saved) {
        lines.clear();
        for (Tag x : saved) {
            CompoundTag c = (CompoundTag) x;
            ChatFormatting color = ChatFormatting.getByName(c.getStringOr("color", ""));
            if (!c.getString("line").isEmpty() && lines.size() < MAX) {
                lines.add(new Line(c.getStringOr("line", ""), color == null ? ChatFormatting.YELLOW : color, c.getBooleanOr("routine", false), Math.max(1, c.getIntOr("count", 0))));
            }
        }
    }
}
