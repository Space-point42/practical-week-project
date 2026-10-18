package cn.edu.njupt.kms.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 多格式文档正文抽取器。
 *
 * <p>题目要求 CMS “支持多种文件格式和多媒体内容”。对可解析出文本的格式，
 * 抽取正文后写入 {@code kb_attachment.extracted_text}，使其参与全文检索与 RAG；
 * 对图片/音视频等二进制格式返回 UNSUPPORTED，由界面以预览方式呈现。</p>
 *
 * <p>每种格式都是独立的 try/catch，单个文件解析失败不会影响整体流程。</p>
 */
@Slf4j
@Component
public class FileTextExtractor {

    /** 抽取结果。 */
    public record Result(String text, String status) {

        public static Result success(String text) {
            return new Result(text, "SUCCESS");
        }

        public static Result failed() {
            return new Result(null, "FAILED");
        }

        public static Result unsupported() {
            return new Result(null, "UNSUPPORTED");
        }
    }

    /** 单文件抽取的最大字符数，防止超大文档拖垮内存与索引。 */
    private static final int MAX_CHARS = 400_000;

    /**
     * 从磁盘文件抽取正文。
     *
     * @param path 文件绝对路径
     * @param ext  扩展名（不含点，小写）
     */
    public Result extract(Path path, String ext) {
        if (path == null || !Files.exists(path)) {
            return Result.failed();
        }
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        try {
            String text = switch (e) {
                case "txt", "md", "markdown", "csv", "json", "xml", "yml", "yaml", "log", "sql", "java", "js", "ts", "py", "html", "htm", "css" ->
                        readPlainText(path, e);
                case "pdf" -> readPdf(path);
                case "docx" -> readDocx(path);
                case "doc" -> readDoc(path);
                case "xlsx" -> readXlsx(path);
                case "xls" -> readXls(path);
                case "pptx" -> readPptx(path);
                case "ppt" -> readPpt(path);
                // 图片、音视频等暂不做 OCR / 语音识别，标记为不支持
                default -> null;
            };

            if (text == null) {
                return Result.unsupported();
            }
            String cleaned = TextUtils.normalizeWhitespace(text);
            if (cleaned.length() > MAX_CHARS) {
                cleaned = cleaned.substring(0, MAX_CHARS);
            }
            return cleaned.isBlank() ? Result.unsupported() : Result.success(cleaned);

        } catch (Exception ex) {
            log.warn("抽取文件正文失败：{} —— {}", path.getFileName(), ex.toString());
            return Result.failed();
        }
    }

    // ------------------------------------------------------------------

    private String readPlainText(Path path, String ext) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        return switch (ext) {
            case "md", "markdown" -> TextUtils.markdownToPlain(raw);
            case "html", "htm" -> TextUtils.stripHtml(raw);
            default -> raw;
        };
    }

    private String readPdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }

    private String readDocx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    @SuppressWarnings("resource")
    private String readDoc(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             HWPFDocument doc = new HWPFDocument(in);
             WordExtractor ex = new WordExtractor(doc)) {
            return ex.getText();
        }
    }

    private String readXlsx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Workbook wb = new XSSFWorkbook(in)) {
            return readWorkbook(wb);
        }
    }

    private String readXls(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             Workbook wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook(in)) {
            return readWorkbook(wb);
        }
    }

    private String readWorkbook(Workbook wb) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet sheet = wb.getSheetAt(i);
            sb.append("【工作表】").append(sheet.getSheetName()).append('\n');
            for (Row row : sheet) {
                StringBuilder line = new StringBuilder();
                for (Cell cell : row) {
                    line.append(cellText(cell)).append('\t');
                }
                if (!line.toString().isBlank()) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String cellText(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                        ? String.valueOf(cell.getLocalDateTimeCellValue())
                        : String.valueOf(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    private String readPptx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XMLSlideShow ppt = new XMLSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("【幻灯片 ").append(idx++).append("】\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        sb.append(ts.getText()).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    @SuppressWarnings("resource")
    private String readPpt(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             HSLFSlideShow ppt = new HSLFSlideShow(in)) {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (org.apache.poi.hslf.usermodel.HSLFSlide slide : ppt.getSlides()) {
                sb.append("【幻灯片 ").append(idx++).append("】\n");
                for (org.apache.poi.hslf.usermodel.HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.hslf.usermodel.HSLFTextShape ts) {
                        sb.append(ts.getText()).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }
}
