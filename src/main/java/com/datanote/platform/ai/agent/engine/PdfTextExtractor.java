package com.datanote.platform.ai.agent.engine;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;

/**
 * PDF → 纯文本提取(Apache PDFBox 2.0.x, 特性B 新增唯一依赖)。
 * 限页数 + 字符数防超大文档爆内存/超长; 加密 PDF 由 PDFBox 抛异常, 调用方按失败处理。
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    public static String extract(Path pdf, int maxPages, int maxChars) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            int pages = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(maxPages > 0 ? Math.min(maxPages, pages) : pages);
            String text = stripper.getText(doc);
            boolean truncated = false;
            if (text != null && text.length() > maxChars) { text = text.substring(0, maxChars); truncated = true; }
            if (maxPages > 0 && pages > maxPages) truncated = true;
            String out = text == null ? "" : text.trim();
            if (truncated) out += "\n…(已截断, 完整请下载文件)";
            return out.isEmpty() ? "(无文本内容, 可能为扫描件/图片型 PDF)" : out;
        }
    }
}
