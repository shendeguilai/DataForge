package cn.datacraft.atcoder;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
        for (ProtectedFragment fragment : fragments) {
            if (occurrences(restored, fragment.beginToken()) != 1
                    || occurrences(restored, fragment.endToken()) != 1) {
                throw new IllegalStateException("AI 未完整保留公式、代码或样例占位符，请重试该题");
            }
            int begin = restored.indexOf(fragment.beginToken());
            int end = restored.indexOf(fragment.endToken(), begin + fragment.beginToken().length());
            if (end < begin) {
                throw new IllegalStateException("AI 改变了公式、代码或样例占位符顺序，请重试该题");
            }
            restored = restored.substring(0, begin) + fragment.html()
                    + restored.substring(end + fragment.endToken().length());
        }
        return restored;
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
