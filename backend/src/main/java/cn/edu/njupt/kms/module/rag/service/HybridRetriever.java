package cn.edu.njupt.kms.module.rag.service;

import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.module.rag.embedding.EmbeddingService;
import cn.edu.njupt.kms.module.rag.entity.RagChunk;
import cn.edu.njupt.kms.module.rag.repository.RagChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 混合检索器：向量通道 + 关键字通道，用倒数排名融合（RRF）合并。
 *
 * <p><b>为什么混合？</b>两条通道的强弱正好互补：</p>
 * <ul>
 *   <li>向量通道按语义相似度召回，擅长「问法与原文用词不同」的情况，
 *       但精度有限，容易召回泛泛而谈的块；</li>
 *   <li>关键字通道（MySQL ngram 全文索引）按词面命中，精确但无法处理同义改写，
 *       也容易漏掉表述方式不同的段落。</li>
 * </ul>
 *
 * <p><b>RRF 融合：</b>对每条通道的排名取 {@code 1 / (k + rank)} 相加，
 * 只需排名、不需要两边的分数可比，天然规避了「相似度 0.83 与 BM25 分 12.4 如何相加」的问题。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetriever {

    private final RagChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final AppProperties props;

    /**
     * 一次检索的候选项。
     *
     * <p>同时保留各通道的排名与得分，便于在界面上解释「为什么这条被召回」。</p>
     */
    public static class Candidate {
        public RagChunk chunk;
        public Integer vectorRank;
        public Integer keywordRank;
        public Double vectorScore;
        public Double keywordScore;
        public double fusedScore;
        public int fusedRank;

        public Long itemId() {
            return chunk.getItemId();
        }
    }

    /** 检索结果。 */
    public record Result(List<Candidate> candidates,
                         int vectorCandidates,
                         int keywordCandidates,
                         boolean vectorChannelUsed) {
    }

    /**
     * 混合检索。
     *
     * @param query     检索串（可能已被本体扩展增强）
     * @param limit     返回的候选数
     * @param itemScope 限定检索的条目范围；为 null 表示全库
     */
    @Transactional(readOnly = true)
    public Result retrieve(String query, int limit, Collection<Long> itemScope) {
        int vectorTopK = props.getRag().getVectorTopK();
        int keywordTopK = props.getRag().getKeywordTopK();
        int rrfK = props.getRag().getRrfK();

        Map<Long, Candidate> merged = new LinkedHashMap<>();

        // ---- 通道一：向量相似度 ----
        boolean vectorUsed = false;
        try {
            float[] queryVector = embeddingService.embed(query);
            if (queryVector.length > 0) {
                List<RagChunk> pool = (itemScope == null)
                        ? chunkRepository.findAllWithEmbedding()
                        : chunkRepository.findWithEmbeddingIn(itemScope);
                List<Map.Entry<RagChunk, Double>> scored = new ArrayList<>(pool.size());
                for (RagChunk chunk : pool) {
                    if (chunk.getEmbedding() == null) {
                        continue;
                    }
                    float[] v = EmbeddingService.fromBytes(chunk.getEmbedding());
                    if (v.length != queryVector.length) {
                        // 维度不一致说明该块由另一种模型生成，跳过（避免比较不同向量空间）
                        continue;
                    }
                    double sim = EmbeddingService.cosine(queryVector, v);
                    if (sim > 0.01) {
                        scored.add(Map.entry(chunk, sim));
                    }
                }
                scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

                int rank = 0;
                for (Map.Entry<RagChunk, Double> e : scored) {
                    if (rank >= vectorTopK) {
                        break;
                    }
                    Candidate c = merged.computeIfAbsent(e.getKey().getId(), k -> new Candidate());
                    c.chunk = e.getKey();
                    c.vectorRank = ++rank;
                    c.vectorScore = e.getValue();
                    vectorUsed = true;
                }
            }
        } catch (Exception e) {
            log.warn("向量通道检索失败，本次仅使用关键字通道：{}", e.toString());
        }

        // ---- 通道二：全文索引 ----
        try {
            String kw = sanitizeForFulltext(query);
            if (!kw.isBlank()) {
                List<Object[]> rows = itemScope == null
                        ? chunkRepository.keywordSearchAll(kw, keywordTopK)
                        : chunkRepository.keywordSearch(kw, itemScope, keywordTopK);

                int rank = 0;
                for (Object[] row : rows) {
                    Long id = ((Number) row[0]).longValue();
                    double score = row[1] == null ? 0 : ((Number) row[1]).doubleValue();
                    Candidate c = merged.get(id);
                    if (c == null) {
                        c = new Candidate();
                        c.chunk = chunkRepository.findById(id).orElse(null);
                        if (c.chunk == null) {
                            continue;
                        }
                        merged.put(id, c);
                    }
                    c.keywordRank = ++rank;
                    c.keywordScore = score;
                }
            }
        } catch (Exception e) {
            log.warn("关键字通道检索失败：{}", e.toString());
        }

        // ---- RRF 融合 ----
        List<Candidate> candidates = new ArrayList<>(merged.values());
        for (Candidate c : candidates) {
            double score = 0;
            if (c.vectorRank != null) {
                score += 1.0 / (rrfK + c.vectorRank);
            }
            if (c.keywordRank != null) {
                score += 1.0 / (rrfK + c.keywordRank);
            }
            // 关键词通道权重略高：在知识库这类术语密集的语料上，词面命中往往更可信
            if (c.keywordRank != null) {
                score *= 1.15;
            }
            c.fusedScore = score;
        }
        candidates.sort((a, b) -> Double.compare(b.fusedScore, a.fusedScore));
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).fusedRank = i + 1;
        }

        int vectorCandidates = (int) candidates.stream().filter(c -> c.vectorRank != null).count();
        int keywordCandidates = (int) candidates.stream().filter(c -> c.keywordRank != null).count();

        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
        }
        return new Result(candidates, vectorCandidates, keywordCandidates, vectorUsed);
    }

    /**
     * 清理查询串以供 MySQL 全文检索使用。
     *
     * <p>布尔模式下的 {@code + - ~ * " ( )} 等是操作符，直接传入会改变语义甚至报错；
     * 这里统一剔除，只保留中英文与数字。</p>
     */
    public static String sanitizeForFulltext(String query) {
        if (query == null) {
            return "";
        }
        return query.replaceAll("[+\\-~*\"()<>@:\\\\/]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
