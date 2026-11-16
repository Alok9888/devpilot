package devPilot.backend.services.indexing;

import java.util.ArrayList;
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
    private final int overlapTokens;

    public CodeChunker(
            @Value("${app.indexing.chunk-size:800}") int chunkSize,
            CodeFileFilter fileFilter) {
        // Spring AI splits by tokens; ~4 characters per token is a reasonable default for code.
        int chunkTokens = Math.max(50, chunkSize / 4);
        this.overlapTokens = chunkTokens / 10; // 10% overlap

        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkTokens)
                .build();
        this.fileFilter = fileFilter;
    }

    public List<Document> chunkFile(UUID userId, String repoId, String filePath, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String language = fileFilter.detectLanguage(filePath);
        String header = "// File: " + filePath + "\n";

        // Add userId metadata to each chunk
        Document source = new Document(header + content, baseMetadata(userId, repoId, filePath, language));
        List<Document> split = splitter.apply(List.of(source));

        // Apply 10% overlap manually
        List<Document> overlapped = applyOverlap(split, userId, repoId, filePath, language);

        return IntStream.range(0, overlapped.size())
                .mapToObj(i -> withChunkIndex(overlapped.get(i), userId, repoId, filePath, language, i))
                .toList();
    }

    private List<Document> applyOverlap(List<Document> chunks, UUID userId, String repoId, String filePath, String language) {
        if (chunks.size() <= 1 || overlapTokens <= 0) {
            return chunks;
        }

        List<Document> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document current = chunks.get(i);
            if (i == 0) {
                result.add(current);
            } else {
                // Combine last 10% of previous chunk with beginning of current
                Document prev = chunks.get(i - 1);
                String prevText = prev.getText();
                String currText = current.getText();

                int overlapStart = Math.max(0, prevText.length() - (overlapTokens * 4)); // ~4 chars per token
                String overlap = prevText.substring(overlapStart);

                Map<String, Object> metadata = new HashMap<>(current.getMetadata());
                metadata.put(RagSettings.METADATA_USER_ID, userId.toString());
                metadata.put(RagSettings.METADATA_REPO_ID, repoId);
                metadata.put("filePath", filePath);
                metadata.put("language", language);

                result.add(new Document(overlap + "\n\n" + currText, metadata));
            }
        }
        return result;
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