package com.tiwalo.banking_core_api.account.internal;

import java.util.UUID;

record AccountView(UUID accountId, String recipientReference, String status, String currency,
                   String availableBalanceMinor) {}
