package devPilot.backend.services.indexing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.services.ai.RagSettings;

import java.util.UUID;

@Component
public class CodeChunker {
    private final TokenTextSplitter splitter;
    private final CodeFileFilter fileFilter;

    public CodeChunker(
            @Value("${app.indexing.chunk-size:800}") int chunkSize,
            CodeFileFilter fileFilter) {
        // Spring AI splits by tokens; ~4 characters per token is a reasonable default for code.
        int chunkTokens = Math.max(50, chunkSize / 4);

        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkTokens)
                .withOverlapPercent(10.0) // 10% overlap for better context preservation
                .build();
        this.fileFilter = fileFilter;
    }

    public List<Document> chunkFile(UUID userId, String repoId, String filePath, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String language = fileFilter.detectLanguage(filePath);
        String header = "// File: " + filePath + "\n";

        Document source = new Document(header + content, baseMetadata(userId, repoId, filePath, language));
        List<Document> split = splitter.apply(List.of(source));

        return IntStream.range(0, split.size())
                .mapToObj(i -> withChunkIndex(split.get(i), userId, repoId, filePath, language, i))
                .toList();
    }

    private static Map<String, Object> baseMetadata(UUID userId, String repoId, String filePath, String language) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put(RagSettings.METADATA_USER_ID, userId.toString());
        metadata.put("filePath", filePath);
        metadata.put("language", language);
        return metadata;
    }

    private static Document withChunkIndex(
            Document chunk,
            UUID userId,
            String repoId,
            String filePath,
            String language,
            int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put(RagSettings.METADATA_USER_ID, userId.toString());
        metadata.put("filePath", filePath);
        metadata.put("language", language);
        metadata.put("chunkIndex", chunkIndex);
        return new Document(chunk.getText(), metadata);
    }
}