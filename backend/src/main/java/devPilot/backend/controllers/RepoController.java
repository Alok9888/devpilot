package devPilot.backend.controllers;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.Repository;
import devPilot.backend.dto.AnalyzeRepoRequest;
import devPilot.backend.config.WorkspaceContext;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
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
            @RequestBody @jakarta.validation.Valid AnalyzeRepoRequest request) {
        String value = request.repoUrl().trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:https://github\\.com/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$")
                .matcher(value);
        if (!matcher.matches()) {
            throw new devPilot.backend.exceptions.BadRequestException("Enter a valid public GitHub repository URL");
        }
        UUID workspaceId = workspaceContext.getWorkspaceId();
        Repository repo = repoService.analyzePublicRepo(workspaceId, matcher.group(1), matcher.group(2));
        if (repo.getIndexStatus() != devPilot.backend.entity.IndexStatus.READY
                && repo.getIndexStatus() != devPilot.backend.entity.IndexStatus.INDEXING) {
            repo = indexingService.startIndexing(repo.getId(), workspaceId);
            indexingService.indexAsync(repo.getId(), workspaceId);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
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
