package com.cardtracker.model;

public record Group(
        int groupId,
        int categoryId,
        String name,
        String abbreviation,
        boolean isSupplemental,
        String publishedOn,
        String modifiedOn) {
}
