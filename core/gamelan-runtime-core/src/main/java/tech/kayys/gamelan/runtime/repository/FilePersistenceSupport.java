package tech.kayys.gamelan.runtime.repository;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

final class FilePersistenceSupport {

    private static final String LOCK_SUFFIX = ".lock";
    private static final int LOCK_STRIPE_COUNT = 256;
    private static final Object[] LOCK_STRIPES = lockStripes();
    private static final ThreadLocal<Set<Path>> HELD_LOCKS = ThreadLocal.withInitial(HashSet::new);

    private FilePersistenceSupport() {
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    static Path root(String rootDirectory) {
        return Path.of(rootDirectory).toAbsolutePath().normalize();
    }

    static String fileName(String value) {
        return safeName(value) + ".json";
    }

    static String directoryName(String value) {
        return safeName(value);
    }

    private static String safeName(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    static <T> T read(Path path, Class<T> type, ObjectMapper objectMapper) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return objectMapper.readValue(path.toFile(), type);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static void writeAtomic(Path root, Path path, Object value, ObjectMapper objectMapper) {
        Path tempFile = null;
        try {
            Path normalized = normalizeTarget(root, path);
            Files.createDirectories(normalized.getParent());
            tempFile = Files.createTempFile(normalized.getParent(), normalized.getFileName().toString(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
            try {
                Files.move(tempFile, normalized, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, normalized, StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            deleteBestEffort(tempFile);
        }
    }

    static boolean writeAtomicIfAbsent(Path root, Path path, Object value, ObjectMapper objectMapper) {
        boolean targetCreated = false;
        Path normalized = normalizeTarget(root, path);
        try {
            Files.createDirectories(normalized.getParent());
            try (OutputStream output = Files.newOutputStream(
                    normalized,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                targetCreated = true;
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, value);
            }
            return true;
        } catch (FileAlreadyExistsException alreadyClaimed) {
            return false;
        } catch (IOException error) {
            if (targetCreated) {
                deleteBestEffort(normalized);
            }
            throw new UncheckedIOException(error);
        }
    }

    static void deleteIfExists(Path root, Path path) {
        deleteBestEffort(normalizeTarget(root, path));
    }

    static List<Path> listJsonFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    static <T> T withFileLock(Path root, Path path, LockedFileOperation<T> operation) {
        Path normalized = normalizeTarget(root, path);
        Path lockPath = normalized.resolveSibling(normalized.getFileName() + LOCK_SUFFIX);
        Set<Path> heldLocks = HELD_LOCKS.get();
        if (heldLocks.contains(lockPath)) {
            return operation.execute();
        }

        synchronized (lockStripe(lockPath)) {
            heldLocks = HELD_LOCKS.get();
            if (heldLocks.contains(lockPath)) {
                return operation.execute();
            }

            try {
                Files.createDirectories(lockPath.getParent());
                try (FileChannel channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                        FileLock ignored = channel.lock()) {
                    heldLocks.add(lockPath);
                    try {
                        return operation.execute();
                    } finally {
                        heldLocks.remove(lockPath);
                        if (heldLocks.isEmpty()) {
                            HELD_LOCKS.remove();
                        }
                    }
                }
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    private static Path normalizeTarget(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("File persistence path escapes configured root");
        }
        return normalized;
    }

    private static void deleteBestEffort(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; the original persistence error is more important.
        }
    }

    private static Object lockStripe(Path path) {
        int index = Math.floorMod(path.toAbsolutePath().normalize().hashCode(), LOCK_STRIPES.length);
        return LOCK_STRIPES[index];
    }

    private static Object[] lockStripes() {
        Object[] locks = new Object[LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    @FunctionalInterface
    interface LockedFileOperation<T> {
        T execute();
    }
}
