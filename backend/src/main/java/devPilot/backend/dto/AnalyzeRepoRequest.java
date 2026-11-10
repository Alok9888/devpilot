package devPilot.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeRepoRequest(@NotBlank String repoUrl) {}
