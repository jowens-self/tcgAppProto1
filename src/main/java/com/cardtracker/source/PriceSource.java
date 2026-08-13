package com.cardtracker.source;

import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;

import java.time.LocalDate;
import java.util.List;

public interface PriceSource {

    String name();

    List<Category> categories() throws Exception;

    List<Group> groups(int categoryId) throws Exception;

    List<Product> products(int categoryId, int groupId) throws Exception;

    List<PriceSnapshot> pricesForGroup(int categoryId, int groupId, LocalDate snapshotDate) throws Exception;

    int requestsUsed();
}
