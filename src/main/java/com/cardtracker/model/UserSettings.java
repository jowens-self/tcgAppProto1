package com.cardtracker.model;

/**
 * One user's preferences, from user_settings. A user with no saved row gets
 * {@link #DEFAULTS}, which match the table's column defaults.
 *
 * showCostLine draws what was paid under the collection's value line.
 * includePreRelease, includeEarly and includeFlipping are the boxes on the
 * trend screens (see TrendScope): one setting each, shown in several places.
 */
public record UserSettings(boolean showCostLine, boolean includePreRelease, boolean includeEarly,
                           boolean includeFlipping) {

    public static final UserSettings DEFAULTS = new UserSettings(false, false, false, false);
}
