package com.tiwalo.banking_core_api.transfer.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

record TransferCommand(String key, UUID source, String recipient, long amount) {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Pattern AMOUNT = Pattern.compile("[1-9][0-9]{0,18}");

    static TransferCommand parse(List<String> keys, JsonNode body) {
        if (keys == null || keys.size() != 1 || !KEY.matcher(keys.getFirst()).matches()
                || !body.isObject() || body.size() != 4) {
            throw new InvalidRequest();
        }
        UUID source = id(text(body, "sourceAccountId"));
        String recipient = text(body, "recipientReference");
        String amount = text(body, "amountMinor");
        if (recipient.isEmpty() || recipient.codePointCount(0, recipient.length()) > 128
                || !StandardCharsets.UTF_8.newEncoder().canEncode(recipient)
                || !"PHP".equals(text(body, "currency")) || !AMOUNT.matcher(amount).matches()) {
            throw new InvalidRequest();
        }
        try {
            return new TransferCommand(keys.getFirst(), source, recipient, Long.parseLong(amount));
        } catch (NumberFormatException ex) {
            throw new InvalidRequest();
        }
    }

    static UUID id(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (id.toString().equalsIgnoreCase(text)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // All malformed identifiers have the same public response.
        }
        throw new InvalidRequest();
    }

    private static String text(JsonNode object, String field) {
        var value = object.path(field);
        if (!value.isString()) {
            throw new InvalidRequest();
        }
        return value.asString();
    }

    static final class InvalidRequest extends RuntimeException {}
}
