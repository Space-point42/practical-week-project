package cn.edu.njupt.kms.module.kb.service;

import cn.edu.njupt.kms.common.BizException;
import cn.edu.njupt.kms.config.AppProperties;
import cn.edu.njupt.kms.module.kb.entity.Attachment;
import cn.edu.njupt.kms.module.kb.repository.AttachmentRepository;
import cn.edu.njupt.kms.security.SecurityUtils;
import cn.edu.njupt.kms.util.FileTextExtractor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 文件存储服务。
 *
 * <p>存储布局：{@code <root>/yyyy/MM/<uuid>.<ext>}，按年月分目录避免单目录文件过多。
 * 上传时依据扩展名判定媒体类型并做白名单校验，随后同步抽取文档正文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyy/MM");

    private final AppProperties props;
    private final AttachmentRepository attachmentRepository;
    private final FileTextExtractor textExtractor;

    /**
     * 启动时把解析后的存储根目录打印出来。
     *
     * <p>放在启动阶段而不是等第一次上传，是因为这个路径**依赖进程的工作目录**：
     * 配置里写的是相对路径 {@code ../storage}，启动脚本会先切到 {@code backend/} 再启动，
     * 以保证附件始终落在 {@code kms/storage}；若换一种方式启动（例如在项目根目录执行 jar），
     * 附件会被写到别处，表现为「之前上传的文件不见了」却不报任何错。
     * 启动日志里能直接看到生效的绝对路径，这类问题就不必靠推测。</p>
     */
    @PostConstruct
    void logResolvedStorageRoot() {
        try {
            Path root = storageRoot();
            log.info("文件存储根目录：{}（配置值 {}，当前工作目录 {}）",
                    root, props.getStorage().getRoot(), Paths.get("").toAbsolutePath());
        } catch (Exception e) {
            log.error("文件存储目录不可用，上传与附件下载将失败：{}", e.getMessage());
        }
    }

    /** 存储根目录的绝对路径。 */
    public Path storageRoot() {
        Path root = Paths.get(props.getStorage().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new BizException("无法创建存储目录：" + root);
        }
        return root;
    }

    /** 上传单个文件并落库为附件记录。 */
    @Transactional
    public Attachment store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        long limit = props.getStorage().getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > limit) {
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR,
                    "文件超过大小限制（%d MB）".formatted(props.getStorage().getMaxFileSizeMb()));
        }

        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? Paths.get(file.getOriginalFilename()).getFileName().toString()
                : "unnamed";
        String ext = extensionOf(originalName).toLowerCase(Locale.ROOT);
        String mediaType = resolveMediaType(ext);

        String subDir = LocalDate.now().format(MONTH_DIR);
        String storedName = UUID.randomUUID().toString().replace("-", "")
                + (ext.isEmpty() ? "" : "." + ext);
        String relativePath = subDir + "/" + storedName;

        Path target = storageRoot().resolve(relativePath).normalize();
        // 防御路径穿越：解析后的路径必须仍在根目录内
        if (!target.startsWith(storageRoot())) {
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR, "非法的文件路径");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("保存上传文件失败：{}", originalName, e);
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR, "文件保存失败：" + e.getMessage());
        }

        Attachment att = new Attachment();
        att.setFileName(originalName);
        att.setStoredName(storedName);
        att.setFilePath(relativePath);
        att.setFileSize(file.getSize());
        att.setMimeType(file.getContentType());
        att.setMediaType(mediaType);
        att.setExtension(ext.isEmpty() ? null : ext);
        att.setUploaderId(SecurityUtils.currentUserId());
        att.setCreatedAt(LocalDateTime.now());
        att.setExtractStatus("PENDING");

        // 同步抽取正文：文档类可被全文检索命中
        FileTextExtractor.Result result = textExtractor.extract(target, ext);
        att.setExtractedText(result.text());
        att.setExtractStatus(result.status());

        return attachmentRepository.save(att);
    }

    /** 批量上传。 */
    @Transactional
    public List<Attachment> storeAll(List<MultipartFile> files) {
        List<Attachment> list = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f != null && !f.isEmpty()) {
                list.add(store(f));
            }
        }
        return list;
    }

    /**
     * 由程序生成的文件内容落库为附件（不经过 HTTP 上传）。
     *
     * <p>用于系统自身产出的文件，例如演示数据中的示例文档，
     * 以及将来可能增加的「导出为 PDF」一类功能 ——
     * 复用同一套存储布局与正文抽取逻辑，避免另起一套。</p>
     *
     * @param fileName 原始文件名（含扩展名）
     * @param content  文件字节
     * @param itemId   直接挂载到的条目 id，可为 null
     */
    @Transactional
    public Attachment storeBytes(String fileName, byte[] content, Long itemId) {
        String ext = extensionOf(fileName).toLowerCase(Locale.ROOT);
        String mediaType = resolveMediaType(ext);

        String subDir = LocalDate.now().format(MONTH_DIR);
        String storedName = UUID.randomUUID().toString().replace("-", "")
                + (ext.isEmpty() ? "" : "." + ext);
        String relativePath = subDir + "/" + storedName;

        Path target = storageRoot().resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot())) {
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR, "非法的文件路径");
        }

        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            log.error("写入生成文件失败：{}", fileName, e);
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR, "文件写入失败：" + e.getMessage());
        }

        Attachment att = new Attachment();
        att.setItemId(itemId);
        att.setFileName(fileName);
        att.setStoredName(storedName);
        att.setFilePath(relativePath);
        att.setFileSize((long) content.length);
        att.setMimeType(guessMimeType(ext));
        att.setMediaType(mediaType);
        att.setExtension(ext.isEmpty() ? null : ext);
        att.setCreatedAt(LocalDateTime.now());

        FileTextExtractor.Result result = textExtractor.extract(target, ext);
        att.setExtractedText(result.text());
        att.setExtractStatus(result.status());

        return attachmentRepository.save(att);
    }

    /** 依据扩展名给出 MIME 类型，供浏览器预览时判断渲染方式。 */
    private String guessMimeType(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            default -> "text/plain";
        };
    }

    /** 读取文件内容用于下载/预览。 */
    public Path resolve(String relativePath) {
        Path target = storageRoot().resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot())) {
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR, "非法的文件路径");
        }
        if (!Files.exists(target)) {
            throw BizException.notFound("文件");
        }
        return target;
    }

    @Transactional
    public void delete(Long attachmentId) {
        Attachment att = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> BizException.notFound("附件"));
        try {
            Files.deleteIfExists(storageRoot().resolve(att.getFilePath()));
        } catch (IOException e) {
            log.warn("删除物理文件失败：{}", att.getFilePath(), e);
        }
        attachmentRepository.delete(att);
    }

    /** 把已上传的附件挂到某个知识条目上。 */
    @Transactional
    public void bindToItem(Long itemId, Collection<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        attachmentRepository.bindToItem(itemId, attachmentIds);
    }

    // ------------------------------------------------------------------

    /** 依据扩展名判定媒体类型，同时充当白名单校验。 */
    private String resolveMediaType(String ext) {
        if (ext.isEmpty()) {
            throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR, "文件缺少扩展名，无法识别类型");
        }
        for (Map.Entry<String, String> e : props.getStorage().getAllowedExtensions().entrySet()) {
            for (String allowed : e.getValue().split(",")) {
                if (allowed.trim().equalsIgnoreCase(ext)) {
                    return e.getKey();
                }
            }
        }
        throw new BizException(cn.edu.njupt.kms.common.ErrorCode.FILE_ERROR,
                "不支持的文件类型：." + ext + "（允许：" + props.getStorage().allowedExtensionList() + "）");
    }

    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot < 0 || dot == fileName.length() - 1) ? "" : fileName.substring(dot + 1);
    }

    /** 依据 MIME 猜测媒体类型，供没有扩展名的场景兜底。 */
    public static String guessMediaTypeByMime(String mime) {
        if (mime == null) {
            return Attachment.MEDIA_FILE;
        }
        if (mime.startsWith("image/")) {
            return Attachment.MEDIA_IMAGE;
        }
        if (mime.startsWith("video/")) {
            return Attachment.MEDIA_VIDEO;
        }
        if (mime.startsWith("audio/")) {
            return Attachment.MEDIA_AUDIO;
        }
        if (mime.startsWith("text/") || mime.contains("pdf") || mime.contains("word")
                || mime.contains("excel") || mime.contains("powerpoint")) {
            return Attachment.MEDIA_DOCUMENT;
        }
        return Attachment.MEDIA_FILE;
    }
}
