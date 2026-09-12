package com.example.codebase_onboarding_assistant.ingestion;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
@Service
public class EmbeddingService {

    private static final String API_URL = (System.getenv("OLLAMA_BASE_URL") != null
            ? System.getenv("OLLAMA_BASE_URL")
            : "http://localhost:11434") + "/api/embeddings";
    private static final String MODEL = "nomic-embed-text";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public record EmbeddedChunk(Chunker.Chunk chunk, float[] vector) {}

    public float[] embed(String text) throws Exception {
        String bodyJson = mapper.writeValueAsString(new EmbeddingRequest(MODEL, text));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode() + " — " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode embeddingArray = root.get("embedding");

        float[] vector = new float[embeddingArray.size()];
        for (int i = 0; i < embeddingArray.size(); i++) {
            vector[i] = (float) embeddingArray.get(i).asDouble();
        }
        return vector;
    }

    public List<EmbeddedChunk> embedChunks(List<Chunker.Chunk> chunks) throws Exception {
        List<EmbeddedChunk> result = new ArrayList<>();
        for (Chunker.Chunk chunk : chunks) {
            float[] vector = embed(chunk.content());
            result.add(new EmbeddedChunk(chunk, vector));
        }
        return result;
    }

    private record EmbeddingRequest(String model, String prompt) {}

    public static void main(String[] args) throws Exception {
        GitHubIngestion ingestion = new GitHubIngestion();
        String owner = "octocat";
        String repo = "Spoon-Knife";
        String branch = "main";

        List<String> allPaths = ingestion.fetchRepoTree(owner, repo, branch);
        List<String> filtered = ingestion.filterPaths(allPaths);

        List<GitHubIngestion.RepoFile> repoFiles = new ArrayList<>();
        for (String path : filtered) {
            String content = ingestion.fetchFileContent(owner, repo, path, branch);
            repoFiles.add(new GitHubIngestion.RepoFile(path, content));
        }

        Chunker chunker = new Chunker();
        List<Chunker.Chunk> chunks = chunker.chunkFiles(repoFiles);

        EmbeddingService embeddingService = new EmbeddingService();
        List<EmbeddedChunk> embedded = embeddingService.embedChunks(chunks);

        System.out.println("Total embedded chunks: " + embedded.size());
        for (EmbeddedChunk ec : embedded) {
            System.out.printf("%s [chunk %d] -> vector length %d%n",
                    ec.chunk().filePath(), ec.chunk().chunkIndex(), ec.vector().length);
        }
    }
}