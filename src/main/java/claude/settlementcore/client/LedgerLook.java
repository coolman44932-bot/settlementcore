package claude.settlementcore.client;

import claude.settlementcore.Core;
import net.minecraft.resources.Identifier;

/**
 * How a race's Ledger looks on its owner's screen ({@link LedgerScreen}): the sheet it is drawn from, the colours of its words
 * and the words of its two pages. The engine's own is stone and parchment ({@link #ENGINE}); a race makes its sheet with
 * tools/gen_ledger_gui.py in its own palette, registers a look under an id ({@link LedgerLooks#register}) and names that id from
 * its settlement (Settlement#ledgerLook).
 *
 * <p>The sheet is 256 x 256 and laid out as the generator draws it: the screen draws and clicks by fixed numbers. Colours are
 * RGB ({@code 0xE8DFC8}); the screen makes them opaque. This class and {@link LedgerLooks} use nothing of the client's, so a race
 * may register its look from its mod's constructor, on a server too.
 *
 * @param texture     the sheet
 * @param text        the title, counts and plain words (the Skaven's bone)
 * @param dim         what matters less: the roads' names, the summary's stores, the empty grid's line
 * @param good        a gain: the currency's count and cell, food into the larder (the Skaven's warp green)
 * @param warn        a thing to mind: stores not reached yet, food out of the larder (ember)
 * @param bad         a loss: the stores full to the last slot (blood)
 * @param backing     the Flows page's sheet under its lines, over the grid's wells (opaque or nearly; the alpha is the screen's)
 * @param title       the screen's title, {@code %s} the settlement's name ("Ledger of %s"); a long one shows the name alone
 * @param storesLabel the Stores page's button
 * @param flowsLabel  the Flows page's button
 * @param storesHint  the search box's hint on the Stores page
 * @param flowsHint   the search box's hint on the Flows page
 */
public record LedgerLook(Identifier texture, int text, int dim, int good, int warn, int bad, int backing, String title,
                         String storesLabel, String flowsLabel, String storesHint, String flowsHint) {

    /** The engine's own: the stone-and-parchment sheet of tools/gen_ledger_gui.py, and words that name no race. */
    public static final LedgerLook ENGINE = new LedgerLook(Identifier.fromNamespaceAndPath(Core.MODID, "textures/gui/ledger.png"),
            0xE8DFC8, 0xA9A294, 0x8FD67A, 0xE8B04A, 0xE0564A, 0x1B1A19, "Ledger of %s", "Stores", "Flows", "Search the stores...", "Search the roads...");

    /** This look drawn from another sheet. */
    public LedgerLook withTexture(Identifier sheet) {
        return new LedgerLook(sheet, text, dim, good, warn, bad, backing, title, storesLabel, flowsLabel, storesHint, flowsHint);
    }

    /** This look in other colours (RGB): see the record's own words for which is which. */
    public LedgerLook withColours(int text, int dim, int good, int warn, int bad, int backing) {
        return new LedgerLook(texture, text, dim, good, warn, bad, backing, title, storesLabel, flowsLabel, storesHint, flowsHint);
    }

    /** This look in other words: the title ({@code %s} the settlement's name), the two pages' buttons and their search hints. */
    public LedgerLook withWords(String title, String storesLabel, String flowsLabel, String storesHint, String flowsHint) {
        return new LedgerLook(texture, text, dim, good, warn, bad, backing, title, storesLabel, flowsLabel, storesHint, flowsHint);
    }
}
