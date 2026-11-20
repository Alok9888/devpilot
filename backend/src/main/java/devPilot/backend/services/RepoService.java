package devPilot.backend.services;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.github.GithubApiClient;
import devPilot.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepoService {
    public record AnalyzeDecision(Repository repository, boolean shouldStart) {}

    private final RepositoryRepository repositoryRepository;
    private final GithubApiClient gitHubApiClient;
    private final IndexingService indexingService;
    private final Set<String> analyzeTasks = ConcurrentHashMap.newKeySet();

    @Transactional
    public Repository analyzePublicRepo(UUID userId, String owner, String name) {
        var remote = gitHubApiClient.getRepository(owner, name);
        Long githubRepoId = toLong(remote.get("id"));
        Repository repo = repositoryRepository.findByUserIdAndGithubRepoId(userId, githubRepoId)
                .orElseGet(Repository::new);
        repo.setUserId(userId);
        repo.setGithubRepoId(githubRepoId);
        repo.setOwner(owner);
        repo.setName(name);
        repo.setFullName(String.valueOf(remote.get("full_name")));
        repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
        repo.setDefaultBranch(remote.get("default_branch") != null
                ? String.valueOf(remote.get("default_branch")) : "main");
        repo.setLanguage(remote.get("language") != null ? String.valueOf(remote.get("language")) : null);
        repo.setHtmlUrl(remote.get("html_url") != null ? String.valueOf(remote.get("html_url")) : null);
        repo.setDescription(remote.get("description") != null ? String.valueOf(remote.get("description")) : null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    
    @Transactional(readOnly = true)
    public List<RepositoryResponse> listStored(UUID userId) {
        return repositoryRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }
    
    @Transactional(readOnly = true)
    public Repository requireOwned(UUID repoId, UUID userId) {
        return repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    
    @Transactional(readOnly = true)
    public IndexStatusResponse status(UUID repoId, UUID userId) {
        Repository repo = requireOwned(repoId, userId);
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getChunksTotal(),
                repo.getChunksProcessed(),
                repo.getIndexingStage(),
                repo.getIndexingDetail(),
                repo.getIndexedAt(),
                repo.getErrorMessage());
    }

     public RepositoryResponse toResponse(Repository repo) {
        return new RepositoryResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.isPrivate(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getHtmlUrl(),
                repo.getDescription(),
                repo.getIndexStatus(),
                repo.getIndexedAt(),
                repo.getChunkCount(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getErrorMessage());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    @Transactional
    public synchronized AnalyzeDecision prepareAnalyze(UUID userId, String owner, String name, boolean force) {
        Repository existing = repositoryRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                .filter(repo -> repo.getOwner().equalsIgnoreCase(owner)
                        && repo.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            if (existing.getIndexStatus() == IndexStatus.READY && !force) {
                return new AnalyzeDecision(existing, false);
            }
            Instant recentCutoff = Instant.now().minus(Duration.ofMinutes(10));
            boolean recentlyUpdated = existing.getUpdatedAt() != null
                    && !existing.getUpdatedAt().isBefore(recentCutoff);
            boolean active = existing.getIndexStatus() == IndexStatus.INDEXING
                    && (recentlyUpdated || indexingService.isIndexingActive(existing.getId()));
            if (active) {
                log.info("Analyze already active for {}/{}, returning existing repository", owner, name);
                return new AnalyzeDecision(existing, false);
            }
            return new AnalyzeDecision(existing, true);
        }

        Repository repo = new Repository();
        repo.setUserId(userId);
        repo.setGithubRepoId(placeholderGithubId(owner, name));
        repo.setOwner(owner);
        repo.setName(name);
        repo.setFullName(owner + "/" + name);
        repo.setDefaultBranch("main");
        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setIndexingStage("creating");
        repo.setIndexingDetail("Creating repo row...");
        return new AnalyzeDecision(repositoryRepository.save(repo), true);
    }

    private static long placeholderGithubId(String owner, String name) {
        UUID key = UUID.nameUUIDFromBytes(
                (owner.toLowerCase() + "/" + name.toLowerCase()).getBytes(StandardCharsets.UTF_8));
        return key.getMostSignificantBits() | Long.MIN_VALUE;
    }

    /**
     * Background task: fetch GitHub metadata, create repo, start indexing
     */
    public void analyzeAndStartIndexing(UUID userId, String owner, String name, UUID repoId, boolean force) {
        String taskKey = userId + ":" + owner.toLowerCase(Locale.ROOT) + "/" + name.toLowerCase(Locale.ROOT);
        if (!analyzeTasks.add(taskKey)) {
            log.info("Analyze already running for {}/{}, skipping duplicate", owner, name);
            return;
        }
        try {
            Repository current = repositoryRepository.findByIdAndUserId(repoId, userId).orElse(null);
            if (current == null) return;
            if (current.getIndexStatus() == IndexStatus.READY && !force) {
                log.info("Repository {}/{} is already ready, skipping analyze", owner, name);
                return;
            }
            Instant recentCutoff = Instant.now().minus(Duration.ofMinutes(10));
            boolean recentlyUpdated = current.getUpdatedAt() != null
                    && !current.getUpdatedAt().isBefore(recentCutoff);
            if (current.getIndexStatus() == IndexStatus.INDEXING
                    && !"creating".equals(current.getIndexingStage())
                    && (recentlyUpdated || indexingService.isIndexingActive(repoId))) {
                log.info("Repository {}/{} is already indexing, skipping analyze", owner, name);
                return;
            }

            var remote = gitHubApiClient.getRepository(owner, name);
            Long githubRepoId = toLong(remote.get("id"));

            Repository repo = repositoryRepository.findByUserIdAndGithubRepoId(userId, githubRepoId)
                    .orElseGet(() -> repositoryRepository
                            .findByUserIdAndGithubRepoId(userId, placeholderGithubId(owner, name))
                            .orElseGet(Repository::new));
            repo.setUserId(userId);
            repo.setGithubRepoId(githubRepoId);
            repo.setOwner(owner);
            repo.setName(name);
            repo.setFullName(String.valueOf(remote.get("full_name")));
            repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
            repo.setDefaultBranch(remote.get("default_branch") != null
                    ? String.valueOf(remote.get("default_branch")) : "main");
            repo.setLanguage(remote.get("language") != null ? String.valueOf(remote.get("language")) : null);
            repo.setHtmlUrl(remote.get("html_url") != null ? String.valueOf(remote.get("html_url")) : null);
            repo.setDescription(remote.get("description") != null ? String.valueOf(remote.get("description")) : null);
            repo.setUpdatedAt(Instant.now());
            repo.setIndexStatus(IndexStatus.INDEXING);
            repo.setIndexingStage("fetching");
            repo.setIndexingDetail("Fetching file list...");
            repo.setFilesTotal(0);
            repo.setFilesProcessed(0);
            repo.setChunkCount(0);
            repo.setChunksTotal(0);
            repo.setChunksProcessed(0);

            repo = repositoryRepository.save(repo);

            // Start indexing async
            indexingService.startIndexing(repo.getId(), userId);
            indexingService.indexAsync(repo.getId(), userId);
        } catch (Exception e) {
            log.error("Background analyze failed for {}/{}: {}", owner, name, e.getMessage(), e);
        } finally {
            analyzeTasks.remove(taskKey);
        }
    }

    @Transactional(readOnly = true)
    public IndexStatusResponse statusByOwnerName(UUID userId, String owner, String name) {
        Repository repo = repositoryRepository
                .findByUserIdAndGithubRepoId(userId, placeholderGithubId(owner, name))
                .orElseGet(() -> repositoryRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                        .filter(candidate -> candidate.getOwner().equalsIgnoreCase(owner)
                                && candidate.getName().equalsIgnoreCase(name))
                        .findFirst()
                        .orElse(null));
        if (repo == null) {
            return new IndexStatusResponse(
                    null,
                    IndexStatus.INDEXING,
                    0, 0, 0, 0, 0,
                    "creating",
                    "Creating repo row...",
                    null,
                    null
            );
        }
        if ("creating".equals(repo.getIndexingStage())) {
            return new IndexStatusResponse(
                    null, IndexStatus.INDEXING, 0, 0, 0, 0, 0,
                    "creating", "Creating repo row...", null, null);
        }
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getChunksTotal(),
                repo.getChunksProcessed(),
                repo.getIndexingStage(),
                repo.getIndexingDetail(),
                repo.getIndexedAt(),
                repo.getErrorMessage());
    }
}
