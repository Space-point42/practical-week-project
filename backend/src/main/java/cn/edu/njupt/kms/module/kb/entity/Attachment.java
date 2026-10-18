package cn.edu.njupt.kms.module.kb.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识条目附件：支持文档、图片、音视频等多格式多媒体内容。
 *
 * <p>上传时同步抽取正文写入 {@link #extractedText}，
 * 使其内容可被全文检索命中、也可进入 RAG 检索分块。</p>
 */
@Data
@Entity
@Table(name = "kb_attachment")
public class Attachment {

    public static final String MEDIA_FILE = "FILE";
    public static final String MEDIA_DOCUMENT = "DOCUMENT";
    public static final String MEDIA_IMAGE = "IMAGE";
    public static final String MEDIA_AUDIO = "AUDIO";
    public static final String MEDIA_VIDEO = "VIDEO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "stored_name", nullable = false, length = 255)
    private String storedName;

    /** 相对存储根目录的路径，形如 {@code 2026/10/uuid.pdf}。 */
    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    private Long fileSize = 0L;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "media_type", nullable = false, length = 16)
    private String mediaType = MEDIA_FILE;

    @Column(length = 16)
    private String extension;

    @Lob
    @Column(name = "extracted_text", columnDefinition = "LONGTEXT")
    private String extractedText;

    /** PENDING / SUCCESS / FAILED / UNSUPPORTED */
    @Column(name = "extract_status", nullable = false, length = 16)
    private String extractStatus = "PENDING";

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "uploader_id")
    private Long uploaderId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public boolean isImage() {
        return MEDIA_IMAGE.equals(mediaType);
    }

    public boolean isVideo() {
        return MEDIA_VIDEO.equals(mediaType);
    }

    /** 人类可读的文件大小。 */
    public String readableSize() {
        long b = fileSize == null ? 0 : fileSize;
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024 * 1024) {
            return "%.1f KB".formatted(b / 1024.0);
        }
        if (b < 1024L * 1024 * 1024) {
            return "%.1f MB".formatted(b / 1024.0 / 1024);
        }
        return "%.1f GB".formatted(b / 1024.0 / 1024 / 1024);
    }
}
