package cn.edu.njupt.kms.module.rag.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** RAG 模块的请求/响应对象集合。 */
public final class RagDTO {

    private RagDTO() {
    }

    /** 提问请求。 */
    @Data
    public static class AskRequest {
        @NotBlank(message = "问题不能为空")
        private String question;

        /** 会话 id；为空时自动创建新会话。 */
        private Long sessionId;

        /** 是否在知识库范围内限定分类。 */
        private Long categoryId;

        /** 是否用本体做查询扩展（默认取配置值）。 */
        private Boolean ontologyExpansion;

        /** 返回的引用条数上限。 */
        private Integer topK;

        /** 是否把检索到的片段原文一并返回（便于前端展示“证据”）。 */
        private Boolean withContext = true;
    }

    /** 问答响应。 */
    @Data
    public static class AnswerDTO {
        private Long sessionId;
        private Long messageId;
        private String question;
        private String answer;

        /** 引用的知识条目。 */
        private List<Citation> citations;

        /** 检索过程的解释性元数据，答辩演示时可直接展示。 */
        private RetrievalMeta retrieval;

        /** 生成模型标识；{@code extractive} 表示离线抽取式回答。 */
        private String model;
        /** 是否降级（未使用大模型）。 */
        private boolean degraded;
        /** 降级原因或提示信息。 */
        private String notice;

        private Integer latencyMs;
        private LocalDateTime createdAt;
    }

    /** 引用来源。 */
    @Data
    public static class Citation {
        /** 编号，与回答正文中的【来源 N】对应。 */
        private Integer index;
        private Long itemId;
        private String itemTitle;
        private String assetType;
        private Long chunkId;
        private Integer chunkIndex;
        private String snippet;
        private Double score;
        /** 该引用在各通道中的排名，体现混合检索的融合过程。 */
        private ChannelRank ranks;
    }

    @Data
    public static class ChannelRank {
        private Integer vectorRank;
        private Integer keywordRank;
        private Integer fusedRank;
        private Double vectorScore;
        private Double keywordScore;
        private Double fusedScore;
    }

    /** 检索过程元数据。 */
    @Data
    public static class RetrievalMeta {
        /** 各通道召回候选数 */
        private int vectorCandidates;
        private int keywordCandidates;
        private int fusedCandidates;
        /** 最终进入上下文的片段数 */
        private int usedChunks;
        /** 检索耗时与生成耗时（毫秒） */
        private long retrievalCostMs;
        private long generationCostMs;
        /** 向量模型说明 */
        private String embeddingModel;
        /** 是否使用向量通道 */
        private boolean vectorChannelUsed;
        /** 本体查询扩展出的知识点 */
        private List<String> expandedConcepts;
        /** 查询经过扩展后的实际检索串 */
        private String effectiveQuery;
        /** 上下文总字符数 */
        private int contextChars;
        /** 逐块的得分明细 */
        private List<Map<String, Object>> chunkScores;
    }

    /** 会话视图。 */
    @Data
    public static class SessionDTO {
        private Long id;
        private String title;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private long messageCount;
        private String lastQuestion;
    }

    /** 消息视图。 */
    @Data
    public static class MessageDTO {
        private Long id;
        private String role;
        private String content;
        private List<Citation> citations;
        private String retrievalMeta;
        private String model;
        private Integer latencyMs;
        private LocalDateTime createdAt;
    }

    /** 知识库向量索引状态（管理员）。 */
    @Data
    public static class IndexStatusDTO {
        private long totalChunks;
        private long chunksWithEmbedding;
        private long indexedItems;
        private String embeddingModel;
        private boolean onlineEmbedding;
        private int dimension;
        private String description;
        private String chatModel;
        private boolean chatAvailable;
        private List<String> staleModels;
    }

    @Data
    public static class ReindexRequest {
        /** 为空表示重建全部；否则只重建指定条目。 */
        private List<Long> itemIds;
    }

    @Data
    public static class ReindexResult {
        private int itemsProcessed;
        private int chunksCreated;
        private long costMs;
        private String embeddingModel;
        private boolean degraded;
    }
}
