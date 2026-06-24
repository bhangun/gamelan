package tech.kayys.gamelan.core.agent.context;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.gamelan.engine.agent.context.AgentContextCursor;
import tech.kayys.gamelan.engine.agent.context.AgentContextDocument;
import tech.kayys.gamelan.engine.agent.context.AgentContextKey;
import tech.kayys.gamelan.engine.agent.context.AgentContextPage;
import tech.kayys.gamelan.engine.agent.context.AgentContextQuery;
import tech.kayys.gamelan.engine.agent.context.AgentContextStore;

/**
 * Local text-file store for coding-agent style context.
 */
@ApplicationScoped
@IfBuildProperty(name = "gamelan.agent.context.store", stringValue = "file", enableIfMissing = true)
public class FileAgentContextStore implements AgentContextStore {

    private static final String METADATA_SUFFIX = ".meta.properties";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String DEFAULT_CONTENT_TYPE = "text/markdown";
    private static final long DEFAULT_MAX_DOCUMENT_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAX_METADATA_BYTES = 256L * 1024L;
    private static final int DEFAULT_MAX_LIST_RESULTS = 1000;
    private static final Duration STALE_TEMP_FILE_AGE = Duration.ofHours(1);
    private static final int LOCK_STRIPE_COUNT = 256;
    private static final Object[] DOCUMENT_LOCK_STRIPES = lockStripes();
    private static final Comparator<DocumentCandidate> CANDIDATE_ORDER = Comparator
            .comparing((DocumentCandidate candidate) -> candidate.key().scope())
            .thenComparing(candidate -> candidate.key().path());

    private final Path rootDirectory;
    private final long maxDocumentBytes;
    private final long maxMetadataBytes;
    private final int maxListResults;

    @Inject
    public FileAgentContextStore(
            @ConfigProperty(name = "gamelan.agent.context.local.root", defaultValue = ".gamelan/agent-context")
            String rootDirectory,
            @ConfigProperty(name = "gamelan.agent.context.max-document-bytes", defaultValue = "8388608")
            long maxDocumentBytes,
            @ConfigProperty(name = "gamelan.agent.context.max-metadata-bytes", defaultValue = "262144")
            long maxMetadataBytes,
            @ConfigProperty(name = "gamelan.agent.context.max-list-results", defaultValue = "1000")
            int maxListResults) {
        this(Path.of(rootDirectory), maxDocumentBytes, maxMetadataBytes, maxListResults);
    }

    public FileAgentContextStore(Path rootDirectory) {
        this(rootDirectory, DEFAULT_MAX_DOCUMENT_BYTES, DEFAULT_MAX_METADATA_BYTES);
    }

    public FileAgentContextStore(Path rootDirectory, long maxDocumentBytes) {
        this(rootDirectory, maxDocumentBytes, DEFAULT_MAX_METADATA_BYTES);
    }

    public FileAgentContextStore(Path rootDirectory, long maxDocumentBytes, long maxMetadataBytes) {
        this(rootDirectory, maxDocumentBytes, maxMetadataBytes, DEFAULT_MAX_LIST_RESULTS);
    }

