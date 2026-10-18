package cn.edu.njupt.kms.module.rag.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import cn.edu.njupt.kms.module.kb.service.CategoryService;
import cn.edu.njupt.kms.module.ontology.dto.OntologyDTO;
import cn.edu.njupt.kms.module.ontology.entity.Concept;
import cn.edu.njupt.kms.module.ontology.service.OntologyService;
import cn.edu.njupt.kms.module.rag.chat.ChatModel;
import cn.edu.njupt.kms.module.rag.chat.ChatService;
import cn.edu.njupt.kms.module.rag.chat.ExtractiveAnswerBuilder;
import cn.edu.njupt.kms.module.rag.dto.RagDTO;
import cn.edu.njupt.kms.module.rag.embedding.EmbeddingService;
import cn.edu.njupt.kms.module.rag.entity.RagChunk;
import cn.edu.njupt.kms.module.rag.entity.RagMessage;
import cn.edu.njupt.kms.module.rag.entity.RagSession;
import cn.edu.njupt.kms.module.rag.repository.RagMessageRepository;
import cn.edu.njupt.kms.module.rag.repository.RagSessionRepository;
import cn.edu.njupt.kms.security.SecurityUtils;
import cn.edu.njupt.kms.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG（检索增强生成）问答服务。
 *
 * <p>完整流程：</p>
 * <pre>
 *   问题
 *    ├─(1) 本体查询扩展：识别问题中的知识点，并沿本体关系补充相邻概念
 *    ├─(2) 混合检索：向量通道 + 全文索引通道，RRF 融合
 *    ├─(3) 上下文组装：按条目去重、控制长度、标注来源编号
 *    ├─(4) 生成：在线大模型 → 失败/未配置则降级为离线抽取式回答
 *    └─(5) 落库：会话、消息、引用与检索过程元数据
 * </pre>
 *
 * <p>第 (1) 步是本系统的特色：由于知识库自带本体，
 * 用户搜「需求分析」时系统能顺带召回「需求获取」「需求验证」等相邻知识点的资料，
 * 这比单纯的语义相似度更贴合「课程知识体系」的组织方式。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final RagSessionRepository sessionRepository;
    private final RagMessageRepository messageRepository;
    private final KnowledgeItemRepository itemRepository;
    private final HybridRetriever retriever;
    private final EmbeddingService embeddingService;
    private final ChatService chatService;
    private final ExtractiveAnswerBuilder extractiveBuilder;
    private final OntologyService ontologyService;
    private final CategoryService categoryService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 问答主流程
    // ==================================================================

    @Transactional
    public RagDTO.AnswerDTO ask(RagDTO.AskRequest req) {
        long start = System.currentTimeMillis();
        String question = req.getQuestion().trim();

        RagSession session = resolveSession(req.getSessionId(), question);
        saveMessage(session.getId(), RagMessage.ROLE_USER, question, null, null, null, null);

        // ---- (1) 本体查询扩展 ----
        long retrievalStart = System.currentTimeMillis();
        QueryExpansion expansion = expandQuery(question, req.getOntologyExpansion());

        // ---- (2) 混合检索 ----
        int topK = req.getTopK() == null ? props.getRag().getTopK() : Math.min(req.getTopK(), 20);
        Collection<Long> scope = resolveScope(req.getCategoryId());
        HybridRetriever.Result retrieved = retriever.retrieve(expansion.query(), topK * 2, scope);

        long retrievalCost = System.currentTimeMillis() - retrievalStart;

        // ---- (3) 上下文组装 ----
        List<RetrievedContext> contexts = buildContexts(retrieved, topK);
        long generationStart = System.currentTimeMillis();

        // ---- (4) 生成 ----
        GenerationOutcome outcome = generate(question, contexts, session.getId());

        long generationCost = System.currentTimeMillis() - generationStart;

        // ---- (5) 落库 ----
        List<RagDTO.Citation> citations = buildCitations(contexts);
        RagDTO.RetrievalMeta meta = buildMeta(expansion, retrieved, contexts, retrievalCost, generationCost);

        RagMessage assistant = saveMessage(session.getId(), RagMessage.ROLE_ASSISTANT,
                outcome.answer(), citations, meta, outcome.model(), (int) (System.currentTimeMillis() - start));

        session.setUpdatedAt(LocalDateTime.now());
        if ("新会话".equals(session.getTitle())) {
            session.setTitle(TextUtils.abbreviate(question, 40));
        }
        sessionRepository.save(session);

        RagDTO.AnswerDTO dto = new RagDTO.AnswerDTO();
        dto.setSessionId(session.getId());
        dto.setMessageId(assistant.getId());
        dto.setQuestion(question);
        dto.setAnswer(outcome.answer());
        dto.setCitations(citations);
        dto.setRetrieval(meta);
        dto.setModel(outcome.model());
        dto.setDegraded(outcome.degraded());
        dto.setNotice(outcome.notice());
        dto.setLatencyMs((int) (System.currentTimeMillis() - start));
        dto.setCreatedAt(assistant.getCreatedAt());
        return dto;
    }

    // ==================================================================
    // (1) 查询扩展
    // ==================================================================

    private record QueryExpansion(String query, List<String> conceptNames, String original) {
    }

    private QueryExpansion expandQuery(String question, Boolean enabledOverride) {
        boolean enabled = enabledOverride != null ? enabledOverride : props.getRag().isOntologyExpansion();
        if (!enabled) {
            return new QueryExpansion(question, List.of(), question);
        }
        try {
            List<OntologyDTO.ConceptDTO> hit = ontologyService.link(question, 8);
            if (hit.isEmpty()) {
                return new QueryExpansion(question, List.of(), question);
            }
            List<Long> hitIds = hit.stream().map(OntologyDTO.ConceptDTO::getId).toList();
            List<Concept> expanded = ontologyService.expand(hitIds,
                    props.getRag().getOntologyExpandLimit());

            if (expanded.isEmpty()) {
                return new QueryExpansion(question,
                        hit.stream().map(OntologyDTO.ConceptDTO::getName).toList(), question);
            }

            // 把扩展概念名拼到查询串尾部：全文通道能直接命中这些词，
            // 向量通道的词面特征也会随之变化，两条通道同时受益。
            List<String> allNames = new ArrayList<>(hit.stream().map(OntologyDTO.ConceptDTO::getName).toList());
            expanded.forEach(c -> allNames.add(c.getName()));
            String expandedQuery = question + " " + expanded.stream()
                    .map(Concept::getName).collect(Collectors.joining(" "));

            return new QueryExpansion(expandedQuery, allNames, question);
        } catch (Exception e) {
            log.debug("本体查询扩展失败，使用原始查询：{}", e.toString());
            return new QueryExpansion(question, List.of(), question);
        }
    }

    // ==================================================================
    // (3) 上下文组装
    // ==================================================================

    private record RetrievedContext(int index, RagChunk chunk, String itemTitle,
                                    String assetType, Long itemId, HybridRetriever.Candidate candidate) {
    }

    /** 把候选分块整理成上下文：同一块只留一次，同一附件的相邻块合并展示。 */
    private List<RetrievedContext> buildContexts(HybridRetriever.Result retrieved, int topK) {
        if (retrieved.candidates().isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = retrieved.candidates().stream()
                .map(HybridRetriever.Candidate::itemId).collect(Collectors.toSet());
        Map<Long, KnowledgeItem> items = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(KnowledgeItem::getId, i -> i));

        List<RetrievedContext> out = new ArrayList<>();
        Set<Long> seenChunks = new HashSet<>();
        int index = 1;

        for (HybridRetriever.Candidate c : retrieved.candidates()) {
            if (out.size() >= topK) {
                break;
            }
            RagChunk chunk = c.chunk;
            if (chunk == null || !seenChunks.add(chunk.getId())) {
                continue;
            }
            KnowledgeItem item = items.get(chunk.getItemId());
            if (item == null || KnowledgeItem.ARCHIVED.equals(item.getStatus())) {
                continue;
            }
            out.add(new RetrievedContext(index++, chunk, item.getTitle(),
                    item.getAssetType(), item.getId(), c));
        }
        return out;
    }

    // ==================================================================
    // (4) 生成
    // ==================================================================

    private record GenerationOutcome(String answer, String model, boolean degraded, String notice) {
    }

    private GenerationOutcome generate(String question, List<RetrievedContext> contexts, Long sessionId) {
        if (contexts.isEmpty()) {
            return new GenerationOutcome(
                    "知识库中暂时没有找到与该问题相关的内容。\n\n"
                            + "建议换用更具体的关键词（例如“等价类划分”“三范式”），"
                            + "或先到「知识图谱」页确认相关知识点的资料是否已录入。",
                    "none", true, "知识库中无相关内容，未调用生成模型");
        }

        List<ChatModel.Message> messages = buildMessages(question, contexts, sessionId);
        ChatModel.Completion completion = chatService.tryComplete(messages);

        if (completion != null) {
            return new GenerationOutcome(completion.content(), completion.model(), false, null);
        }

        // 降级：离线抽取式回答
        String reason = chatService.isOffline()
                ? "未配置生成模型 API Key"
                : "生成模型调用失败";
        List<ExtractiveAnswerBuilder.Source> sources = contexts.stream()
                .map(c -> new ExtractiveAnswerBuilder.Source(
                        c.index(), c.itemId(), c.itemTitle(), c.chunk().getContent(),
                        round(c.candidate().fusedScore * 100)))
                .toList();

        String answer = extractiveBuilder.build(question, sources, reason);
        return new GenerationOutcome(answer, "extractive", true, reason);
    }

    /** 组装对话消息：system 提示 + 多轮历史 + 带编号上下文的当前问题。 */
    private List<ChatModel.Message> buildMessages(String question, List<RetrievedContext> contexts, Long sessionId) {
        List<ChatModel.Message> messages = new ArrayList<>();
        messages.add(ChatModel.Message.system(systemPrompt()));

        // 历史轮次。
        // 注意：本轮提问在调用本方法之前就已落库，因此 findRecent 取回的最后一条
        // 就是本次的问题本身。若不去掉，同一个问题会在请求里出现两次 ——
        // 既浪费上下文，也可能让模型误以为被追问了两次。
        int rounds = props.getRag().getHistoryRounds();
        if (rounds > 0) {
            List<RagMessage> recent = new ArrayList<>(
                    messageRepository.findRecent(sessionId, PageRequest.of(0, rounds * 2 + 1)));
            Collections.reverse(recent);

            int last = recent.size() - 1;
            if (last >= 0) {
                RagMessage tail = recent.get(last);
                if (RagMessage.ROLE_USER.equals(tail.getRole())
                        && question.equals(tail.getContent())) {
                    recent.remove(last);
                }
            }

            for (RagMessage m : recent) {
                if (RagMessage.ROLE_USER.equals(m.getRole())) {
                    messages.add(ChatModel.Message.user(TextUtils.abbreviate(m.getContent(), 500)));
                } else {
                    messages.add(ChatModel.Message.assistant(TextUtils.abbreviate(m.getContent(), 800)));
                }
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("以下是从知识库中检索到的资料片段，请仅依据这些资料回答问题。\n\n");
        for (RetrievedContext c : contexts) {
            prompt.append("【来源 ").append(c.index()).append("】")
                    .append("《").append(c.itemTitle()).append("》")
                    .append("（").append(assetTypeName(c.assetType())).append("）\n")
                    .append(TextUtils.abbreviate(c.chunk().getContent(), 1200))
                    .append("\n\n");
        }
        prompt.append("---\n\n用户问题：").append(question);

        messages.add(ChatModel.Message.user(prompt.toString()));
        return messages;
    }

    private String systemPrompt() {
        return """
                你是《专业课程设计II》课程知识库的问答助手，服务于南京邮电大学计算机学院的学生。

                回答要求：
                1. 只依据【来源】中提供的资料作答，不要引入资料之外的推测；资料不足时明确说明「现有资料未涵盖」。
                2. 在每个结论后用【来源 N】标注依据，便于读者核对原文。
                3. 用简体中文回答，结构清晰：先给结论，再分点展开，必要时给出例子。
                4. 涉及概念定义、方法步骤、对比分析时，尽量保持与资料一致的术语表述。
                5. 若多个来源存在分歧，指出分歧并分别标注来源。
                """.trim();
    }

    // ==================================================================
    // 引用与元数据
    // ==================================================================

    private List<RagDTO.Citation> buildCitations(List<RetrievedContext> contexts) {
        return contexts.stream().map(c -> {
            RagDTO.Citation citation = new RagDTO.Citation();
            citation.setIndex(c.index());
            citation.setItemId(c.itemId());
            citation.setItemTitle(c.itemTitle());
            citation.setAssetType(c.assetType());
            citation.setChunkId(c.chunk().getId());
            citation.setChunkIndex(c.chunk().getChunkIndex());
            citation.setSnippet(TextUtils.abbreviate(
                    TextUtils.normalizeWhitespace(c.chunk().getContent()), 260));
            citation.setScore(round(c.candidate().fusedScore * 100));

            RagDTO.ChannelRank ranks = new RagDTO.ChannelRank();
            ranks.setVectorRank(c.candidate().vectorRank);
            ranks.setKeywordRank(c.candidate().keywordRank);
            ranks.setFusedRank(c.candidate().fusedRank);
            ranks.setVectorScore(c.candidate().vectorScore == null ? null : round(c.candidate().vectorScore));
            ranks.setKeywordScore(c.candidate().keywordScore == null ? null : round(c.candidate().keywordScore));
            ranks.setFusedScore(round(c.candidate().fusedScore * 100));
            citation.setRanks(ranks);
            return citation;
        }).toList();
    }

    private RagDTO.RetrievalMeta buildMeta(QueryExpansion expansion,
                                           HybridRetriever.Result retrieved,
                                           List<RetrievedContext> contexts,
                                           long retrievalCost,
                                           long generationCost) {
        RagDTO.RetrievalMeta meta = new RagDTO.RetrievalMeta();
        meta.setVectorCandidates(retrieved.vectorCandidates());
        meta.setKeywordCandidates(retrieved.keywordCandidates());
        meta.setFusedCandidates(retrieved.candidates().size());
        meta.setUsedChunks(contexts.size());
        meta.setRetrievalCostMs(retrievalCost);
        meta.setGenerationCostMs(generationCost);
        meta.setEmbeddingModel(embeddingService.modelId());
        meta.setVectorChannelUsed(retrieved.vectorChannelUsed());
        meta.setExpandedConcepts(expansion.conceptNames());
        boolean expanded = !expansion.query().equals(expansion.original());
        meta.setEffectiveQuery(expanded ? expansion.query() : expansion.original());
        meta.setContextChars(contexts.stream().mapToInt(c -> c.chunk().getContent().length()).sum());

        meta.setChunkScores(contexts.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", c.index());
            m.put("chunkId", c.chunk().getId());
            m.put("itemTitle", c.itemTitle());
            m.put("vectorRank", c.candidate().vectorRank);
            m.put("keywordRank", c.candidate().keywordRank);
            m.put("vectorScore", c.candidate().vectorScore);
            m.put("keywordScore", c.candidate().keywordScore);
            m.put("fusedScore", round(c.candidate().fusedScore * 100));
            return m;
        }).toList());

        return meta;
    }

    // ==================================================================
    // 会话管理
    // ==================================================================

    private RagSession resolveSession(Long sessionId, String question) {
        Long userId = SecurityUtils.currentUserId();
        if (sessionId != null) {
            RagSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> BizException.notFound("会话"));
            // 只允许访问自己的会话（管理员除外）
            if (!SecurityUtils.isAdmin() && session.getUserId() != null
                    && !session.getUserId().equals(userId)) {
                throw BizException.forbidden("无权访问他人的问答会话");
            }
            return session;
        }
        RagSession session = new RagSession();
        session.setUserId(userId);
        session.setTitle(TextUtils.abbreviate(question, 40));
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public List<RagDTO.SessionDTO> listSessions() {
        Long userId = SecurityUtils.currentUserId();
        List<RagSession> sessions = SecurityUtils.isAdmin()
                ? sessionRepository.findAll()
                : sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);

        return sessions.stream().map(s -> {
            RagDTO.SessionDTO dto = new RagDTO.SessionDTO();
            dto.setId(s.getId());
            dto.setTitle(s.getTitle());
            dto.setCreatedAt(s.getCreatedAt());
            dto.setUpdatedAt(s.getUpdatedAt());
            dto.setMessageCount(messageRepository.countBySessionId(s.getId()));
            messageRepository.findRecent(s.getId(), PageRequest.of(0, 1)).stream().findFirst()
                    .ifPresent(m -> {
                        if (RagMessage.ROLE_USER.equals(m.getRole())) {
                            dto.setLastQuestion(TextUtils.abbreviate(m.getContent(), 60));
                        }
                    });
            return dto;
        }).sorted(Comparator.comparing(RagDTO.SessionDTO::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    @Transactional(readOnly = true)
    public List<RagDTO.MessageDTO> listMessages(Long sessionId) {
        RagSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BizException.notFound("会话"));
        if (!SecurityUtils.isAdmin() && session.getUserId() != null
                && !session.getUserId().equals(SecurityUtils.currentUserId())) {
            throw BizException.forbidden("无权访问他人的问答会话");
        }

        return messageRepository.findBySessionIdOrderByIdAsc(sessionId).stream()
                .map(m -> {
                    RagDTO.MessageDTO dto = new RagDTO.MessageDTO();
                    dto.setId(m.getId());
                    dto.setRole(m.getRole());
                    dto.setContent(m.getContent());
                    dto.setRetrievalMeta(m.getRetrievalMeta());
                    dto.setModel(m.getModel());
                    dto.setLatencyMs(m.getLatencyMs());
                    dto.setCreatedAt(m.getCreatedAt());
                    if (StringUtils.hasText(m.getCitations())) {
                        try {
                            dto.setCitations(objectMapper.readValue(m.getCitations(),
                                    new TypeReference<List<RagDTO.Citation>>() {
                                    }));
                        } catch (Exception e) {
                            dto.setCitations(List.of());
                        }
                    }
                    return dto;
                })
                .toList();
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        RagSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BizException.notFound("会话"));
        if (!SecurityUtils.isAdmin() && session.getUserId() != null
                && !session.getUserId().equals(SecurityUtils.currentUserId())) {
            throw BizException.forbidden("无权删除他人的问答会话");
        }
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.delete(session);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private RagMessage saveMessage(Long sessionId, String role, String content,
                                   List<RagDTO.Citation> citations,
                                   RagDTO.RetrievalMeta meta,
                                   String model, Integer latencyMs) {
        RagMessage m = new RagMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setModel(model);
        m.setLatencyMs(latencyMs);
        m.setCreatedAt(LocalDateTime.now());
        try {
            if (citations != null) {
                m.setCitations(objectMapper.writeValueAsString(citations));
            }
            if (meta != null) {
                m.setRetrievalMeta(objectMapper.writeValueAsString(meta));
            }
        } catch (Exception e) {
            log.debug("序列化引用/元数据失败：{}", e.toString());
        }
        return messageRepository.save(m);
    }

    /** 分类限定：返回该分类子树下的全部条目 id；不限定时返回 null。 */
    private Collection<Long> resolveScope(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        List<Long> categoryIds = categoryService.selfAndDescendantIds(categoryId);
        if (categoryIds.isEmpty()) {
            return List.of(-1L);
        }
        List<Long> itemIds = itemRepository.findPublishedInCategories(categoryIds, PageRequest.of(0, 5000))
                .getContent().stream().map(KnowledgeItem::getId).toList();
        return itemIds.isEmpty() ? List.of(-1L) : itemIds;
    }

    private String assetTypeName(String type) {
        return cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO.ItemDTO.assetTypeName(type);
    }

    private double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
