package com.dadot.server.infrastructure.dart;

import com.dadot.server.domain.disclosure.support.OriginalProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LocalOriginalStoreTest {
    @TempDir Path root;
    @Test void writesImmutableContentAddressedObjectsAndDeduplicates() throws Exception {
        var store = new LocalOriginalStore(new OriginalProperties(true, root.toString()));
        String key;
        try (var stage = store.stage()) {
            Files.writeString(stage.path(), "original bytes"); key = store.put(1, "20260101000001", stage.path()).key();
        }
        assertEquals("original bytes", Files.readString(root.resolve(key)));
        try (var stage = store.stage()) {
            Files.writeString(stage.path(), "original bytes"); assertEquals(key, store.put(1, "20260101000001", stage.path()).key());
        }
        try (var files = Files.list(root.resolve(".work"))) { assertEquals(0, files.count()); }
        assertFalse(key.startsWith("/"));
    }
    @Test void refusesToOverwriteCorruptStoredObjects() throws Exception {
        var store = new LocalOriginalStore(new OriginalProperties(true, root.toString())); String key;
        try (var stage = store.stage()) { Files.writeString(stage.path(), "correct"); key = store.put(1, "20260101000001", stage.path()).key(); }
        Files.writeString(root.resolve(key), "corrupt");
        try (var stage = store.stage()) {
            Files.writeString(stage.path(), "correct");
            assertEquals("DART_STORAGE_CORRUPT", assertThrows(DartFailure.class, () -> store.put(1, "20260101000001", stage.path())).code());
        }
        assertEquals("corrupt", Files.readString(root.resolve(key)));
    }
    @Test void cleanupOnlyRemovesExpiredStagingFiles() throws Exception {
        var store = new LocalOriginalStore(new OriginalProperties(true, root.toString()));
        try (var old = store.stage(); var current = store.stage()) {
            Files.setLastModifiedTime(old.path(), java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(90000)));
            store.cleanup(); assertFalse(Files.exists(old.path())); assertTrue(Files.exists(current.path()));
        }
    }
    @Test void disabledStoreDoesNotCreateDirectoriesAndRejectsUse() {
        var path = root.resolve("not-created"); var store = new LocalOriginalStore(new OriginalProperties(false, path.toString()));
        assertFalse(store.ready()); assertFalse(Files.exists(path)); assertThrows(DartFailure.class, store::stage);
    }
    @Test void rejectsInvalidRootAndTraversalInputs() throws Exception {
        Path file = root.resolve("file"); Files.writeString(file, "file");
        assertThrows(IllegalStateException.class, () -> new LocalOriginalStore(new OriginalProperties(true, file.toString())));
        var store = new LocalOriginalStore(new OriginalProperties(true, root.toString()));
        try (var stage = store.stage()) { assertThrows(IllegalArgumentException.class, () -> store.put(1, "../escape", stage.path())); }
    }
}
