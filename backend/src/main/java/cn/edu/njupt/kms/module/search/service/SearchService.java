package cn.edu.njupt.kms.module.search.service;

import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.entity.Attachment;
import cn.edu.njupt.kms.module.kb.entity.KnowledgeItem;
import cn.edu.njupt.kms.module.kb.repository.AttachmentRepository;
import cn.edu.njupt.kms.module.kb.repository.KnowledgeItemRepository;
import cn.edu.njupt.kms.module.kb.service.CategoryService;
import cn.edu.njupt.kms.module.kb.service.KnowledgeItemService;
import cn.edu.njupt.kms.module.ontology.dto.OntologyDTO;
import cn.edu.njupt.kms.module.ontology.service.OntologyService;
import cn.edu.njupt.kms.module.search.dto.SearchDTO;
import cn.edu.njupt.kms.module.search.entity.SearchLog;
import cn.edu.njupt.kms.module.search.repository.SearchLogRepository;
import cn.edu.njupt.kms.security.SecurityUtils;
import cn.edu.njupt.kms.util.TextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 搜索引擎服务。
 *
 * <p>提供三条检索通道：</p>
 * <ol>
 *   <li><b>全文检索</b> —— MySQL 8 全文索引（ngram 解析器，中文按二元切分），
 *       由数据库给出相关度得分，再由本服务做标题加权与归一化；</li>
 *   <li><b>高级检索</b> —— 分类（含子分类）/ 类型 / 标签 / 知识点 / 作者 / 时间多条件组合；</li>
 *   <li><b>降级检索</b> —— 全文索引未命中或不可用时，退回 LIKE 模糊匹配，
 *       保证查询始终有结果而不是白屏。</li>
 * </ol>
 *
 * <p>同时负责热搜词、查询联想与检索日志的落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final KnowledgeItemRepository itemRepository;
    private final AttachmentRepository attachmentRepository;
    private final SearchLogRepository searchLogRepository;
    private final KnowledgeItemService itemService;
    private final OntologyService ontologyService;
    private final CategoryService categoryService;
    private final AppProperties props;

    /** 单次查询串的字符上限，防止超长输入拖慢全文检索。 */
    private static final int MAX_KEYWORD_LENGTH = 120;

    /**
     * 全文通道的候选集放大倍数。
     *
     * <p>数据库的相关度排序与应用层的打分排序口径不同，必须多召回一些候选，
     * 应用层才有机会把真正相关的文档挑上来。倍数取 6 是在召回质量与
     * 内存开销之间的折中（读 60 条候选来呈现 10 条结果）。</p>
     */
    private static final int OVERSAMPLE = 6;

    /** 候选集上限，防止深翻页时一次加载过多记录。 */
    private static final int MAX_CANDIDATES = 300;

    /**
     * 单个检索通道的返回结果。
     *
     * @param hits  本页命中（数量不超过请求的 size）
     * @param total 满足条件的总记录数，用于前端分页
     */
    private record Channel(List<SearchDTO.Hit> hits, long total, boolean fulltext) {
        static Channel empty() {
            return new Channel(List.of(), 0, false);
        }
    }

    // ==================================================================
    // 主检索
    // ==================================================================

    @Transactional
    public SearchDTO.SearchResult search(SearchDTO.SearchRequest req) {
        long start = System.currentTimeMillis();
        SearchDTO.SearchResult result = new SearchDTO.SearchResult();

        String keyword = req.getKeyword() == null ? "" : req.getKeyword().trim();
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            keyword = keyword.substring(0, MAX_KEYWORD_LENGTH);
        }
        boolean hasKeyword = !keyword.isEmpty();

        List<String> terms = hasKeyword ? TextUtils.queryTerms(keyword) : List.of();
        result.setTerms(terms);

        Channel channel;
        String mode = req.getMode() == null ? SearchDTO.Mode.AUTO : req.getMode();

        if (hasKeyword && !SearchDTO.Mode.ADVANCED.equals(mode)) {
            // 优先走全文索引通道
            channel = tryFulltext(keyword, req, terms);
            if (channel.hits().isEmpty()) {
                // 全文索引不可用或无命中：降级为模糊匹配，但保留其真实总数
                Channel fallback = fallbackLikeSearch(keyword, req, terms);
                channel = new Channel(fallback.hits(), fallback.total(), false);
                result.setModeUsed(fallback.hits().isEmpty() && !hasKeyword
                        ? "条件过滤" : "模糊匹配（全文索引未命中，已降级）");
            } else {
                result.setModeUsed("全文索引（ngram 二元切分）");
            }
        } else {
            channel = conditionSearch(req, terms);
            result.setModeUsed(hasKeyword ? "条件 + 关键词过滤" : "条件过滤");
        }

        // 全文通道已在自身内部完成了排序与分页；此处只对条件通道/降级通道补一次排序
        List<SearchDTO.Hit> hits = new ArrayList<>(channel.hits());
        if (hasKeyword && !channel.fulltext()) {
            hits.sort(Comparator.comparing(SearchDTO.Hit::getScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
        }
        // 弱匹配判定：没有任何一条在标题/摘要/关键词上命中查询词，
        // 说明这些候选只是 ngram 二元组的偶然重合。
        // 此处**不隐藏**结果 —— 正文中的匹配同样是有效召回，
        // 直接丢弃会误伤「关键词只出现在正文」的查询。
        // 正确做法是如实标记，由界面提示用户这是模糊结果。
        boolean weakMatch = hasKeyword && !hits.isEmpty()
                && hits.stream().noneMatch(h -> Boolean.TRUE.equals(h.getTitleMatch())
                || Boolean.TRUE.equals(h.getSummaryMatch()));

        result.setHits(hits);
        result.setWeakMatch(weakMatch);
        result.setFulltextUsed(channel.fulltext());
        result.setCostMs(System.currentTimeMillis() - start);
        result.setPage(req.getPage());
        result.setSize(req.getSize());
        result.setTotal(channel.total());
        result.setPages((int) Math.ceil((double) channel.total() / Math.max(req.getSize(), 1)));

        // 附件的可视化信息（视频/图片）
        attachMedia(hits);

        // 查询串涉及的本体知识点（帮助用户沿知识结构继续探索）
        if (hasKeyword) {
            result.setLinkedConcepts(ontologyService.link(keyword, 6).stream()
                    .map(c -> {
                        SearchDTO.LinkedConcept lc = new SearchDTO.LinkedConcept();
                        lc.setId(c.getId());
                        lc.setName(c.getName());
                        lc.setDomain(c.getDomain());
                        return lc;
                    })
                    .toList());
        }

        // 无结果或仅有弱匹配时都给出建议
        if (hasKeyword && (hits.isEmpty() || weakMatch)) {
            result.setSuggestions(buildNoResultSuggestions(keyword));
        }

        recordSearch(keyword, req, hits.size(), (int) (System.currentTimeMillis() - start));
        return result;
    }

    // ==================================================================
    // 通道一：全文索引
    // ==================================================================

    private Channel tryFulltext(String keyword, SearchDTO.SearchRequest req, List<String> terms) {
        try {
            int size = Math.min(Math.max(req.getSize(), 1), props.getSearch().getMaxPageSize());
            int page = Math.max(req.getPage() - 1, 0);

            // 关键：数据库只负责「召回」，不负责「排序」。
            // MySQL 的 ngram 相关度偏好长文档，直接按它取前 size 条，
            // 会让标题精确命中的文档根本进不了候选池 —— 之后再怎么重排也无济于事。
            // 因此这里先扩大候选集（按页深线性放大，上限 300 条），
            // 在应用层完成打分排序后再本地分页。
            int fetchSize = Math.min(Math.max((page + 1) * size * OVERSAMPLE, size), MAX_CANDIDATES);
            Page<Object[]> rows = searchInCategoryScope(keyword, req, PageRequest.of(0, fetchSize));
            if (rows.isEmpty()) {
                return Channel.empty();
            }

            // 行结构为 [id, score]
            LinkedHashMap<Long, Double> scoreById = new LinkedHashMap<>();
            List<Long> orderedIds = new ArrayList<>();
            for (Object[] row : rows.getContent()) {
                Long id = ((Number) row[0]).longValue();
                Double score = row[1] == null ? 0.0 : ((Number) row[1]).doubleValue();
                if (scoreById.putIfAbsent(id, score) == null) {
                    orderedIds.add(id);
                }
            }

            // 先应用高级检索的附加条件，再装配展示字段
            List<KnowledgeItem> items = itemRepository.findByIdInAndDeleted(orderedIds, 0);
            Map<Long, KnowledgeItem> byId = items.stream()
                    .collect(Collectors.toMap(KnowledgeItem::getId, i -> i));

            List<KnowledgeItem> filtered = orderedIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(i -> KnowledgeItem.PUBLISHED.equals(i.getStatus()))
                    .filter(i -> matchesExtraFilters(i, req))
                    .toList();

            if (filtered.isEmpty()) {
                return Channel.empty();
            }

            List<KnowledgeDTO.ItemDTO> dtos = itemService.enrichList(filtered);
            List<SearchDTO.Hit> hits = buildHits(dtos, filtered, terms, scoreById);
            hits.sort(Comparator.comparing(SearchDTO.Hit::getScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));

            // 在候选池内本地分页（排序已由应用层决定，数据库的 LIMIT 不能再用于分页）
            int from = Math.min(page * size, hits.size());
            int to = Math.min(from + size, hits.size());
            return new Channel(hits.subList(from, to), rows.getTotalElements(), true);
        } catch (Exception e) {
            // 全文索引不可用（例如索引未建立）时静默降级，不影响用户检索
            log.warn("全文检索失败，将降级为模糊匹配：{}", e.toString());
            return Channel.empty();
        }
    }

    /** 按是否限定分类选择不同的全文检索语句。 */
    private Page<Object[]> searchInCategoryScope(String keyword, SearchDTO.SearchRequest req, PageRequest pageable) {
        if (req.getCategoryId() != null) {
            List<Long> catIds = categoryService.selfAndDescendantIds(req.getCategoryId());
            if (catIds.isEmpty()) {
                return Page.empty(pageable);
            }
            return itemRepository.fulltextSearchInCategories(keyword, "", catIds, pageable);
        }
        return itemRepository.fulltextSearch(keyword, "", pageable);
    }

    // ==================================================================
    // 通道二：条件过滤
    // ==================================================================

    private Channel conditionSearch(SearchDTO.SearchRequest req, List<String> terms) {
        Page<KnowledgeItem> page = itemService.findItems(toItemQuery(req), true);
        List<KnowledgeItem> items = page.getContent().stream()
                .filter(i -> matchesExtraFilters(i, req))
                .toList();
        if (items.isEmpty()) {
            return new Channel(List.of(), page.getTotalElements(), false);
        }
        List<KnowledgeDTO.ItemDTO> dtos = itemService.enrichList(items);
        return new Channel(buildHits(dtos, items, terms, Map.of()), page.getTotalElements(), false);
    }

    // ==================================================================
    // 通道三：降级模糊匹配
    // ==================================================================

    private Channel fallbackLikeSearch(String keyword, SearchDTO.SearchRequest req, List<String> terms) {
        try {
            // 附件正文命中：把命中的附件所属条目也纳入候选
            Set<Long> extraIds = new LinkedHashSet<>();
            if (keyword.length() >= 2) {
                extraIds.addAll(attachmentRepository.searchItemIdsByFulltext(keyword));
            }

            KnowledgeDTO.ItemQuery q = toItemQuery(req);
            q.setKeyword(keyword);
            // 关键词条件下关闭“仅标题”，否则附件命中会被排除
            req.setTitleOnly(false);

            Page<KnowledgeItem> page = itemService.findItems(q, true);

            LinkedHashMap<Long, KnowledgeItem> merged = new LinkedHashMap<>();
            for (KnowledgeItem i : page.getContent()) {
                merged.put(i.getId(), i);
            }
            if (!extraIds.isEmpty()) {
                itemRepository.findByIdInAndDeleted(extraIds, 0).stream()
                        .filter(i -> KnowledgeItem.PUBLISHED.equals(i.getStatus()))
                        .filter(i -> matchesExtraFilters(i, req))
                        .forEach(i -> merged.putIfAbsent(i.getId(), i));
            }
            // 仍无结果时，退一步只按标题模糊匹配，尽可能给出可用结果
            if (merged.isEmpty()) {
                itemRepository.findAll().stream()
                        .filter(i -> i.getDeleted() == null || i.getDeleted() == 0)
                        .filter(i -> KnowledgeItem.PUBLISHED.equals(i.getStatus()))
                        .filter(i -> i.getTitle() != null && i.getTitle().contains(keyword))
                        .limit(50)
                        .forEach(i -> merged.putIfAbsent(i.getId(), i));
            }

            List<KnowledgeItem> items = new ArrayList<>(merged.values());
            if (items.isEmpty()) {
                return Channel.empty();
            }
            List<KnowledgeDTO.ItemDTO> dtos = itemService.enrichList(items);

            // 降级通道没有数据库得分，用「标题命中 / 摘要命中 / 正文命中」自行打分
            Map<Long, Double> scores = new LinkedHashMap<>();
            for (KnowledgeItem i : items) {
                scores.put(i.getId(), heuristicScore(i, terms));
            }
            List<SearchDTO.Hit> hits = new ArrayList<>(buildHits(dtos, items, terms, scores));
            hits.sort(Comparator.comparing(SearchDTO.Hit::getScore,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            // 降级通道在内存里拼候选集，得分排序后再本地分页
            long total = hits.size();
            int size = Math.min(Math.max(req.getSize(), 1), props.getSearch().getMaxPageSize());
            int from = Math.min(Math.max(req.getPage() - 1, 0) * size, hits.size());
            int to = Math.min(from + size, hits.size());
            return new Channel(hits.subList(from, to), total, false);
        } catch (Exception e) {
            log.warn("降级检索同样失败：{}", e.toString());
            return Channel.empty();
        }
    }

    /** 降级通道的启发式打分：标题 > 摘要 > 正文。 */
    private double heuristicScore(KnowledgeItem item, List<String> terms) {
        double score = 0.0;
        String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase();
        String summary = item.getSummary() == null ? "" : item.getSummary().toLowerCase();
        String body = item.getPlainText() == null ? "" : item.getPlainText().toLowerCase();

        for (String term : terms) {
            String t = term.toLowerCase();
            if (t.isEmpty()) {
                continue;
            }
            if (title.contains(t)) {
                score += 3.0;
            }
            if (summary.contains(t)) {
                score += 1.0;
            }
            if (body.contains(t)) {
                score += 0.3;
            }
        }
        // 热度与新鲜度的小幅加成
        score += Math.min(item.getViewCount() == null ? 0 : item.getViewCount(), 200) / 1000.0;
        return score;
    }

    // ==================================================================
    // 结果装配
    // ==================================================================

    /**
     * 把条目实体装配成检索引擎的结果项。
     *
     * <p><b>打分策略</b>：数据库的全文相关度只能作为一路信号，不能直接用。
     * 原因在于 ngram 索引按二元组切分，长文档天然包含更多二元组，
     * 于是「关于检索技术的长报告」在检索「软件测试」时会被大量无关二元组推高，
     * 反而排在标题就叫《软件测试用例设计》的文档前面 —— 这显然不是用户想要的。</p>
     *
     * <p>因此最终得分由三部分加权：</p>
     * <ul>
     *   <li><b>字段命中率（0.65）</b>：查询词在标题、摘要、关键词中的覆盖比例，
     *       其中标题命中权重最高。这是排序的主导信号。</li>
     *   <li><b>全文相关度（0.25）</b>：数据库给出的相关度，归一化后作为补充，
     *       保证正文中的深入匹配也能得到体现。</li>
     *   <li><b>热度（0.10）</b>：浏览量的对数压缩，用于同等相关度时的区分。</li>
     * </ul>
     *
     * @param scores 数据库给出的全文相关度（可为空，此时该项按 0 计）
     */
    private List<SearchDTO.Hit> buildHits(List<KnowledgeDTO.ItemDTO> dtos,
                                          List<KnowledgeItem> items,
                                          List<String> terms,
                                          Map<Long, Double> scores) {
        if (dtos.isEmpty()) {
            return List.of();
        }
        double maxScore = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        String prefix = props.getSearch().getHighlightPrefix();
        String suffix = props.getSearch().getHighlightSuffix();
        int snippetLen = props.getSearch().getSnippetLength();

        List<SearchDTO.Hit> hits = new ArrayList<>(dtos.size());
        for (int i = 0; i < dtos.size(); i++) {
            KnowledgeDTO.ItemDTO d = dtos.get(i);
            KnowledgeItem item = items.get(i);

            SearchDTO.Hit h = new SearchDTO.Hit();
            h.setId(d.getId());
            h.setTitle(d.getTitle());
            h.setTitleHighlight(TextUtils.highlight(d.getTitle(), terms, prefix, suffix));
            h.setSummary(d.getSummary());
            h.setAssetType(d.getAssetType());
            h.setAssetTypeName(d.getAssetTypeName());
            h.setStatus(d.getStatus());
            h.setCategoryId(d.getCategoryId());
            h.setCategoryName(d.getCategoryName());
            h.setAuthorName(d.getAuthorName());
            h.setTags(d.getTags() == null ? List.of()
                    : d.getTags().stream().map(KnowledgeDTO.TagDTO::getName).toList());
            h.setConceptNames(d.getConceptNames() == null ? List.of() : d.getConceptNames());
            h.setViewCount(d.getViewCount());
            h.setLikeCount(d.getLikeCount());
            h.setVersionNo(d.getVersionNo());
            h.setPublishedAt(d.getPublishedAt());
            h.setUpdatedAt(d.getUpdatedAt());

            // 摘要片段：优先从正文取，正文为空时退回摘要
            String source = StringUtils.hasText(item.getPlainText()) ? item.getPlainText() : d.getSummary();
            h.setSnippet(TextUtils.snippet(source, terms, snippetLen, prefix, suffix));

            // ---- 打分 ----
            String title = d.getTitle() == null ? "" : d.getTitle().toLowerCase();
            String summary = d.getSummary() == null ? "" : d.getSummary().toLowerCase();
            String keywords = item.getKeywords() == null ? "" : item.getKeywords().toLowerCase();
            String body = item.getPlainText() == null ? "" : item.getPlainText().toLowerCase();

            double titleCoverage = coverage(terms, title);
            double summaryCoverage = coverage(terms, summary + " " + keywords);
            double bodyCoverage = coverage(terms, body);
            double fieldScore = titleCoverage * 0.80 + summaryCoverage * 0.20;

            Double raw = scores.get(d.getId());
            double fulltextScore = (raw != null && maxScore > 0) ? Math.min(raw / maxScore, 1.0) : 0.0;
            double popularity = Math.min(1.0,
                    Math.log1p(item.getViewCount() == null ? 0 : item.getViewCount()) / Math.log1p(500));

            double score = fieldScore * 0.65 + fulltextScore * 0.25 + popularity * 0.10;

            // 标题完整包含整个查询串时给一次额外奖励 —— 这类结果几乎总是用户想要的
            if (!terms.isEmpty()) {
                String full = terms.get(0).toLowerCase();
                if (title.contains(full)) {
                    score += 0.20;
                }
            }
            score = Math.min(score, 1.0);

            h.setScore(Math.round(score * 1000) / 1000.0);
            h.setScoreDetail(
                    "字段命中 标题 %.0f%%/摘要 %.0f%%/正文 %.0f%% ×0.65 + 全文相关度 %.3f ×0.25 + 热度 %.3f ×0.10"
                            .formatted(titleCoverage * 100, summaryCoverage * 100, bodyCoverage * 100,
                                    fulltextScore, popularity));
            h.setTitleMatch(titleCoverage > 0);
            h.setSummaryMatch(summaryCoverage > 0);
            hits.add(h);
        }
        return hits;
    }

    /** 查询词在给定字段中的覆盖率（命中的词数 / 总词数）。 */
    private double coverage(List<String> terms, String field) {
        if (terms.isEmpty() || field.isEmpty()) {
            return 0;
        }
        String lower = field.toLowerCase();
        int hit = 0;
        for (String term : terms) {
            if (!term.isEmpty() && lower.contains(term.toLowerCase())) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    /** 附加条件（标题限定、必须含附件）在内存中过滤。 */
    private boolean matchesExtraFilters(KnowledgeItem item, SearchDTO.SearchRequest req) {
        if (Boolean.TRUE.equals(req.getTitleOnly()) && StringUtils.hasText(req.getKeyword())) {
            String title = item.getTitle() == null ? "" : item.getTitle().toLowerCase();
            if (req.getKeyword().trim().toLowerCase().split("\\s+").length > 0) {
                boolean hit = false;
                for (String t : TextUtils.queryTerms(req.getKeyword())) {
                    if (!t.isEmpty() && title.contains(t.toLowerCase())) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return false;
                }
            }
        }
        if (req.getAuthorId() != null && !req.getAuthorId().equals(item.getAuthorId())) {
            return false;
        }
        if (req.getCreatedFrom() != null && item.getCreatedAt() != null
                && item.getCreatedAt().isBefore(req.getCreatedFrom())) {
            return false;
        }
        if (req.getCreatedTo() != null && item.getCreatedAt() != null
                && item.getCreatedAt().isAfter(req.getCreatedTo())) {
            return false;
        }
        if (req.getAssetTypes() != null && !req.getAssetTypes().isEmpty()
                && !req.getAssetTypes().contains(item.getAssetType())) {
            return false;
        }
        if (Boolean.TRUE.equals(req.getWithAttachmentOnly())
                && attachmentRepository.countByItemId(item.getId()) == 0) {
            return false;
        }
        return true;
    }

    /** 为命中项补充图片/视频附件信息，便于结果卡片直接展示缩略图或播放器。 */
    private void attachMedia(List<SearchDTO.Hit> hits) {
        if (hits.isEmpty()) {
            return;
        }
        List<Long> ids = hits.stream().map(SearchDTO.Hit::getId).toList();
        Map<Long, List<Attachment>> byItem = attachmentRepository.findByItemIdIn(ids).stream()
                .collect(Collectors.groupingBy(Attachment::getItemId));

        for (SearchDTO.Hit h : hits) {
            List<Attachment> atts = byItem.getOrDefault(h.getId(), List.of());
            h.setImages(atts.stream()
                    .filter(a -> Attachment.MEDIA_IMAGE.equals(a.getMediaType()))
                    .limit(4)
                    .map(a -> SearchDTO.AttachmentBrief.of(a.getId(), a.getFileName(),
                            "/api/files/preview/" + a.getFilePath(), a.getMediaType(), null))
                    .toList());
            h.setVideos(atts.stream()
                    .filter(a -> Attachment.MEDIA_VIDEO.equals(a.getMediaType()))
                    .limit(2)
                    .map(a -> SearchDTO.AttachmentBrief.of(a.getId(), a.getFileName(),
                            "/api/files/preview/" + a.getFilePath(), a.getMediaType(), h.getImages().isEmpty()
                                    ? null : h.getImages().get(0).getUrl()))
                    .toList());
        }
    }

    private KnowledgeDTO.ItemQuery toItemQuery(SearchDTO.SearchRequest req) {
        KnowledgeDTO.ItemQuery q = new KnowledgeDTO.ItemQuery();
        q.setCategoryId(req.getCategoryId());
        q.setAssetTypes(req.getAssetTypes());
        q.setTagIds(req.getTagIds());
        q.setConceptIds(req.getConceptIds());
        q.setAuthorId(req.getAuthorId());
        q.setCreatedFrom(req.getCreatedFrom());
        q.setCreatedTo(req.getCreatedTo());
        q.setStatus(KnowledgeItem.PUBLISHED);
        q.setPage(req.getPage());
        q.setSize(req.getSize());
        switch (req.getSortBy() == null ? "RELEVANCE" : req.getSortBy()) {
            case "LATEST" -> {
                q.setSortBy("publishedAt");
                q.setSortDir("desc");
            }
            case "HOTTEST" -> {
                q.setSortBy("viewCount");
                q.setSortDir("desc");
            }
            case "LIKES" -> {
                q.setSortBy("likeCount");
                q.setSortDir("desc");
            }
            default -> {
                q.setSortBy("updatedAt");
                q.setSortDir("desc");
            }
        }
        return q;
    }

    // ==================================================================
    // 联想、热搜与日志
    // ==================================================================

    /** 搜索联想：历史搜索词 + 知识点 + 条目标题 + 标签。 */
    @Transactional(readOnly = true)
    public List<SearchDTO.Suggestion> suggest(String keyword, int limit) {
        List<SearchDTO.Suggestion> out = new ArrayList<>();
        if (!StringUtils.hasText(keyword)) {
            // 空输入时给热搜词作为起点
            for (SearchDTO.HotKeyword hk : hotKeywords(8)) {
                out.add(suggestion(hk.getKeyword(), "HISTORY", null));
            }
            return out;
        }
        String kw = keyword.trim();

        for (String k : searchLogRepository.findKeywordsStartingWith(kw, PageRequest.of(0, limit))) {
            out.add(suggestion(k, "HISTORY", null));
        }
        for (OntologyDTO.ConceptDTO c : ontologyService.search(kw).stream().limit(limit).toList()) {
            out.add(suggestion(c.getName(), "CONCEPT", c.getId()));
        }
        itemRepository.findHot(PageRequest.of(0, 200)).stream()
                .filter(i -> i.getTitle() != null && i.getTitle().toLowerCase().contains(kw.toLowerCase()))
                .limit(limit)
                .forEach(i -> out.add(suggestion(i.getTitle(), "TITLE", i.getId())));

        // 去重并截断
        Map<String, SearchDTO.Suggestion> dedup = new LinkedHashMap<>();
        for (SearchDTO.Suggestion s : out) {
            dedup.putIfAbsent(s.getText(), s);
        }
        return dedup.values().stream().limit(limit).toList();
    }

    private SearchDTO.Suggestion suggestion(String text, String type, Long refId) {
        SearchDTO.Suggestion s = new SearchDTO.Suggestion();
        s.setText(text);
        s.setType(type);
        s.setRefId(refId);
        return s;
    }

    @Transactional(readOnly = true)
    public List<SearchDTO.HotKeyword> hotKeywords(int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(props.getSearch().getHotKeywordWindowDays());
        return searchLogRepository.hotKeywords(since, PageRequest.of(0, Math.max(limit, 1))).stream()
                .map(r -> new SearchDTO.HotKeyword((String) r[0], ((Number) r[1]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SearchDTO.SearchStats stats(int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(props.getSearch().getHotKeywordWindowDays());
        SearchDTO.SearchStats s = new SearchDTO.SearchStats();
        s.setTotalSearches(searchLogRepository.countByCreatedAtAfter(since));
        s.setAverageCostMs(Math.round(searchLogRepository.averageCost(since) * 100) / 100.0);
        s.setHotKeywords(hotKeywords(limit));
        s.setNoResultKeywords(searchLogRepository.noResultKeywords(since, PageRequest.of(0, limit)).stream()
                .map(r -> new SearchDTO.HotKeyword((String) r[0], ((Number) r[1]).longValue()))
                .toList());
        return s;
    }

    /** 无结果时给出可操作的替代建议。 */
    private List<String> buildNoResultSuggestions(String keyword) {
        List<String> tips = new ArrayList<>();
        tips.add("尝试使用更短的关键词，例如「" + TextUtils.abbreviate(keyword, 6) + "」");
        tips.add("中文全文检索按二元切分建索引，单字查询建议改为双字及以上");
        List<OntologyDTO.ConceptDTO> concepts = ontologyService.search(keyword);
        if (!concepts.isEmpty()) {
            tips.add("知识库中相关知识点：「" + concepts.get(0).getName() + "」，可点击按知识点浏览");
        }
        return tips;
    }

    private void recordSearch(String keyword, SearchDTO.SearchRequest req, int resultCount, int costMs) {
        try {
            SearchLog entry = new SearchLog();
            entry.setUserId(SecurityUtils.currentUserId());
            entry.setKeyword(keyword);
            entry.setSearchType(SearchDTO.Mode.ADVANCED.equals(req.getMode()) ? "ADVANCED" : "SIMPLE");
            entry.setResultCount(resultCount);
            entry.setCostMs(costMs);
            entry.setCreatedAt(LocalDateTime.now());
            searchLogRepository.save(entry);
        } catch (Exception e) {
            log.debug("记录检索日志失败：{}", e.toString());
        }
    }
}
