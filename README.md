# Codebase Onboarding Assistant

An AI-powered tool that generates a personalized "start here" guide for any public GitHub repository. Instead of manually digging through an unfamiliar codebase, point this at a repo and get a plain-English breakdown of what it does, which files matter most, and how the pieces connect.

Built as a Retrieval-Augmented Generation (RAG) pipeline on Spring Boot, using free/local tooling end-to-end — no paid API keys required.

## What it does

1. **Ingests** a GitHub repository's file tree via the GitHub API
2. **Filters** files down to relevant source code and docs (skips build artifacts, dependencies, etc.)
3. **Chunks** file contents into manageable pieces
4. **Embeds** each chunk into a vector representation using a local embedding model (Ollama)
5. **Generates** a detailed onboarding guide covering project overview, per-file explanations, architecture, and setup steps — using a local LLM (Ollama)
6. Serves everything through a **REST API** with a simple web frontend

## Tech Stack

- **Backend:** Java 21, Spring Boot 4
- **LLM & Embeddings:** Ollama (runs models locally — no API costs)
  - nomic-embed-text for embeddings
  - llama3.2:1b for guide generation
- **Frontend:** Plain HTML/CSS/JS (served as a Spring Boot static resource)
- **Containerization:** Docker + Docker Compose

## Architecture

GitHub Repo URL
      |
      v
GitHubIngestion  -->  fetches file tree + file contents via GitHub API
      |
      v
Chunker          -->  splits file contents into chunks
      |
      v
EmbeddingService -->  generates vector embeddings (Ollama)
      |
      v
GuideGenerator   -->  feeds file content + context into local LLM (Ollama)
      |
      v
OnboardingController  -->  exposes it all via POST /api/generate-guide
      |
      v
Frontend (index.html)  -->  simple form + rendered guide output

## Prerequisites

- Java 21+
- Maven
- Ollama installed locally (https://ollama.com), with these models pulled:
  ollama pull nomic-embed-text
  ollama pull llama3.2:1b
- A GitHub personal access token (classic, repo scope) from https://github.com/settings/tokens — needed to avoid GitHub's low rate limit for unauthenticated requests

## Setup

1. Clone this repository
2. Create a .env file in the project root:
   GITHUB_TOKEN=your_github_token_here
3. Make sure Ollama is running locally (http://localhost:11434)
4. Run the app:
   mvn spring-boot:run
5. Open http://localhost:8080 in your browser

## Running with Docker

This project includes a Dockerfile and docker-compose.yml that run both the Spring Boot app and Ollama as containers:

docker-compose up

Note: after the Ollama container starts for the first time, you'll need to pull the models inside it:
docker exec -it <ollama_container_name> ollama pull nomic-embed-text
docker exec -it <ollama_container_name> ollama pull llama3.2:1b

## API

POST /api/generate-guide

Request body:
{
  "owner": "octocat",
  "repo": "Spoon-Knife",
  "branch": "main"
}

Response:
{
  "guide": "..."
}

## Known Limitations

- Uses a small local LLM (llama3.2:1b) for speed, which can occasionally infer plausible-but-inaccurate details not present in the actual code (a known tradeoff of smaller models vs. larger ones)
- Only ingests certain file extensions (see INCLUDED_EXTENSIONS in GitHubIngestion.java) — configurable
- GitHub's secondary rate limits can slow down ingestion for very large repositories

## Future Improvements

- Swap in a larger local model (or hosted API) for improved accuracy
- Add semantic search/retrieval ranking back in for very large repos where full-content context exceeds the LLM's context window
- Persist embeddings instead of regenerating them on every request
