package cn.edu.njupt.kms.module.rag.embedding;

import cn.edu.njupt.kms.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量化服务：按配置选择模型，并保证「永远有一个可用的模型」。
 *
 * <p>选择逻辑：</p>
 * <ol>
 *   <li>配置为 {@code openai} 且填了 API Key → 用在线模型；</li>
 *   <li>否则 → 用本地哈希向量模型（离线可用）；</li>
 *   <li>在线模型调用中抛异常（限流、断网、额度用尽）→ 本次自动降级为本地模型，
 *       保证问答功能不中断，同时在日志中留下告警。</li>
 * </ol>
 *
 * <p>需要注意：本地与在线模型产出的向量<b>不在同一向量空间</b>，
 * 不能混用做相似度比较。因此分块记录里保存了 {@code embed_model}，
 * 检索时只比对与当前模型一致的分块；切换模型后需重建索引。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final AppProperties props;
    private final ObjectMapper objectMapper;

    private EmbeddingModel primary;
    private EmbeddingModel fallback;

    @PostConstruct
    void init() {
        AppProperties.Rag.Embedding cfg = props.getRag().getEmbedding();

        this.fallback = new LocalHashingEmbedding(
                cfg.getLocal().getDim(),
                cfg.getLocal().getNgramMin(),
                cfg.getLocal().getNgramMax());

        if ("openai".equalsIgnoreCase(cfg.getProvider())) {
            OpenAiEmbedding online = new OpenAiEmbedding(cfg.getOpenai(), objectMapper);
            if (online.available()) {
                this.primary = online;
                log.info("向量模型：在线模型 {}（baseUrl={}）",
                        cfg.getOpenai().getModel(), cfg.getOpenai().getBaseUrl());
            } else {
                this.primary = fallback;
                log.warn("配置了在线向量模型但未提供 API Key，已回退到本地哈希向量模型 {}",
                        fallback.modelId());
            }
        } else {
            this.primary = fallback;
            log.info("向量模型：本地哈希向量 {}（维度 {}）", fallback.modelId(), fallback.dimension());
        }
    }

    /** 当前主用模型标识。 */
    public String modelId() {
        return primary.modelId();
    }

    /** 当前主用模型的维度；在线模型在首次调用前为 0（未知）。 */
    public int dimension() {
        return primary.dimension();
    }

    /** 是否正在使用在线向量模型。 */
    public boolean isOnline() {
        return primary != fallback;
    }

    /** 是否降级到了本地模型（供界面提示）。 */
    public boolean isDegraded() {
        return primary == fallback;
    }

    public String description() {
        return isOnline()
                ? "在线语义向量模型 " + primary.modelId()
                : "本地哈希向量模型 " + primary.modelId() + "（离线可用，词汇级匹配）";
    }

    /** 单条文本向量化。 */
    public float[] embed(String text) {
        try {
            float[] v = primary.embed(text);
            if (v.length > 0) {
                return v;
            }
            return fallback.embed(text);
        } catch (Exception e) {
            log.warn("在线向量模型调用失败，本次降级为本地模型：{}", e.getMessage());
            return fallback.embed(text);
        }
    }

    /**
     * 批量向量化的结果。
     *
     * @param vectors    向量列表
     * @param modelId    真正产出这批向量的模型标识（可能因降级而非主用模型）
     * @param degraded   本次是否发生了降级
     */
    public record BatchResult(List<float[]> vectors, String modelId, boolean degraded) {
    }

    /**
     * 批量向量化（建索引时使用）。
     *
     * <p>返回实际生效的模型标识，调用方据此写入分块的 {@code embed_model} 字段 ——
     * 这一点很重要：不同模型产出的向量不在同一空间，检索时必须按模型分组比对。</p>
     */
    public BatchResult embedBatch(List<String> texts) {
        if (primary != fallback) {
            try {
                List<float[]> vectors = primary.embedBatch(texts);
                if (vectors.size() == texts.size() && vectors.stream().allMatch(v -> v.length > 0)) {
                    return new BatchResult(vectors, primary.modelId(), false);
                }
                log.warn("在线向量模型批量返回数量或维度异常（期望 {} 条），本次降级为本地模型", texts.size());
            } catch (Exception e) {
                log.warn("在线向量模型批量调用失败，本次降级为本地模型：{}", e.getMessage());
            }
        }
        return new BatchResult(fallback.embedBatch(texts), fallback.modelId(), primary != fallback);
    }

    // ------------------------------------------------------------------
    // 向量与字节序列的互转
    // ------------------------------------------------------------------

    /** float[] → 小端字节序列。 */
    public static byte[] toBytes(float[] vector) {
        if (vector == null) {
            return null;
        }
        byte[] bytes = new byte[vector.length * 4];
        for (int i = 0; i < vector.length; i++) {
            int bits = Float.floatToIntBits(vector[i]);
            bytes[i * 4] = (byte) (bits & 0xFF);
            bytes[i * 4 + 1] = (byte) ((bits >> 8) & 0xFF);
            bytes[i * 4 + 2] = (byte) ((bits >> 16) & 0xFF);
            bytes[i * 4 + 3] = (byte) ((bits >> 24) & 0xFF);
        }
        return bytes;
    }

    /** 小端字节序列 → float[]。 */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return new float[0];
        }
        int n = bytes.length / 4;
        float[] vector = new float[n];
        for (int i = 0; i < n; i++) {
            int bits = (bytes[i * 4] & 0xFF)
                    | ((bytes[i * 4 + 1] & 0xFF) << 8)
                    | ((bytes[i * 4 + 2] & 0xFF) << 16)
                    | ((bytes[i * 4 + 3] & 0xFF) << 24);
            vector[i] = Float.intBitsToFloat(bits);
        }
        return vector;
    }

    /** 余弦相似度；向量已归一化，等于点积。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        // 归一化向量直接返回点积；未归一化时除以模长（容错）
        return Math.abs(na - 1.0) < 1e-3 && Math.abs(nb - 1.0) < 1e-3
                ? dot
                : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
