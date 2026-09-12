package com.example.codebase_onboarding_assistant.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

public class VectorStore {

    private final List<EmbeddingService.EmbeddedChunk> store = new ArrayList<>();

    public void addAll(List<EmbeddingService.EmbeddedChunk> chunks) {
        store.addAll(chunks);
    }

    public record ScoredChunk(Chunker.Chunk chunk, double score) {}

    public List<ScoredChunk> search(float[] queryVector, int topN) {
        List<ScoredChunk> scored = new ArrayList<>();
        for (EmbeddingService.EmbeddedChunk ec : store) {
            double score = cosineSimilarity(queryVector, ec.vector());
            scored.add(new ScoredChunk(ec.chunk(), score));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

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
        List<EmbeddingService.EmbeddedChunk> embedded = embeddingService.embedChunks(chunks);

        VectorStore vectorStore = new VectorStore();
        vectorStore.addAll(embedded);

        String query = "What does this repository do?";
        float[] queryVector = embeddingService.embed(query);

        List<ScoredChunk> results = vectorStore.search(queryVector, 3);

        System.out.println("Query: " + query);
        System.out.println("Top results:");
        for (ScoredChunk sc : results) {
            System.out.printf("%s [chunk %d] -> score %.4f%n",
                    sc.chunk().filePath(), sc.chunk().chunkIndex(), sc.score());
        }
    }
}