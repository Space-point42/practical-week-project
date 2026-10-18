package cn.edu.njupt.kms.module.stats.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.rag.chat.ChatService;
import cn.edu.njupt.kms.module.rag.embedding.EmbeddingService;
import cn.edu.njupt.kms.module.stats.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 统计看板接口（公开只读，供首页展示）。 */
@Tag(name = "11 统计看板", description = "总览、分布、趋势与最新动态")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final EmbeddingService embeddingService;
    private final ChatService chatService;

    @Operation(summary = "总览指标")
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(statsService.overview());
    }

    @Operation(summary = "分布数据（类型/状态/分类/知识域/标签）")
    @GetMapping("/distribution")
    public ApiResponse<Map<String, Object>> distribution() {
        return ApiResponse.ok(statsService.distribution());
    }

    @Operation(summary = "发布趋势")
    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "14") int days) {
        return ApiResponse.ok(statsService.trend(days));
    }

    @Operation(summary = "最新动态")
    @GetMapping("/activity")
    public ApiResponse<List<Map<String, Object>>> activity(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(statsService.recentActivity(Math.min(limit, 20)));
    }

    @Operation(summary = "知识地图热度")
    @GetMapping("/concept-heat")
    public ApiResponse<List<Map<String, Object>>> conceptHeat(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(statsService.conceptHeat(Math.min(limit, 100)));
    }

    @Operation(summary = "系统能力自检（模型/检索通道可用性）")
    @GetMapping("/system-info")
    public ApiResponse<Map<String, Object>> systemInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("embeddingModel", embeddingService.modelId());
        m.put("embeddingDescription", embeddingService.description());
        m.put("embeddingOnline", embeddingService.isOnline());
        m.put("chatModel", chatService.modelId());
        m.put("chatDescription", chatService.description());
        m.put("chatAvailable", chatService.available());
        return ApiResponse.ok(m);
    }
}
