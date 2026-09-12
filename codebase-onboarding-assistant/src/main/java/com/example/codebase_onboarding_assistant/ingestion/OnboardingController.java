package com.example.codebase_onboarding_assistant.ingestion;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OnboardingController {

    private final GitHubIngestion ingestion;
    private final Chunker chunker;
    private final GuideGenerator guideGenerator;

    public OnboardingController(GitHubIngestion ingestion, Chunker chunker, GuideGenerator guideGenerator) {
        this.ingestion = ingestion;
        this.chunker = chunker;
        this.guideGenerator = guideGenerator;
    }

    public record GuideRequest(String owner, String repo, String branch) {}
    public record GuideResponse(String guide) {}

    @PostMapping("/generate-guide")
    public GuideResponse generateGuide(@RequestBody GuideRequest request) throws Exception {
        String branch = request.branch() != null ? request.branch() : "main";

        List<String> allPaths = ingestion.fetchRepoTree(request.owner(), request.repo(), branch);
        List<String> filtered = ingestion.filterPaths(allPaths);

        List<GitHubIngestion.RepoFile> repoFiles = new ArrayList<>();
        for (String path : filtered) {
            String content = ingestion.fetchFileContentWithRetry(request.owner(), request.repo(), path, branch);
            repoFiles.add(new GitHubIngestion.RepoFile(path, content));
        }

        List<Chunker.Chunk> chunks = chunker.chunkFiles(repoFiles);

        // Keep only the first chunk per file — enough real content to describe each file specifically.
        Map<String, Chunker.Chunk> firstChunkPerFile = new LinkedHashMap<>();
        for (Chunker.Chunk c : chunks) {
            firstChunkPerFile.putIfAbsent(c.filePath(), c);
        }

        // Truncate each snippet so the prompt stays a manageable size across many files.
        List<Chunker.Chunk> fileSnippets = new ArrayList<>();
        for (Chunker.Chunk c : firstChunkPerFile.values()) {
            String truncated = c.content().length() > 400
                    ? c.content().substring(0, 400) + "..."
                    : c.content();
            fileSnippets.add(new Chunker.Chunk(c.filePath(), c.chunkIndex(), truncated));
        }

        String guide = guideGenerator.generateGuide(filtered, fileSnippets);
        return new GuideResponse(guide);
    }
}