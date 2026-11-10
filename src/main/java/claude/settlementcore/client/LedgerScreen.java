package claude.settlementcore.client;

import claude.settlementcore.ledger.HaulLog;
import claude.settlementcore.ledger.LedgerMenu;
import claude.settlementcore.ledger.LedgerNet;
import claude.settlementcore.ledger.SettlementLedger;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Ledger's screen ({@link LedgerMenu}): everything the settlement keeps in a searchable grid over its owner's inventory, like
 * an ME terminal. Click a thing to take a stack onto the cursor (right-click: half, shift-click: into the inventory); click the
 * grid with something carried to put it in (right-click: one); shift-click the inventory to put a stack in. The search takes a
 * name, or {@code @mod}. The settlement's currency, when it has any, is the lit cell at the head of the grid.
 *
 * <p>The Flows page: every road things went along in the last ten minutes ({@link HaulLog}), the larder's food first, then the
 * goods, the busiest first. The search filters it by the thing or by a place: "coal" shows every road coal took, "larder" what
 * fed the larder and what ate from it.
 *
 * <p>The Under-Empire's Hoard-Ledger screen, on 26.1's screen API and without its Orders page (the Workshop's orders are a
 * race's). How it looks is a {@link LedgerLook}, named by the settlement; its words name no race.
 *
 * <p>26.1 draws a screen in three goes: {@link #extractBackground} (the sheet and the scrollbar's knob), {@link #extractContents}
 * (the widgets, {@link #extractLabels}, the inventory's slots, and after them the page), and {@link #extractTooltip}. Colours
 * must carry their alpha there: text with none is not drawn at all.
 */
public class LedgerScreen extends AbstractContainerScreen<LedgerMenu> {

    private static final Identifier SLOT_HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back");
    private static final Identifier SLOT_HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front");
    private static final int GRID_W = LedgerMenu.COLS * 18, GRID_H = LedgerMenu.ROWS * 18;
    /** The scrollbar's track on the panel, and its two knobs on the sheet (the lit one, and the dull one for nothing to scroll). */
    private static final int BAR_X = 175, BAR_W = 12, KNOB_H = 15, KNOB_U = 196, KNOB_DULL_U = 208;
    private static final int SUMMARY_Y = LedgerMenu.GRID_Y + GRID_H + 4;
    private static final int FLOW_H = 12, FLOW_ROWS = GRID_H / FLOW_H;
    /** How far the title may run before the page buttons. */
    private static final int TITLE_ROOM = LedgerMenu.W - 92;
    private static final int WHITE = 0xFFFFFFFF;

    private enum Page {
        STORES, FLOWS
    }

    private enum Sort {
        MOST("Most"), NAME("A-Z"), MOD("Mod");

        final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    /** A line of the grid as it is shown, and where it stands in the tally the server sent: a click names that place, not the item. */
    private record Shown(SettlementLedger.Line line, int index) {
    }

    private final LedgerLook look;
    /** The look's colours, opaque, and three of them bare (RGB) for the washes the screen lays at its own alpha. */
    private final int text, dim, good, warn, bad;
    private final int textRgb, goodRgb, backingRgb;
    private final String fullTitle;
    private final ItemStack currencyIcon, bread = new ItemStack(Items.BREAD), bowl = new ItemStack(Items.BOWL),
            barrel = new ItemStack(Items.BARREL), chest = new ItemStack(Items.CHEST);

    private EditBox search;
    private Button sortButton, storesTab, flowsTab;
    private Sort sort = Sort.MOST;
    private Page page = Page.STORES;
    private List<Shown> shown = List.of();
    private List<HaulLog.Flow> shownFlows = List.of();
    private int row;
    private int seen = -1;
    /** The scrollbar's knob is held. */
    private boolean dragging;
    /** The button now down went down on the grid or the scrollbar: its release is the screen's own, not a slot's. */
    private boolean pressed;

    public LedgerScreen(LedgerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, LedgerMenu.W, LedgerMenu.H);
        look = LedgerLooks.get(menu.look);
        text = 0xFF000000 | look.text();
        dim = 0xFF000000 | look.dim();
        good = 0xFF000000 | look.good();
        warn = 0xFF000000 | look.warn();
        bad = 0xFF000000 | look.bad();
        textRgb = look.text() & 0xFFFFFF;
        goodRgb = look.good() & 0xFFFFFF;
        backingRgb = look.backing() & 0xFFFFFF;
        String name = menu.settlementName;
        fullTitle = name.isEmpty() ? look.storesLabel() : look.title().replace("%s", name);
        currencyIcon = new ItemStack(menu.currencyItem);
    }

    @Override
    protected void init() {
        super.init();
        String typed = search == null ? "" : search.getValue();  // a resized window builds the screen again: the search is kept
        search = new EditBox(font, leftPos + 11, topPos + 22, 112, 10, Component.literal("Search"));
        search.setBordered(false);
        search.setMaxLength(50);
        search.setTextColor(text);
        search.setValue(typed);
        search.setResponder(s -> {
            row = 0;
            refilter();
        });
        addRenderableWidget(search);
        sortButton = addRenderableWidget(Button.builder(Component.literal(sort.label), b -> {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            row = 0;
            refreshButtons();
            refilter();
        }).bounds(leftPos + 131, topPos + 19, 38, 14).build());
        storesTab = addRenderableWidget(Button.builder(Component.literal(look.storesLabel()), b -> turnTo(Page.STORES))
                .bounds(leftPos + imageWidth - 82, topPos + 3, 37, 12).build());
        flowsTab = addRenderableWidget(Button.builder(Component.literal(look.flowsLabel()), b -> turnTo(Page.FLOWS))
                .bounds(leftPos + imageWidth - 44, topPos + 3, 37, 12).build());
        storesTab.setTooltip(Tooltip.create(Component.literal("Everything the settlement keeps. Take and put here.")));
        flowsTab.setTooltip(Tooltip.create(Component.literal("Where goods and food went in the last " + HaulLog.MINUTES
                + " minutes. Search by a thing or a place.")));
        sortButton.setTooltip(Tooltip.create(Component.literal("Sort: most first, by name, or by mod")));
        refreshButtons();
        refilter();
    }

    private void turnTo(Page p) {
        page = p;
        row = 0;
        refreshButtons();
        refilter();
    }

    /** Turn to a page by number (0 Stores, 1 Flows): a way in for a tool that photographs screens. */
    public void turnToPage(int i) {
        turnTo(Page.values()[Math.floorMod(i, Page.values().length)]);
    }

    private void refreshButtons() {
        if (storesTab == null) {
            return;                                                 // turned to a page before the screen was laid out: init does this
        }
        storesTab.active = page != Page.STORES;                  // the open page's button is the dull one
        flowsTab.active = page != Page.FLOWS;
        sortButton.visible = page != Page.FLOWS;
        sortButton.setMessage(Component.literal(sort.label));
        // the box draws its hint unclipped: one wider than the box would run under the sort button
        String hint = page == Page.STORES ? look.storesHint() : look.flowsHint();
        int rgb = look.dim() & 0xFFFFFF;
        search.setHint(Component.literal(font.width(hint) <= search.getWidth() ? hint : "Search...").withStyle(s -> s.withColor(rgb)));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.version != seen) {
            seen = menu.version;
            refilter();
        }
    }

    private void refilter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<HaulLog.Flow> roads = new ArrayList<>();
        for (HaulLog.Flow f : menu.flows) {
            if (f.food() && matches(f, q)) {
                roads.add(f);                                       // the larder's food first
            }
        }
        for (HaulLog.Flow f : menu.flows) {
            if (!f.food() && matches(f, q)) {
                roads.add(f);
            }
        }
        shownFlows = roads;
        List<Shown> out = new ArrayList<>();
        // each line's name is made once a tally (the review of 3e14b9f: the sort made it in its comparator, tens of thousands of
        // times a tally for a hold of a few thousand kinds)
        if (names.length != menu.lines.size() || namesOf != menu.version) {
            names = new String[menu.lines.size()];
            namesOf = menu.version;
        }
        for (int i = 0; i < menu.lines.size(); i++) {
            SettlementLedger.Line l = menu.lines.get(i);
            boolean byMod = q.startsWith("@");
            if (q.isEmpty() || (byMod ? namespace(l.item()).contains(q.substring(1)) : nameAt(i).contains(q))) {
                out.add(new Shown(l, i));
            }
        }
        switch (sort) {
            case NAME -> out.sort(Comparator.comparing(s -> nameAt(s.index())));
            case MOD -> out.sort(Comparator.comparing((Shown s) -> namespace(s.line().item())).thenComparing(s -> nameAt(s.index())));
            default -> {
            }                                                       // most first: as the tally came, the currency at its head
        }
        shown = out;
        row = Math.max(0, Math.min(row, maxRow()));
    }

    // ------------------------------------------------------------------ searching

    private static String name(ItemStack item) {
        return item.getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    /** The names of this tally's lines, lower case, each made when first asked. */
    private String[] names = new String[0];
    private int namesOf = -1;

    private String nameAt(int line) {
        if (names[line] == null) {
            names[line] = name(menu.lines.get(line).item());
        }
        return names[line];
    }

    private static String namespace(ItemStack item) {
        return BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace();
    }

    private static boolean matches(ItemStack item, String q) {
        if (q.isEmpty()) {
            return true;
        }
        if (q.startsWith("@")) {
            return namespace(item).contains(q.substring(1));
        }
        return name(item).contains(q);
    }

    private static boolean matches(HaulLog.Flow f, String q) {
        if (q.isEmpty()) {
            return true;
        }
        if (q.startsWith("@")) {
            return f.item() != null && BuiltInRegistries.ITEM.getKey(f.item()).getNamespace().contains(q.substring(1));
        }
        String what = f.item() == null ? "food" : new ItemStack(f.item()).getHoverName().getString();
        return what.toLowerCase(Locale.ROOT).contains(q) || f.from().toLowerCase(Locale.ROOT).contains(q) || f.to().toLowerCase(Locale.ROOT).contains(q);
    }

    // ------------------------------------------------------------------ where things are

    private int maxRow() {
        return page == Page.FLOWS ? Math.max(0, shownFlows.size() - FLOW_ROWS)
                : Math.max(0, (shown.size() + LedgerMenu.COLS - 1) / LedgerMenu.COLS - LedgerMenu.ROWS);
    }

    /** The grid's cell under the mouse (0 to 53, by rows), or -1. */
    private int cellAt(double mx, double my) {
        int x = (int) Math.floor(mx) - leftPos - LedgerMenu.GRID_X, y = (int) Math.floor(my) - topPos - LedgerMenu.GRID_Y;
        if (x < 0 || y < 0 || x >= GRID_W || y >= GRID_H) {
            return -1;
        }
        return y / 18 * LedgerMenu.COLS + x / 18;
    }

    /** The line under the mouse on the Stores page, or null. */
    @Nullable
    private Shown lineAt(double mx, double my) {
        int cell = cellAt(mx, my);
        int i = row * LedgerMenu.COLS + cell;
        return page != Page.STORES || cell < 0 || i >= shown.size() ? null : shown.get(i);
    }

    /** The road under the mouse on the Flows page, or null. */
    @Nullable
    private HaulLog.Flow flowAt(double mx, double my) {
        int x = (int) Math.floor(mx) - leftPos - LedgerMenu.GRID_X, y = (int) Math.floor(my) - topPos - LedgerMenu.GRID_Y;
        if (page != Page.FLOWS || x < 0 || y < 0 || x >= GRID_W || y >= FLOW_ROWS * FLOW_H) {
            return null;
        }
        int i = row + y / FLOW_H;
        return i < shownFlows.size() ? shownFlows.get(i) : null;
    }

    private boolean onBar(double mx, double my) {
        return mx >= leftPos + BAR_X && mx < leftPos + BAR_X + BAR_W && my >= topPos + LedgerMenu.GRID_Y && my < topPos + LedgerMenu.GRID_Y + GRID_H;
    }

    private boolean onTitle(int mx, int my) {
        return my >= topPos + 5 && my < topPos + 15 && mx >= leftPos + 8 && mx < leftPos + 8 + font.width(shownTitle());
    }

    private boolean onSummary(int mx, int my) {
        return my >= topPos + SUMMARY_Y - 1 && my < topPos + SUMMARY_Y + 9 && mx >= leftPos + 8 && mx < leftPos + imageWidth - 8;
    }

    /** Where a cell's item is drawn: a pixel inside its well. */
    private int cellX(int cell) {
        return leftPos + LedgerMenu.GRID_X + cell % LedgerMenu.COLS * 18 + 1;
    }

    private int cellY(int cell) {
        return topPos + LedgerMenu.GRID_Y + cell / LedgerMenu.COLS * 18 + 1;
    }

    // ------------------------------------------------------------------ drawing

    /** Behind everything: the sheet, and the scrollbar's knob where the page stands. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractBackground(g, mx, my, partial);
        g.blit(RenderPipelines.GUI_TEXTURED, look.texture(), leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256);
        int max = maxRow();
        int knobY = topPos + LedgerMenu.GRID_Y + (max == 0 ? 0 : (GRID_H - KNOB_H) * row / max);
        g.blit(RenderPipelines.GUI_TEXTURED, look.texture(), leftPos + BAR_X, knobY, max == 0 ? KNOB_DULL_U : KNOB_U, 0f, BAR_W, KNOB_H, 256, 256);
    }

    /** The widgets, the labels and the inventory's slots are vanilla's; the page is drawn after them, in the screen's own coordinates. */
    @Override
    public void extractContents(GuiGraphicsExtractor g, int mx, int my, float partial) {
        super.extractContents(g, mx, my, partial);
        if (page == Page.FLOWS) {
            extractFlows(g, mx, my);
        } else {
            extractStores(g, mx, my);
        }
    }

    private void extractStores(GuiGraphicsExtractor g, int mx, int my) {
        int hover = cellAt(mx, my);
        for (int cell = 0; cell < LedgerMenu.COLS * LedgerMenu.ROWS; cell++) {
            int i = row * LedgerMenu.COLS + cell;
            if (i >= shown.size()) {
                break;
            }
            SettlementLedger.Line l = shown.get(i).line();
            int x = cellX(cell), y = cellY(cell);
            if (l.source() == LedgerMenu.FROM_CURRENCY) {
                g.fill(x - 1, y - 1, x + 17, y + 17, 0x55000000 | goodRgb);   // the settlement's currency: a lit cell
            }
            if (cell == hover) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, x - 4, y - 4, 24, 24);
            }
            g.item(l.item(), x, y);
            g.itemDecorations(font, l.item(), x, y);              // its damage bar; the count is the Ledger's own, drawn small
            if (l.count() > 1) {
                smallCount(g, shortCount(l.count()), x, y, WHITE);
            }
            if (cell == hover) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, x - 4, y - 4, 24, 24);
            }
        }
        if (hover >= 0 && row * LedgerMenu.COLS + hover >= shown.size()) {     // an empty cell lights too: a carried stack goes in there
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, cellX(hover) - 4, cellY(hover) - 4, 24, 24);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, cellX(hover) - 4, cellY(hover) - 4, 24, 24);
        }
        if (shown.isEmpty()) {
            String msg = menu.version == 0 || menu.summary.unreached() > 0 ? "Counting the stores..."   // no tally yet, or stores not reached
                    : !menu.lines.isEmpty() ? "Nothing in the stores by that name."
                    : menu.summary.stores() == 0 ? "No chests or barrels in the settlement yet." : "The stores are empty.";
            centred(g, msg, topPos + LedgerMenu.GRID_Y + 44);
        }
    }

    /** A half-size number in a cell's bottom right corner. */
    private void smallCount(GuiGraphicsExtractor g, String n, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x + 16, y + 16);
        g.pose().scale(0.5f, 0.5f);
        g.text(font, n, -font.width(n), -8, color, true);
        g.pose().popMatrix();
    }

    /** An item at half size, its corner at {@code x}, {@code y}. */
    private void smallItem(GuiGraphicsExtractor g, ItemStack item, int x, int y) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.5f, 0.5f);
        g.item(item, 0, 0);
        g.pose().popMatrix();
    }

    /** A line over the middle of the grid, wrapped to its width. */
    private void centred(GuiGraphicsExtractor g, String msg, int y) {
        int cx = leftPos + LedgerMenu.GRID_X + GRID_W / 2;
        for (FormattedCharSequence line : font.split(Component.literal(msg), GRID_W - 8)) {
            g.centeredText(font, line, cx, y, dim);
            y += 10;
        }
    }

    /** The Flows page: one road a line, the larder's food first, on a sheet of its own over the grid's wells. */
    private void extractFlows(GuiGraphicsExtractor g, int mx, int my) {
        int x0 = leftPos + LedgerMenu.GRID_X, y0 = topPos + LedgerMenu.GRID_Y;
        g.fill(x0, y0, x0 + GRID_W, y0 + GRID_H, 0xF0000000 | backingRgb);
        HaulLog.Flow hover = flowAt(mx, my);
        for (int i = 0; i < FLOW_ROWS && row + i < shownFlows.size(); i++) {
            HaulLog.Flow f = shownFlows.get(row + i);
            int y = y0 + i * FLOW_H;
            if (f == hover) {
                g.fill(x0, y, x0 + GRID_W, y + FLOW_H, 0x40000000 | textRgb);
            }
            smallItem(g, f.item() == null ? bread : new ItemStack(f.item()), x0 + 2, y + 2);
            String n = amount(f);
            int color = !f.food() ? text : f.to().equals(HaulLog.LARDER) ? good : warn;
            g.text(font, n, x0 + 12, y + 2, color, false);
            int tx = x0 + 12 + Math.max(24, font.width(n) + 4);
            g.text(font, fit(f.from() + " \u2192 " + f.to(), x0 + GRID_W - tx - 2), tx, y + 2, dim, false);
        }
        if (shownFlows.isEmpty()) {
            centred(g, !menu.flows.isEmpty() ? "No road by that name." : "Nothing has moved yet.", y0 + 44);
            if (menu.flows.isEmpty()) {
                centred(g, "Goods are hauled every few seconds.", y0 + 56);
            }
        }
    }

    /** What a road carried, as its line says it: "+12" food into the larder, "-8" out of it, else the count. */
    private static String amount(HaulLog.Flow f) {
        String n = shortCount(f.count());
        if (f.food()) {
            return (f.to().equals(HaulLog.LARDER) ? "+" : "-") + n;
        }
        return n;
    }

    /** The title and the line under the grid. Drawn in the panel's own coordinates (its corner is 0, 0); the mouse is not asked here. */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {
        g.text(font, shownTitle(), 8, 6, text, false);
        if (page == Page.FLOWS) {
            long[] l = HaulLog.larder(menu.flows);
            int x = icon(g, bread, 8, HaulLog.LARDER + " +" + shortCount(l[0]), good);
            x = icon(g, bowl, x + 6, "out -" + shortCount(l[1]), warn);
            g.text(font, "(" + HaulLog.MINUTES + " min)", x + 6, SUMMARY_Y, dim, false);
            return;
        }
        // the stores' line: the currency, the food, the stores and their rooms; then, as far as the panel has room, how full and the fare
        SettlementLedger.Summary s = menu.summary;
        int room = imageWidth - 16;
        String currency = String.valueOf(s.currency()), food = String.valueOf(s.food());
        boolean counting = s.unreached() > 0;
        String stores = counting ? "counting " + (s.stores() - s.unreached()) + "/" + s.stores()
                : s.stores() + " in " + s.rooms() + (s.rooms() == 1 ? " room" : " rooms");
        String slots = s.used() + "/" + s.slots();
        int width = part(currency) + 6 + part(food) + 6 + part(stores);
        if (width > room) {
            width -= font.width(stores);
            stores = String.valueOf(s.stores());                 // huge counts: the stores' number alone, the rest is in the tooltip
            width += font.width(stores);
        }
        boolean showSlots = s.slots() > 0 && width + 6 + part(slots) <= room;
        width += showSlots ? 6 + part(slots) : 0;
        boolean showFare = !s.fare().isEmpty() && width + font.width(" " + s.fare()) <= room;
        int x = icon(g, currencyIcon, 8, currency, good);
        x = icon(g, bread, x + 6, showFare ? food + " " + s.fare() : food, text);
        x = icon(g, barrel, x + 6, stores, counting ? warn : dim);
        if (showSlots) {
            icon(g, chest, x + 6, slots, s.used() >= s.slots() ? bad : s.used() * 10L >= s.slots() * 9L ? warn : dim);
        }
    }

    /** How wide {@link #icon} draws a word: its picture and the word. */
    private int part(String word) {
        return 10 + font.width(word);
    }

    /** A half-size icon and a word after it, on the summary line; returns where the next goes. */
    private int icon(GuiGraphicsExtractor g, ItemStack icon, int x, String word, int color) {
        smallItem(g, icon, x, SUMMARY_Y);
        g.text(font, word, x + 10, SUMMARY_Y, color, false);
        return x + 10 + font.width(word);
    }

    /** The title as it fits beside the page buttons: a long one shows the settlement's name alone, and the whole title on hover. */
    private String shownTitle() {
        if (font.width(fullTitle) <= TITLE_ROOM) {
            return fullTitle;
        }
        return fit(menu.settlementName.isEmpty() ? fullTitle : menu.settlementName, TITLE_ROOM);
    }

    /** Text cut to {@code width} with "..." when it runs over, so a cut line reads as cut. */
    private String fit(String s, int width) {
        return font.width(s) <= width ? s : font.plainSubstrByWidth(s, Math.max(0, width - font.width("..."))) + "...";
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor g, int mx, int my) {
        if (onTitle(mx, my) && !shownTitle().equals(fullTitle)) {
            g.setTooltipForNextFrame(font, Component.literal(fullTitle), mx, my);
            return;
        }
        if (page == Page.FLOWS) {
            HaulLog.Flow f = flowAt(mx, my);
            if (f != null) {
                String what = f.item() == null ? "food" : new ItemStack(f.item()).getHoverName().getString();
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(String.format(Locale.ROOT, "%,d ", f.count()) + what).withStyle(ChatFormatting.GREEN));
                tip.add(Component.literal(f.from() + " \u2192 " + f.to()));
                tip.add(Component.literal("in the last " + HaulLog.MINUTES + " minutes").withStyle(ChatFormatting.DARK_GRAY));
                g.setComponentTooltipForNextFrame(font, tip, mx, my);
            } else if (onSummary(mx, my)) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal("Food into the larder, and eaten or spent out of it, the last " + HaulLog.MINUTES + " minutes"));
                tip.add(Component.literal("Search \"larder\" for what fed it and what ate from it").withStyle(ChatFormatting.DARK_GRAY));
                g.setComponentTooltipForNextFrame(font, tip, mx, my);
            } else {
                super.extractTooltip(g, mx, my);
            }
            return;
        }
        Shown at = lineAt(mx, my);
        if (at != null && menu.getCarried().isEmpty()) {
            SettlementLedger.Line l = at.line();
            List<Component> tip = new ArrayList<>(getTooltipFromContainerItem(l.item()));
            String count = String.format(Locale.ROOT, "%,d", l.count());
            if (l.source() == LedgerMenu.FROM_CURRENCY) {
                tip.add(Component.literal("The settlement's currency: " + count).withStyle(ChatFormatting.GREEN));
                tip.add(Component.literal("It pays for what the settlement builds; what you take comes out of it.").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                tip.add(Component.literal("In the stores: " + count).withStyle(ChatFormatting.GREEN));
            }
            tip.add(Component.literal("Click: a stack  Right-click: half  Shift: to your pack").withStyle(ChatFormatting.DARK_GRAY));
            g.setTooltipForNextFrame(font, tip, l.item().getTooltipImage(), l.item(), mx, my);
        } else if (onSummary(mx, my)) {
            SettlementLedger.Summary s = menu.summary;
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Currency held: " + String.format(Locale.ROOT, "%,d", s.currency())).withStyle(ChatFormatting.GREEN));
            tip.add(Component.literal("Food in the larder: " + s.food() + (s.fare().isEmpty() ? "" : ", fare " + s.fare())));
            tip.add(Component.literal(s.stores() + " chests and barrels in " + s.rooms() + (s.rooms() == 1 ? " room, " : " rooms, ")
                    + s.used() + "/" + s.slots() + " slots full").withStyle(ChatFormatting.GRAY));
            if (s.unreached() > 0) {
                tip.add(Component.literal(s.unreached() + " not reached yet: their ground is still loading").withStyle(ChatFormatting.GOLD));
            }
            if (menu.more > 0) {
                tip.add(Component.literal(menu.more + " more kinds, the rarest, are too many to list here").withStyle(ChatFormatting.GOLD));
            }
            g.setComponentTooltipForNextFrame(font, tip, mx, my);
        } else {
            super.extractTooltip(g, mx, my);
        }
    }

    /** 999, 1.2k, 34k, 1.2M. */
    static String shortCount(long n) {
        if (n < 1000) {
            return Long.toString(n);
        }
        if (n < 10_000) {
            return String.format(Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k");
        }
        if (n < 1_000_000) {
            return n / 1000 + "k";
        }
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M");
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int button = event.button();
        if (cellAt(mx, my) >= 0) {
            pressed = true;
            if (search.isFocused()) {
                setFocused((GuiEventListener) null);            // a click on the grid leaves the search: E closes the screen again
            }
            if (page == Page.FLOWS || button != 0 && button != 1) {
                return true;                                        // the Flows page is to read, not to take from
            }
            ItemStack carried = menu.getCarried();
            Shown at = lineAt(mx, my);
            if (!carried.isEmpty()) {
                send(LedgerMenu.PUT_CARRIED, 0, button == 1 ? 1 : carried.getCount());
            } else if (at != null) {
                int stack = (int) Math.min(at.line().item().getMaxStackSize(), at.line().count());
                if (button == 0 && event.hasShiftDown()) {
                    send(LedgerMenu.TAKE_TO_INVENTORY, at.index(), stack);
                } else if (button == 0) {
                    send(LedgerMenu.TAKE, at.index(), stack);
                } else {
                    send(LedgerMenu.TAKE, at.index(), Math.max(1, (stack + 1) / 2));
                }
            }
            return true;
        }
        if (button == 0 && onBar(mx, my)) {
            pressed = true;
            dragging = true;
            scrollTo(my);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** A click to the server: the line by its place in the tally on the screen (the server holds the same tally under the same number). */
    private void send(int action, int index, int amount) {
        ClientPacketDistributor.sendToServer(new LedgerNet.LedgerAct(menu.containerId, action, menu.serial, index, amount));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            scrollTo(event.y());
            return true;
        }
        return pressed || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressed) {                                              // a press the grid or the scrollbar took: no slot is clicked by its release
            pressed = false;
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void scrollTo(double my) {
        double f = (my - topPos - LedgerMenu.GRID_Y - KNOB_H / 2.0) / (GRID_H - KNOB_H);
        row = (int) Math.round(Math.max(0, Math.min(1, f)) * maxRow());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY != 0 && (cellAt(mx, my) >= 0 || onBar(mx, my))) {   // over the grid or its bar: elsewhere the wheel is the pack's (a bundle)
            row = Math.max(0, Math.min(maxRow(), row - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    /**
     * While the search is being written in, keys are its own: Escape leaves the box (a second closes the screen), and no letter
     * closes the screen as the inventory key or swaps a hotbar slot.
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search.isFocused()) {
            if (event.isEscape()) {
                setFocused((GuiEventListener) null);
                return true;
            }
            return search.keyPressed(event) || search.canConsumeInput() || super.keyPressed(event);
        }
        return super.keyPressed(event);
    }
}
