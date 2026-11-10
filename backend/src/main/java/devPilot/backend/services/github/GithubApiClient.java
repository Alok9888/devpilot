package devPilot.backend.services.github;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.HttpClientErrorException;

import devPilot.backend.exceptions.NotFoundException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GithubApiClient {

    private static final String API_BASE = "https://api.github.com";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient.Builder restClientBuilder;
    @Value("${github.token:}")
    private String githubToken;

    public Map<String, Object> getRepository(String owner, String repo) {
        try {
            return client().get().uri("/repos/{owner}/{repo}", owner, repo).retrieve().body(MAP);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NotFoundException("Repo not found or private");
        }
    }

    public Map<String, Object> getRepoTree(String owner, String repo, String branch) {
        try {
            return client()
                .get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .retrieve()
                .body(MAP);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NotFoundException("Repo not found or private");
        }
    }

    public String getFileContent(String owner, String repo, String path) {
        Map<String, Object> body = client()
                .get()
                .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .retrieve()
                .body(MAP);
        if (body == null) {
            return null;
        }
        Object encoding = body.get("encoding");
        Object content = body.get("content");
        if (content == null) {
            return null;
        }
        if ("base64".equals(String.valueOf(encoding))) {
            String raw = String.valueOf(content).replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        }
        return String.valueOf(content);
    }
    
    private RestClient client(){
          RestClient.Builder builder = restClientBuilder
                .baseUrl(API_BASE)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "DevPilot");
          if (githubToken != null && !githubToken.isBlank()) {
              builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + githubToken);
          }
          return builder.build();
    }
}
