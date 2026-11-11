package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
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
    private static final int PROGRESS_EVERY_N_FILES = 5;
    private static final int[] RETRY_DELAYS_MS = {2000, 5000, 10000};

    private final RepositoryRepository repositoryRepository;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

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
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }


      private void doIndex(UUID repoId, UUID userId) {
        log.info("{} [INDEX] start repoId={}", Instant.now(), repoId);
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        deleteExistingVectors(repoId.toString());

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        List<String> filePaths = listIndexableFiles(tree);
        log.info("{} [INDEX] eligible files repoId={} count={}", Instant.now(), repoId, filePaths.size());

        updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;
        int batchesSucceeded = 0;
        int totalBatches = 0;

        for (String path : filePaths) {
            try {
                String content = gitHubApiClient.getFileContent(
                        repo.getOwner(), repo.getName(), path);
                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    totalBatches++;
                    if (addBatchWithRetry(batch, repoId)) {
                        batchesSucceeded++;
                    }
                    batch.clear();
                }
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}", path, repo.getFullName(), ex);
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (!batch.isEmpty()) {
            totalBatches++;
            if (addBatchWithRetry(batch, repoId)) {
                batchesSucceeded++;
            }
        }

        if (batchesSucceeded == 0 && totalBatches > 0) {
            markFailed(repoId, "All embedding batches failed (quota exceeded)");
        } else {
            markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
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


       @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .toList();
    }

     private void deleteExistingVectors(String repoId) {
        try {
            var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
            log.info("{} [INDEX] deleting existing vectors repoId={}", Instant.now(), repoId);
            vectorStore.delete(filter);
            log.info("{} [INDEX] deleted existing vectors repoId={}", Instant.now(), repoId);
        } catch (Exception ex) {
            log.error("Could not delete existing vectors for repo {}", repoId, ex);
            throw new IllegalStateException("Could not clear existing vectors before indexing", ex);
        }
    };

      @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

      @Transactional
    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("{} [INDEX] ready repoId={} name={} files={}/{} chunks={}",
                Instant.now(), repoId, fullName, processedFiles, totalFiles, totalChunks);
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