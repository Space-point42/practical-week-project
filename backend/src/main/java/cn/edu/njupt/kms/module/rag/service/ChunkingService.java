package cn.edu.njupt.kms.module.rag.service;

import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块服务。
 *
 * <p>分块质量直接决定 RAG 的效果：块太大则噪声多、超出模型上下文；
 * 块太小则语义不完整。这里采用「段落优先 + 定长兜底 + 重叠」的策略：</p>
 * <ol>
 *   <li>先按空行与标题行切成自然段；</li>
 *   <li>把相邻小段拼到接近目标长度，尽量在段落边界处收口；</li>
 *   <li>超过目标长度的段落按句号/分号等标点二次切分；</li>
 *   <li>相邻块之间保留 {@code overlap} 字符的重叠，
 *       避免关键信息正好落在切分点上被割裂。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final AppProperties props;

    /** 分块结果。 */
    public record Chunk(int index, String content, int charCount) {
    }

    /** 按配置对文本分块。 */
    public List<Chunk> split(String text) {
        return split(text, props.getRag().getChunkSize(), props.getRag().getChunkOverlap());
    }

    /**
     * 对文本分块。
     *
     * @param text      纯文本
     * @param chunkSize 目标块长度（字符）
     * @param overlap   相邻块重叠长度（字符）
     */
    public List<Chunk> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(chunkSize, 120);
        int ov = Math.max(0, Math.min(overlap, size / 2));

        String normalized = TextUtils.normalizeWhitespace(text);
        List<String> paragraphs = splitParagraphs(normalized);

        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            // 单段就超长：先把已有内容收口，再把该段按标点切开
            if (para.length() > size) {
                flush(current, blocks);
                blocks.addAll(splitLongParagraph(para, size, ov));
                continue;
            }
            if (current.length() + para.length() + 1 > size && current.length() > 0) {
                flush(current, blocks);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(para);
        }
        flush(current, blocks);

        // 加重叠窗口：把上一块的尾部拼到下一块开头
        List<Chunk> chunks = new ArrayList<>();
        String previousTail = "";
        for (int i = 0; i < blocks.size(); i++) {
            String content = blocks.get(i);
            if (ov > 0 && !previousTail.isEmpty()) {
                content = previousTail + "\n" + content;
            }
            if (content.length() > size + ov + 8) {
                // 拼接后超长，按上限截断，保证单块规模可控
                content = content.substring(0, size + ov);
            }
            chunks.add(new Chunk(i, content, content.length()));
            previousTail = ov > 0 && blocks.get(i).length() > ov
                    ? blocks.get(i).substring(blocks.get(i).length() - ov)
                    : blocks.get(i);
        }
        return chunks;
    }

    private void flush(StringBuilder current, List<String> blocks) {
        if (current.length() > 0) {
            blocks.add(current.toString());
            current.setLength(0);
        }
    }

    /** 按空行分自然段；无空行时退化按换行分。 */
    private List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String block : text.split("\n\\s*\n")) {
            String t = block.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() <= 800) {
                out.add(t);
            } else {
                // 无空行的长文本（常见于 PDF 抽取结果）按换行继续拆
                for (String line : t.split("\n")) {
                    String l = line.trim();
                    if (!l.isEmpty()) {
                        out.add(l);
                    }
                }
            }
        }
        return out;
    }

    /** 超长段落：优先在句末标点处切开，切不动时按长度硬切。 */
    private List<String> splitLongParagraph(String para, int size, int overlap) {
        List<String> out = new ArrayList<>();
        List<String> sentences = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || (c == '.' && i + 1 < para.length() && Character.isWhitespace(para.charAt(i + 1)))) {
                sentences.add(para.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < para.length()) {
            sentences.add(para.substring(start));
        }

        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            if (s.length() > size) {
                // 单个句子仍超长：硬切
                for (int i = 0; i < s.length(); i += size - overlap) {
                    out.add(s.substring(i, Math.min(i + size, s.length())));
                }
                continue;
            }
            if (current.length() + s.length() > size && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(s);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }
}
