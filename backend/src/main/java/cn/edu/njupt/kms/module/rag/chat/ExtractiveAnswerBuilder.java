package cn.edu.njupt.kms.module.rag.chat;

import cn.edu.njupt.kms.util.TextUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 离线抽取式回答生成器。
 *
 * <p>在没有大模型可用时（未配置 API Key、断网、调用失败），
 * 退而把检索到的最相关片段整理成一份「有据可查」的回答：</p>
 * <ul>
 *   <li>先给出命中的知识条目清单；</li>
 *   <li>再从原文中抽取与问题最相关的句子作为要点，逐条标注来源编号；</li>
 *   <li>最后提示这是离线模式、并建议查看引用的原文。</li>
 * </ul>
 *
 * <p>它不是生成式回答，但胜在完全离线、零幻觉 —— 每一句都可在原文中核对，
 * 对课程设计答辩演示是一个稳定可靠的后备方案。</p>
 */
@Component
public class ExtractiveAnswerBuilder {

    /** 抽取的要点数量上限。 */
    private static final int MAX_POINTS = 6;
    /** 单个要点的最大字符数。 */
    private static final int MAX_POINT_LENGTH = 220;

    /**
     * 构造抽取式回答。
     *
     * @param question 用户问题
     * @param sources  按相关度排序的检索片段（编号从 1 开始）
     * @param reason   触发降级的原因，会展示给用户
     */
    public String build(String question, List<Source> sources, String reason) {
        if (sources == null || sources.isEmpty()) {
            return """
                    未能在知识库中找到与该问题相关的内容。

                    建议：
                    1. 换用更短、更具体的关键词，例如把「软件测试怎么做」改为「测试用例设计」；
                    2. 先到「知识图谱」页按知识点浏览，确认知识库中是否已有该主题的资料；
                    3. 若该主题确有缺口，可由编辑角色补录后再提问。
                    """.trim();
        }

        List<String> terms = TextUtils.queryTerms(question);
        Set<String> usedSentences = new LinkedHashSet<>();
        List<String> points = new ArrayList<>();

        for (Source s : sources) {
            if (points.size() >= MAX_POINTS) {
                break;
            }
            for (String sentence : splitSentences(s.content())) {
                if (points.size() >= MAX_POINTS) {
                    break;
                }
                String trimmed = sentence.trim();
                if (trimmed.length() < 12 || usedSentences.contains(trimmed)) {
                    continue;
                }
                if (relevance(trimmed, terms) <= 0) {
                    continue;
                }
                usedSentences.add(trimmed);
                points.add("- %s　【来源 %d：%s】".formatted(
                        TextUtils.abbreviate(trimmed, MAX_POINT_LENGTH),
                        s.index(), TextUtils.abbreviate(s.title(), 24)));
            }
        }

        // 若按句子筛选后要点不足，退化为直接列出片段开头
        if (points.isEmpty()) {
            for (Source s : sources) {
                if (points.size() >= MAX_POINTS) {
                    break;
                }
                points.add("- %s　【来源 %d：%s】".formatted(
                        TextUtils.abbreviate(TextUtils.normalizeWhitespace(s.content()), MAX_POINT_LENGTH),
                        s.index(), TextUtils.abbreviate(s.title(), 24)));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**（离线抽取式回答）** 未启用大模型生成").append(reason == null ? "" : "，" + reason)
                .append("。以下要点直接从知识库原文中抽取，可点击来源核对原文。\n\n");

        sb.append("**与「").append(TextUtils.abbreviate(question, 40)).append("」最相关的要点：**\n");
        points.forEach(p -> sb.append(p).append('\n'));

        sb.append("\n**引用的知识条目：**\n");
        Set<Long> seenItems = new LinkedHashSet<>();
        for (Source s : sources) {
            if (seenItems.add(s.itemId())) {
                sb.append("- 来源 %d：%s（相关度 %.2f）\n".formatted(
                        s.index(), s.title(), s.score()));
            }
        }

        sb.append("\n如需更完整的归纳与推理，请在 `application.yml` 中配置 `kms.rag.chat` 的 API Key 后重试。");
        return sb.toString();
    }

    /** 按中文与英文标点切句。 */
    private List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String flat = TextUtils.normalizeWhitespace(text);
        List<String> out = new ArrayList<>();
        for (String part : flat.split("(?<=[。！？；!?;\\n])|(?<=[.]\\s)")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        // 极长段落（无标点）按长度硬切
        List<String> normalized = new ArrayList<>();
        for (String s : out) {
            if (s.length() <= 400) {
                normalized.add(s);
            } else {
                for (int i = 0; i < s.length(); i += 300) {
                    normalized.add(s.substring(i, Math.min(i + 300, s.length())));
                }
            }
        }
        return normalized;
    }

    /** 句子与查询词的重合度（命中词数 × 词长权重）。 */
    private double relevance(String sentence, List<String> terms) {
        String lower = sentence.toLowerCase();
        double score = 0;
        for (String term : terms) {
            if (term.isEmpty()) {
                continue;
            }
            if (lower.contains(term.toLowerCase())) {
                score += 1 + Math.min(term.length(), 5) * 0.3;
            }
        }
        return score;
    }

    /**
     * 检索到的片段。
     *
     * @param index   引用编号（从 1 开始）
     * @param itemId  所属知识条目
     * @param title   条目标题
     * @param content 片段正文
     * @param score   相关度（已归一化）
     */
    public record Source(int index, Long itemId, String title, String content, double score) {
    }
}
