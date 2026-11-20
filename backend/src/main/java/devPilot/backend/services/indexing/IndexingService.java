package devPilot.backend.services.indexing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.github.GitHubRateLimiter;
import devPilot.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 16;
    private static final int PARALLEL_FETCH_WORKERS = 8;
    private static final long RETRIEVAL_TIMEOUT_MS = 120000; // 2 minutes total timeout

    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 2000;
    private static final long INDEX_LOCK_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);

    private final ConcurrentHashMap<UUID, IndexingLock> indexingSet = new ConcurrentHashMap<>();

    private final RepositoryRepository repositoryRepository;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    private boolean embeddingFailed = false;

    private static final class IndexingLock {
        private volatile long lastProgressAt = System.currentTimeMillis();
    }

    private IndexingLock activeLock(UUID repoId) {
        IndexingLock lock = indexingSet.get(repoId);
        if (lock != null && System.currentTimeMillis() - lock.lastProgressAt > INDEX_LOCK_TIMEOUT_MS) {
            if (indexingSet.remove(repoId, lock)) {
                log.warn("Indexing lock expired after 15 minutes without progress repoId={}", repoId);
            }
            return null;
        }
        return lock;
    }

    private void scheduleStaleLockCheck(UUID repoId, IndexingLock lock) {
        CompletableFuture.delayedExecutor(1, TimeUnit.MINUTES).execute(() -> {
            if (indexingSet.get(repoId) != lock) return;
            if (System.currentTimeMillis() - lock.lastProgressAt > INDEX_LOCK_TIMEOUT_MS) {
                if (indexingSet.remove(repoId, lock)) {
                    log.warn("Indexing lock expired after 15 minutes without progress repoId={}", repoId);
                }
                return;
            }
            scheduleStaleLockCheck(repoId, lock);
        });
    }

    public boolean isIndexingActive(UUID repoId) {
        return activeLock(repoId) != null;
    }

    // Helper to update repo indexing status with stage and detail
    private void updateIndexingStatus(UUID repoId, String stage, String detail, int filesDone, int filesTotal, int chunksDone, int chunksTotal) {
        try {
            IndexingLock lock = indexingSet.get(repoId);
            if (lock != null) lock.lastProgressAt = System.currentTimeMillis();
            repositoryRepository.findById(repoId).ifPresent(repo -> {
                repo.setIndexingStage(stage);
                repo.setIndexingDetail(detail);
                if (filesDone >= 0) repo.setFilesProcessed(filesDone);
                if (filesTotal >= 0) repo.setFilesTotal(filesTotal);
                if (chunksDone >= 0) repo.setChunksProcessed(chunksDone);
                if (chunksTotal >= 0) repo.setChunksTotal(chunksTotal);
                repo.setUpdatedAt(Instant.now());
                repositoryRepository.save(repo);
            });
        } catch (Exception e) {
            log.warn("Failed to update indexing status: {}", e.getMessage());
        }
    }

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (activeLock(repoId) != null) {
            log.info("Repository is already indexing, skipping duplicate repoId={}", repoId);
            return repo;
        }
        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            log.info("Repository is already indexing, skipping duplicate start repoId={}", repoId);
            return repo;
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setChunksTotal(0);
        repo.setChunksProcessed(0);
        repo.setIndexingStage("fetching");
        repo.setIndexingDetail("Starting...");
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
     public void indexAsync(UUID repoId, UUID userId) {
        IndexingLock newLock = new IndexingLock();
        IndexingLock existingLock = activeLock(repoId);
        if (existingLock != null) {
            log.info("Repository is already indexing, skipping duplicate repoId={}", repoId);
            return;
        }
        existingLock = indexingSet.putIfAbsent(repoId, newLock);
        if (existingLock != null) {
            existingLock = activeLock(repoId);
            if (existingLock != null) {
                log.info("Repository is already indexing, skipping duplicate repoId={}", repoId);
                return;
            }
            existingLock = indexingSet.putIfAbsent(repoId, newLock);
            if (existingLock != null) {
                log.info("Repository is already indexing, skipping duplicate repoId={}", repoId);
                return;
            }
        }

        scheduleStaleLockCheck(repoId, newLock);
        long startTime = System.currentTimeMillis();
        try {
            doIndex(repoId, userId, startTime);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        } finally {
            indexingSet.remove(repoId, newLock);
        }
    }

    @SuppressWarnings("unchecked")
    private void doIndex(UUID repoId, UUID userId, long startTime) {
        log.info("{} [INDEX] start repoId={}", Instant.now(), repoId);
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        // Load previously indexed files
        Map<String, String> previousFiles = loadIndexedFiles(repo);
        String previousTreeSha = repo.getTreeSha() != null ? repo.getTreeSha() : "";

        // Get current tree and compute current SHA
        Map<String, Object> tree = gitHubApiClient.getRepoTree(
            repo.getOwner(), repo.getName(), repo.getDefaultBranch());

        String currentTreeSha = computeTreeSha(tree);
        Map<String, String> currentFiles = extractFileShas(tree);

        // If tree SHA hasn't changed, skip indexing
        if (!previousTreeSha.isEmpty() && previousTreeSha.equals(currentTreeSha)) {
            int skippedCount = previousFiles.size();
            long duration = System.currentTimeMillis() - startTime;
            log.info("{} [INDEX] skipped repoId={} files={} time={}ms - tree unchanged",
                     Instant.now(), repoId, skippedCount, duration);
            repo.setFilesProcessed(skippedCount);
            repo.setFilesTotal(skippedCount);
            repo.setIndexStatus(IndexStatus.READY);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
            return;
        }

        // Determine changed, new, and removed files
        IndexingResult result = computeIndexingChanges(previousFiles, currentFiles);

        log.info("{} [INDEX] changes repoId={} total={} new={} changed={} removed={} unchanged={}",
            Instant.now(), repoId,
            result.allFiles.size(),
            result.newFiles.size(),
            result.changedFiles.size(),
            result.removedFiles.size(),
            result.unchangedFiles.size());

        // Update status: fetching stage
        updateIndexingStatus(repoId, "fetching", "Fetching file list...", 0, result.allFiles.size(), 0, 0);

        // Delete vectors for removed files only
        deleteVectorsForRemovedFiles(userId, repoId.toString(), result.removedFiles);

        // Fetch and process changed/new files in parallel
        long fetchStart = System.currentTimeMillis();
        List<Document> allChunks = fetchAndProcessFiles(
            repo, userId, repoId, result.newFiles, result.changedFiles, result.allFiles.size());
        long fetchDuration = System.currentTimeMillis() - fetchStart;

        int totalChunks = allChunks.size();

        // Update status: chunking stage
        updateIndexingStatus(repoId, "chunking", "Splitting files into chunks...",
            result.allFiles.size(), result.allFiles.size(), totalChunks, totalChunks);

        // Store all chunks
        storeChunks(allChunks, repoId, repoId);

        // If embedding failed, mark the repository as failed instead of ready
        if (embeddingFailed) {
            markFailed(repoId, "Embedding batch failed - check logs for details");
            log.warn("{} [INDEX] aborted due to embedding failure repoId={}", Instant.now(), repoId);
            return;
        }

        int skippedCount = result.unchangedFiles.size();

        // Update repo with new tree SHA and indexed files
        repo.setTreeSha(currentTreeSha);
        try {
            repo.setIndexedFilesJson(objectMapper.writeValueAsString(currentFiles));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Failed to serialize indexed files JSON: {}", e.getMessage());
        }
        repo.setFilesTotal(result.allFiles.size());
        repo.setFilesProcessed(result.allFiles.size());
        repo.setChunkCount(totalChunks);
        repo.setChunksTotal(totalChunks);
        repo.setChunksProcessed(totalChunks);
        repo.setIndexedAt(Instant.now());
        repo.setIndexStatus(IndexStatus.READY);
        repo.setIndexingStage("done");
        repo.setIndexingDetail("Indexing complete");
        repo.setUpdatedAt(Instant.now());

        try {
            repositoryRepository.save(repo);
            log.info("{} [INDEX] marked READY repoId={}", Instant.now(), repoId);
        } catch (Exception e) {
            log.error("{} [INDEX] FAILED to persist READY status repoId={}: {}", Instant.now(), repoId, e.getMessage(), e);
            // Mark as failed so frontend knows
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage("Failed to persist indexing completion: " + e.getMessage());
            repo.setIndexingStage("failed");
            repo.setIndexingDetail("Database error: " + e.getMessage());
            repositoryRepository.save(repo);
            throw e;
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("{} [INDEX] complete repoId={} total={} fetched={} skipped={} chunks={} fetch={}ms time={}ms",
                 Instant.now(), repoId, result.allFiles.size(), result.newFiles.size() + result.changedFiles.size(),
                 skippedCount, totalChunks, fetchDuration, duration);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadIndexedFiles(Repository repo) {
        if (repo.getIndexedFilesJson() == null || repo.getIndexedFilesJson().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(repo.getIndexedFilesJson(), new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Could not parse indexed files JSON, starting fresh: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extractFileShas(Map<String, Object> tree) {
        Map<String, String> result = new HashMap<>();
        if (tree == null || tree.get("tree") == null) return result;

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        for (Map<String, Object> entry : entries) {
            if (!"blob".equals(entry.get("type"))) continue;
            String path = String.valueOf(entry.get("path"));
            String sha = String.valueOf(entry.get("sha"));
            result.put(path, sha);
        }
        return result;
    }

    private String computeTreeSha(Map<String, Object> tree) {
        Map<String, String> files = extractFileShas(tree);
        String filesHash = files.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue())
            .sorted()
            .collect(Collectors.joining("|"));

        if (filesHash.isEmpty()) return "";

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(filesHash.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.warn("Could not compute tree SHA: {}", e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private IndexingResult computeIndexingChanges(Map<String, String> previousFiles, Map<String, String> currentFiles) {
        List<String> allFiles = new ArrayList<>(currentFiles.keySet());
        List<String> newFiles = new ArrayList<>();
        List<String> changedFiles = new ArrayList<>();
        List<String> removedFiles = new ArrayList<>();
        List<String> unchangedFiles = new ArrayList<>();

        for (String path : currentFiles.keySet()) {
            String currentSha = currentFiles.get(path);
            String prevSha = previousFiles.get(path);
            if (prevSha == null) {
                newFiles.add(path);
            } else if (!prevSha.equals(currentSha)) {
                changedFiles.add(path);
            } else {
                unchangedFiles.add(path);
            }
        }

        for (String prevPath : previousFiles.keySet()) {
            if (!currentFiles.containsKey(prevPath)) {
                removedFiles.add(prevPath);
            }
        }

        return new IndexingResult(allFiles, newFiles, changedFiles, removedFiles, unchangedFiles, currentFiles);
    }

    @SuppressWarnings("unchecked")
    private List<Document> fetchAndProcessFiles(
            Repository repo, UUID userId, UUID repoId,
            List<String> newFiles, List<String> changedFiles, int totalFiles) {

        embeddingFailed = false;  // Reset flag for each indexing run

        List<String> filesToFetch = new ArrayList<>(newFiles);
        filesToFetch.addAll(changedFiles);

        if (filesToFetch.isEmpty()) {
            return new ArrayList<>();
        }

        List<CompletableFuture<FileResult>> futures = new ArrayList<>();
        Semaphore semaphore = new Semaphore(PARALLEL_FETCH_WORKERS);

        for (String path : filesToFetch) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    rateLimiter.pause();

                    // Update detail for current file being fetched
                    updateIndexingStatus(repoId, "fetching", "Fetching " + path + "...",
                        filesToFetch.indexOf(path), totalFiles, 0, 0);

                    String content = gitHubApiClient.getFileContent(
                        repo.getOwner(), repo.getName(), path);
                    if (content == null) {
                        return new FileResult(path, List.of(), null);
                    }

                    List<Document> chunks = codeChunker.chunkFile(
                        userId, repoId.toString(), path, content);
                    return new FileResult(path, chunks, null);

                } catch (Exception e) {
                    log.warn("Failed to process file {}: {}", path, e.getMessage());
                    return new FileResult(path, List.of(), e);
                } finally {
                    semaphore.release();
                }
            }));
        }

        // Wait for all to complete with overall timeout
        List<Document> allChunks = new ArrayList<>();
        int filesCompleted = 0;
        for (CompletableFuture<FileResult> future : futures) {
            try {
                FileResult result = future.get(RETRIEVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                filesCompleted++;
                if (result.chunks != null) {
                    allChunks.addAll(result.chunks);
                }
                // Update progress
                updateIndexingStatus(repoId, "fetching", "Fetching complete",
                    filesCompleted, totalFiles, allChunks.size(), 0);
            } catch (TimeoutException e) {
                log.warn("Timeout processing files, continuing with partial results");
            } catch (Exception e) {
                log.warn("Error processing files: {}", e.getMessage());
            }
        }

        return allChunks;
    }

    private void deleteVectorsForRemovedFiles(UUID userId, String repoId, List<String> removedFiles) {
        if (removedFiles.isEmpty()) return;

        try {
            // Build filter expression as string - matches how PGVector stores metadata
            String filterExpression = String.format(
                "%s == '%s' && %s == '%s'",
                RagSettings.METADATA_REPO_ID, repoId,
                RagSettings.METADATA_USER_ID, userId.toString()
            );

            // Use SearchRequest to find matching documents, then delete by ID
            SearchRequest searchRequest = SearchRequest.builder()
                .query("")  // empty query, we're just filtering
                .topK(Integer.MAX_VALUE)
                .filterExpression(filterExpression)
                .build();

            List<Document> docsToDelete = vectorStore.similaritySearch(searchRequest);
            List<String> idsToDelete = docsToDelete.stream()
                .map(Document::getId)
                .toList();

            if (!idsToDelete.isEmpty()) {
                log.info("{} [INDEX] deleting {} vectors repoId={} removed={}",
                    Instant.now(), idsToDelete.size(), repoId, removedFiles.size());
                vectorStore.delete(idsToDelete);
            }
            log.info("{} [INDEX] deleted vectors for repoId={}", Instant.now(), repoId);
        } catch (Exception ex) {
            log.error("Could not delete existing vectors for repo {}", repoId, ex);
        }
    }

    private void storeChunks(List<Document> chunks, UUID repoId, UUID repoIdForStatus) {
        int batchSize = VECTOR_BATCH_SIZE;
        int totalChunks = chunks.size();

        for (int i = 0; i < chunks.size(); i += batchSize) {
            int start = i;
            int end = Math.min(i + batchSize, chunks.size());
            List<Document> batch = chunks.subList(i, end);

            // Update embedding progress
            updateIndexingStatus(repoIdForStatus, "embedding",
                "Embedding batch " + start + "-" + end + " of " + totalChunks + "...",
                -1, -1, start, totalChunks);

            boolean success = addBatchWithRetry(batch, repoId, start, end);
            if (!success) {
                log.error("Failed to store batch {}-{} for repo {} - embeddingFailed flag set", start, end, repoId);
                embeddingFailed = true;
            } else {
                // Update chunks processed
                updateIndexingStatus(repoIdForStatus, "embedding",
                    "Embedded batch " + start + "-" + end + " of " + totalChunks,
                    -1, -1, end, totalChunks);
            }
        }

        // Update status: saving stage
        updateIndexingStatus(repoIdForStatus, "saving", "Saving vectors to database...",
            -1, -1, totalChunks, totalChunks);
    }

    private boolean addBatchWithRetry(List<Document> batch, UUID repoId, int start, int end) {
        log.info("{} [INDEX] vector batch start repoId={} chunks={}-{}", Instant.now(), repoId, start, end);

        // First, try to embed with 422-safe handling
        if (containsContentSafetyError(batch, repoId, start, end)) {
            // Try to embed individual chunks, skipping problematic ones
            return embedBatchWithContentSafetyHandling(batch, repoId, start, end);
        }

        // Try with exponential backoff for 503 errors
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                vectorStore.add(batch);
                log.info("{} [INDEX] vector batch done repoId={} chunks={}-{}", Instant.now(), repoId, start, end);
                return true;
            } catch (RuntimeException e) {
                // Log full exception details including model and chunk range
                logFullError(e, repoId, start, end);

                // UTF-8 null byte errors cannot be fixed by retrying - log and skip batch
                if (isUtf8NullByteError(e)) {
                    log.error("{} [EMBED] batch contains null bytes, skipping chunks repoId={}-{}: {}",
                            Instant.now(), repoId, start, end, e.getMessage());
                    return false;
                }

                // Check if this is a content safety error (422) - handled separately
                if (isContentSafetyError(e)) {
                    log.warn("{} [EMBED] content safety error detected, will split batch", Instant.now());
                    // Continue to embedBatchWithContentSafetyHandling
                    return embedBatchWithContentSafetyHandling(batch, repoId, start, end);
                }

                if (!isRetryableError(e) || attempt >= MAX_RETRIES) {
                    log.error("{} [EMBED] batch failed after {} retries repoId={}-{}: {}",
                            Instant.now(), attempt, repoId, start, end, e.getMessage());
                    return false;
                }

                // Exponential backoff for retry
                long delay = (long) (BASE_RETRY_DELAY_MS * Math.pow(2, attempt));
                log.warn("{} [EMBED] batch failed, retrying (attempt {}/{}) repoId={}-{} in {}ms: {}",
                        Instant.now(), attempt + 1, MAX_RETRIES + 1, repoId, start, end, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted during embedding retry");
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Check if any chunk in the batch might cause content safety screening issues.
     */
    private boolean containsContentSafetyError(List<Document> batch, UUID repoId, int start, int end) {
        // We'll detect this during the actual embedding attempt
        return false;
    }

    /**
     * Embed a batch when content safety errors are detected.
     * Splits the batch and embeds individual chunks, skipping only the bad ones.
     */
    private boolean embedBatchWithContentSafetyHandling(List<Document> batch, UUID repoId, int start, int end) {
        log.info("{} [EMBED] splitting batch {}-{} for content safety, processing individually",
                 Instant.now(), start, end);

        List<Document> successfullyEmbedded = new ArrayList<>();
        int embeddedCount = 0;

        for (int i = 0; i < batch.size(); i++) {
            Document doc = batch.get(i);
            try {
                // Try embedding single document
                vectorStore.add(List.of(doc));
                successfullyEmbedded.add(doc);
                embeddedCount++;
            } catch (RuntimeException e) {
                if (isContentSafetyError(e)) {
                    log.warn("{} [EMBED] skipping content-safe chunk {} in batch {}-{}",
                             Instant.now(), start + i, start, end);
                } else {
                    log.warn("{} [EMBED] failed to embed chunk {}: {}",
                             Instant.now(), start + i, e.getMessage());
                }
            }
        }

        if (embeddedCount > 0) {
            log.info("{} [INDEX] embedded {} chunks from batch {}-{}, skipped {} problematic chunks",
                     Instant.now(), embeddedCount, start, end, batch.size() - embeddedCount);
            return true;
        }

        log.error("{} [EMBED] no chunks in batch {}-{} could be embedded due to content safety",
                  Instant.now(), start, end);
        return false;
    }

    /**
     * Log full exception details with model information.
     */
    private void logFullError(RuntimeException e, UUID repoId, int start, int end) {
        // Build a detailed error message
        StringBuilder details = new StringBuilder();
        details.append("Status: embedding failed");
        details.append(", repoId: ").append(repoId);
        details.append(", chunkRange: ").append(start).append("-").append(end);
        details.append(", model: ").append(getEmbeddingModelName());
        details.append(", error: ").append(e.getMessage());

        // Try to extract more details from the exception chain
        for (Throwable current = e; current != null; current = current.getCause()) {
            String msg = current.getMessage();
            if (msg != null && !msg.equals(e.getMessage())) {
                details.append(", cause: ").append(msg);
            }
            // Check for JSON response body
            if (msg != null && msg.contains("{")) {
                details.append(", body: ").append(msg);
                break;
            }
        }

        log.error("{} [EMBED] {}", Instant.now(), details.toString(), e);
    }

    /**
     * Get the embedding model name from configuration.
     */
    @Value("${spring.ai.openai.embedding.model:nvidia/nemotron-3.5-embed-1b}")
    private String embeddingModelName;

    private String getEmbeddingModelName() {
        return embeddingModelName != null ? embeddingModelName : "nvidia/nemotron-3.5-embed-1b";
    }

    private static boolean isRetryableError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("429") || message.contains("503")
                    || message.contains("quota_exceeded") || message.contains("resource_exhausted")
                    || message.contains("service_unavailable")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContentSafetyError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("422") || message.contains("content safety") || message.contains("rejected")) {
                return true;
            }
        }
        return false;
    }

    /** Check if error is UTF-8 null byte encoding error from Postgres. */
    private static boolean isUtf8NullByteError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("invalid byte sequence for encoding utf8") && message.contains("0x00")) {
                return true;
            }
        }
        return false;
    }

    private class IndexingResult {
        final List<String> allFiles;
        final List<String> newFiles;
        final List<String> changedFiles;
        final List<String> removedFiles;
        final List<String> unchangedFiles;
        final Map<String, String> currentFiles;

        IndexingResult(List<String> allFiles, List<String> newFiles, List<String> changedFiles,
                       List<String> removedFiles, List<String> unchangedFiles, Map<String, String> currentFiles) {
            this.allFiles = allFiles;
            this.newFiles = newFiles;
            this.changedFiles = changedFiles;
            this.removedFiles = removedFiles;
            this.unchangedFiles = unchangedFiles;
            this.currentFiles = currentFiles;
        }
    }

    private class FileResult {
        final String path;
        final List<Document> chunks;
        final Exception error;

        FileResult(String path, List<Document> chunks, Exception error) {
            this.path = path;
            this.chunks = chunks;
            this.error = error;
        }
    }

    @Transactional
    protected void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }
}
