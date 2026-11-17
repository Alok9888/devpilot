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
import devPilot.backend.exceptions.BadRequestException;
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
    private static final int VECTOR_BATCH_SIZE = 32;
    private static final int PARALLEL_FETCH_WORKERS = 8;
    private static final long RETRIEVAL_TIMEOUT_MS = 120000; // 2 minutes total timeout

    private static final int[] RETRY_DELAYS_MS = {2000, 5000, 10000};

    private final RepositoryRepository repositoryRepository;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
     public void indexAsync(UUID repoId, UUID userId) {
        long startTime = System.currentTimeMillis();
        try {
            doIndex(repoId, userId, startTime);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
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

        // Delete vectors for removed files only
        deleteVectorsForRemovedFiles(userId, repoId.toString(), result.removedFiles);

        // Fetch and process changed/new files in parallel
        long fetchStart = System.currentTimeMillis();
        List<Document> allChunks = fetchAndProcessFiles(
            repo, userId, repoId, result.newFiles, result.changedFiles);
        long fetchDuration = System.currentTimeMillis() - fetchStart;

        int totalChunks = allChunks.size();

        // Store all chunks
        storeChunks(allChunks, repoId);

        int skippedCount = result.unchangedFiles.size();

        // Update repo with new tree SHA and indexed files
        repo.setTreeSha(currentTreeSha);
        repo.setIndexedFilesJson(objectMapper.writeValueAsString(currentFiles));
        repo.setFilesTotal(result.allFiles.size());
        repo.setFilesProcessed(result.allFiles.size());
        repo.setChunkCount(totalChunks);
        repo.setIndexedAt(Instant.now());
        repositoryRepository.save(repo);

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
            List<String> newFiles, List<String> changedFiles) {

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
        for (CompletableFuture<FileResult> future : futures) {
            try {
                FileResult result = future.get(RETRIEVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (result.chunks != null) {
                    allChunks.addAll(result.chunks);
                }
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

    private void storeChunks(List<Document> chunks, UUID repoId) {
        int batchSize = VECTOR_BATCH_SIZE;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<Document> batch = chunks.subList(i, end);

            boolean success = addBatchWithRetry(batch, repoId);
            if (!success) {
                log.error("Failed to store batch {}-{} for repo {}", i, end, repoId);
            }
        }
    }

    private boolean addBatchWithRetry(List<Document> batch, UUID repoId) {
        log.info("{} [INDEX] vector batch start repoId={} chunks={}", Instant.now(), repoId, batch.size());

        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            try {
                vectorStore.add(batch);
                log.info("{} [INDEX] vector batch done repoId={} chunks={}", Instant.now(), repoId, batch.size());
                return true;
            } catch (RuntimeException e) {
                if (!isRetryableError(e) || attempt >= RETRY_DELAYS_MS.length) {
                    log.error("{} [EMBED] batch failed after {} retries repoId={}: {}",
                            Instant.now(), attempt, repoId, e.getMessage());
                    return false;
                }
                log.warn("{} [EMBED] batch failed, retrying (attempt {}/{}) repoId={} in {}ms: {}",
                        Instant.now(), attempt + 1, RETRY_DELAYS_MS.length + 1, repoId, RETRY_DELAYS_MS[attempt], e.getMessage());
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Interrupted during embedding retry");
                    return false;
                }
            }
        }
        return false;
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