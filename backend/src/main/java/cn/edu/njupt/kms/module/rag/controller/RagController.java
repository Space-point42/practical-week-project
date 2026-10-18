package cn.edu.njupt.kms.module.rag.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.module.rag.dto.RagDTO;
import cn.edu.njupt.kms.module.rag.service.RagIndexService;
import cn.edu.njupt.kms.module.rag.service.RagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG 检索增强问答接口。
 *
 * <p>问答会产生会话记录，因此要求登录；
 * 索引管理仅管理员可用。</p>
 */
@Tag(name = "10 RAG 问答", description = "基于知识库的检索增强问答")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final RagIndexService indexService;

    @Operation(summary = "提问（检索增强生成）")
    @PostMapping("/ask")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RagDTO.AnswerDTO> ask(@Valid @RequestBody RagDTO.AskRequest req) {
        return ApiResponse.ok(ragService.ask(req));
    }

    @Operation(summary = "我的问答会话列表")
    @GetMapping("/sessions")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<RagDTO.SessionDTO>> sessions() {
        return ApiResponse.ok(ragService.listSessions());
    }

    @Operation(summary = "会话消息记录")
    @GetMapping("/sessions/{sessionId}/messages")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<RagDTO.MessageDTO>> messages(@PathVariable Long sessionId) {
        return ApiResponse.ok(ragService.listMessages(sessionId));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        ragService.deleteSession(sessionId);
        return ApiResponse.ok("已删除", null);
    }

    // ---------------- 索引管理 ----------------

    @Operation(summary = "向量索引状态")
    @GetMapping("/index/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RagDTO.IndexStatusDTO> indexStatus() {
        return ApiResponse.ok(indexService.status());
    }

    @Operation(summary = "重建向量索引（全量或指定条目）")
    @PostMapping("/index/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<RagDTO.ReindexResult> rebuild(@RequestBody(required = false) RagDTO.ReindexRequest req) {
        List<Long> ids = req == null ? null : req.getItemIds();
        return ApiResponse.ok("索引重建完成", indexService.reindexAll(ids));
    }
}
