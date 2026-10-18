package cn.edu.njupt.kms.module.rag.embedding;

import cn.edu.njupt.kms.util.TextUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地哈希向量模型（离线可用，零外部依赖）。
 *
 * <p>做法是「特征哈希 + TF 加权 + L2 归一化」：</p>
 * <ol>
 *   <li>抽取特征：jieba 分词结果 + 字符 n-gram（中文的关键补充）+ 英文词元；</li>
 *   <li>把每个特征哈希到固定维度的桶里，并用第二个哈希决定符号（signed hashing），
 *       以降低哈希冲突带来的系统性偏差；</li>
 *   <li>权重取 sublinear TF {@code 1 + ln(tf)}，并按特征类型加权
 *       （词 > 二元组 > 单字）；</li>
 *   <li>L2 归一化，使余弦相似度等于点积。</li>
 * </ol>
 *
 * <p><b>与语义向量的区别：</b>该方法捕捉的是词汇层面的重叠，
 * 不具备同义扩展能力。因此检索时它与 MySQL 全文通道做 RRF 融合，
 * 真正的语义理解交由上层的大模型完成。生产环境建议切换为在线向量模型。</p>
 */
@Slf4j
public class LocalHashingEmbedding implements EmbeddingModel {

    private final int dim;
    private final int ngramMin;
    private final int ngramMax;
    private final String modelId;

    public LocalHashingEmbedding(int dim, int ngramMin, int ngramMax) {
        this.dim = dim;
        this.ngramMin = ngramMin;
        this.ngramMax = ngramMax;
        this.modelId = "local-hash-%d-%d_%d".formatted(dim, ngramMin, ngramMax);
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dim];
        if (text == null || text.isBlank()) {
            return vector;
        }

        for (Map.Entry<String, Double> e : features(text).entrySet()) {
            String feature = e.getKey();
            int hash = murmurLike(feature);
            int bucket = Math.floorMod(hash, dim);
            // 用另一个哈希位决定符号，抵消冲突带来的向量同向漂移
            int sign = ((hash >>> 16) & 1) == 0 ? 1 : -1;
            vector[bucket] += (float) (sign * e.getValue());
        }

        l2Normalize(vector);
        return vector;
    }

    /**
     * 抽取加权特征。
     *
     * <p>权重设计：完整词权重最高，字符二元组次之，单字最低 ——
     * 这样“软件测试”这类术语的贡献远大于它被切碎后的片段。</p>
     */
    private Map<String, Double> features(String text) {
        Map<String, Double> tf = new LinkedHashMap<>();

        // 1) 词级特征
        for (String token : TextUtils.tokenize(text)) {
            String key = "w:" + token.toLowerCase();
            tf.merge(key, 1.0, Double::sum);
        }

        // 2) 字符 n-gram 特征（对中文尤其重要：术语切分不一致时的兜底）
        String compact = text.replaceAll("\\s+", " ").toLowerCase();
        for (int n = ngramMin; n <= ngramMax; n++) {
            for (int i = 0; i + n <= compact.length(); i++) {
                String gram = compact.substring(i, i + n);
                if (gram.isBlank() || isPunctuation(gram)) {
                    continue;
                }
                // 单字与二元组降权，避免它们淹没词级特征
                double weight = n == 1 ? 0.25 : 0.6;
                tf.merge(n + "g:" + gram, weight, Double::sum);
            }
        }

        // 3) sublinear TF：抑制高频特征的过度影响
        Map<String, Double> weighted = new LinkedHashMap<>(tf.size());
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            double count = e.getValue();
            double base = e.getKey().startsWith("w:") ? 1.0
                    : e.getKey().startsWith("2g:") ? 0.6 : 0.25;
            double value = (1 + Math.log(Math.max(count / base, 1.0))) * base;
            weighted.put(e.getKey(), value);
        }
        return weighted;
    }

    private boolean isPunctuation(String s) {
        return s.chars().allMatch(c -> !Character.isLetterOrDigit(c));
    }

    /** 稳定的字符串哈希（对内容敏感、分布均匀，与 MurmurHash 思路一致）。 */
    private static int murmurLike(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
            h ^= h >>> 13;
        }
        return h;
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

    /** 供调试：查看一段文本被抽成了哪些特征。 */
    public List<String> debugFeatures(String text) {
        return new ArrayList<>(features(text).keySet());
    }
}
