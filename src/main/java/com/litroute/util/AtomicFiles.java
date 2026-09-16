package com.litroute.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write-to-temp-then-rename helper shared by every local JSON store (M7a-e
 * and the credential file). A crash mid-write leaves the previous version of
 * the file intact instead of a half-written, corrupt one, per the
 * cross-file-consistency mitigation in the design doc's risk section.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeAtomically(Path target, byte[] content) throws IOException {
        Path parentDir = target.toAbsolutePath().getParent();
        Files.createDirectories(parentDir);
        Path tempFile = Files.createTempFile(parentDir, target.getFileName().toString(), ".tmp");
        try {
            Files.write(tempFile, content);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
