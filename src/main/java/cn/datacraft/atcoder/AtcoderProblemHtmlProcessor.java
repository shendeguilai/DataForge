package cn.datacraft.atcoder;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class AtcoderProblemHtmlProcessor {
    private static final String BASE_URL = "https://atcoder.jp/";
    private static final String PDF_SOURCE_HEADING = "PDF Extracted Source";
    private static final String MARKDOWN_SOURCE_HEADING = "Markdown Imported Source";
    private static final String PROTECTED_TOKEN_PREFIX = "__DATAFORGE_ATCODER_PROTECTED_";
    private static final Pattern PROTECTED_TOKEN = Pattern.compile(
            "__DATAFORGE_ATCODER_PROTECTED_(?:BEGIN|END)_\\d{4}__");
    private static final Pattern MANUAL_SECTION = Pattern.compile("^\\s*【([^】]+)】\\s*$");
    private static final Pattern SAMPLE_SECTION = Pattern.compile("样例(?:输入|输出)\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MANUAL_HEADINGS = Set.of("题目描述", "输入格式", "输出格式", "说明", "数据范围");
    private static final Safelist STATEMENT_TAGS = new Safelist()
            .addTags("div", "section", "p", "h2", "h3", "h4", "ul", "ol", "li",
                    "pre", "code", "var", "span", "strong", "em", "b", "i", "br", "hr",
                    "table", "thead", "tbody", "tfoot", "tr", "th", "td", "details", "summary",
                    "sup", "sub", "ruby", "rt", "img", "a")
            .addAttributes("a", "href")
            .addAttributes("img", "src", "alt", "width", "height")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(false);
    private final Parser markdownParser;
    private final HtmlRenderer markdownRenderer;

    AtcoderProblemHtmlProcessor() {
        List<Extension> extensions = List.of(AutolinkExtension.create(), TablesExtension.create());
        markdownParser = Parser.builder().extensions(extensions).build();
        markdownRenderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
    }

    String extractEnglish(String pageHtml) {
        Document page = Jsoup.parse(pageHtml == null ? "" : pageHtml, BASE_URL);
        Element english = page.selectFirst("#task-statement .lang-en");
        if (english == null || english.text().isBlank()) {
            throw new IllegalStateException("英文题面尚未发布，请稍后重试");
        }
        return cleanFragment(english.html());
    }

    TranslationInput prepareTranslationInput(String sourceHtml) {
        Document prompt = fragment(sourceHtml);
        List<ProtectedFragment> fragments = new ArrayList<>();
        protect(prompt, fragments, "pre");
        protect(prompt, fragments, "a");
        protect(prompt, fragments, "code");
        protect(prompt, fragments, "var");
        protect(prompt, fragments, "img");
        return new TranslationInput(prompt.body().html(), List.copyOf(fragments));
    }

    String prepareTranslation(String sourceHtml, TranslationInput input, String translatedOutput) {
        String output = restoreProtectedFragments(stripMarkdownFence(translatedOutput), input.fragments());
        return prepareEditedTranslation(sourceHtml, output);
    }

    String prepareDraft(TranslationInput input, String translatedOutput) {
        String output = restoreProtectedFragmentsBestEffort(stripMarkdownFence(translatedOutput), input.fragments());
        String draft = cleanFragment(PROTECTED_TOKEN.matcher(output).replaceAll(""));
        if (fragment(draft).body().text().isBlank()) return null;
        return draft;
    }

    String prepareEditedTranslation(String sourceHtml, String editedHtml) {
        if (editedHtml == null || editedHtml.isBlank()) {
            throw new IllegalArgumentException("译文不能为空");
        }
        String translated = cleanFragment(editedHtml);
        Document source = fragment(sourceHtml);
        Document target = fragment(translated);
        restoreElements(source, target, "pre");
        restoreElements(source, target, "code");
        restoreElements(source, target, "var");
        restoreImagesAndLinks(source, target);
        if (target.body().text().isBlank()) throw new IllegalStateException("AI 未返回有效译文");
        return target.body().html();
    }

    String prepareManualTranslation(String editedHtml) {
        if (editedHtml == null || editedHtml.isBlank()) throw new IllegalArgumentException("译文不能为空");
        String translated = cleanFragment(editedHtml);
        if (fragment(translated).body().text().isBlank()) throw new IllegalArgumentException("译文不能为空");
        return translated;
    }

    String preparePdfSource(String label, String title, int startPage, int endPage, String sourceText) {
        Document output = fragment("");
        Element section = output.body().appendElement("section");
        section.appendElement("h3").text(PDF_SOURCE_HEADING + " / " + label + " - " + title);
        section.appendElement("p").text(startPage == endPage
                ? "Source page: " + startPage
                : "Source pages: " + startPage + "-" + endPage);
        section.appendElement("pre").text(sourceText == null ? "" : sourceText.strip());
        return prepareManualTranslation(output.body().html());
    }

    String preparePdfDraft(String translatedOutput) {
        String draft = cleanFragment(stripMarkdownFence(translatedOutput));
        if (fragment(draft).body().text().isBlank()) return null;
        return draft;
    }

    String preparePdfTranslation(String translatedOutput, int samplePairCount) {
        String translated = preparePdfDraft(translatedOutput);
        if (translated == null) throw new IllegalStateException("AI 未返回有效的 PDF 译文");
        Document document = fragment(translated);
        List<String> headings = document.select("h2,h3,h4").eachText();
        if (headings.stream().noneMatch(heading -> heading.contains("题目描述"))) {
            throw new IllegalStateException("AI 返回的 PDF 译文缺少“题目描述”段落");
        }
        if (headings.stream().noneMatch(heading -> heading.contains("输入"))
                || headings.stream().noneMatch(heading -> heading.contains("输出"))) {
            throw new IllegalStateException("AI 返回的 PDF 译文缺少输入或输出段落");
        }
        int requiredSamples = Math.max(0, samplePairCount * 2);
        if (document.select("pre").size() < requiredSamples) {
            throw new IllegalStateException("AI 未完整保留 PDF 中的样例输入输出，请重试该题");
        }
        return translated;
    }

    boolean isPdfSource(String sourceHtml) {
        if (sourceHtml == null || sourceHtml.isBlank()) return false;
        Element heading = fragment(sourceHtml).selectFirst("h2,h3,h4");
        return heading != null && heading.text().startsWith(PDF_SOURCE_HEADING + " /");
    }

    String prepareMarkdownSource(AtcoderContestMarkdownParser.ParsedProblem problem, String filename) {
        Document output = fragment("");
        Element section = output.body().appendElement("section");
        section.appendElement("h3").text(MARKDOWN_SOURCE_HEADING + " / "
                + problem.label() + " - " + problem.title());
        section.appendElement("p").text("Imported file: " + safeImportedFilename(filename));
        section.appendElement("p").text("Contest: " + problem.contestId()
                + " / Problem: " + problem.problemId());
        section.appendElement("pre").text(problem.sourceMarkdown());
        return prepareManualTranslation(output.body().html());
    }

    String renderMarkdownProblem(String sourceMarkdown) {
        if (sourceMarkdown == null || sourceMarkdown.isBlank()) {
            throw new IllegalArgumentException("Markdown 题面不能为空");
        }
        String rendered = markdownRenderer.render(markdownParser.parse(sourceMarkdown));
        Document document = fragment(rendered);
        Element title = document.selectFirst("body > h1");
        if (title != null) title.remove();
        Element metadata = document.selectFirst("body > ul");
        if (metadata != null && metadata.text().contains("Contest:")
                && metadata.text().contains("Problem:")) {
            metadata.remove();
        }
        convertInputFormatBlocks(document);
        wrapMarkdownMath(document);
        String cleaned = cleanFragment(document.body().html());
        if (fragment(cleaned).body().text().isBlank()) {
            throw new IllegalArgumentException("Markdown 没有可翻译的题面内容");
        }
        return cleaned;
    }

    String prepareMarkdownTranslation(String renderedSourceHtml, TranslationInput input,
                                      String translatedOutput, int samplePairCount) {
        String translated;
        try {
            translated = prepareTranslation(renderedSourceHtml, input, translatedOutput);
        } catch (IllegalStateException markerError) {
            try {
                String withoutMarkers = PROTECTED_TOKEN.matcher(
                        stripMarkdownFence(translatedOutput)).replaceAll("");
                translated = prepareEditedTranslation(renderedSourceHtml, withoutMarkers);
            } catch (RuntimeException structureError) {
                throw new IllegalStateException(markerError.getMessage()
                        + "；HTML 结构恢复也失败：" + structureError.getMessage(), structureError);
            }
        }
        Document document = fragment(translated);
        List<String> headings = document.select("h2,h3,h4").eachText();
        if (headings.stream().noneMatch(heading -> heading.contains("题目描述"))) {
            throw new IllegalStateException("AI 返回的 Markdown 译文缺少“题目描述”段落");
        }
        if (headings.stream().noneMatch(heading -> heading.contains("输入"))
                || headings.stream().noneMatch(heading -> heading.contains("输出"))) {
            throw new IllegalStateException("AI 返回的 Markdown 译文缺少输入或输出段落");
        }
        int requiredSamples = Math.max(0, samplePairCount * 2);
        if (document.select("pre").size() < requiredSamples) {
            throw new IllegalStateException("AI 未完整保留 Markdown 中的样例输入输出，请重试该题");
        }
        return translated;
    }

    String markdownSourceText(String sourceHtml) {
        if (!isMarkdownSource(sourceHtml)) throw new IllegalArgumentException("当前题目不是 Markdown 导入来源");
        Element source = fragment(sourceHtml).selectFirst("pre");
        if (source == null || source.wholeText().isBlank()) {
            throw new IllegalStateException("Markdown 原文不存在，无法重新翻译");
        }
        return source.wholeText();
    }

    String markdownSourceFilename(String sourceHtml) {
        if (!isMarkdownSource(sourceHtml)) return null;
        for (Element paragraph : fragment(sourceHtml).select("p")) {
            String text = paragraph.text();
            if (text.startsWith("Imported file: ")) return text.substring("Imported file: ".length()).strip();
        }
        return null;
    }

    boolean isMarkdownSource(String sourceHtml) {
        if (sourceHtml == null || sourceHtml.isBlank()) return false;
        Element heading = fragment(sourceHtml).selectFirst("h2,h3,h4");
        return heading != null && heading.text().startsWith(MARKDOWN_SOURCE_HEADING + " /");
    }

    boolean isImportedSource(String sourceHtml) {
        return isPdfSource(sourceHtml) || isMarkdownSource(sourceHtml);
    }

    private static String safeImportedFilename(String filename) {
        String value = filename == null ? "contest.md" : filename.replace('\\', '/').strip();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        return value.isBlank() ? "contest.md" : value;
    }

    private static void convertInputFormatBlocks(Document document) {
        for (Element heading : document.select("h2,h3")) {
            String title = heading.text().strip().toLowerCase(Locale.ROOT);
            if (!title.equals("input") && !title.equals("input format")) continue;
            Element current = heading.nextElementSibling();
            while (current != null && !current.tagName().matches("h1|h2")) {
                Element next = current.nextElementSibling();
                if (current.tagName().equals("pre")) renderInputFormatBlock(current);
                current = next;
            }
        }
    }

    private static void renderInputFormatBlock(Element block) {
        String source = block.wholeText().replace("\r\n", "\n").replace('\r', '\n');
        block.empty();
        String[] lines = source.split("\\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) lineCount--;
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            List<String> tokens = splitInputFormatTokens(lines[lineIndex]);
            for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
                if (tokenIndex > 0) block.appendChild(new TextNode("   "));
                block.appendElement("var").text(tokens.get(tokenIndex));
            }
            if (lineIndex + 1 < lineCount) block.appendElement("br");
        }
    }

    private static List<String> splitInputFormatTokens(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int braceDepth = 0;
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value == '{' && (index == 0 || line.charAt(index - 1) != '\\')) braceDepth++;
            if (value == '}' && braceDepth > 0 && (index == 0 || line.charAt(index - 1) != '\\')) braceDepth--;
            boolean separator = Character.isWhitespace(value) && braceDepth == 0
                    && (index == 0 || line.charAt(index - 1) != '\\');
            if (separator) {
                if (!token.isEmpty()) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(value);
            }
        }
        if (!token.isEmpty()) tokens.add(token.toString());
        return tokens;
    }

    private static void wrapMarkdownMath(Document document) {
        for (Element element : new ArrayList<>(document.body().getAllElements())) {
            if (element.is("pre,code,var")) continue;
            for (TextNode node : new ArrayList<>(element.textNodes())) wrapMarkdownMath(node);
        }
    }

    private static void wrapMarkdownMath(TextNode node) {
        String value = node.getWholeText();
        int cursor = 0;
        boolean changed = false;
        List<org.jsoup.nodes.Node> replacements = new ArrayList<>();
        while (cursor < value.length()) {
            int start = nextUnescapedDollar(value, cursor);
            if (start < 0) break;
            int delimiterLength = start + 1 < value.length() && value.charAt(start + 1) == '$' ? 2 : 1;
            int end = findClosingDollar(value, start + delimiterLength, delimiterLength);
            if (end < 0) break;
            if (start > cursor) replacements.add(new TextNode(value.substring(cursor, start)));
            replacements.add(new Element("var").text(value.substring(start + delimiterLength, end)));
            cursor = end + delimiterLength;
            changed = true;
        }
        if (!changed) return;
        if (cursor < value.length()) replacements.add(new TextNode(value.substring(cursor)));
        for (org.jsoup.nodes.Node replacement : replacements) node.before(replacement);
        node.remove();
    }

    private static int nextUnescapedDollar(String value, int fromIndex) {
        for (int index = fromIndex; index < value.length(); index++) {
            if (value.charAt(index) == '$' && (index == 0 || value.charAt(index - 1) != '\\')) return index;
        }
        return -1;
    }

    private static int findClosingDollar(String value, int fromIndex, int delimiterLength) {
        for (int index = fromIndex; index <= value.length() - delimiterLength; index++) {
            if (value.charAt(index) != '$' || (index > 0 && value.charAt(index - 1) == '\\')) continue;
            if (delimiterLength == 1 && index + 1 < value.length() && value.charAt(index + 1) == '$') continue;
            if (delimiterLength == 2 && value.charAt(index + 1) != '$') continue;
            return index;
        }
        return -1;
    }

    String prepareStructuredManualTranslation(String rawText) {
        String text = rawText == null ? "" : rawText.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isBlank()) throw new IllegalArgumentException("手动题面不能为空");
        List<ManualSection> sections = parseManualSections(text);
        if (sections.isEmpty() || sections.stream().noneMatch(section -> "题目描述".equals(section.heading()))) {
            throw new IllegalArgumentException("手动题面必须包含【题目描述】段落");
        }
        Document output = fragment("");
        for (ManualSection item : sections) {
            Element section = output.body().appendElement("section");
            section.appendElement("h3").text(item.heading());
            if (SAMPLE_SECTION.matcher(item.heading()).matches()) {
                section.appendElement("pre").text(item.content().strip());
            } else {
                appendParagraphs(section, item.content());
            }
        }
        return prepareManualTranslation(output.body().html());
    }

    private static List<ManualSection> parseManualSections(String text) {
        List<ManualSection> sections = new ArrayList<>();
        String heading = null;
        StringBuilder content = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            Matcher marker = MANUAL_SECTION.matcher(line);
            if (marker.matches()) {
                if (heading != null) sections.add(manualSection(heading, content));
                heading = marker.group(1).trim();
                if (!MANUAL_HEADINGS.contains(heading) && !SAMPLE_SECTION.matcher(heading).matches()) {
                    throw new IllegalArgumentException("不支持的手动题面段落：【" + heading + "】");
                }
                content.setLength(0);
            } else if (heading == null) {
                if (!line.isBlank()) throw new IllegalArgumentException("手动题面必须从【题目描述】开始");
            } else {
                if (!content.isEmpty()) content.append('\n');
                content.append(line);
            }
        }
        if (heading != null) sections.add(manualSection(heading, content));
        return sections;
    }

    private static ManualSection manualSection(String heading, StringBuilder content) {
        String value = content.toString().strip();
        if (value.isBlank()) throw new IllegalArgumentException("手动题面段落不能为空：【" + heading + "】");
        return new ManualSection(heading, value);
    }

    private static void appendParagraphs(Element section, String content) {
        for (String block : content.split("\\n\\s*\\n")) {
            Element paragraph = section.appendElement("p");
            String[] lines = block.strip().split("\\n", -1);
            for (int index = 0; index < lines.length; index++) {
                if (index > 0) paragraph.appendElement("br");
                paragraph.appendText(lines[index]);
            }
        }
    }

    private static String restoreProtectedFragmentsBestEffort(String output, List<ProtectedFragment> fragments) {
        String restored = output;
        for (ProtectedFragment fragment : fragments) {
            int begin = restored.indexOf(fragment.beginToken());
            int end = begin < 0 ? -1 : restored.indexOf(
                    fragment.endToken(), begin + fragment.beginToken().length());
            if (begin >= 0 && end >= begin) {
                restored = restored.substring(0, begin) + fragment.html()
                        + restored.substring(end + fragment.endToken().length());
            }
        }
        return restored;
    }

    private static void protect(Document prompt, List<ProtectedFragment> fragments, String selector) {
        for (Element element : new ArrayList<>(prompt.select(selector))) {
            if (hasProtectedAncestor(element, fragments)) continue;
            String index = String.format("%04d", fragments.size());
            String beginToken = PROTECTED_TOKEN_PREFIX + "BEGIN_" + index + "__";
            String endToken = PROTECTED_TOKEN_PREFIX + "END_" + index + "__";
            fragments.add(new ProtectedFragment(beginToken, endToken, element.outerHtml(), element));
            element.before(new org.jsoup.nodes.TextNode(beginToken));
            element.after(new org.jsoup.nodes.TextNode(endToken));
        }
    }

    private static boolean hasProtectedAncestor(Element element, List<ProtectedFragment> fragments) {
        for (Element parent : element.parents()) {
            if (fragments.stream().anyMatch(fragment -> fragment.element() == parent)) return true;
        }
        return false;
    }

    private static String restoreProtectedFragments(String output, List<ProtectedFragment> fragments) {
        String restored = output;
        for (int index = 0; index < fragments.size(); index++) {
            ProtectedFragment fragment = fragments.get(index);
            int beginCount = occurrences(restored, fragment.beginToken());
            int endCount = occurrences(restored, fragment.endToken());
            if (beginCount != 1 || endCount != 1) {
                String issue = beginCount == 0 || endCount == 0 ? "标记被遗漏" : "标记被重复";
                throw new IllegalStateException("AI 未完整保留公式、代码或样例占位符：第 "
                        + (index + 1) + " 个" + protectedDescription(fragment.element())
                        + issue + "（开始标记 " + beginCount + " 次，结束标记 " + endCount + " 次），请重试该题");
            }
            int begin = restored.indexOf(fragment.beginToken());
            int end = restored.indexOf(fragment.endToken(), begin + fragment.beginToken().length());
            if (end < begin) {
                throw new IllegalStateException("AI 改变了公式、代码或样例占位符顺序：第 "
                        + (index + 1) + " 个" + protectedDescription(fragment.element()) + "顺序错误，请重试该题");
            }
            restored = restored.substring(0, begin) + fragment.html()
                    + restored.substring(end + fragment.endToken().length());
        }
        return restored;
    }

    private static String protectedDescription(Element element) {
        String type = switch (element.tagName()) {
            case "pre" -> "输入格式或样例代码块";
            case "var" -> "公式";
            case "code" -> "行内代码";
            case "a" -> "链接";
            case "img" -> "图片";
            default -> "受保护内容";
        };
        String preview = element.text().replaceAll("\\s+", " ").strip();
        if (preview.length() > 36) preview = preview.substring(0, 36) + "…";
        return preview.isBlank() ? type + "" : type + "“" + preview + "”";
    }

    private static int occurrences(String source, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }

    private static String cleanFragment(String html) {
        Document dirty = fragment(html);
        absolutizeResources(dirty);
        Document clean = new Cleaner(STATEMENT_TAGS).clean(dirty);
        clean.outputSettings(outputSettings());
        filterResources(clean);
        return clean.body().html();
    }

    private static Document fragment(String html) {
        Document document = Jsoup.parseBodyFragment(html == null ? "" : html, BASE_URL);
        document.outputSettings(outputSettings());
        return document;
    }

    private static Document.OutputSettings outputSettings() {
        return new Document.OutputSettings().prettyPrint(false);
    }

    private static void absolutizeResources(Document document) {
        document.select("a[href]").forEach(link -> link.attr("href", link.absUrl("href")));
        document.select("img[src]").forEach(image -> image.attr("src", image.absUrl("src")));
    }

    private static void filterResources(Document document) {
        document.select("img[src]").forEach(image -> {
            if (!isAtcoderResource(image.attr("src"))) image.remove();
            else image.attr("loading", "lazy").attr("referrerpolicy", "no-referrer");
        });
        document.select("a[href]").forEach(link -> {
            if (!isAtcoderResource(link.attr("href"))) link.removeAttr("href");
            else link.attr("target", "_blank").attr("rel", "noreferrer");
        });
    }

    private static boolean isAtcoderResource(String value) {
        try {
            String host = URI.create(value).getHost();
            return host != null && (host.equalsIgnoreCase("atcoder.jp")
                    || host.toLowerCase(Locale.ROOT).endsWith(".atcoder.jp"));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void restoreElements(Document source, Document target, String selector) {
        List<Element> originals = source.select(selector);
        List<Element> translations = target.select(selector);
        if (originals.size() != translations.size()) {
            throw new IllegalStateException("AI 改变了题面中的公式、代码或样例结构");
        }
        for (int i = 0; i < originals.size(); i++) {
            if (!originals.get(i).tagName().equals(translations.get(i).tagName())) {
                throw new IllegalStateException("AI 改变了题面中的受保护标签");
            }
            translations.get(i).html(originals.get(i).html());
        }
    }

    private static void restoreImagesAndLinks(Document source, Document target) {
        restoreAttribute(source.select("img"), target.select("img"), "src");
        restoreAttribute(source.select("a"), target.select("a"), "href");
    }

    private static void restoreAttribute(List<Element> source, List<Element> target, String attribute) {
        if (source.size() != target.size()) {
            throw new IllegalStateException("AI 改变了题面中的链接或图片结构");
        }
        for (int i = 0; i < source.size(); i++) {
            String value = source.get(i).attr(attribute);
            if (value.isBlank()) target.get(i).removeAttr(attribute);
            else target.get(i).attr(attribute, value);
        }
    }

    private static String stripMarkdownFence(String value) {
        String output = value == null ? "" : value.trim();
        if (output.startsWith("```")) {
            output = output.replaceFirst("^```(?:html)?\\s*", "")
                    .replaceFirst("\\s*```$", "");
        }
        return output;
    }

    record TranslationInput(String html, List<ProtectedFragment> fragments) {}

    private record ManualSection(String heading, String content) {}

    private record ProtectedFragment(String beginToken, String endToken, String html, Element element) {}
}
