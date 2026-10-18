package cn.edu.njupt.kms.util;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本处理工具：Markdown 去标记、中文分词、关键词抽取、检索片段与高亮。
 *
 * <p>分词使用 jieba 的搜索引擎模式，兼顾精度与长词召回；
 * 叠加一份针对本领域（软件工程 + 数据库）的自定义词典，
 * 避免“用例”“本体”“规范化”等术语被切碎。</p>
 */
public final class TextUtils {

    private TextUtils() {
    }

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final TextContentRenderer MARKDOWN_RENDERER =
            TextContentRenderer.builder().build();

    /** 领域自定义词典：保证专业术语不被切分。 */
    private static final List<String> DOMAIN_WORDS = List.of(
            "软件工程", "需求分析", "需求获取", "需求验证", "需求规格说明", "软件设计",
            "体系结构", "详细设计", "设计模式", "软件测试", "单元测试", "集成测试", "系统测试",
            "验收测试", "回归测试", "白盒测试", "黑盒测试", "测试用例", "等价类划分", "边界值分析",
            "项目管理", "进度管理", "关键路径", "成本估算", "风险管理", "配置管理", "质量保证",
            "数据库", "关系模型", "关系代数", "结构化查询语言", "规范化", "函数依赖", "范式",
            "事务", "并发控制", "封锁协议", "索引", "查询优化", "视图", "存储过程", "触发器",
            "实体联系模型", "概念结构设计", "逻辑结构设计", "物理结构设计", "数据字典", "完整性约束",
            "知识图谱", "知识本体", "本体", "知识库", "全文检索", "语义检索", "向量检索",
            "检索增强生成", "内容管理", "版本管理", "知识抽取", "实体链接", "知识表示",
            "用例图", "类图", "时序图", "活动图", "状态图", "部署图", "流程图", "数据流图",
            "代码评审", "持续集成", "版本控制", "敏捷开发", "瀑布模型", "螺旋模型", "迭代开发",
            "软件维护", "软件演化", "技术债务", "需求变更", "可维护性", "可靠性", "可用性");

    /** 停用词：检索与关键词抽取时过滤。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "和", "是", "在", "我", "有", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "自己", "这", "那", "与", "及", "或", "等", "被", "把", "对", "为", "以", "从",
            "并", "而", "但", "则", "之", "其", "该", "此", "可以", "进行", "通过", "以及",
            "我们", "他们", "这个", "那个", "什么", "如何", "怎么", "哪些", "为了", "一种",
            "the", "a", "an", "of", "to", "is", "are", "and", "or", "in", "on", "for", "with",
            "as", "by", "at", "be", "this", "that", "it", "we", "you", "they");

    /** 领域词集合，用于关键词抽取时加权。 */
    private static final Set<String> DOMAIN_WORD_SET = Set.copyOf(DOMAIN_WORDS);

    // ==================================================================
    // Markdown / HTML 去标记
    // ==================================================================

    /** 把 Markdown 正文转换为纯文本，用于建立全文索引。 */
    public static String markdownToPlain(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = MARKDOWN_PARSER.parse(markdown);
        String text = MARKDOWN_RENDERER.render(document);
        return normalizeWhitespace(text);
    }

