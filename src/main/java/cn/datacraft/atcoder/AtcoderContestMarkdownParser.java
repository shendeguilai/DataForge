package cn.datacraft.atcoder;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class AtcoderContestMarkdownParser {
    private static final int MAX_CHARACTERS = 1_000_000;
    private static final Pattern TASK_HEADER = Pattern.compile("^#\\s+([A-Z])\\s*[-–—]\\s*(.+?)\\s*$");
    private static final Pattern CONTEST_METADATA = Pattern.compile(
            "(?im)^-\\s*Contest:\\s*`?([a-z0-9][a-z0-9_-]{0,63})`?\\s*$");
    private static final Pattern PROBLEM_METADATA = Pattern.compile(
            "(?im)^-\\s*Problem:\\s*`?([a-z0-9][a-z0-9_-]{0,127})`?\\s*$");
    private static final Pattern SAMPLE_INPUT = Pattern.compile("^##\\s+Sample Input\\s+(\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SAMPLE_OUTPUT = Pattern.compile("^##\\s+Sample Output\\s+(\\d+)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");

    ParsedContestMarkdown parse(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("请选择要上传的 Markdown 文件");
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!name.isBlank() && !name.endsWith(".md") && !name.endsWith(".markdown")) {
            throw new IllegalArgumentException("只支持 .md 或 .markdown 文件");
        }
        String markdown = decodeUtf8(bytes).replace("\r\n", "\n").replace('\r', '\n');
        if (!markdown.isEmpty() && markdown.charAt(0) == '\uFEFF') markdown = markdown.substring(1);
        if (markdown.length() > MAX_CHARACTERS) throw new IllegalArgumentException("Markdown 内容不能超过 100 万字符");

        List<ParsedProblem> problems = splitProblems(markdown);
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("没有识别到形如“# A - 题目名称”的一级标题");
        }
        long uniqueLabels = problems.stream().map(ParsedProblem::label).distinct().count();
        if (uniqueLabels != problems.size()) throw new IllegalArgumentException("Markdown 中出现了重复的题目标号");
        Set<String> contestIds = new LinkedHashSet<>();
        for (ParsedProblem problem : problems) contestIds.add(problem.contestId());
        if (contestIds.size() != 1) throw new IllegalArgumentException("Markdown 各题的 Contest 信息不一致");
        return new ParsedContestMarkdown(contestIds.iterator().next(), safeFilename(filename), List.copyOf(problems));
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "contest.md" : filename.replace('\\', '/').strip();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        if (value.isBlank()) value = "contest.md";
        return value.length() > 255 ? value.substring(value.length() - 255) : value;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Markdown 必须使用 UTF-8 编码", ex);
        }
    }

    private static List<ParsedProblem> splitProblems(String markdown) {
        List<ParsedProblem> problems = new ArrayList<>();
        ProblemBuilder current = null;
        FenceState fence = null;
        for (String line : markdown.split("\\n", -1)) {
            Matcher fenceMatcher = FENCE.matcher(line);
            if (fence == null) {
                Matcher header = TASK_HEADER.matcher(line);
                if (header.matches()) {
                    if (current != null) problems.add(current.finish());
                    current = new ProblemBuilder(header.group(1), header.group(2).strip());
                }
                if (current != null) current.append(line);
                if (fenceMatcher.matches()) {
                    fence = new FenceState(fenceMatcher.group(1).charAt(0), fenceMatcher.group(1).length());
                }
            } else {
                if (current != null) current.append(line);
                if (isClosingFence(line, fence)) fence = null;
            }
        }
        if (fence != null) throw new IllegalArgumentException("Markdown 中存在未闭合的代码块");
        if (current != null) problems.add(current.finish());
        return problems;
    }

    private static boolean isClosingFence(String line, FenceState state) {
        String stripped = line.strip();
        if (stripped.length() < state.length()) return false;
        for (int index = 0; index < stripped.length(); index++) {
            if (stripped.charAt(index) != state.marker()) return false;
        }
        return true;
    }

    private static String requiredMetadata(Pattern pattern, String markdown, String label, String name) {
        Matcher matcher = pattern.matcher(markdown);
        if (!matcher.find()) throw new IllegalArgumentException("题目 " + label + " 缺少 " + name + " 元数据");
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    private static int samplePairCount(String markdown, String label) {
        Set<String> inputs = sectionNumbers(markdown, SAMPLE_INPUT);
        Set<String> outputs = sectionNumbers(markdown, SAMPLE_OUTPUT);
        if (!inputs.equals(outputs)) throw new IllegalArgumentException("题目 " + label + " 的样例输入输出编号不匹配");
        return inputs.size();
    }

    private static Set<String> sectionNumbers(String markdown, Pattern heading) {
        Set<String> values = new LinkedHashSet<>();
        FenceState fence = null;
        for (String line : markdown.split("\\n", -1)) {
            Matcher fenceMatcher = FENCE.matcher(line);
            if (fence == null) {
                Matcher matcher = heading.matcher(line);
                if (matcher.matches()) values.add(matcher.group(1));
                if (fenceMatcher.matches()) {
                    fence = new FenceState(fenceMatcher.group(1).charAt(0), fenceMatcher.group(1).length());
                }
            } else if (isClosingFence(line, fence)) {
                fence = null;
            }
        }
        return values;
    }

    record ParsedContestMarkdown(String contestId, String filename, List<ParsedProblem> problems) {}

    record ParsedProblem(String label, String title, String contestId, String problemId,
                         String sourceMarkdown, int samplePairCount) {}

    private record FenceState(char marker, int length) {}

    private static final class ProblemBuilder {
        private final String label;
        private final String title;
        private final StringBuilder markdown = new StringBuilder();

        private ProblemBuilder(String label, String title) {
            this.label = label;
            this.title = title;
        }

        private void append(String line) {
            if (!markdown.isEmpty()) markdown.append('\n');
            markdown.append(line);
        }

        private ParsedProblem finish() {
            String source = markdown.toString().strip();
            if (source.length() < 80) throw new IllegalArgumentException("题目 " + label + " 的 Markdown 内容过少");
            String contestId = requiredMetadata(CONTEST_METADATA, source, label, "Contest");
            String problemId = requiredMetadata(PROBLEM_METADATA, source, label, "Problem");
            return new ParsedProblem(label, title, contestId, problemId, source,
                    samplePairCount(source, label));
        }
    }
}
