package tech.kayys.gamelan.core.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tech.kayys.gamelan.engine.agent.context.AgentContextCursor;
import tech.kayys.gamelan.engine.agent.context.AgentContextDocument;
import tech.kayys.gamelan.engine.agent.context.AgentContextKey;
import tech.kayys.gamelan.engine.agent.context.AgentContextQuery;
import tech.kayys.gamelan.engine.agent.context.AgentContextScopes;
import tech.kayys.gamelan.engine.tenant.TenantId;

class FileAgentContextStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveLoadAppendListAndDeleteTextDocuments() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/thread-1.md");

        store.save(new AgentContextDocument(
                key,
                "# Thread\n",
                "text/markdown",
                Map.of("agent", "codex"),
                Instant.now())).await().indefinitely();

        store.append(key, "user: lanjut\n", Map.of("turns", "1")).await().indefinitely();

        AgentContextDocument loaded = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("# Thread\nuser: lanjut\n", loaded.content());
        assertEquals("codex", loaded.metadata().get("agent"));
        assertEquals("1", loaded.metadata().get("turns"));

        var documents = store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD)).await().indefinitely();
        assertEquals(1, documents.size());
        assertEquals("threads/thread-1.md", documents.get(0).key().path());

        Path contentPath = tempDir.resolve("tenant-1/workspace-1/thread/threads/thread-1.md");
        assertTrue(Files.isRegularFile(contentPath));

        store.delete(key).await().indefinitely();
        assertFalse(store.load(key).await().indefinitely().isPresent());
        assertFalse(Files.exists(contentPath));
    }

    @Test
    void keyRejectsTraversalPaths() {
        assertThrows(IllegalArgumentException.class, () -> new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.WORKSPACE,
                "../AGENTS.md"));
    }

    @Test
    void storeKeepsOfflineCodingAgentFilesTextNative() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        AgentContextKey agents = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.WORKSPACE,
                "AGENTS.md");
        AgentContextKey skill = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.SKILL,
                "java/SKILL.md");

        store.save(new AgentContextDocument(agents, "# Agent\n", "text/markdown", Map.of(), Instant.EPOCH))
                .await()
                .indefinitely();
        store.append(skill, "# Java Skill\n", Map.of("agent", "codex")).await().indefinitely();

        assertEquals("# Agent\n", Files.readString(tempDir.resolve("tenant-1/workspace-1/workspace/AGENTS.md")));
        assertEquals("# Java Skill\n", Files.readString(tempDir.resolve("tenant-1/workspace-1/skill/java/SKILL.md")));
        assertTrue(Files.isRegularFile(
                tempDir.resolve("tenant-1/workspace-1/skill/java/SKILL.md.meta.properties")));
    }

    @Test
    void listSkipsInvalidArtifactsAndReturnsDeterministicOrder() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        store.save(document(AgentContextScopes.THREAD, "threads/z.md", "thread\n")).await().indefinitely();
        store.save(document(AgentContextScopes.WORKSPACE, "AGENTS.md", "agents\n")).await().indefinitely();
        store.save(document(AgentContextScopes.SKILL, "java/SKILL.md", "skill\n")).await().indefinitely();

        Path invalidScopeArtifact = tempDir.resolve("tenant-1/workspace-1/bad..scope/doc.md");
        Files.createDirectories(invalidScopeArtifact.getParent());
        Files.writeString(invalidScopeArtifact, "ignore me\n");

        List<String> keys = store.list(new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", null))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();

        assertEquals(List.of(
                "skill:java/SKILL.md",
                "thread:threads/z.md",
                "workspace:AGENTS.md"), keys);
    }

    @Test
    void listSupportsPathPrefixQueries() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        store.save(document(AgentContextScopes.THREAD, "threads/session-1.md", "thread-1\n"))
                .await()
                .indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-2.md", "thread-2\n"))
                .await()
                .indefinitely();
        store.save(document(AgentContextScopes.THREAD, "notes/session-1.md", "note\n"))
                .await()
                .indefinitely();
        store.save(document(AgentContextScopes.SKILL, "threads/session-3.md", "skill\n"))
                .await()
                .indefinitely();

        List<String> scopedKeys = store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session-"))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();
        List<String> crossScopeKeys = store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                null,
                "threads/session-"))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();

        assertEquals(List.of(
                "thread:threads/session-1.md",
                "thread:threads/session-2.md"), scopedKeys);
        assertEquals(List.of(
                "skill:threads/session-3.md",
                "thread:threads/session-1.md",
                "thread:threads/session-2.md"), crossScopeKeys);
    }

    @Test
    void listSupportsMaxResultsAfterDeterministicOrdering() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        store.save(document(AgentContextScopes.WORKSPACE, "AGENTS.md", "agents\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-2.md", "thread-2\n")).await().indefinitely();
        store.save(document(AgentContextScopes.SKILL, "java/SKILL.md", "skill\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-1.md", "thread-1\n")).await().indefinitely();

        List<String> keys = store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                null,
                null,
                2))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();

        assertEquals(List.of(
                "skill:java/SKILL.md",
                "thread:threads/session-1.md"), keys);
    }

    @Test
    void listAppliesConfiguredDefaultLimitWhenMaxResultsIsOmitted() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 1024, 2048, 2);
        store.save(document(AgentContextScopes.WORKSPACE, "AGENTS.md", "agents\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-2.md", "thread-2\n")).await().indefinitely();
        store.save(document(AgentContextScopes.SKILL, "java/SKILL.md", "skill\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-1.md", "thread-1\n")).await().indefinitely();

        List<String> keys = store.list(new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", null))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();

        assertEquals(List.of(
                "skill:java/SKILL.md",
                "thread:threads/session-1.md"), keys);
    }

    @Test
    void listRejectsMaxResultsAboveConfiguredLimit() {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 1024, 2048, 2);

        assertThrows(IllegalArgumentException.class, () -> store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                null,
                null,
                3)).await().indefinitely());
    }

    @Test
    void listPageUsesConfiguredDefaultLimitAndContinuationWhenMaxResultsIsOmitted() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 1024, 2048, 2);
        store.save(document(AgentContextScopes.WORKSPACE, "AGENTS.md", "agents\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-2.md", "thread-2\n")).await().indefinitely();
        store.save(document(AgentContextScopes.SKILL, "java/SKILL.md", "skill\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-1.md", "thread-1\n")).await().indefinitely();
        AgentContextQuery query = new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", null);

        var page = store.listPage(query).await().indefinitely();

        List<String> keys = page.documents().stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();
        assertEquals(List.of(
                "skill:java/SKILL.md",
                "thread:threads/session-1.md"), keys);
        assertTrue(page.hasContinuation());

        AgentContextQuery nextQuery = page.nextQuery(query).orElseThrow();
        assertEquals(2, nextQuery.maxResults());
        assertEquals(new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-1.md"), nextQuery.after());
    }

    @Test
    void listSupportsCursorPaginationAfterDeterministicOrdering() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        store.save(document(AgentContextScopes.WORKSPACE, "AGENTS.md", "agents\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-2.md", "thread-2\n")).await().indefinitely();
        store.save(document(AgentContextScopes.SKILL, "java/SKILL.md", "skill\n")).await().indefinitely();
        store.save(document(AgentContextScopes.THREAD, "threads/session-1.md", "thread-1\n")).await().indefinitely();

        List<String> secondPage = store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                null,
                null,
                2,
                new AgentContextCursor(AgentContextScopes.THREAD, "threads/session-1.md")))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();

        assertEquals(List.of(
                "thread:threads/session-2.md",
                "workspace:AGENTS.md"), secondPage);
    }

    @Test
    void saveRejectsDocumentsExceedingConfiguredByteLimit() {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 5);
        AgentContextDocument document = document(AgentContextScopes.THREAD, "threads/large.md", "123456");

        assertThrows(IllegalArgumentException.class, () -> store.save(document).await().indefinitely());
        assertFalse(Files.exists(tempDir.resolve("tenant-1/workspace-1/thread/threads/large.md")));
    }

    @Test
    void saveRejectsMetadataExceedingConfiguredByteLimitBeforeWritingContent() {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 1024, 512);
        AgentContextDocument document = new AgentContextDocument(
                new AgentContextKey(
                        TenantId.of("tenant-1"),
                        "workspace-1",
                        AgentContextScopes.THREAD,
                        "threads/large-metadata.md"),
                "safe content\n",
                "text/markdown",
                Map.of("blob", "x".repeat(1024)),
                Instant.EPOCH);

        assertThrows(IllegalArgumentException.class, () -> store.save(document).await().indefinitely());
        assertFalse(Files.exists(tempDir.resolve("tenant-1/workspace-1/thread/threads/large-metadata.md")));
    }

    @Test
    void appendRejectsDocumentsExceedingConfiguredByteLimitWithoutMutatingExistingContent() {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 5);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        store.append(key, "12345").await().indefinitely();

        assertThrows(IllegalArgumentException.class, () -> store.append(key, "6").await().indefinitely());

        AgentContextDocument loaded = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("12345", loaded.content());
    }

    @Test
    void appendRejectsMetadataExceedingConfiguredByteLimitWithoutMutatingExistingContent() {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 1024, 512);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        store.append(key, "base\n", Map.of("agent", "codex")).await().indefinitely();

        assertThrows(IllegalArgumentException.class,
                () -> store.append(key, "mutation\n", Map.of("blob", "x".repeat(1024))).await().indefinitely());

        AgentContextDocument loaded = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("base\n", loaded.content());
        assertEquals("codex", loaded.metadata().get("agent"));
        assertFalse(loaded.metadata().containsKey("blob"));
    }

    @Test
    void appendRejectsOversizedManualLocalFilesBeforeMutation() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 5);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/manual.md");
        Path contentPath = tempDir.resolve("tenant-1/workspace-1/thread/threads/manual.md");
        Files.createDirectories(contentPath.getParent());
        Files.writeString(contentPath, "123456");

        assertThrows(IllegalArgumentException.class, () -> store.append(key, "x").await().indefinitely());
        assertEquals("123456", Files.readString(contentPath));
    }

    @Test
    void listSkipsOversizedManualLocalFiles() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 5);
        Path contentPath = tempDir.resolve("tenant-1/workspace-1/thread/threads/manual.md");
        Files.createDirectories(contentPath.getParent());
        Files.writeString(contentPath, "123456");

        List<AgentContextDocument> documents = store.list(
                new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD))
                .await()
                .indefinitely();

        assertTrue(documents.isEmpty());
    }

    @Test
    void loadIgnoresOversizedManualMetadataSidecar() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 1024, 5);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/manual.md");
        Path contentPath = tempDir.resolve("tenant-1/workspace-1/thread/threads/manual.md");
        Files.createDirectories(contentPath.getParent());
        Files.writeString(contentPath, "recover content\n");
        Files.writeString(contentPath.resolveSibling("manual.md.meta.properties"),
                "contentType=text/plain\nmetadata.agent=codex\n");

        AgentContextDocument loaded = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("recover content\n", loaded.content());
        assertEquals("text/markdown", loaded.contentType());
        assertTrue(loaded.metadata().isEmpty());
    }

    @Test
    void listSkipsOversizedManualLocalFilesBeforeApplyingMaxResults() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir, 5);
        Path oversizedPath = tempDir.resolve("tenant-1/workspace-1/skill/java/SKILL.md");
        Files.createDirectories(oversizedPath.getParent());
        Files.writeString(oversizedPath, "123456");
        store.save(document(AgentContextScopes.THREAD, "threads/session.md", "ok")).await().indefinitely();

        List<String> keys = store.list(new AgentContextQuery(
                TenantId.of("tenant-1"),
                "workspace-1",
                null,
                null,
                1))
                .await()
                .indefinitely()
                .stream()
                .map(document -> document.key().scope() + ":" + document.key().path())
                .toList();

        assertEquals(List.of("thread:threads/session.md"), keys);
    }

    @Test
    void malformedMetadataSidecarDoesNotHideRecoverableContent() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        store.save(new AgentContextDocument(
                key,
                "assistant: recover this\n",
                "text/markdown",
                Map.of("agent", "codex"),
                Instant.EPOCH)).await().indefinitely();
        Path metadataPath = tempDir.resolve(
                "tenant-1/workspace-1/thread/threads/session.md.meta.properties");
        Files.writeString(metadataPath, "contentType=text/markdown\nmetadata.agent=codex\nbad=" + "\\" + "u12\n");

        AgentContextDocument loaded = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("assistant: recover this\n", loaded.content());
        assertEquals("text/markdown", loaded.contentType());
        assertTrue(loaded.metadata().isEmpty());

        assertEquals(1, store.list(new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD))
                .await()
                .indefinitely()
                .size());

        store.append(key, "user: continue\n", Map.of("turn", "2")).await().indefinitely();

        AgentContextDocument repaired = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("assistant: recover this\nuser: continue\n", repaired.content());
        assertEquals("2", repaired.metadata().get("turn"));
    }

    @Test
    void symlinkedMetadataSidecarIsIgnoredAndRepairedInsideStore() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        store.save(new AgentContextDocument(
                key,
                "assistant: local\n",
                "text/markdown",
                Map.of("agent", "codex"),
                Instant.EPOCH)).await().indefinitely();
        Path outsideMetadata = tempDir.resolve("outside-session.md.meta.properties");
        Path metadataPath = tempDir.resolve(
                "tenant-1/workspace-1/thread/threads/session.md.meta.properties");
        Files.writeString(outsideMetadata, "contentType=text/plain\nmetadata.agent=outside\n");
        Files.delete(metadataPath);
        createSymbolicLinkOrSkip(metadataPath, outsideMetadata);

        AgentContextDocument loaded = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("assistant: local\n", loaded.content());
        assertEquals("text/markdown", loaded.contentType());
        assertTrue(loaded.metadata().isEmpty());

        store.append(key, "user: repair sidecar\n", Map.of("turn", "2")).await().indefinitely();

        assertFalse(Files.isSymbolicLink(metadataPath));
        assertEquals("contentType=text/plain\nmetadata.agent=outside\n", Files.readString(outsideMetadata));
        AgentContextDocument repaired = store.load(key).await().indefinitely().orElseThrow();
        assertEquals("2", repaired.metadata().get("turn"));
    }

    @Test
    void listCleansOnlyStaleTemporaryFiles() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        store.save(new AgentContextDocument(
                key,
                "assistant: stable\n",
                "text/markdown",
                Map.of(),
                Instant.EPOCH)).await().indefinitely();
        Path directory = tempDir.resolve("tenant-1/workspace-1/thread/threads");
        Path staleTemp = directory.resolve("session.md-stale.tmp");
        Path freshTemp = directory.resolve("session.md-fresh.tmp");
        Files.writeString(staleTemp, "stale\n");
        Files.writeString(freshTemp, "fresh\n");
        Files.setLastModifiedTime(staleTemp, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        List<AgentContextDocument> documents = store.list(
                new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD))
                .await()
                .indefinitely();

        assertEquals(1, documents.size());
        assertFalse(Files.exists(staleTemp));
        assertTrue(Files.exists(freshTemp));
    }

    @Test
    void loadAndListIgnoreSymlinkedContentFiles() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        Path outsideFile = tempDir.resolve("outside-secret.md");
        Path contentPath = tempDir.resolve("tenant-1/workspace-1/thread/threads/session.md");
        Files.writeString(outsideFile, "secret\n");
        Files.createDirectories(contentPath.getParent());
        createSymbolicLinkOrSkip(contentPath, outsideFile);

        assertFalse(store.load(key).await().indefinitely().isPresent());
        assertTrue(store.list(new AgentContextQuery(TenantId.of("tenant-1"), "workspace-1", AgentContextScopes.THREAD))
                .await()
                .indefinitely()
                .isEmpty());
    }

    @Test
    void appendRejectsSymlinkedScopeDirectories() throws Exception {
        FileAgentContextStore store = new FileAgentContextStore(tempDir);
        Path outsideDirectory = tempDir.resolve("outside-scope");
        Path scopeDirectory = tempDir.resolve("tenant-1/workspace-1/thread");
        Files.createDirectories(outsideDirectory);
        Files.createDirectories(scopeDirectory.getParent());
        createSymbolicLinkOrSkip(scopeDirectory, outsideDirectory);

        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/session.md");
        assertThrows(UncheckedIOException.class,
                () -> store.append(key, "must stay inside store\n").await().indefinitely());
        assertFalse(Files.exists(outsideDirectory.resolve("threads/session.md")));
    }

    @Test
    void concurrentAppendsDoNotLoseThreadHistory() {
        List<FileAgentContextStore> stores = IntStream.range(0, 4)
                .mapToObj(ignored -> new FileAgentContextStore(tempDir))
                .toList();
        AgentContextKey key = new AgentContextKey(
                TenantId.of("tenant-1"),
                "workspace-1",
                AgentContextScopes.THREAD,
                "threads/concurrent.md");
        int appendCount = 64;
        stores.get(0).load(key).await().indefinitely();
        var executor = Executors.newFixedThreadPool(8);
        try {
            CompletableFuture<?>[] futures = IntStream.range(0, appendCount)
                    .mapToObj(index -> CompletableFuture.runAsync(
                            () -> stores.get(index % stores.size()).append(
                                    key,
                                    "turn-" + index + "\n",
                                    Map.of("turn-" + index, String.valueOf(index)))
                                    .await().indefinitely(),
                            executor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        } finally {
            executor.shutdownNow();
        }

        AgentContextDocument loaded = stores.get(0).load(key).await().indefinitely().orElseThrow();
        List<String> lines = loaded.content().lines().toList();
        assertEquals(appendCount, lines.size());
        for (int index = 0; index < appendCount; index++) {
            assertTrue(lines.contains("turn-" + index));
            assertEquals(String.valueOf(index), loaded.metadata().get("turn-" + index));
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException error) {
            assumeTrue(false, "Symbolic links are not supported in this environment");
        }
    }

    private static AgentContextDocument document(String scope, String path, String content) {
        return new AgentContextDocument(
                new AgentContextKey(TenantId.of("tenant-1"), "workspace-1", scope, path),
                content,
                "text/markdown",
                Map.of(),
                Instant.EPOCH);
    }
}
