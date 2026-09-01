package com.firefly.schedule.holiday;

import java.util.Set;

/** Source adapter for an approved, auditable official holiday dataset. */
public interface HolidayProvider {
    String id();
    Set<String> supportedJurisdictions();
    HolidayDataset fetch(HolidayQuery query) throws HolidayProviderException;
}
