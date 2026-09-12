package com.example.codebase_onboarding_assistant.ingestion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

@Service
public class GuideGenerator {

    private static final String API_URL = (System.getenv("OLLAMA_BASE_URL") != null
            ? System.getenv("OLLAMA_BASE_URL")
            : "http://localhost:11434") + "/api/generate";
    private static final String MODEL = "llama3.2:1b";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private record GenerateRequest(String model, String prompt, boolean stream, Options options) {}
    private record Options(int num_ctx) {}

    public String generateGuide(List<String> allFilePaths, List<Chunker.Chunk> fileSnippets) throws Exception {
        StringBuilder fileList = new StringBuilder();
        for (String path : allFilePaths) {
            fileList.append("- ").append(path).append("\n");
        }

        StringBuilder context = new StringBuilder();
        for (Chunker.Chunk chunk : fileSnippets) {
            context.append("File: ").append(chunk.filePath()).append("\n");
            context.append(chunk.content()).append("\n\n");
        }

        String prompt = """
            You are generating a DETAILED onboarding guide for a developer who just joined this project.

            Full list of files in this repository:
            %s

            Actual content snippets from EVERY file above (for YOUR understanding only — do not copy any of this into your answer):
            %s

            Write a detailed onboarding guide covering ALL of the following:
            1. Project overview: what this repository does and its purpose, in plain English
            2. Go through EVERY file listed above individually and explain its SPECIFIC role based on its actual content snippet — mention real details like class names, method purposes, or fields you see, described in plain English. Never paste, quote, or reproduce the actual code, XML, or config content — describe it in your own words only
            3. Key files to prioritize reading first, and why, in more depth
            4. Architecture, design patterns, and how the files connect to each other
            5. How someone would run/set up this project locally — only describe steps you can reasonably infer from the actual file contents. If unsure of exact commands, say "refer to the project's README for exact setup steps" instead of guessing
            6. Suggestions for where to start making changes

            STRICT RULES:
            - Do NOT include raw code snippets, file contents, XML blocks, or config file dumps anywhere in your response
            - Do NOT repeat the same section twice
            - Every explanation must be in prose/bullet form, never a code block
            - Be specific to THIS repository's actual content, not generic descriptions
            - Be thorough and do not skip any files from the list above
            - Use headers and bullet points for structure
            - Base your description ONLY on the actual file content shown above — do NOT assume functionality based on the project or file names alone (e.g. do not assume "fake news detector" means machine learning unless the actual code shows ML libraries or model training code)   
            Write the guide now:
            """.formatted(fileList.toString(), context.toString());

        String bodyJson = mapper.writeValueAsString(new GenerateRequest(MODEL, prompt, false, new Options(8192)));
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
        return root.get("response").asText();
    }
}