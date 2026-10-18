package cn.edu.njupt.kms.module.kb.controller;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.module.kb.dto.KnowledgeDTO;
import cn.edu.njupt.kms.module.kb.entity.Attachment;
import cn.edu.njupt.kms.module.kb.repository.AttachmentRepository;
import cn.edu.njupt.kms.module.kb.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * 文件上传与访问接口。
 *
 * <p>上传接口返回附件 id，创建/更新条目时通过 {@code attachmentIds} 绑定；
 * 预览/下载接口按存储路径直接读取文件，图片与视频可在前端内联播放。</p>
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "08 文件", description = "多格式文件的上传、预览与下载")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService storageService;
    private final AttachmentRepository attachmentRepository;
    private final AppProperties props;

    @Operation(summary = "上传单个文件")
    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<KnowledgeDTO.AttachmentDTO> upload(@RequestParam("file") MultipartFile file) {
        Attachment att = storageService.store(file);
        return ApiResponse.ok("上传成功", KnowledgeDTO.AttachmentDTO.from(att, "/api/files/preview"));
    }

    @Operation(summary = "批量上传文件")
    @PostMapping("/upload-batch")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<List<KnowledgeDTO.AttachmentDTO>> uploadBatch(@RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.ok("上传成功", storageService.storeAll(files).stream()
                .map(a -> KnowledgeDTO.AttachmentDTO.from(a, "/api/files/preview"))
                .toList());
    }

    @Operation(summary = "附件详情")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDTO.AttachmentDTO> get(@PathVariable Long id) {
        Attachment att = attachmentRepository.findById(id)
                .orElseThrow(() -> cn.edu.njupt.kms.common.BizException.notFound("附件"));
        return ApiResponse.ok(KnowledgeDTO.AttachmentDTO.from(att, "/api/files/preview"));
    }

    @Operation(summary = "删除附件")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        storageService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }

    @Operation(summary = "允许的文件扩展名说明")
    @GetMapping("/allowed-extensions")
    public ApiResponse<java.util.Map<String, String>> allowedExtensions() {
        return ApiResponse.ok(props.getStorage().getAllowedExtensions());
    }

    // ---------------- 文件访问 ----------------

    /**
     * 预览/下载。
     *
     * <p>路径参数为相对存储根目录的路径，例如 {@code 2026/10/abc123.pdf}。
     * 该方法在安全配置中放行，便于 {@code <img src>}、{@code <video src>} 直接引用。</p>
     *
     * @param download 为 true 时以附件形式下载，否则内联预览
     */
    @Operation(summary = "文件预览/下载")
    @GetMapping("/preview/{*path}")
    public ResponseEntity<Resource> preview(@PathVariable String path,
                                            @RequestParam(defaultValue = "false") boolean download,
                                            HttpServletRequest request) {
        // Spring 6 的 {*path} 会带上前导斜杠，这里去掉后再交给存储服务
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path file = storageService.resolve(relative);

        Resource resource = new FileSystemResource(file);
        String contentType = request.getServletContext().getMimeType(file.getFileName().toString());
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        if (download) {
            String encoded = URLEncoder.encode(file.getFileName().toString(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encoded);
        } else {
            // 允许浏览器内联渲染，并对视频拖动进度条提供 Range 支持
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.setCacheControl("public, max-age=86400");
        }

        return ResponseEntity.ok().headers(headers).body(resource);
    }

    @Operation(summary = "按附件 id 预览（重定向到实际存储路径）")
    @GetMapping("/preview-by-id/{id}")
    public ResponseEntity<Resource> previewById(@PathVariable Long id,
                                                @RequestParam(defaultValue = "false") boolean download) {
        Attachment att = attachmentRepository.findById(id)
                .orElseThrow(() -> cn.edu.njupt.kms.common.BizException.notFound("附件"));
        return preview(att.getFilePath(), download, currentRequest());
    }

    private HttpServletRequest currentRequest() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest();
    }
}
