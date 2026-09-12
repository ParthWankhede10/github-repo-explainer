package com.example.codebase_onboarding_assistant.ingestion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Step 1 of the Codebase Onboarding Assistant pipeline: INGESTION.
 *
 * Flow:
 *   1. Ask GitHub for the full file tree of a repo (one API call, recursive=1)
 *   2. Filter that tree down to files we actually care about (source code, docs)
 *   3. Fetch the raw content of each included file
 *
 * Run this as a plain Java program first to see it work end-to-end.
 * Later, this logic moves into a Spring @Service (GitHubIngestionService).
 */
@Service
public class GitHubIngestion {

    // File extensions worth reading. Add/remove as you like.
    private static final Set<String> INCLUDED_EXTENSIONS = Set.of(
            "java", "py", "js", "ts", "jsx", "tsx", "md", "yml", "yaml", "properties", "xml"
    );

    // Directory prefixes to skip entirely — noise, not signal.
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "node_modules/", "target/", "build/", ".git/", "dist/", "vendor/"
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public record RepoFile(String path, String content) {}

    /**
     * Step 1a: Get every file path in the repo via the Git Trees API.
     */
    public List<String> fetchRepoTree(String owner, String repo, String branch) throws Exception {
        String url = String.format(
                "https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1",
                owner, repo, branch
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "codebase-onboarding-assistant")
                .header("Authorization", "Bearer " + System.getenv("GITHUB_TOKEN"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("GitHub API error: " + response.statusCode() + " — " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode tree = root.get("tree");

        List<String> paths = new ArrayList<>();
        for (JsonNode node : tree) {
            // "blob" = a file. "tree" = a directory (we don't need those, recursive=1 already expanded them).
            if ("blob".equals(node.get("type").asText())) {
                paths.add(node.get("path").asText());
            }
        }
        return paths;
    }

    /**
     * Step 1b: Filter the full path list down to files we want to actually ingest.
     */
    public List<String> filterPaths(List<String> allPaths) {
        List<String> filtered = new ArrayList<>();
        for (String path : allPaths) {
            boolean excluded = EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith);
            if (excluded) continue;

            int dotIndex = path.lastIndexOf('.');
            if (dotIndex == -1) continue; // no extension, skip

            String ext = path.substring(dotIndex + 1).toLowerCase();
            if (INCLUDED_EXTENSIONS.contains(ext)) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    /**
     * Step 1c: Fetch the actual content of one file (GitHub returns it base64-encoded).
     */
    public String fetchFileContent(String owner, String repo, String path, String branch) throws Exception {
        String url = String.format(
                "https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                owner, repo, path, branch
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "codebase-onboarding-assistant")
                .header("Authorization", "Bearer " + System.getenv("GITHUB_TOKEN"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("GitHub API error for " + path + ": " + response.statusCode());
        }

        JsonNode root = mapper.readTree(response.body());
        String base64Content = root.get("content").asText();
        // GitHub base64-encodes with newlines every 60 chars — strip them before decoding.
        String cleaned = base64Content.replace("\n", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return new String(decoded);
    }


    public String fetchFileContentWithRetry(String owner, String repo, String path, String branch) throws Exception {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Thread.sleep(500);
                return fetchFileContent(owner, repo, path, branch);
            } catch (RuntimeException e) {
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("403");
                System.out.println("Attempt " + attempt + " failed for " + path + ": " + e.getMessage());
                if (isRateLimit && attempt < maxRetries) {
                    long waitMs = 5000L * attempt; // 5s, 10s, 15s, 20s
                    System.out.println("Waiting " + waitMs + "ms before retry...");
                    Thread.sleep(waitMs);
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("Failed after retries: " + path);
    }
    /**
     * Runs the full Step 1 pipeline for one repo and prints a verification summary.
     */
    public static void main(String[] args) throws Exception {
        GitHubIngestion ingestion = new GitHubIngestion();

        String owner = "octocat";
        String repo = "Spoon-Knife";
        String branch = "main";

        System.out.println("Fetching repo tree for " + owner + "/" + repo + "...");
        List<String> allPaths = ingestion.fetchRepoTree(owner, repo, branch);
        System.out.println("Total files in repo: " + allPaths.size());

        List<String> filtered = ingestion.filterPaths(allPaths);
        System.out.println("Files after filtering: " + filtered.size());
        filtered.forEach(p -> System.out.println("  -> " + p));

        List<RepoFile> repoFiles = new ArrayList<>();
        for (String path : filtered) {
            String content = ingestion.fetchFileContent(owner, repo, path, branch);
            repoFiles.add(new RepoFile(path, content));
        }

        System.out.println("\n--- Verification ---");
        for (RepoFile file : repoFiles) {
            System.out.printf("%s -> %d characters%n", file.path(), file.content().length());
        }
    }
}