    /** 去除 HTML 标签（用于富文本正文）。 */
    public static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return normalizeWhitespace(html.replaceAll("<[^>]+>", " "));
    }

    /** 压缩空白字符。 */
    public static String normalizeWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(' ', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    // ==================================================================
    // 分词与关键词
    // ==================================================================

    /** 中文分词（搜索引擎模式，返回已过滤停用词、长度≥2 的词）。 */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH).stream()
                .map(s -> s.word.trim())
                .filter(w -> !w.isEmpty())
                .filter(w -> !STOP_WORDS.contains(w.toLowerCase()))
                .filter(w -> w.length() >= 2 || isChinese(w))
                .filter(w -> !w.matches("[\\p{Punct}\\s]+"))
                .toList();
    }

    /**
     * 基于 TF 的关键词抽取。
     *
     * <p>对候选词按“词频 × 长度权重”打分，并抑制与标题重复的词。</p>
     *
     * @param text  正文
     * @param title 标题（可为 null）
     * @param topN  返回数量
     */
    public static List<String> extractKeywords(String text, String title, int topN) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> freq = new HashMap<>();
        for (String t : tokens) {
            freq.merge(t, 1, Integer::sum);
        }
        Set<String> titleTokens = new HashSet<>(tokenize(title == null ? "" : title));

        return freq.entrySet().stream()
                .sorted((a, b) -> Double.compare(score(b, titleTokens), score(a, titleTokens)))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double score(Map.Entry<String, Integer> e, Set<String> titleTokens) {
        String w = e.getKey();
        double lengthBonus = Math.min(w.length(), 6) * 0.35;
        double titleBonus = titleTokens.contains(w) ? 1.5 : 0.0;
        // 领域术语加权：它们更能代表课程知识点的实质内容
        double domainBonus = DOMAIN_WORD_SET.contains(w) ? 2.0 : 0.0;
        // 纯数字/纯符号降权
        double typePenalty = w.matches("\\d+") ? 0.3 : 1.0;
        return (e.getValue() + lengthBonus + titleBonus + domainBonus) * typePenalty;
    }

    // ==================================================================
    // 检索片段与高亮
    // ==================================================================

    /**
     * 生成检索结果摘要片段：定位第一个命中词的位置，取上下文窗口。
     *
     * @param text      原文
     * @param terms     查询词（可为分词后的词集合）
     * @param maxLength 片段最大长度
     * @param prefix    高亮前缀
     * @param suffix    高亮后缀
     */
    public static String snippet(String text, Collection<String> terms, int maxLength,
                                 String prefix, String suffix) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = normalizeWhitespace(text);
        if (flat.length() <= maxLength) {
            return highlight(flat, terms, prefix, suffix);
        }

        // 找到任一查询词的最早出现位置作为窗口中心
        int hit = -1;
        String hitTerm = null;
        String lower = flat.toLowerCase();
        for (String term : terms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            int idx = lower.indexOf(term.toLowerCase());
            if (idx >= 0 && (hit < 0 || idx < hit)) {
                hit = idx;
                hitTerm = term;
            }
        }

        int start;
        if (hit < 0) {
            start = 0;
        } else {
            // 让命中词大致居中
            start = Math.max(0, hit - maxLength / 3);
        }
        if (start + maxLength > flat.length()) {
            start = Math.max(0, flat.length() - maxLength);
        }
        int end = Math.min(flat.length(), start + maxLength);

        String window = flat.substring(start, end);
        window = highlight(window, terms, prefix, suffix);
        return (start > 0 ? "…" : "") + window + (end < flat.length() ? "…" : "");
    }

    /**
     * 用指定标签包裹命中的查询词（大小写不敏感）。
     *
     * <p>实现为**单趟扫描**：先找出所有词在原文中的出现区间，合并重叠区间，
     * 再一次性拼接结果。这样做的必要性在于 —— 若逐个词依次替换，
     * 先插入的标记会污染后续匹配，产生 {@code <mark><mark>软件</mark>测试</mark>}
     * 这类嵌套标记，既影响渲染也影响样式。</p>
     */
    public static String highlight(String text, Collection<String> terms, String prefix, String suffix) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) {
            return text == null ? "" : text;
        }
        String lowerText = text.toLowerCase();
        List<int[]> spans = new ArrayList<>();

        for (String term : terms) {
            if (term == null || term.isBlank() || term.length() > text.length()) {
                continue;
            }
            String lowerTerm = term.toLowerCase();
            int from = 0;
            int idx;
            while ((idx = lowerText.indexOf(lowerTerm, from)) >= 0) {
                spans.add(new int[]{idx, idx + term.length()});
                from = idx + term.length();
            }
        }
        if (spans.isEmpty()) {
            return text;
        }

        // 合并重叠或相邻的区间，保证每个字符最多被标记一次
        spans.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] s : spans) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], s[1]);
            } else {
                merged.add(new int[]{s[0], s[1]});
            }
        }

        StringBuilder sb = new StringBuilder(text.length() + merged.size() * (prefix.length() + suffix.length()));
        int cursor = 0;
        for (int[] span : merged) {
            sb.append(text, cursor, span[0])
                    .append(prefix)
                    .append(text, span[0], span[1])
                    .append(suffix);
            cursor = span[1];
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    /**
     * 从查询串中提取用于高亮与匹配统计的词。
     *
     * <p>包含三部分：完整查询串、jieba 分词结果、以及**字符二元组**。
     * 补二元组的原因有两点：</p>
     * <ol>
     *   <li>MySQL 的 ngram 索引正是按二元组切分的，用二元组统计匹配情况
     *       与应用层打分口径一致；</li>
     *   <li>分词器可能把「软件测试」保留为整词而不切分，此时若文档中写作
     *       「测试用例」，仅靠整串与分词结果都无法体现相关性。</li>
     * </ol>
     */
    public static List<String> queryTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(q);
        terms.addAll(tokenize(q));

        // 中文查询补充字符二元组
        if (containsCjk(q) && q.length() >= 2) {
            for (int i = 0; i + 2 <= q.length(); i++) {
                String bigram = q.substring(i, i + 2);
                if (!bigram.matches(".*[\\s\\p{Punct}].*")) {
                    terms.add(bigram);
                }
            }
        }
        return new ArrayList<>(terms);
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    /** 截断字符串并追加省略号。 */
    public static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 内容指纹，用于判断内容是否发生变化。 */
    public static String md5(String s) {
        if (s == null) {
            s = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * 字符级差异统计（简化版 LCS 长度）。
     *
     * <p>仅用于版本对比时给出“新增/删除字符数”的量级参考，
     * 精确的行级 diff 由 {@code DiffService} 完成。</p>
     */
    public static int[] charDelta(String oldText, String newText) {
        String a = oldText == null ? "" : oldText;
        String b = newText == null ? "" : newText;
        int lcs = lcsLength(a, b);
        int added = b.length() - lcs;
        int removed = a.length() - lcs;
        return new int[]{Math.max(added, 0), Math.max(removed, 0)};
    }

    private static int lcsLength(String a, String b) {
        // 超长文本退化为长度估计，避免 O(n*m) 内存爆炸
        if ((long) a.length() * b.length() > 4_000_000L) {
            return (int) (Math.min(a.length(), b.length()) * 0.6);
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            Arrays.fill(cur, 0);
        }
        return prev[b.length()];
    }

    private static boolean isChinese(String w) {
        return w.length() == 1 && w.charAt(0) >= 0x4E00 && w.charAt(0) <= 0x9FFF;
    }

    /** 判断文本是否包含 CJK 字符。 */
    public static boolean containsCjk(String s) {
        if (s == null) {
            return false;
        }
        Matcher m = Pattern.compile("[\\u4e00-\\u9fff]").matcher(s);
        return m.find();
    }
}
