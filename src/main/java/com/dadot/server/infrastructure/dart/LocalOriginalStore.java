package com.dadot.server.infrastructure.dart;

import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Stored;
import com.dadot.server.domain.disclosure.support.OriginalProperties;
import com.dadot.server.domain.disclosure.support.OriginalStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public final class LocalOriginalStore implements OriginalStore {

    private final Path root;
    private final Path work;

    public LocalOriginalStore(OriginalProperties properties) {
        if (!properties.enabled()) {
            root = null;
            work = null;
            return;
        }

        try {
            Path configured = Path.of(properties.root())
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(configured);

            root = configured.toRealPath();
            work = root.resolve(".work");

            Files.createDirectories(work);

            if (Files.isSymbolicLink(work)) {
                throw new IOException("Invalid storage directory");
            }

            try (var ignored = stage()) {
                // 설정된 디렉터리에 실제로 쓸 수 있는지 시작 시 확인한다.
            }

        } catch (IOException ex) {
            throw new IllegalStateException(
                    "DART original storage is unavailable"
            );
        }
    }

    @Override
    public boolean ready() {
        return root != null;
    }

    @Override
    public Temporary stage() {
        if (!ready()) {
            throw new DartFailure(
                    "DART_STORAGE_NOT_CONFIGURED",
                    false
            );
        }

        try {
            return new Temporary(
                    Files.createTempFile(work, "download-", ".tmp")
            );

        } catch (IOException ex) {
            throw new DartFailure("DART_STORAGE_ERROR", true);
        }
    }

    @Override
    public Stored put(long companyId, String receipt, Path file) {
        if (!ready()) {
            throw new DartFailure(
                    "DART_STORAGE_NOT_CONFIGURED",
                    false
            );
        }

        if (companyId < 1
                || !receipt.matches("[0-9]{14}")
                || !file.toAbsolutePath()
                .normalize()
                .getParent()
                .equals(work)) {
            throw new IllegalArgumentException(
                    "Invalid original storage input"
            );
        }

        try {
            if (!Files.isRegularFile(
                    file,
                    LinkOption.NOFOLLOW_LINKS
            ) || Files.size(file) > 20L * 1024 * 1024) {
                throw new DartFailure("DART_DOCUMENT_LIMIT", false);
            }

            String hash = hash(file);

            String key = "disclosures/"
                    + companyId
                    + "/"
                    + receipt
                    + "/"
                    + hash
                    + ".zip";

            Path parent = root;

            for (String part : new String[]{
                    "disclosures",
                    Long.toString(companyId),
                    receipt
            }) {
                parent = parent.resolve(part);
                Files.createDirectories(parent);

                if (Files.isSymbolicLink(parent)) {
                    throw new IOException(
                            "Invalid storage directory"
                    );
                }
            }

            Path target = root.resolve(key);

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(
                        target,
                        LinkOption.NOFOLLOW_LINKS
                ) || !hash.equals(hash(target))) {
                    throw new DartFailure(
                            "DART_STORAGE_CORRUPT",
                            false
                    );
                }

            } else {
                try (var channel = FileChannel.open(
                        file,
                        StandardOpenOption.WRITE
                )) {
                    channel.force(true);
                }

                Files.move(
                        file,
                        target,
                        StandardCopyOption.ATOMIC_MOVE
                );
            }

            try (var directory = FileChannel.open(
                    parent,
                    StandardOpenOption.READ
            )) {
                directory.force(true);
            }

            return new Stored(key, hash);

        } catch (DartFailure ex) {
            throw ex;

        } catch (IOException ex) {
            throw new DartFailure("DART_STORAGE_ERROR", true);
        }
    }

    @Override
    public void cleanup() {
        if (!ready()) {
            return;
        }

        try (var files = Files.newDirectoryStream(
                work,
                "download-*.tmp"
        )) {
            var cutoff = Instant.now().minusSeconds(86400);

            for (var file : files) {
                if (Files.isRegularFile(
                        file,
                        LinkOption.NOFOLLOW_LINKS
                ) && Files.getLastModifiedTime(
                        file,
                        LinkOption.NOFOLLOW_LINKS
                ).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }

        } catch (IOException ex) {
            throw new DartFailure("DART_STORAGE_ERROR", true);
        }
    }

    private String hash(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");

            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[16384];
                long total = 0;
                int n;

                while ((n = stream.read(buffer)) != -1) {
                    if ((total += n) > 20L * 1024 * 1024) {
                        throw new DartFailure(
                                "DART_DOCUMENT_LIMIT",
                                false
                        );
                    }

                    digest.update(buffer, 0, n);
                }
            }

            return HexFormat.of().formatHex(digest.digest());

        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 unavailable"
            );
        }
    }
}