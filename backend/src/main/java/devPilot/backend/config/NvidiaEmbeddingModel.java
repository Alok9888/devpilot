package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.springframework.ai.model.EmbeddingUtils;

import java.util.List;
import java.util.ArrayList;

/**
 * Custom EmbeddingModel wrapper for NVIDIA's Nemotron-3.5-embed-1b model.
 * This model requires an input_type parameter ("passage" or "query") in the request body.
 */
public class NvidiaEmbeddingModel implements EmbeddingModel {

    private final OpenAIClient openAIClient;
    private final String model;
    private final Integer dimensions;

    public NvidiaEmbeddingModel(OpenAIClient openAIClient, String model, Integer dimensions) {
        this.openAIClient = openAIClient;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest embeddingRequest) {
        // Build embedding request with input_type field for NVIDIA API
        EmbeddingCreateParams.Builder paramsBuilder = EmbeddingCreateParams.builder()
                .model(model)
                .input(EmbeddingCreateParams.Input.ofArrayOfStrings(embeddingRequest.getInstructions()));

        if (dimensions != null) {
            paramsBuilder.dimensions(dimensions);
        }

        // Add input_type to the request body - required by NVIDIA's embed models
        paramsBuilder.putAdditionalBodyProperty("input_type", JsonValue.from("passage"));

        EmbeddingCreateParams params = paramsBuilder.build();

        CreateEmbeddingResponse response = openAIClient.embeddings().create(params);

        return generateEmbeddingResponse(response);
    }

    private EmbeddingResponse generateEmbeddingResponse(CreateEmbeddingResponse response) {
        List<Embedding> data = generateEmbeddingList(response.data());
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(response.model());
        return new EmbeddingResponse(data, metadata);
    }

    private List<Embedding> generateEmbeddingList(List<com.openai.models.embeddings.Embedding> nativeData) {
        List<Embedding> data = new ArrayList<>();
        for (com.openai.models.embeddings.Embedding nativeDatum : nativeData) {
            List<Float> nativeDatumEmbedding = nativeDatum.embedding();
            long nativeIndex = nativeDatum.index();
            Embedding embedding = new Embedding(EmbeddingUtils.toPrimitive(nativeDatumEmbedding),
                    Math.toIntExact(nativeIndex));
            data.add(embedding);
        }
        return data;
    }

    @Override
    public float[] embed(Document document) {
        EmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();
        EmbeddingResponse response = call(
            new EmbeddingRequest(List.of(document.getFormattedContent()), options));
        if (response.getResults().isEmpty()) {
            return new float[0];
        }
        return response.getResults().get(0).getOutput();
    }

    @Override
    public int dimensions() {
        return dimensions != null ? dimensions : 0;
    }
}