    public FileAgentContextStore(Path rootDirectory, long maxDocumentBytes, long maxMetadataBytes, int maxListResults) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.maxDocumentBytes = requirePositiveLimit("maxDocumentBytes", maxDocumentBytes);
        this.maxMetadataBytes = requirePositiveLimit("maxMetadataBytes", maxMetadataBytes);
        this.maxListResults = requirePositiveLimit("maxListResults", maxListResults);
    }

    @Override
    public Uni<AgentContextDocument> save(AgentContextDocument document) {
        requireContentWithinLimit(document.content());
        return Uni.createFrom().item(() -> {
            try {
                Path contentPath = contentPath(document.key());
                return withDocumentLock(contentPath, () -> {
                    AgentContextDocument stored = new AgentContextDocument(
                            document.key(),
                            document.content(),
                            document.contentType(),
                            document.metadata(),
                            Instant.now());
                    String metadataText = metadataText(stored);
                    writeContent(contentPath, stored.content());
                    writeMetadata(metadataText, contentPath);
                    return stored;
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public Uni<Optional<AgentContextDocument>> load(AgentContextKey key) {
        return Uni.createFrom().item(() -> {
            try {
                Path contentPath = contentPath(key);
                return withDocumentLock(contentPath, () -> {
                    if (!isSafeRegularFile(contentPath)) {
                        return Optional.empty();
                    }
                    requireFileWithinLimit(contentPath);
                    String content = readStringNoFollow(contentPath);
                    Metadata metadata = readMetadata(contentPath);
                    return Optional.of(new AgentContextDocument(
                            key,
                            content,
                            metadata.contentType(),
                            metadata.values(),
                            lastModifiedNoFollow(contentPath)));
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public Uni<AgentContextDocument> append(AgentContextKey key, String content, Map<String, String> metadata) {
        requireContentWithinLimit(content);
        return Uni.createFrom().item(() -> {
            try {
                Path contentPath = contentPath(key);
                return withDocumentLock(contentPath, () -> {
                    boolean existingFile = isSafeRegularFile(contentPath);
                    if (existingFile) {
                        requireFileWithinLimit(contentPath);
                    }
                    String existingContent = existingFile ? readStringNoFollow(contentPath) : "";
                    Metadata existingMetadata = readMetadata(contentPath);
                    AgentContextDocument document = new AgentContextDocument(
                            key,
                            existingContent + (content != null ? content : ""),
                            existingMetadata.contentType(),
                            merge(existingMetadata.values(), metadata),
                            Instant.now());
                    requireContentWithinLimit(document.content());
                    String metadataText = metadataText(document);
                    writeContent(contentPath, document.content());
                    writeMetadata(metadataText, contentPath);
                    return document;
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public Uni<List<AgentContextDocument>> list(AgentContextQuery query) {
        return list(query, maxListResults);
    }

    @Override
    public Uni<AgentContextPage> listPage(AgentContextQuery query) {
        AgentContextQuery requestedQuery = effectivePageQuery(query);
        AgentContextQuery storageQuery = continuationProbeQuery(requestedQuery);
        return list(storageQuery, continuationProbeLimit())
                .map(documents -> AgentContextPage.from(requestedQuery, documents));
    }

    private Uni<List<AgentContextDocument>> list(AgentContextQuery query, int maxAllowedResults) {
        return Uni.createFrom().item(() -> {
            Objects.requireNonNull(query, "AgentContextQuery cannot be null");
            long resultLimit = maxResults(query, maxAllowedResults);
            Path workspaceRoot = workspaceRoot(query);
            Path scanRoot = scanRoot(query, workspaceRoot);
            try {
                if (!isSafeDirectory(scanRoot)) {
                    return List.of();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            Instant staleTempCutoff = Instant.now().minus(STALE_TEMP_FILE_AGE);
            try (Stream<Path> files = Files.walk(scanRoot)) {
                return files.filter(this::isSafeRegularFileForListing)
                        .filter(path -> isContentFileForListing(path, staleTempCutoff))
                        .map(path -> candidateFromPath(query, workspaceRoot, path))
                        .flatMap(Optional::stream)
                        .filter(candidate -> isAfterCursor(candidate.key(), query.after()))
                        .filter(candidate -> isWithinFileLimitForListing(candidate.contentPath()))
                        .sorted(CANDIDATE_ORDER)
                        .limit(resultLimit)
                        .map(this::documentFromCandidate)
                        .flatMap(Optional::stream)
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public Uni<Void> delete(AgentContextKey key) {
        return Uni.createFrom().voidItem()
                .invoke(() -> {
                    try {
                        Path contentPath = contentPath(key);
                        withDocumentLock(contentPath, () -> {
                            Files.deleteIfExists(contentPath);
                            Files.deleteIfExists(metadataPath(contentPath));
                            return null;
                        });
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private Optional<DocumentCandidate> candidateFromPath(
            AgentContextQuery query,
            Path workspaceRoot,
            Path contentPath) {
        try {
            Path relative = workspaceRoot.relativize(contentPath);
            if (relative.getNameCount() < 2) {
                return Optional.empty();
            }
            String scope = relative.getName(0).toString();
            if (query.scope() != null && !query.scope().equals(scope)) {
                return Optional.empty();
            }
            String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
            if (query.pathPrefix() != null && !path.startsWith(query.pathPrefix())) {
                return Optional.empty();
            }
            AgentContextKey key = new AgentContextKey(query.tenantId(), query.workspaceId(), scope, path);
            return Optional.of(new DocumentCandidate(key, contentPath));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Optional<AgentContextDocument> documentFromCandidate(DocumentCandidate candidate) {
        try {
            return withDocumentLock(candidate.contentPath(), () -> {
                if (!isSafeRegularFile(candidate.contentPath())) {
                    return Optional.empty();
                }
                requireFileWithinLimit(candidate.contentPath());
                String content = readStringNoFollow(candidate.contentPath());
                Metadata metadata = readMetadata(candidate.contentPath());
                return Optional.of(new AgentContextDocument(
                        candidate.key(),
                        content,
                        metadata.contentType(),
                        metadata.values(),
                        lastModifiedNoFollow(candidate.contentPath())));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Path workspaceRoot(AgentContextQuery query) {
        return rootDirectory
                .resolve(query.tenantId().value())
                .resolve(query.workspaceId())
                .normalize();
    }

    private Path scanRoot(AgentContextQuery query, Path workspaceRoot) {
        Path root = workspaceRoot;
        if (query.scope() != null) {
            root = root.resolve(query.scope());
            String prefixDirectory = prefixDirectory(query.pathPrefix());
            if (prefixDirectory != null) {
                root = root.resolve(prefixDirectory);
            }
        }
        Path normalized = root.normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Agent context query path escapes workspace root");
        }
        return normalized;
    }

    private String prefixDirectory(String pathPrefix) {
        if (pathPrefix == null) {
            return null;
        }
        if (pathPrefix.endsWith("/")) {
            return pathPrefix.substring(0, pathPrefix.length() - 1);
        }
        int separator = pathPrefix.lastIndexOf('/');
        return separator > 0 ? pathPrefix.substring(0, separator) : null;
    }

    private AgentContextQuery effectivePageQuery(AgentContextQuery query) {
        Objects.requireNonNull(query, "AgentContextQuery cannot be null");
        int pageSize = (int) maxResults(query, maxListResults);
        if (query.maxResults() != null) {
            return query;
        }
        return new AgentContextQuery(
                query.tenantId(),
                query.workspaceId(),
                query.scope(),
                query.pathPrefix(),
                pageSize,
                query.after());
    }

    private AgentContextQuery continuationProbeQuery(AgentContextQuery query) {
        if (query.maxResults() == Integer.MAX_VALUE) {
            return query;
        }
        return new AgentContextQuery(
                query.tenantId(),
                query.workspaceId(),
                query.scope(),
                query.pathPrefix(),
                query.maxResults() + 1,
                query.after());
    }

    private int continuationProbeLimit() {
        return maxListResults == Integer.MAX_VALUE ? maxListResults : maxListResults + 1;
    }

    private long maxResults(AgentContextQuery query, int maxAllowedResults) {
        if (query.maxResults() == null) {
            return maxListResults;
        }
        if (query.maxResults() > maxAllowedResults) {
            throw new IllegalArgumentException(
                    "Agent context list request exceeds max result limit of " + maxAllowedResults + ": "
                            + query.maxResults());
        }
        return query.maxResults();
    }

    private boolean isAfterCursor(AgentContextKey key, AgentContextCursor after) {
        if (after == null) {
            return true;
        }
        int scopeComparison = key.scope().compareTo(after.scope());
        if (scopeComparison != 0) {
            return scopeComparison > 0;
        }
        return key.path().compareTo(after.path()) > 0;
    }

    private Path contentPath(AgentContextKey key) {
        Path resolved = rootDirectory
                .resolve(key.tenantId().value())
                .resolve(key.workspaceId())
                .resolve(key.scope())
                .resolve(key.path())
                .normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Agent context path escapes configured root");
        }
        return resolved;
    }

    private void writeContent(Path contentPath, String content) throws IOException {
        writeStringAtomic(contentPath, content != null ? content : "");
    }

    private static long requirePositiveLimit(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static int requirePositiveLimit(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private void requireContentWithinLimit(String content) {
        long bytes = (content != null ? content : "").getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxDocumentBytes) {
            throw new IllegalArgumentException(
                    "Agent context document exceeds max size of " + maxDocumentBytes + " bytes: " + bytes);
        }
    }

    private void requireFileWithinLimit(Path contentPath) throws IOException {
        long bytes = Files.readAttributes(contentPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        if (bytes > maxDocumentBytes) {
            throw new IllegalArgumentException(
                    "Agent context document exceeds max size of " + maxDocumentBytes + " bytes: " + bytes);
        }
    }

    private boolean isWithinFileLimitForListing(Path contentPath) {
        try {
            requireFileWithinLimit(contentPath);
            return true;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private String metadataText(AgentContextDocument document) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("contentType", document.contentType());
        properties.setProperty("updatedAt", document.updatedAt().toString());
        document.metadata().forEach((key, value) -> properties.setProperty("metadata." + key, value));
        StringWriter writer = new StringWriter();
        properties.store(writer, "Gamelan agent context metadata");
        String text = writer.toString();
        requireMetadataWithinLimit(text);
        return text;
    }

    private void requireMetadataWithinLimit(String metadataText) {
        long bytes = (metadataText != null ? metadataText : "").getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxMetadataBytes) {
            throw new IllegalArgumentException(
                    "Agent context metadata exceeds max size of " + maxMetadataBytes + " bytes: " + bytes);
        }
    }

    private void writeMetadata(String metadataText, Path contentPath) throws IOException {
        Path metadataPath = metadataPath(contentPath);
        Path tempFile = createSiblingTempFile(metadataPath);
        Files.writeString(tempFile, metadataText, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            moveAtomically(tempFile, metadataPath);
            tempFile = null;
        } finally {
            deleteBestEffort(tempFile);
        }
    }

    private Metadata readMetadata(Path contentPath) throws IOException {
        Path metadataPath = metadataPath(contentPath);
        if (!isSafeRegularFile(metadataPath)) {
            return defaultMetadata();
        }
        if (!isWithinMetadataFileLimit(metadataPath)) {
            return defaultMetadata();
        }

        Properties properties = new Properties();
        try (var channel = FileChannel.open(metadataPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var reader = Channels.newReader(channel, StandardCharsets.UTF_8.newDecoder(), -1)) {
            properties.load(reader);
        } catch (IllegalArgumentException ignored) {
            return defaultMetadata();
        }

        Map<String, String> metadata = properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("metadata."))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        name -> name.substring("metadata.".length()),
                        properties::getProperty));
        return new Metadata(properties.getProperty("contentType", DEFAULT_CONTENT_TYPE), metadata);
    }

    private boolean isWithinMetadataFileLimit(Path metadataPath) throws IOException {
        long bytes = Files.readAttributes(metadataPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        return bytes <= maxMetadataBytes;
    }

    private Metadata defaultMetadata() {
        return new Metadata(DEFAULT_CONTENT_TYPE, Map.of());
    }

    private String readStringNoFollow(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var reader = Channels.newReader(channel, StandardCharsets.UTF_8.newDecoder(), -1)) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
            return content.toString();
        }
    }

    private Instant lastModifiedNoFollow(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .lastModifiedTime()
                .toInstant();
    }

    private Path metadataPath(Path contentPath) {
        return contentPath.resolveSibling(contentPath.getFileName() + METADATA_SUFFIX);
    }

    private Path lockPath(Path contentPath) {
        return contentPath.resolveSibling(contentPath.getFileName() + LOCK_SUFFIX);
    }

    private boolean isSidecarFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(METADATA_SUFFIX)
                || fileName.endsWith(LOCK_SUFFIX)
                || fileName.endsWith(TEMP_SUFFIX);
    }

    private boolean isContentFileForListing(Path path, Instant staleTempCutoff) {
        if (isTempFile(path)) {
            deleteStaleTempFile(path, staleTempCutoff);
            return false;
        }
        return !isSidecarFile(path);
    }

    private boolean isTempFile(Path path) {
        return path.getFileName().toString().endsWith(TEMP_SUFFIX);
    }

    private void deleteStaleTempFile(Path path, Instant staleTempCutoff) {
        try {
            Instant updatedAt = lastModifiedNoFollow(path);
            if (updatedAt.isBefore(staleTempCutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Stale temp cleanup is best-effort and must not break context listing.
        }
    }

    private Map<String, String> merge(Map<String, String> existing, Map<String, String> updates) {
        java.util.HashMap<String, String> merged = new java.util.HashMap<>(existing);
        if (updates != null) {
            merged.putAll(updates);
        }
        return Map.copyOf(merged);
    }

    private Object lock(Path contentPath) {
        int index = Math.floorMod(contentPath.toAbsolutePath().normalize().hashCode(), DOCUMENT_LOCK_STRIPES.length);
        return DOCUMENT_LOCK_STRIPES[index];
    }

    private <T> T withDocumentLock(Path contentPath, LockedAction<T> action) throws IOException {
        Path normalized = contentPath.toAbsolutePath().normalize();
        synchronized (lock(normalized)) {
            Path lockPath = lockPath(normalized);
            createDirectoriesInsideRoot(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                return action.execute();
            }
        }
    }

    private void writeStringAtomic(Path targetPath, String value) throws IOException {
        Path tempFile = createSiblingTempFile(targetPath);
        try {
            Files.writeString(tempFile, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            moveAtomically(tempFile, targetPath);
            tempFile = null;
        } finally {
            deleteBestEffort(tempFile);
        }
    }

    private Path createSiblingTempFile(Path targetPath) throws IOException {
        createDirectoriesInsideRoot(targetPath.getParent());
        return Files.createTempFile(targetPath.getParent(), targetPath.getFileName().toString(), TEMP_SUFFIX);
    }

    private void createDirectoriesInsideRoot(Path directory) throws IOException {
        Path normalized = normalizeInsideRoot(directory);
        if (Files.exists(rootDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(rootDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Agent context root is not a directory: " + rootDirectory);
        }
        Files.createDirectories(rootDirectory);

        Path current = rootDirectory;
        for (Path name : rootDirectory.relativize(normalized)) {
            current = current.resolve(name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IOException("Agent context path contains a symbolic link: " + current);
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Agent context path segment is not a directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private boolean isSafeDirectory(Path directory) throws IOException {
        Path normalized = normalizeInsideRoot(directory);
        return !containsSymbolicLink(normalized)
                && Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isSafeRegularFile(Path path) throws IOException {
        Path normalized = normalizeInsideRoot(path);
        return !containsSymbolicLink(normalized)
                && Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isSafeRegularFileForListing(Path path) {
        try {
            return isSafeRegularFile(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean containsSymbolicLink(Path path) {
        Path current = rootDirectory;
        if (Files.isSymbolicLink(current)) {
            return true;
        }
        for (Path name : rootDirectory.relativize(path)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private Path normalizeInsideRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Agent context path escapes configured root");
        }
        return normalized;
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteBestEffort(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The original write/move failure is more useful than cleanup failure.
        }
    }

    private record Metadata(String contentType, Map<String, String> values) {
    }

    private record DocumentCandidate(AgentContextKey key, Path contentPath) {
    }

    private static Object[] lockStripes() {
        Object[] locks = new Object[LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    @FunctionalInterface
    private interface LockedAction<T> {
        T execute() throws IOException;
    }
}
