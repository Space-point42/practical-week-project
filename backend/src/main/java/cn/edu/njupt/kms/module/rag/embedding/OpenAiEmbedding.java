package cn.edu.njupt.kms.module.rag.embedding;

import cn.edu.njupt.kms.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容的在线向量模型。
 *
 * <p>按 {@code POST {base-url}/embeddings} 协议调用，
 * 可对接 OpenAI、DeepSeek 系、阿里百炼（DashScope 兼容模式）、硅基流动、
 * 智谱、Moonshot 以及本地 Ollama 等任意兼容实现。</p>
 *
 * <p>调用失败（网络异常、配额不足、模型名错误）时抛出异常，
 * 由 {@code EmbeddingService} 决定降级为本地模型，避免整条链路中断。</p>
 */
@Slf4j
public class OpenAiEmbedding implements EmbeddingModel {

    private final AppProperties.Rag.Embedding.OpenAi cfg;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String modelId;

    public OpenAiEmbedding(AppProperties.Rag.Embedding.OpenAi cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.modelId = "openai:" + cfg.getModel();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dimension() {
        // 在线模型的维度由服务端决定，首次调用后可从返回结果推断；
        // 这里返回 0 表示「未知」，调用方应以实际向量长度为准。
        return 0;
    }

    @Override
    public boolean available() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text == null ? "" : text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (!available()) {
            throw new IllegalStateException("未配置在线向量模型的 API Key");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        int batchSize = Math.max(cfg.getBatchSize(), 1);

        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> slice = texts.subList(i, Math.min(i + batchSize, texts.size()));
            out.addAll(requestBatch(slice));
        }
        return out;
    }

    private List<float[]> requestBatch(List<String> texts) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", cfg.getModel());
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalize(cfg.getBaseUrl()) + "/embeddings"))
                    .timeout(Duration.ofSeconds(Math.max(cfg.getTimeoutSeconds(), 10)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "向量接口返回 %d：%s".formatted(response.statusCode(),
                                cn.edu.njupt.kms.util.TextUtils.abbreviate(response.body(), 300)));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new IllegalStateException("向量接口返回结构异常：缺少 data 数组");
            }

            List<float[]> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                float[] v = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                l2Normalize(v);
                vectors.add(v);
            }
            return vectors;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("向量接口调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("调用向量接口失败：" + e.getMessage(), e);
        }
    }

    private String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static void l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        if (sum <= 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] /= norm;
        }
    }
}
