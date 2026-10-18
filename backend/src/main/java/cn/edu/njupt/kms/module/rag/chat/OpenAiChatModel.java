package cn.edu.njupt.kms.module.rag.chat;

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
import java.util.List;

/**
 * OpenAI 兼容的对话生成模型。
 *
 * <p>调用 {@code POST {base-url}/chat/completions}，兼容 DeepSeek、通义千问（兼容模式）、
 * 智谱、Moonshot、硅基流动、本地 Ollama 等服务。</p>
 */
@Slf4j
public class OpenAiChatModel implements ChatModel {

    private final AppProperties.Rag.Chat.OpenAi cfg;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final int timeoutSeconds;

    public OpenAiChatModel(AppProperties.Rag.Chat cfg, ObjectMapper mapper) {
        this.cfg = cfg.getOpenai();
        this.mapper = mapper;
        this.timeoutSeconds = Math.max(cfg.getOpenai().getTimeoutSeconds(), 10);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String modelId() {
        return cfg.getModel();
    }

    @Override
    public boolean available() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override
    public Completion complete(List<Message> messages) {
        if (!available()) {
            throw new IllegalStateException("未配置生成模型的 API Key");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", cfg.getModel());
            body.put("temperature", cfg.getTemperature());
            body.put("max_tokens", cfg.getMaxTokens());
            body.put("stream", false);
            // 推理强度：仅当配置了才发送。部分兼容服务不认这个参数，
            // 无条件发送会导致 400，因此留空即不下发。
            if (cfg.getReasoningEffort() != null && !cfg.getReasoningEffort().isBlank()) {
                body.put("reasoning_effort", cfg.getReasoningEffort());
            }

            ArrayNode msgs = body.putArray("messages");
            for (Message m : messages) {
                ObjectNode node = msgs.addObject();
                node.put("role", m.role());
                node.put("content", m.content());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalize(cfg.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "生成接口返回 %d：%s".formatted(response.statusCode(),
                                cn.edu.njupt.kms.util.TextUtils.abbreviate(response.body(), 300)));
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("生成接口返回结构异常：缺少 choices");
            }

            JsonNode choice = choices.get(0);
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank()) {
                // 不能说"空回答"就完事 —— 推理类模型（如 DeepSeek 的 flash/pro）
                // 会先输出 reasoning_content，再输出 content。
                // 若 max_tokens 偏小被思考过程耗尽，content 就会为空且 finish_reason=length，
                // 报错必须把这个信号带出来，否则排查时会误以为是接口或 Key 的问题。
                String finish = choice.path("finish_reason").asText("unknown");
                JsonNode usage = root.path("usage");
                int reasoningTokens = usage.path("completion_tokens_details")
                        .path("reasoning_tokens").asInt(0);
                if (reasoningTokens > 0 || "length".equals(finish)) {
                    throw new IllegalStateException(
                            "生成接口只返回了思考过程而未返回答案（finish_reason=%s，推理消耗 %d tokens）。"
                                    .formatted(finish, reasoningTokens)
                                    + "该模型属推理类，请调大 kms.rag.chat.openai.max-tokens 后重试");
                }
                throw new IllegalStateException("生成接口返回了空回答（finish_reason=" + finish + "）");
            }
            return Completion.of(content.trim(), modelId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("生成接口调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("调用生成接口失败：" + e.getMessage(), e);
        }
    }

    private String normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
