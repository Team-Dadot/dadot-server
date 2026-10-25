package com.dadot.server.domain.disclosure.model;

import java.util.List;
import java.util.Map;

public final class DisclosureOriginal {
    private DisclosureOriginal() {}

    public record Work(
            long itemId,
            String receiptNo,
            String reportType,
            int attempt
    ) {}

    public record Stored(
            String key,
            String archiveHash
    ) {}

    public record Block(
            String key,
            String heading,
            String text,
            Map<String, Object> locator
    ) {}

    public record Extracted(
            String entryName,
            String contentHash,
            List<Block> blocks
    ) {}
}