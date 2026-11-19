package com.tiwalo.banking_core_api.account.internal;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

record AccountPage(List<AccountView> accounts, @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor) {}
