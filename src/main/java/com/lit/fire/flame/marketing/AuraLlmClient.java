package com.lit.fire.flame.marketing;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Thin client for AuraLLM (see wakanda/aura/AuraLLM), the standalone Ollama/Bedrock-backed
 * chat gateway other Aura services already call for LLM completions (e.g. AuraService's
 * LLMServiceImpl talks to the same {@code llm.url}). The endpoint takes a single free-text
 * prompt and returns the model's raw generated text as the response body — no JSON envelope.
 */
public class AuraLlmClient {

    private final String chatUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration requestTimeout;

    public AuraLlmClient(String chatUrl, int timeoutSeconds) {
        this.chatUrl = chatUrl;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Sends {@code prompt} to AuraLLM and returns its raw text reply.
     * Uses sendAsync().get(timeout) rather than send() — a bare synchronous send() has no
     * response timeout of its own and can hang indefinitely if AuraLLM (or the underlying
     * Ollama process) stalls mid-generation (see 4bdba65 for the same fix elsewhere in this app).
     */
    public String chat(String prompt) throws Exception {
        Map<String, String> body = Map.of("prompt", prompt);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(requestTimeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new Exception("AuraLLM request timed out after " + requestTimeout.toSeconds() + "s");
        }

        if (response.statusCode() != 200) {
            throw new Exception("AuraLLM HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return response.body();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
