package com.cardtracker.source;

import com.cardtracker.model.InventorySnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Bulk import of the owner's holdings from a Seller Portal CSV export they
 * placed in inbox/ by hand. One of two ways inventory gets in, not the only
 * one: rows can also be entered directly in the app, which needs nothing from
 * this interface.
 *
 * No implementation yet on purpose; see CLAUDE.md "Known unknowns": the
 * export's real columns haven't been seen, so a parser would be guessing. Get
 * a sample export before implementing this. That wait applies to this
 * interface alone, not to inventory tracking as a whole.
 */
public interface InventorySource {

    List<InventorySnapshot> read(Path csvFile) throws Exception;
}
