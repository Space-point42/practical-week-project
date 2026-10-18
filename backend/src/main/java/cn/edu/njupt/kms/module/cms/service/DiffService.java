package cn.edu.njupt.kms.module.cms.service;

import cn.edu.njupt.kms.module.cms.dto.CmsDTO;
import cn.edu.njupt.kms.util.TextUtils;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 版本差异计算。
 *
 * <p>行级 diff 采用经典的最长公共子序列（LCS）回溯法：
 * 先用动态规划求出 LCS 长度表，再从右下角回溯生成操作序列，
 * 相比逐行比较能正确识别「中间插入若干行」这类改动。</p>
 */
@Service
public class DiffService {

    /** 参与 diff 的最大行数，超出则退化为「整体替换」以免耗时过长。 */
    private static final int MAX_LINES = 3000;

    /**
     * 计算两个版本的差异。
     *
     * @param from 旧版本
     * @param to   新版本
     */
    public CmsDTO.VersionDiffDTO diff(CmsDTO.VersionDTO from, CmsDTO.VersionDTO to) {
        CmsDTO.VersionDiffDTO d = new CmsDTO.VersionDiffDTO();
        d.setFromVersion(from.getVersionNo());
        d.setToVersion(to.getVersionNo());
        d.setFromTitle(from.getTitle());
        d.setToTitle(to.getTitle());
        d.setTitleChanged(!Objects.equals(from.getTitle(), to.getTitle()));
        d.setSummaryChanged(!Objects.equals(from.getSummary(), to.getSummary()));
        d.setCategoryChanged(!Objects.equals(from.getCategoryId(), to.getCategoryId()));

        List<String> oldContent = from.getContent() == null ? List.of() : lines(from.getContent());
        List<String> newContent = to.getContent() == null ? List.of() : lines(to.getContent());

        List<CmsDTO.DiffLine> lines = computeLineDiff(oldContent, newContent);
        d.setLines(lines);
        d.setAddedLines((int) lines.stream().filter(l -> "ADD".equals(l.getType())).count());
        d.setRemovedLines((int) lines.stream().filter(l -> "REMOVE".equals(l.getType())).count());

        int[] delta = TextUtils.charDelta(from.getContent(), to.getContent());
        d.setCharAdded(delta[0]);
        d.setCharRemoved(delta[1]);

        // 标注变化
        Set<String> fromConcepts = new LinkedHashSet<>(from.getConceptNames() == null ? List.of() : from.getConceptNames());
        Set<String> toConcepts = new LinkedHashSet<>(to.getConceptNames() == null ? List.of() : to.getConceptNames());
        d.setConceptsAdded(toConcepts.stream().filter(c -> !fromConcepts.contains(c)).toList());
        d.setConceptsRemoved(fromConcepts.stream().filter(c -> !toConcepts.contains(c)).toList());

        Set<String> fromTags = new LinkedHashSet<>(from.getTags() == null ? List.of() : from.getTags());
        Set<String> toTags = new LinkedHashSet<>(to.getTags() == null ? List.of() : to.getTags());
        d.setTagsAdded(toTags.stream().filter(t -> !fromTags.contains(t)).toList());
        d.setTagsRemoved(fromTags.stream().filter(t -> !toTags.contains(t)).toList());

        return d;
    }

    /**
     * 基于 LCS 的行级差异。
     *
     * <p>复杂度 O(n·m) 时间、O(n·m) 空间（用 short 存长度表以降低内存占用）。
     * 对课程设计场景的文档规模（数百行）足够快。</p>
     */
    private List<CmsDTO.DiffLine> computeLineDiff(List<String> oldLines, List<String> newLines) {
        List<CmsDTO.DiffLine> result = new ArrayList<>();

        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) {
            // 超大文档：不做精细比对，直接标记为新旧整体
            oldLines.forEach(l -> result.add(CmsDTO.DiffLine.of("REMOVE", result.size() + 1, null, l)));
            newLines.forEach(l -> result.add(CmsDTO.DiffLine.of("ADD", null, result.size() + 1, l)));
            return result;
        }

        int n = oldLines.size();
        int m = newLines.size();
        int[][] lcs = new int[n + 1][m + 1];

        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(CmsDTO.DiffLine.of("SAME", i + 1, j + 1, oldLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(CmsDTO.DiffLine.of("REMOVE", i + 1, null, oldLines.get(i)));
                i++;
            } else {
                result.add(CmsDTO.DiffLine.of("ADD", null, j + 1, newLines.get(j)));
                j++;
            }
        }
        while (i < n) {
            result.add(CmsDTO.DiffLine.of("REMOVE", i + 1, null, oldLines.get(i)));
            i++;
        }
        while (j < m) {
            result.add(CmsDTO.DiffLine.of("ADD", null, j + 1, newLines.get(j)));
            j++;
        }

        return collapseContext(result);
    }

    /**
     * 折叠大段未改动的上下文，只保留改动附近的行，
     * 避免前端因为返回几千行相同内容而卡顿。
     *
     * @param contextSize 改动前后各保留的行数
     */
    private List<CmsDTO.DiffLine> collapseContext(List<CmsDTO.DiffLine> lines) {
        final int contextSize = 3;
        boolean[] keep = new boolean[lines.size()];

        for (int i = 0; i < lines.size(); i++) {
            if (!"SAME".equals(lines.get(i).getType())) {
                for (int k = Math.max(0, i - contextSize); k <= Math.min(lines.size() - 1, i + contextSize); k++) {
                    keep[k] = true;
                }
            }
        }

        List<CmsDTO.DiffLine> out = new ArrayList<>();
        boolean skipping = false;
        for (int i = 0; i < lines.size(); i++) {
            if (keep[i]) {
                out.add(lines.get(i));
                skipping = false;
            } else if (!skipping) {
                // 用一条占位行表示被折叠的上下文
                out.add(CmsDTO.DiffLine.of("SKIP", null, null, "⋯ 未改动的上下文已折叠 ⋯"));
                skipping = true;
            }
        }
        return out;
    }

    /** 按行拆分，统一换行符。 */
    private List<String> lines(String text) {
        return Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }
}
