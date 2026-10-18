package cn.edu.njupt.kms.module.rag.service;

import cn.edu.njupt.kms.common.event.ItemChangedEvent;
import cn.edu.njupt.kms.module.kb.entity.Attachment;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.repository.AttachmentRepository;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import cn.edu.njupt.kms.module.rag.dto.RagDTO;
import cn.edu.njupt.kms.module.rag.embedding.EmbeddingService;
import cn.edu.njupt.kms.module.rag.entity.RagChunk;
import cn.edu.njupt.kms.module.rag.repository.RagChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量索引服务：把知识条目的正文与附件正文切块、向量化后落库。
 *
 * <p>通过监听 {@link ItemChangedEvent} 与知识库模块解耦：
 * 条目保存/发布/回滚/归档时发布事件，本服务异步重建该条目的索引。
 * 异步执行使编辑保存的响应时间不受向量化耗时影响。</p>
 *
 * <p>重建策略是「先删后建」（幂等）：无论之前索引处于什么状态，
 * 重建后一定与当前正文一致，避免增量更新导致的索引漂移。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIndexService {

    private final RagChunkRepository chunkRepository;
    private final KnowledgeItemRepository itemRepository;
    private final AttachmentRepository attachmentRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;

    /**
     * 自身的代理引用。
     *
     * <p>类内部直接调用 {@code this.reindexItem(...)} 属于自调用，**不会**经过 Spring 代理，
     * 因而 {@code @Transactional} 不生效 —— 这正是早期版本里
     * 「Executing an update/delete query」异常的原因。
     * 通过 {@link ObjectProvider} 拿到代理再调用，事务边界才真正建立。</p>
     */
    private final ObjectProvider<RagIndexService> selfProvider;

    // ==================================================================
    // 事件驱动
    // ==================================================================

    @Async
    @EventListener
    public void onItemChanged(ItemChangedEvent event) {
        try {
            if (ItemChangedEvent.DELETED.equals(event.operation())) {
                selfProvider.getObject().removeIndex(event.itemId());
                log.debug("条目 #{} 已删除，移除其检索索引", event.itemId());
                return;
            }
            selfProvider.getObject().reindexItem(event.itemId());
        } catch (Exception e) {
            // 索引是派生数据，失败不应影响主流程；记录后由管理员手动重建
            log.warn("重建条目 #{} 的检索索引失败：{}", event.itemId(), e.toString());
        }
    }

    // ==================================================================
    // 索引构建
    // ==================================================================

    /**
     * 重建单个条目的索引。
     *
     * @return 生成的分块数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int reindexItem(Long itemId) {
        KnowledgeItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null || (item.getDeleted() != null && item.getDeleted() == 1)) {
            removeIndex(itemId);
            return 0;
        }
        // 归档条目不参与问答检索
        if (KnowledgeItem.ARCHIVED.equals(item.getStatus())) {
            removeIndex(itemId);
            return 0;
        }

        chunkRepository.deleteByItemId(itemId);

        List<PendingChunk> pending = new ArrayList<>();
        int index = 0;

        // 正文
        if (StringUtils.hasText(item.getPlainText())) {
            for (ChunkingService.Chunk c : chunkingService.split(item.getPlainText())) {
                pending.add(new PendingChunk(index++, c.content(), RagChunk.SOURCE_CONTENT, null));
            }
        }

        // 附件正文（含文件名，便于按文件名提问）
        for (Attachment att : attachmentRepository.findByItemIdOrderByIdAsc(itemId)) {
            String text = att.getExtractedText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String withName = "【附件：%s】\n%s".formatted(att.getFileName(), text);
            for (ChunkingService.Chunk c : chunkingService.split(withName)) {
                pending.add(new PendingChunk(index++, c.content(), RagChunk.SOURCE_ATTACHMENT, att.getId()));
            }
        }

        if (pending.isEmpty()) {
            log.debug("条目 #{} 无可用文本，未生成检索分块", itemId);
            return 0;
        }

        EmbeddingService.BatchResult batch = embeddingService.embedBatch(
                pending.stream().map(PendingChunk::content).toList());

        List<RagChunk> chunks = new ArrayList<>(pending.size());
        for (int i = 0; i < pending.size(); i++) {
            PendingChunk p = pending.get(i);
            RagChunk chunk = new RagChunk();
            chunk.setItemId(itemId);
            chunk.setItemTitle(item.getTitle());
            chunk.setSourceType(p.sourceType());
            chunk.setSourceId(p.sourceId());
            chunk.setChunkIndex(p.index());
            chunk.setContent(p.content());
            chunk.setCharCount(p.content().length());
            chunk.setCreatedAt(LocalDateTime.now());

            if (i < batch.vectors().size()) {
                float[] vector = batch.vectors().get(i);
                chunk.setEmbedding(EmbeddingService.toBytes(vector));
                chunk.setDim(vector.length);
                chunk.setEmbedModel(batch.modelId());
            }
            chunks.add(chunk);
        }
        chunkRepository.saveAll(chunks);

        log.debug("条目 #{} 索引重建完成：{} 个分块，向量模型 {}", itemId, chunks.size(), batch.modelId());
        return chunks.size();
    }

    /** 移除某条目的全部索引。 */
    @Transactional
    public void removeIndex(Long itemId) {
        chunkRepository.deleteByItemId(itemId);
    }

    /**
     * 全量重建索引。
     *
     * <p>切换向量模型后必须执行一次，因为不同模型产出的向量不在同一空间。</p>
     */
    public RagDTO.ReindexResult reindexAll(List<Long> itemIds) {
        long start = System.currentTimeMillis();
        List<KnowledgeItem> items;
        if (itemIds == null || itemIds.isEmpty()) {
            items = itemRepository.findAll().stream()
                    .filter(i -> i.getDeleted() == null || i.getDeleted() == 0)
                    .filter(i -> !KnowledgeItem.ARCHIVED.equals(i.getStatus()))
                    .toList();
        } else {
            items = itemRepository.findAllById(itemIds);
        }

        int chunkCount = 0;
        int processed = 0;
        RagIndexService self = selfProvider.getObject();
        for (KnowledgeItem item : items) {
            try {
                // 每个条目一个独立事务：单条失败不会拖垮整批
                chunkCount += self.reindexItem(item.getId());
                processed++;
            } catch (Exception e) {
                log.warn("重建条目 #{} 索引失败：{}", item.getId(), e.toString());
            }
        }

        RagDTO.ReindexResult result = new RagDTO.ReindexResult();
        result.setItemsProcessed(processed);
        result.setChunksCreated(chunkCount);
        result.setCostMs(System.currentTimeMillis() - start);
        result.setEmbeddingModel(embeddingService.modelId());
        result.setDegraded(embeddingService.isDegraded());
        log.info("全量重建检索索引完成：条目 {} 条，分块 {} 个，耗时 {} ms",
                processed, chunkCount, result.getCostMs());
        return result;
    }

    /** 索引状态。 */
    @Transactional(readOnly = true)
    public RagDTO.IndexStatusDTO status() {
        RagDTO.IndexStatusDTO dto = new RagDTO.IndexStatusDTO();
        dto.setTotalChunks(chunkRepository.countAll());
        dto.setChunksWithEmbedding(chunkRepository.countWithEmbedding());
        dto.setIndexedItems(chunkRepository.findAllWithEmbedding().stream()
                .map(RagChunk::getItemId).distinct().count());
        dto.setEmbeddingModel(embeddingService.modelId());
        dto.setOnlineEmbedding(embeddingService.isOnline());
        dto.setDimension(embeddingService.dimension());
        dto.setDescription(embeddingService.description());

        List<String> models = chunkRepository.listEmbedModels();
        dto.setStaleModels(models.stream()
                .filter(m -> !m.equals(embeddingService.modelId()))
                .toList());
        return dto;
    }

    private record PendingChunk(int index, String content, String sourceType, Long sourceId) {
    }
}
