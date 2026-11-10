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
import devPilot.backend.services.AnonymousWorkspace;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final RepoService repoService;

    private final IndexingService indexingService;

    @GetMapping
    public List<RepositoryResponse> list() {
        return repoService.listStored(AnonymousWorkspace.ID);
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
        Repository repo = repoService.analyzePublicRepo(AnonymousWorkspace.ID, matcher.group(1), matcher.group(2));
        if (repo.getIndexStatus() != devPilot.backend.entity.IndexStatus.READY
                && repo.getIndexStatus() != devPilot.backend.entity.IndexStatus.INDEXING) {
            repo = indexingService.startIndexing(repo.getId(), AnonymousWorkspace.ID);
            indexingService.indexAsync(repo.getId(), AnonymousWorkspace.ID);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id) {
        return repoService.toResponse(repoService.requireOwned(id, AnonymousWorkspace.ID));
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(@PathVariable UUID id) {
        Repository repo = indexingService.startIndexing(id, AnonymousWorkspace.ID);
        indexingService.indexAsync(id, AnonymousWorkspace.ID);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id) {
        return repoService.status(id, AnonymousWorkspace.ID);
    }

}
