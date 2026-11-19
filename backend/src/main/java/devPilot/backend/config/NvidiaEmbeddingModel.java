package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Document;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.models.embeddings.EmbeddingModel as OpenAiEmbeddingModel;

import java.util.List;
import java.util.stream.Collectors;

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
    public EmbeddingResponse embed(org.springframework.ai.embedding.model.EmbeddingRequest embeddingRequest) {
        // Build embedding request with input_type field for NVIDIA API
        EmbeddingCreateParams.Builder paramsBuilder = EmbeddingCreateParams.builder()
                .model(OpenAiEmbeddingModel.fromValue(model))
                .input(EmbeddingCreateParams.Input.ofArrayOfStrings(embeddingRequest.getInstructions()));

        if (dimensions != null) {
            paramsBuilder.dimensions(dimensions);
        }

        // Add input_type to the request body - required by NVIDIA's embed models
        paramsBuilder.putAdditionalProperty("input_type", JsonValue.string("passage"));

        EmbeddingCreateParams params = paramsBuilder.build();

        CreateEmbeddingResponse response = openAIClient.embeddings().create(params);

        List<com.openai.models.embeddings.Embedding> embeddings = response.data();
        List<double[]> vectors = embeddings.stream()
                .map(e -> e.embedding().stream().mapToDouble(Double::doubleValue).toArray())
                .collect(Collectors.toList());

        return new EmbeddingResponse(vectors, response.model(), null);
    }

    @Override
    public float[] embed(Document document) {
        EmbeddingResponse response = embed(
            new org.springframework.ai.embedding.model.EmbeddingRequest(List.of(document.getFormattedContent()), getOptions()));
        if (response.getResults().isEmpty()) {
            return new float[0];
        }
        return response.getResults().get(0).getOutput();
    }

    @Override
    public EmbeddingOptions getOptions() {
        return OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();
    }
}