package com.tiwalo.banking_core_api.account.internal;

import java.util.UUID;

final class InvalidAccountQuery extends RuntimeException {

    static UUID parseUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equalsIgnoreCase(value)) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // All malformed identifiers have the same public response.
        }
        throw new InvalidAccountQuery();
    }
}
