package com.example.codebase_onboarding_assistant.ingestion;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
@Service
public class Chunker {

    private static final int CHUNK_SIZE_CHARS = 1500;   // ~roughly a few hundred tokens
    private static final int OVERLAP_CHARS = 200;        // keeps context continuity between chunks

    public record Chunk(String filePath, int chunkIndex, String content) {}

    public List<Chunk> chunkFile(GitHubIngestion.RepoFile file) {
        List<Chunk> chunks = new ArrayList<>();
        String content = file.content();
        int length = content.length();

        if (length <= CHUNK_SIZE_CHARS) {
            chunks.add(new Chunk(file.path(), 0, content));
            return chunks;
        }

        int start = 0;
        int index = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE_CHARS, length);
            chunks.add(new Chunk(file.path(), index, content.substring(start, end)));
            index++;
            if (end == length) break;
            start = end - OVERLAP_CHARS; // step back for overlap
        }
        return chunks;
    }

    public List<Chunk> chunkFiles(List<GitHubIngestion.RepoFile> files) {
        List<Chunk> allChunks = new ArrayList<>();
        for (GitHubIngestion.RepoFile file : files) {
            allChunks.addAll(chunkFile(file));
        }
        return allChunks;
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
        List<Chunk> chunks = chunker.chunkFiles(repoFiles);

        System.out.println("Total chunks: " + chunks.size());
        for (Chunk c : chunks) {
            System.out.printf("%s [chunk %d] -> %d chars%n", c.filePath(), c.chunkIndex(), c.content().length());
        }
    }
}