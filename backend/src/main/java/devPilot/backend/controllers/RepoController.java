package devPilot.backend.controllers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.Repository;
import devPilot.backend.dto.AnalyzeRepoRequest;
import devPilot.backend.config.WorkspaceContext;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
@Slf4j
public class RepoController {

    private final RepoService repoService;
    private final IndexingService indexingService;
    private final WorkspaceContext workspaceContext;

    @GetMapping
    public List<RepositoryResponse> list() {
        return repoService.listStored(workspaceContext.getWorkspaceId());
    }

    @PostMapping("/analyze")
    public ResponseEntity<RepositoryResponse> analyze(
            @RequestBody @jakarta.validation.Valid AnalyzeRepoRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        String value = request.repoUrl().trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:https://github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$")
                .matcher(value);
        if (!matcher.matches()) {
            throw new devPilot.backend.exceptions.BadRequestException("Enter a valid public GitHub repository URL");
        }

        UUID workspaceId = workspaceContext.getWorkspaceId();
        String owner = matcher.group(1);
        String name = matcher.group(2);

        RepoService.AnalyzeDecision decision = repoService.prepareAnalyze(workspaceId, owner, name, force);
        Repository repo = decision.repository();
        if (decision.shouldStart()) {
            CompletableFuture.runAsync(() -> {
                try {
                    repoService.analyzeAndStartIndexing(workspaceId, owner, name, repo.getId(), force);
                } catch (Exception e) {
                    log.error("Background analyze failed for {}/{}: {}", owner, name, e.getMessage(), e);
                }
            });
        }
        HttpStatus responseStatus = repo.getIndexStatus() == devPilot.backend.entity.IndexStatus.INDEXING
                ? HttpStatus.ACCEPTED
                : (decision.shouldStart() ? HttpStatus.ACCEPTED : HttpStatus.OK);
        return ResponseEntity.status(responseStatus).body(repoService.toResponse(repo));
    }

    @GetMapping("/analyze/status")
    public IndexStatusResponse analyzeStatus(@RequestParam String url, @RequestParam(required = false) UUID repoId) {
        UUID workspaceId = workspaceContext.getWorkspaceId();
        if (repoId != null) {
            return repoService.status(repoId, workspaceId);
        }
        // Parse owner/name from URL
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:https://github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$")
                .matcher(url);
        if (!matcher.matches()) {
            throw new devPilot.backend.exceptions.BadRequestException("Invalid URL");
        }
        return repoService.statusByOwnerName(workspaceId, matcher.group(1), matcher.group(2));
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id) {
        return repoService.toResponse(repoService.requireOwned(id, workspaceContext.getWorkspaceId()));
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(@PathVariable UUID id) {
        UUID workspaceId = workspaceContext.getWorkspaceId();
        Repository repo = indexingService.startIndexing(id, workspaceId);
        indexingService.indexAsync(id, workspaceId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id) {
        return repoService.status(id, workspaceContext.getWorkspaceId());
    }

}
