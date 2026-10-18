package cn.edu.njupt.kms.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
// POI 的通配符导入会与 java.awt 的 Color / Font 冲突，因此逐个显式导入
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 演示用文件生成器。
 *
 * <p>知识库要能演示「多格式文档 + 正文抽取可被检索」这一能力，
 * 就必须有真实可解析的示例文件。本类在初始化时现场生成
 * PDF / PNG / DOCX / XLSX / Markdown 五类文件，而不是把二进制资源打包进仓库 ——
 * 这样仓库保持轻量，且生成的文件内容与知识库条目一一对应、可被检索命中。</p>
 *
 * <p>PDF 使用系统字体 {@code simhei.ttf} 嵌入中文；
 * 找不到该字体时降级为英文占位内容，保证文件始终可生成、可解析。</p>
 */
@Slf4j
@Component
public class SampleFileFactory {

    private static final String[] CJK_FONT_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simkai.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/System/Library/Fonts/PingFang.ttc"
    };

    private final File cjkFont = locateCjkFont();

    private File locateCjkFont() {
        for (String path : CJK_FONT_CANDIDATES) {
            File f = new File(path);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    public boolean supportsChinesePdf() {
        return cjkFont != null;
    }

    // ==================================================================
    // PDF
    // ==================================================================

    /**
     * 生成一份 PDF 文档。
     *
     * @param title  标题
     * @param lines  正文行（按顺序排版，自动分页）
     */
    public byte[] pdf(String title, List<String> lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            PDFont titleFont = loadFont(doc, true);
            PDFont bodyFont = loadFont(doc, false);
            boolean cjk = cjkFont != null;

            float margin = 56;
            float y = PDRectangle.A4.getHeight() - margin;
            float lineHeight = 20;

            PDPageContentStream cs = new PDPageContentStream(doc, page);
            try {
                // 标题
                cs.beginText();
                cs.setFont(titleFont, 18);
                cs.newLineAtOffset(margin, y);
                cs.showText(safe(cjk ? title : "Document"));
                cs.endText();
                y -= 34;

                // 分隔线
                cs.setStrokingColor(new java.awt.Color(0x2a, 0x78, 0xd6));
                cs.setLineWidth(1.4f);
                cs.moveTo(margin, y + 10);
                cs.lineTo(PDRectangle.A4.getWidth() - margin, y + 10);
                cs.stroke();
                y -= 16;

                for (String raw : lines) {
                    String line = raw == null ? "" : raw;
                    for (String wrapped : wrap(line, 46)) {
                        if (y < margin + 40) {
                            cs.close();
                            page = new PDPage(PDRectangle.A4);
                            doc.addPage(page);
                            cs = new PDPageContentStream(doc, page);
                            y = PDRectangle.A4.getHeight() - margin;
                        }
                        cs.beginText();
                        cs.setFont(bodyFont, 11);
                        cs.newLineAtOffset(margin, y);
                        cs.showText(safe(cjk ? wrapped : asciiFallback(wrapped)));
                        cs.endText();
                        y -= lineHeight;
                    }
                }
            } finally {
                cs.close();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private PDFont loadFont(PDDocument doc, boolean bold) throws IOException {
        if (cjkFont != null) {
            // 嵌入子集化字体，避免 PDF 依赖阅读器所安装的字体
            return PDType0Font.load(doc, cjkFont);
        }
        return new org.apache.pdfbox.pdmodel.font.PDType1Font(
                bold ? org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD
                        : org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
    }

    /** 去掉 PDF 基础字体不支持的字符（无中文字体时的兜底）。 */
    private String asciiFallback(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c < 128 ? c : '?');
        }
        return sb.toString();
    }

    /** 去掉字体不支持的字符，避免 showText 抛异常导致整份文件生成失败。 */
    private String safe(String s) {
        if (s == null) {
            return "";
        }
        // 常见不可打印字符与 emoji 会被字体拒绝
        return s.replaceAll("[\\p{So}\\p{Cn}]", "").replace("\t", "    ");
    }

    private List<String> wrap(String line, int width) {
        if (line.length() <= width) {
            return List.of(line);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < line.length(); i += width) {
            out.add(line.substring(i, Math.min(i + width, line.length())));
        }
        return out;
    }

    // ==================================================================
    // 图片（用 Java2D 画结构图，比纯色块更像真实素材）
    // ==================================================================

    /** 画一张分层架构图。 */
    public byte[] architectureDiagram(String title, List<String> layers) throws IOException {
        int width = 1000;
        int height = 620;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(0x0b, 0x0b, 0x0b));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 26));
            g.drawString(title, 48, 62);
            g.setColor(new Color(0x2a, 0x78, 0xd6));
            g.fillRect(48, 78, 64, 4);

            Color[] palette = {
                    new Color(0x2a, 0x78, 0xd6), new Color(0x1b, 0xaf, 0x7a),
                    new Color(0xeb, 0x68, 0x34), new Color(0x4a, 0x3a, 0xa7)
            };

            int y = 122;
            int boxH = layers.size() >= 4 ? 96 : 110;
            for (int i = 0; i < layers.size(); i++) {
                Color c = palette[i % palette.length];
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 22));
                g.fillRoundRect(48, y, width - 96, boxH, 14, 14);
                g.setColor(c);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(48, y, width - 96, boxH, 14, 14);

                // 左侧色条
                g.fillRoundRect(48, y, 6, boxH, 3, 3);

                g.setFont(new Font("Microsoft YaHei", Font.BOLD, 17));
                g.setColor(new Color(0x0b, 0x0b, 0x0b));
                g.drawString(layers.get(i), 76, y + 38);

                g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
                g.setColor(new Color(0x52, 0x51, 0x4e));
                g.drawString(layerDescription(i), 76, y + 64);

                // 层间连接箭头
                if (i < layers.size() - 1) {
                    g.setColor(new Color(0xc3, 0xc2, 0xb7));
                    g.setStroke(new BasicStroke(1.6f));
                    int cx = width / 2;
                    g.drawLine(cx, y + boxH, cx, y + boxH + 20);
                    g.fillPolygon(new int[]{cx - 5, cx + 5, cx}, new int[]{y + boxH + 20, y + boxH + 20, y + boxH + 27}, 3);
                    g.drawString("REST / JSON", cx + 14, y + boxH + 18);
                }
                y += boxH + 28;
            }

            g.setColor(new Color(0x89, 0x87, 0x81));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.drawString("《专业课程设计II》知识管理系统 · 体系结构示意图", 48, height - 22);
        } finally {
            g.dispose();
        }
        return toPng(img);
    }

    private String layerDescription(int index) {
        return switch (index) {
            case 0 -> "Vue 3 + Element Plus + ECharts —— 看板 / 知识库 / 图谱 / 检索 / 问答 / 版本";
            case 1 -> "Spring Boot 3 —— 认证鉴权 · 知识库 · 本体 · 检索 · RAG · 内容管理 · 统计";
            case 2 -> "MySQL 8.0（utf8mb4 + ngram 全文索引）—— 20 张表：业务表 / 关联表 / 日志表";
            default -> "对象存储目录 storage/ —— 按年月分目录存放文档、图片与音视频附件";
        };
    }

    /** 画一张实体联系图（E-R）。 */
    public byte[] erDiagram() throws IOException {
        int width = 1000;
        int height = 600;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(0x0b, 0x0b, 0x0b));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
            g.drawString("知识管理系统 E-R 图（局部）", 48, 56);
            g.setColor(new Color(0x1b, 0xaf, 0x7a));
            g.fillRect(48, 70, 64, 4);

            // 实体
            entity(g, 90, 140, "知识条目", new Color(0x2a, 0x78, 0xd6));
            entity(g, 400, 120, "分类", new Color(0x1b, 0xaf, 0x7a));
            entity(g, 400, 270, "标签", new Color(0xeb, 0x68, 0x34));
            entity(g, 400, 420, "本体概念", new Color(0x4a, 0x3a, 0xa7));
            entity(g, 720, 140, "附件", new Color(0x2a, 0x78, 0xd6));
            entity(g, 720, 300, "历史版本", new Color(0x1b, 0xaf, 0x7a));
            entity(g, 720, 450, "版本策略", new Color(0xeb, 0x68, 0x34));

            // 联系菱形 + 基数
            relation(g, 265, 165, "属于", "N:1");
            relation(g, 265, 300, "标注", "M:N");
            relation(g, 265, 450, "关联", "M:N");
            relation(g, 615, 165, "包含", "1:N");
            relation(g, 615, 300, "留存", "1:N");
            relation(g, 615, 450, "适用", "N:1");

            g.setColor(new Color(0x52, 0x51, 0x4e));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("矩形 = 实体，菱形 = 联系，连线上的标注为该联系的基数", 48, height - 46);
            g.setColor(new Color(0x89, 0x87, 0x81));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            g.drawString("《专业课程设计II》知识管理系统 · 概念结构设计成果", 48, height - 24);
        } finally {
            g.dispose();
        }
        return toPng(img);
    }

    /** 画一张知识图谱全景示意图。 */
    public byte[] graphOverviewDiagram() throws IOException {
        int width = 1000;
        int height = 620;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(0x0b, 0x0b, 0x0b));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
            g.drawString("知识本体全景（三个知识域）", 48, 56);
            g.setColor(new Color(0x4a, 0x3a, 0xa7));
            g.fillRect(48, 70, 64, 4);

            Color se = new Color(0x2a, 0x78, 0xd6);
            Color db = new Color(0x1b, 0xaf, 0x7a);
            Color km = new Color(0xeb, 0x68, 0x34);

            // 三个簇
            drawCluster(g, 240, 320, 150, se, "软件工程", new String[]{
                    "软件需求", "软件设计", "软件构造", "软件测试", "软件维护", "项目管理", "软件过程"});
            drawCluster(g, 530, 250, 130, db, "数据库", new String[]{
                    "数据模型", "关系理论", "SQL", "数据库设计", "实现与运行", "新技术"});
            drawCluster(g, 760, 430, 110, km, "知识管理", new String[]{
                    "知识库", "检索技术", "RAG", "内容管理", "知识图谱"});

            // 跨域连线
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{6f, 5f}, 0f));
            g.setColor(new Color(0xc3, 0xc2, 0xb7));
            g.drawLine(370, 340, 430, 280);   // 软件工程 ↔ 数据库
            g.drawLine(640, 290, 690, 400);   // 数据库 ↔ 知识管理

            g.setColor(new Color(0x52, 0x51, 0x4e));
            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            g.drawString("实线 = 域内关系（上下位 / 组成 / 先修 / 应用于 / 产出）", 48, height - 48);
            g.drawString("虚线 = 跨域关系（相关 / 近义）", 48, height - 28);
        } finally {
            g.dispose();
        }
        return toPng(img);
    }

    private void drawCluster(Graphics2D g, int cx, int cy, int radius, java.awt.Color color,
                             String label, String[] children) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 26));
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
        g.setStroke(new BasicStroke(1.4f));
        g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // 中心节点
        int r = 26;
        g.setColor(color);
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        int tw = g.getFontMetrics().stringWidth(label);
        g.drawString(label, cx - tw / 2, cy + 5);

        // 子节点
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        for (int i = 0; i < children.length; i++) {
            double angle = -Math.PI / 2 + (2 * Math.PI * i) / children.length;
            int nx = cx + (int) (Math.cos(angle) * (radius - 24));
            int ny = cy + (int) (Math.sin(angle) * (radius - 24));

            g.setColor(new Color(0xc3, 0xc2, 0xb7));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(cx, cy, nx, ny);

            g.setColor(color);
            g.fillOval(nx - 5, ny - 5, 10, 10);

            int w = g.getFontMetrics().stringWidth(children[i]);
            int tx = nx + (nx >= cx ? 10 : -w - 10);
            g.setColor(new Color(0x52, 0x51, 0x4e));
            g.drawString(children[i], tx, ny + 4);
        }
    }

    private void entity(Graphics2D g, int x, int y, String name, java.awt.Color color) {
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 24));
        g.fillRoundRect(x, y, 150, 58, 10, 10);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.8f));
        g.drawRoundRect(x, y, 150, 58, 10, 10);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        g.setColor(new Color(0x0b, 0x0b, 0x0b));
        int w = g.getFontMetrics().stringWidth(name);
        g.drawString(name, x + (150 - w) / 2, y + 36);
    }

    private void relation(Graphics2D g, int cx, int cy, String name, String cardinality) {
        g.setColor(new Color(0xc3, 0xc2, 0xb7));
        g.setStroke(new BasicStroke(1.4f));
        g.drawLine(cx - 70, cy, cx + 70, cy);

        int[] xs = {cx - 40, cx + 40, cx + 40, cx - 40};
        int[] ys = {cy - 22, cy - 22, cy + 22, cy + 22};
        g.setColor(new Color(0xff, 0xff, 0xff));
        g.fillPolygon(xs, ys, 4);
        g.setColor(new Color(0x52, 0x51, 0x4e));
        g.drawPolygon(xs, ys, 4);

        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        int w = g.getFontMetrics().stringWidth(name);
        g.drawString(name, cx - w / 2, cy + 5);

        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        g.setColor(new Color(0x89, 0x87, 0x81));
        g.drawString(cardinality, cx + 44, cy - 8);
    }

    private byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ==================================================================
    // Office 文档
    // ==================================================================

    /** 生成一个 DOCX 文档。 */
    public byte[] docx(String title, List<String> paragraphs) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph head = doc.createParagraph();
            XWPFRun headRun = head.createRun();
            headRun.setBold(true);
            headRun.setFontSize(18);
            headRun.setFontFamily("微软雅黑");
            headRun.setText(title);

            doc.createParagraph();

            for (String p : paragraphs) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setFontSize(11);
                run.setFontFamily("微软雅黑");
                run.setText(p);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 生成一个 XLSX 表格。
     *
     * @param sheetName 工作表名
     * @param headers   表头
     * @param rows      数据行
     */
    public byte[] xlsx(String sheetName, List<String> headers, List<List<String>> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);

            CellStyle headerStyle = wb.createCellStyle();
            // 必须写全限定名：文件里同时存在 java.awt.Font（绘图用）与 POI 的 Font
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontName("微软雅黑");
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int r = 1;
            for (List<String> row : rows) {
                Row dataRow = sheet.createRow(r++);
                for (int i = 0; i < row.size(); i++) {
                    dataRow.createCell(i).setCellValue(row.get(i));
                }
            }

            for (int i = 0; i < headers.size(); i++) {
                sheet.setColumnWidth(i, 22 * 256);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 生成一个 Markdown 文本文件。 */
    public byte[] markdown(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
