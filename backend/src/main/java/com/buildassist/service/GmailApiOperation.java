package com.buildassist.service;

import com.buildassist.config.AppProperties;

public enum GmailApiOperation {
    MESSAGES_LIST,
    MESSAGES_GET_METADATA,
    MESSAGES_GET_FULL,
    HISTORY_LIST,
    PROFILE_GET,
    DRAFTS_CREATE;

    public int quotaUnits(AppProperties.Gmail properties) {
        return switch (this) {
            case MESSAGES_LIST -> properties.getListQuotaUnits();
            case MESSAGES_GET_METADATA -> properties.getGetMetadataQuotaUnits();
            case MESSAGES_GET_FULL -> properties.getGetFullQuotaUnits();
            case HISTORY_LIST -> properties.getHistoryQuotaUnits();
            case PROFILE_GET -> properties.getProfileQuotaUnits();
            case DRAFTS_CREATE -> properties.getDraftCreateQuotaUnits();
        };
    }
}
