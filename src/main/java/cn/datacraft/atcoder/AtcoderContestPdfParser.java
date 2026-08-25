package cn.datacraft.atcoder;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class AtcoderContestPdfParser {
    private static final int MAX_PAGES = 100;
    private static final int MAX_EXTRACTED_CHARACTERS = 500_000;
    private static final Pattern TASK_HEADER = Pattern.compile("^\\s*([A-Z])\\s*[-–—]\\s*(.+?)\\s*$");
    private static final Pattern PRIVATE_USE = Pattern.compile("[\\uE000-\\uF8FF]");
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("\\n{3,}");

    ParsedContestPdf parse(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("请选择要上传的 PDF 文件");
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!name.isBlank() && !name.endsWith(".pdf")) throw new IllegalArgumentException("只支持 PDF 文件");
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) throw new IllegalArgumentException("PDF 没有可读取的页面");
            if (pageCount > MAX_PAGES) throw new IllegalArgumentException("PDF 页数不能超过 " + MAX_PAGES + " 页");

            List<ParsedProblem> problems = new ArrayList<>();
            ProblemBuilder current = null;
            int extractedCharacters = 0;
            for (int page = 1; page <= pageCount; page++) {
                String pageText = extractPage(document, page);
                extractedCharacters += pageText.length();
                if (extractedCharacters > MAX_EXTRACTED_CHARACTERS) {
                    throw new IllegalArgumentException("PDF 可提取文字过多，请拆分后上传");
                }
                Header header = findHeader(pageText);
                if (header != null) {
                    if (current != null) problems.add(current.finish(page - 1));
                    current = new ProblemBuilder(header.label(), header.title(), page);
                    pageText = removeHeaderLine(pageText, header.line());
                }
                if (current != null && !pageText.isBlank()) current.append(pageText);
            }
            if (current != null) problems.add(current.finish(pageCount));

            if (extractedCharacters < 200) {
                throw new IllegalArgumentException("PDF 几乎没有可提取文字，可能是扫描件；当前版本请使用带文本层的 PDF");
            }
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("没有识别到形如“A - 题目名称”的题目标题，请确认这是整场 AtCoder 题面 PDF");
            }
            long uniqueLabels = problems.stream().map(ParsedProblem::label).distinct().count();
            if (uniqueLabels != problems.size()) throw new IllegalArgumentException("PDF 中出现了重复的题目标号");
            return new ParsedContestPdf(pageCount, List.copyOf(problems));
        } catch (IOException ex) {
            throw new IllegalArgumentException("PDF 读取失败，请确认文件未损坏且没有密码保护", ex);
        }
    }

    private static String extractPage(PDDocument document, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return cleanText(stripper.getText(document));
    }

    private static String cleanText(String raw) {
        StringBuilder cleaned = new StringBuilder();
        String normalized = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if (value == '\u0000') cleaned.append("fi");
            else if (value == '\n' || value == '\t' || value >= 0x20) cleaned.append(value);
        }
        String withoutIcons = PRIVATE_USE.matcher(cleaned).replaceAll("");
        List<String> lines = withoutIcons.lines().map(String::stripTrailing).toList();
        return EXCESSIVE_BLANK_LINES.matcher(String.join("\n", lines).strip()).replaceAll("\n\n");
    }

    private static Header findHeader(String pageText) {
        String[] lines = pageText.split("\\n", -1);
        int inspected = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            Matcher matcher = TASK_HEADER.matcher(line);
            if (matcher.matches()) return new Header(matcher.group(1), matcher.group(2).strip(), line);
            if (++inspected >= 5) break;
        }
        return null;
    }

    private static String removeHeaderLine(String pageText, String headerLine) {
        int index = pageText.indexOf(headerLine);
        if (index < 0) return pageText;
        return (pageText.substring(0, index) + pageText.substring(index + headerLine.length())).strip();
    }

    record ParsedContestPdf(int pageCount, List<ParsedProblem> problems) {}

    record ParsedProblem(String label, String title, int startPage, int endPage, String sourceText) {
        int samplePairCount() {
            Matcher matcher = Pattern.compile("(?im)^\\s*Sample Input\\s+\\d+\\s*$").matcher(sourceText);
            int count = 0;
            while (matcher.find()) count++;
            return count;
        }
    }

    private record Header(String label, String title, String line) {}

    private static final class ProblemBuilder {
        private final String label;
        private final String title;
        private final int startPage;
        private final StringBuilder text = new StringBuilder();

        private ProblemBuilder(String label, String title, int startPage) {
            this.label = label;
            this.title = title;
            this.startPage = startPage;
        }

        private void append(String pageText) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append(pageText);
        }

        private ParsedProblem finish(int endPage) {
            String source = text.toString().strip();
            if (source.length() < 80) throw new IllegalArgumentException("题目 " + label + " 提取到的文字过少，请检查 PDF");
            return new ParsedProblem(label, title, startPage, endPage, source);
        }
    }
}
