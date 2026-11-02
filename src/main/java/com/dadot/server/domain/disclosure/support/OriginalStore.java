package com.dadot.server.domain.disclosure.support;

import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Stored;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface OriginalStore {

    boolean ready();

    Temporary stage();

    Stored put(long companyId, String receiptNo, Path file);

    void cleanup();

    record Temporary(Path path) implements AutoCloseable {

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                LoggerFactory.getLogger(OriginalStore.class)
                        .warn("DART staging cleanup failed");
            }
        }
    }
}