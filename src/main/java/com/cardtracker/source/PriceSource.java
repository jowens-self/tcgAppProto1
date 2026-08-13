package com.cardtracker.source;

import com.cardtracker.model.PriceSnapshot;

import java.time.LocalDate;
import java.util.List;

public interface PriceSource {

    String name();

    List<PriceSnapshot> pricesForGroup(int categoryId, int groupId, LocalDate snapshotDate) throws Exception;
}
