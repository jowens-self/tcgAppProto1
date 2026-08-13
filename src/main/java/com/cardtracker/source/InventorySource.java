package com.cardtracker.source;

import com.cardtracker.model.InventorySnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads the owner's own holdings from a Seller Portal CSV export they placed
 * in inbox/ by hand. No implementation yet on purpose - see CLAUDE.md "Known
 * unknowns": the export's real columns haven't been seen, so a parser would
 * be guessing. Get a sample export before implementing this.
 */
public interface InventorySource {

    List<InventorySnapshot> read(Path csvFile) throws Exception;
}
