package cn.edu.njupt.kms.module.rag.embedding;

import java.util.List;

/**
 * 向量化模型抽象。
 *
 * <p>实现有两类：</p>
 * <ul>
 *   <li>{@link LocalHashingEmbedding} —— 纯本地、零外部依赖的哈希向量，
 *       保证系统在完全离线时也能提供向量检索通道；</li>
 *   <li>{@link OpenAiEmbedding} —— 走 OpenAI 兼容的 {@code /embeddings} 接口，
 *       可获得真正的语义向量。</li>
 * </ul>
 *
 * <p>两者产出的向量一律做 L2 归一化，因此余弦相似度可直接用点积计算。</p>
 */
public interface EmbeddingModel {

    /** 模型标识，写入分块记录，用于判断已有向量是否需要重建。 */
    String modelId();

    /** 向量维度。 */
    int dimension();

    /** 单条文本向量化。 */
    float[] embed(String text);

    /** 批量向量化；默认逐条调用，在线模型会覆写为真正的批量请求。 */
    default List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** 该模型当前是否可用（例如在线模型缺少 API Key 时返回 false）。 */
    default boolean available() {
        return true;
    }
}
