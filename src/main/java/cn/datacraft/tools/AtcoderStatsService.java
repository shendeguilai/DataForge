package cn.datacraft.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AtcoderStatsService {
    private static final String HISTORY_URL = "https://atcoder.jp/users/{user}/history/json";
    private static final String CONTESTS_URL = "https://kenkoooo.com/atcoder/resources/contests.json";
    private static final String PROBLEMS_URL = "https://kenkoooo.com/atcoder/resources/problems.json";
    private static final String CONTEST_PROBLEMS_URL = "https://kenkoooo.com/atcoder/resources/contest-problem.json";
    private static final String SUBMISSIONS_URL = "https://kenkoooo.com/atcoder/atcoder-api/v3/user/submissions";
    private static final String STANDINGS_URL = "https://atcoder.jp/contests/{contest}/standings";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(TOKYO);
    private static final Pattern ACCEPTED_TRIED_ROW = Pattern.compile("(?is)<tr[^>]*>.*?Accepted\\s*/\\s*Tried.*?</tr>");
    private static final Pattern ACCEPTED_TRIED_PAIR = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final Duration RESOURCE_CACHE_TTL = Duration.ofMinutes(30);
    private static final int RECENT_CONTEST_LIMIT = 5;

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String atcoderCookie;
    private volatile ResourceCache cachedResources;
    private final Map<String, ContestTotalsCache> contestTotalsCache = new HashMap<>();

    public AtcoderStatsService(ObjectMapper mapper, @Value("${dataforge.atcoder.cookie:}") String atcoderCookie) {
        this.mapper = mapper;
        this.atcoderCookie = atcoderCookie == null ? "" : atcoderCookie.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public AtcoderStatsResponse fetchRecentStats(String username) {
        String normalizedUser = normalizeUsername(username);
        List<HistoryEntry> history = fetchJson(
                HISTORY_URL.replace("{user}", normalizedUser),
                new TypeReference<List<HistoryEntry>>() {}
        );
        if (history.isEmpty()) {
            throw new NoSuchElementException("没有找到该用户的 AtCoder 参赛记录");
        }

        ResourceBundle resources = loadResources();
        List<ContestContext> contests = history.stream()
                .sorted(Comparator.comparing((HistoryEntry item) -> parseInstant(item.endTime)).reversed())
                .limit(RECENT_CONTEST_LIMIT)
                .map(item -> buildContestContext(item, resources))
                .sorted(Comparator.comparing(context -> context.startEpoch))
                .collect(Collectors.toList());

        long fromSecond = contests.stream()
                .mapToLong(context -> context.startEpoch)
                .min()
                .orElse(0L);
        List<SubmissionEntry> submissions = fetchJson(
                UriComponentsBuilder.fromHttpUrl(SUBMISSIONS_URL)
                        .queryParam("user", normalizedUser)
                        .queryParam("from_second", fromSecond)
                        .build()
                        .encode(StandardCharsets.UTF_8)
                        .toUriString(),
                new TypeReference<List<SubmissionEntry>>() {}
        );

        Map<String, List<SubmissionEntry>> submissionsByContest = new HashMap<>();
        for (ContestContext contest : contests) {
            List<SubmissionEntry> inContest = submissions.stream()
                    .filter(submission -> contest.contestId.equals(submission.contestId))
                    .filter(submission -> submission.epochSecond >= contest.startEpoch)
                    .filter(submission -> submission.epochSecond <= contest.endEpoch)
                    .sorted(Comparator.comparingLong(submission -> submission.epochSecond))
                    .collect(Collectors.toList());
            submissionsByContest.put(contest.contestId, inContest);
        }

        AtcoderStatsResponse response = new AtcoderStatsResponse();
        response.username = normalizedUser;
        response.generatedAt = DISPLAY_TIME.format(Instant.now());
        response.contests = contests.stream()
                .sorted(Comparator.comparing((ContestContext context) -> context.endEpoch).reversed())
                .map(contest -> buildContestStats(contest, submissionsByContest.getOrDefault(contest.contestId, List.of())))
                .collect(Collectors.toList());
        return response;
    }

    private ContestContext buildContestContext(HistoryEntry history, ResourceBundle resources) {
        String contestId = toContestId(history.contestScreenName);
        ContestResource contestResource = resources.contestsById.get(contestId);
        long endEpoch = parseInstant(history.endTime).getEpochSecond();
        long startEpoch = contestResource == null ? Math.max(0, endEpoch - Duration.ofHours(2).getSeconds()) : contestResource.startEpochSecond;
        long duration = contestResource == null ? endEpoch - startEpoch : contestResource.durationSecond;
        ContestContext context = new ContestContext();
        context.contestId = contestId;
        context.contestName = choose(history.contestName, contestResource == null ? null : contestResource.title, contestId);
        context.history = history;
        context.startEpoch = startEpoch;
        context.endEpoch = startEpoch + Math.max(0, duration);
        if (context.endEpoch < endEpoch) {
            context.endEpoch = endEpoch;
        }
        context.problems = resources.problemsByContest.getOrDefault(contestId, List.of());
        return context;
    }

    private AtcoderStatsResponse.ContestStats buildContestStats(ContestContext contest, List<SubmissionEntry> submissions) {
        ContestProblemTotals contestTotals = loadContestProblemTotals(contest);
        Map<String, List<SubmissionEntry>> submissionsByProblem = submissions.stream()
                .collect(Collectors.groupingBy(item -> item.problemId, LinkedHashMap::new, Collectors.toList()));
        Map<String, ProblemResource> problemById = contest.problems.stream()
                .collect(Collectors.toMap(problem -> problem.id, problem -> problem, (first, second) -> first, LinkedHashMap::new));

        List<ProblemResource> problems = new ArrayList<>(contest.problems);
        for (SubmissionEntry submission : submissions) {
            if (!problemById.containsKey(submission.problemId)) {
                ProblemResource problem = new ProblemResource();
                problem.id = submission.problemId;
                problem.contestId = submission.contestId;
                problem.problemIndex = submission.problemId;
                problem.title = submission.problemId;
                problem.name = submission.problemId;
                problems.add(problem);
                problemById.put(problem.id, problem);
            }
        }

        Set<String> acceptedProblems = new HashSet<>();
        for (SubmissionEntry submission : submissions) {
            if ("AC".equals(submission.result)) {
                acceptedProblems.add(submission.problemId);
            }
        }

        AtcoderStatsResponse.ContestStats stats = new AtcoderStatsResponse.ContestStats();
        stats.contestId = contest.contestId;
        stats.contestName = contest.contestName;
        stats.contestUrl = "https://atcoder.jp/contests/" + contest.contestId;
        stats.startTime = DISPLAY_TIME.format(Instant.ofEpochSecond(contest.startEpoch));
        stats.endTime = DISPLAY_TIME.format(Instant.ofEpochSecond(contest.endEpoch));
        stats.rated = contest.history.rated;
        stats.place = contest.history.place;
        stats.oldRating = contest.history.oldRating;
        stats.newRating = contest.history.newRating;
        stats.ratingDelta = ratingDelta(contest.history.oldRating, contest.history.newRating);
        stats.performance = contest.history.performance;
        stats.acCount = acceptedProblems.size();
        stats.submissionCount = submissions.size();
        stats.problems = problems.stream()
                .sorted(AtcoderStatsService::compareProblems)
                .map(problem -> buildProblemStats(contest, problem, submissionsByProblem.getOrDefault(problem.id, List.of()), contestTotals))
                .collect(Collectors.toList());
        stats.submissions = submissions.stream()
                .map(submission -> buildSubmissionStats(contest, submission, problemById.get(submission.problemId)))
                .collect(Collectors.toList());
        return stats;
    }

    private AtcoderStatsResponse.ProblemStats buildProblemStats(ContestContext contest, ProblemResource problem, List<SubmissionEntry> submissions, ContestProblemTotals contestTotals) {
        ProblemTotals totals = contestTotals.totals.get(problem.id);
        AtcoderStatsResponse.ProblemStats stats = new AtcoderStatsResponse.ProblemStats();
        stats.problemId = problem.id;
        stats.problemIndex = problem.problemIndex;
        stats.title = choose(problem.title, problem.name, problem.id);
        stats.url = "https://atcoder.jp/contests/" + contest.contestId + "/tasks/" + problem.id;
        stats.accepted = submissions.stream().anyMatch(submission -> "AC".equals(submission.result));
        stats.submissionCount = submissions.size();
        stats.globalStatsAvailable = contestTotals.available;
        stats.globalAcceptedCount = totals == null ? 0 : totals.acceptedCount;
        stats.globalSubmissionUserCount = totals == null ? 0 : totals.submittedCount;
        submissions.stream()
                .filter(submission -> "AC".equals(submission.result))
                .min(Comparator.comparingLong(submission -> submission.epochSecond))
                .ifPresent(submission -> {
                    stats.firstAcceptedAt = DISPLAY_TIME.format(Instant.ofEpochSecond(submission.epochSecond));
                    stats.firstAcceptedElapsed = formatElapsed(submission.epochSecond - contest.startEpoch);
                });
        return stats;
    }

    private ContestProblemTotals loadContestProblemTotals(ContestContext contest) {
        Instant now = Instant.now();
        synchronized (contestTotalsCache) {
            ContestTotalsCache cached = contestTotalsCache.get(contest.contestId);
            if (cached != null && cached.loadedAt.plus(RESOURCE_CACHE_TTL).isAfter(now)) {
                return cached.totals;
            }
        }

        if (atcoderCookie.isBlank()) {
            ContestProblemTotals totals = new ContestProblemTotals(Map.of(), false);
            synchronized (contestTotalsCache) {
                contestTotalsCache.put(contest.contestId, new ContestTotalsCache(totals, now));
            }
            return totals;
        }

        ContestProblemTotals totals;
        try {
            totals = new ContestProblemTotals(fetchContestProblemTotals(contest), true);
        } catch (IllegalStateException ex) {
            totals = new ContestProblemTotals(Map.of(), false);
        }
        synchronized (contestTotalsCache) {
            contestTotalsCache.put(contest.contestId, new ContestTotalsCache(totals, now));
        }
        return totals;
    }

    private Map<String, ProblemTotals> fetchContestProblemTotals(ContestContext contest) {
        String html = fetchText(
                STANDINGS_URL.replace("{contest}", contest.contestId),
                Duration.ofSeconds(8),
                "text/html"
        );
        if (html.contains("<title>Sign In - AtCoder</title>")) {
            throw new IllegalStateException("AtCoder standings 需要登录 Cookie");
        }

        Matcher rowMatcher = ACCEPTED_TRIED_ROW.matcher(html);
        if (!rowMatcher.find()) {
            throw new IllegalStateException("AtCoder standings 未找到 Accepted / Tried 汇总");
        }

        Map<String, ProblemTotals> totals = new HashMap<>();
        Matcher pairMatcher = ACCEPTED_TRIED_PAIR.matcher(rowMatcher.group());
        int index = 0;
        while (pairMatcher.find() && index < contest.problems.size()) {
            ProblemResource problem = contest.problems.get(index++);
            ProblemTotals problemTotals = new ProblemTotals();
            problemTotals.acceptedCount = Integer.parseInt(pairMatcher.group(1));
            problemTotals.submittedCount = Integer.parseInt(pairMatcher.group(2));
            totals.put(problem.id, problemTotals);
        }
        return totals;
    }

    private AtcoderStatsResponse.SubmissionStats buildSubmissionStats(ContestContext contest, SubmissionEntry submission, ProblemResource problem) {
        AtcoderStatsResponse.SubmissionStats stats = new AtcoderStatsResponse.SubmissionStats();
        stats.id = submission.id;
        stats.problemId = submission.problemId;
        stats.problemIndex = problem == null ? submission.problemId : problem.problemIndex;
        stats.problemTitle = problem == null ? submission.problemId : choose(problem.title, problem.name, problem.id);
        stats.submittedAt = DISPLAY_TIME.format(Instant.ofEpochSecond(submission.epochSecond));
        stats.elapsedSeconds = submission.epochSecond - contest.startEpoch;
        stats.elapsedText = formatElapsed(stats.elapsedSeconds);
        stats.result = submission.result;
        stats.language = submission.language;
        stats.point = submission.point;
        stats.url = "https://atcoder.jp/contests/" + contest.contestId + "/submissions/" + submission.id;
        return stats;
    }

    private ResourceBundle loadResources() {
        ResourceCache cache = cachedResources;
        Instant now = Instant.now();
        if (cache != null && cache.loadedAt.plus(RESOURCE_CACHE_TTL).isAfter(now)) {
            return cache.resources;
        }

        List<ContestResource> contests = fetchJson(CONTESTS_URL, new TypeReference<List<ContestResource>>() {});
        List<ProblemResource> problems = fetchJson(PROBLEMS_URL, new TypeReference<List<ProblemResource>>() {});
        List<ContestProblemResource> contestProblems = fetchJson(CONTEST_PROBLEMS_URL, new TypeReference<List<ContestProblemResource>>() {});
        ResourceBundle bundle = new ResourceBundle();
        bundle.contestsById = contests.stream()
                .collect(Collectors.toMap(contest -> contest.id, contest -> contest, (first, second) -> first));
        Map<String, ProblemResource> detailsByContestProblem = problems.stream()
                .filter(problem -> problem.id != null && problem.contestId != null)
                .collect(Collectors.toMap(problem -> problem.contestId + "\n" + problem.id, problem -> problem, (first, second) -> first));
        bundle.problemsByContest = contestProblems.stream()
                .filter(problem -> problem.contestId != null && problem.problemId != null)
                .map(problem -> mergeProblem(problem, detailsByContestProblem.get(problem.contestId + "\n" + problem.problemId)))
                .collect(Collectors.groupingBy(problem -> problem.contestId, Collectors.collectingAndThen(Collectors.toList(), list -> {
                    list.sort(AtcoderStatsService::compareProblems);
                    return list;
                })));
        cachedResources = new ResourceCache(bundle, now);
        return bundle;
    }

    private static ProblemResource mergeProblem(ContestProblemResource contestProblem, ProblemResource detail) {
        ProblemResource problem = new ProblemResource();
        problem.id = contestProblem.problemId;
        problem.contestId = contestProblem.contestId;
        problem.problemIndex = contestProblem.problemIndex;
        if (detail != null) {
            problem.name = detail.name;
            problem.title = detail.title;
        }
        if (problem.name == null || problem.name.isBlank()) {
            problem.name = contestProblem.problemId;
        }
        if (problem.title == null || problem.title.isBlank()) {
            problem.title = choose(contestProblem.problemIndex, contestProblem.problemId);
        }
        return problem;
    }

    private <T> T fetchJson(String url, TypeReference<T> type) {
        return fetchJson(url, type, Duration.ofSeconds(45));
    }

    private <T> T fetchJson(String url, TypeReference<T> type, Duration timeout) {
        try {
            HttpRequest request = requestBuilder(url, timeout, "application/json")
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                throw new NoSuchElementException("没有找到该 AtCoder 用户或数据");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AtCoder 数据源暂时不可用，状态码：" + response.statusCode());
            }
            return mapper.readValue(response.body(), type);
        } catch (IOException ex) {
            throw new IllegalStateException("AtCoder 数据解析失败：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AtCoder 查询被中断", ex);
        }
    }

    private String fetchText(String url, Duration timeout, String accept) {
        try {
            HttpRequest request = requestBuilder(url, timeout, accept).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("AtCoder 数据源暂时不可用，状态码：" + response.statusCode());
            }
            return response.body();
        } catch (IOException ex) {
            throw new IllegalStateException("AtCoder 数据解析失败：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AtCoder 查询被中断", ex);
        }
    }

    private HttpRequest.Builder requestBuilder(String url, Duration timeout, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", accept)
                .header("User-Agent", "DataForge AtCoder tools/0.1")
                .GET();
        if (!atcoderCookie.isBlank() && url.startsWith("https://atcoder.jp/")) {
            builder.header("Cookie", atcoderCookie);
        }
        return builder;
    }

    private static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (!value.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("AtCoder 用户名只能包含字母、数字和下划线");
        }
        return value;
    }

    private static Instant parseInstant(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }

    private static String toContestId(String contestScreenName) {
        if (contestScreenName == null || contestScreenName.isBlank()) {
            throw new IllegalStateException("参赛记录缺少比赛 ID");
        }
        int dot = contestScreenName.indexOf('.');
        return dot < 0 ? contestScreenName : contestScreenName.substring(0, dot);
    }

    private static String choose(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Integer ratingDelta(Integer oldRating, Integer newRating) {
        if (oldRating == null || newRating == null) {
            return null;
        }
        return newRating - oldRating;
    }

    private static String formatElapsed(long seconds) {
        long normalized = Math.max(0, seconds);
        long hours = normalized / 3600;
        long minutes = (normalized % 3600) / 60;
        long rest = normalized % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, rest);
    }

    private static int compareProblems(ProblemResource first, ProblemResource second) {
        return compareIndex(first.problemIndex, second.problemIndex);
    }

    private static int compareIndex(String first, String second) {
        String a = Objects.toString(first, "");
        String b = Objects.toString(second, "");
        int ai = 0;
        int bi = 0;
        while (ai < a.length() && bi < b.length()) {
            char ac = a.charAt(ai);
            char bc = b.charAt(bi);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int aj = ai;
                int bj = bi;
                while (aj < a.length() && Character.isDigit(a.charAt(aj))) aj++;
                while (bj < b.length() && Character.isDigit(b.charAt(bj))) bj++;
                int numberCompare = Integer.compare(
                        Integer.parseInt(a.substring(ai, aj)),
                        Integer.parseInt(b.substring(bi, bj))
                );
                if (numberCompare != 0) return numberCompare;
                ai = aj;
                bi = bj;
                continue;
            }
            int charCompare = Character.compare(Character.toUpperCase(ac), Character.toUpperCase(bc));
            if (charCompare != 0) return charCompare;
            ai++;
            bi++;
        }
        return Integer.compare(a.length(), b.length());
    }

    private static class ResourceCache {
        final ResourceBundle resources;
        final Instant loadedAt;

        ResourceCache(ResourceBundle resources, Instant loadedAt) {
            this.resources = resources;
            this.loadedAt = loadedAt;
        }
    }

    private static class ContestTotalsCache {
        final ContestProblemTotals totals;
        final Instant loadedAt;

        ContestTotalsCache(ContestProblemTotals totals, Instant loadedAt) {
            this.totals = totals;
            this.loadedAt = loadedAt;
        }
    }

    private static class ContestProblemTotals {
        final Map<String, ProblemTotals> totals;
        final boolean available;

        ContestProblemTotals(Map<String, ProblemTotals> totals, boolean available) {
            this.totals = totals;
            this.available = available;
        }
    }

    private static class ProblemTotals {
        int acceptedCount;
        int submittedCount;
    }

    private static class ResourceBundle {
        Map<String, ContestResource> contestsById = Map.of();
        Map<String, List<ProblemResource>> problemsByContest = Map.of();
    }

    private static class ContestContext {
        String contestId;
        String contestName;
        HistoryEntry history;
        long startEpoch;
        long endEpoch;
        List<ProblemResource> problems = List.of();
    }

    private static class HistoryEntry {
        @JsonProperty("IsRated")
        public Boolean rated;
        @JsonProperty("Place")
        public Integer place;
        @JsonProperty("OldRating")
        public Integer oldRating;
        @JsonProperty("NewRating")
        public Integer newRating;
        @JsonProperty("Performance")
        public Integer performance;
        @JsonProperty("ContestScreenName")
        public String contestScreenName;
        @JsonProperty("ContestName")
        public String contestName;
        @JsonProperty("EndTime")
        public String endTime;
    }

    private static class ContestResource {
        public String id;
        @JsonProperty("start_epoch_second")
        public long startEpochSecond;
        @JsonProperty("duration_second")
        public long durationSecond;
        public String title;
    }

    private static class ProblemResource {
        public String id;
        @JsonProperty("contest_id")
        public String contestId;
        @JsonProperty("problem_index")
        public String problemIndex;
        public String name;
        public String title;
    }

    private static class ContestProblemResource {
        @JsonProperty("contest_id")
        public String contestId;
        @JsonProperty("problem_id")
        public String problemId;
        @JsonProperty("problem_index")
        public String problemIndex;
    }

    private static class SubmissionEntry {
        public long id;
        @JsonProperty("epoch_second")
        public long epochSecond;
        @JsonProperty("problem_id")
        public String problemId;
        @JsonProperty("contest_id")
        public String contestId;
        public String language;
        public Double point;
        public String result;
    }
}
