package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemContestView;
import cn.datacraft.atcoder.AtcoderProblemDtos.AdminProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ImportedBundleView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemDetailView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemOverviewView;
import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemTaskView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

@Service
public class AtcoderProblemTranslationService {
    private static final long NO_ACTIVE_JOB = -1L;

    private final AtcoderLeaderboardConfigRepository configs;
    private final AtcoderProblemTranslationRepository translations;
    private final AtcoderProblemSourceGateway sourceGateway;
    private final AtcoderProblemTranslator translator;
    private final AtcoderProblemHtmlProcessor htmlProcessor;
    private final AtcoderContestPdfParser pdfParser;
    private final AtcoderContestMarkdownParser markdownParser;
    private final ObjectMapper mapper;
    private final Executor coordinatorExecutor;
    private final Executor translationExecutor;
    private final Clock clock;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicLong activeGeneration = new AtomicLong(NO_ACTIVE_JOB);
    private volatile String activeContestId;

    @Autowired
    public AtcoderProblemTranslationService(
            AtcoderLeaderboardConfigRepository configs,
            AtcoderProblemTranslationRepository translations,
            AtcoderProblemSourceGateway sourceGateway,
            AtcoderProblemTranslator translator,
            AtcoderProblemHtmlProcessor htmlProcessor,
            AtcoderContestPdfParser pdfParser,
            AtcoderContestMarkdownParser markdownParser,
            ObjectMapper mapper,
            @Qualifier("translationCoordinatorExecutor") Executor coordinatorExecutor,
            @Qualifier("translationExecutor") Executor translationExecutor) {
        this(configs, translations, sourceGateway, translator, htmlProcessor, pdfParser, markdownParser, mapper,
                coordinatorExecutor, translationExecutor, Clock.systemUTC());
    }

    AtcoderProblemTranslationService(
            AtcoderLeaderboardConfigRepository configs,
            AtcoderProblemTranslationRepository translations,
            AtcoderProblemSourceGateway sourceGateway,
            AtcoderProblemTranslator translator,
            AtcoderProblemHtmlProcessor htmlProcessor,
            ObjectMapper mapper,
            Executor coordinatorExecutor,
            Executor translationExecutor,
            Clock clock) {
        this(configs, translations, sourceGateway, translator, htmlProcessor,
                new AtcoderContestPdfParser(), new AtcoderContestMarkdownParser(), mapper,
                coordinatorExecutor, translationExecutor, clock);
    }

    private AtcoderProblemTranslationService(
            AtcoderLeaderboardConfigRepository configs,
            AtcoderProblemTranslationRepository translations,
            AtcoderProblemSourceGateway sourceGateway,
            AtcoderProblemTranslator translator,
            AtcoderProblemHtmlProcessor htmlProcessor,
            AtcoderContestPdfParser pdfParser,
            AtcoderContestMarkdownParser markdownParser,
            ObjectMapper mapper,
            Executor coordinatorExecutor,
            Executor translationExecutor,
            Clock clock) {
        this.configs = configs;
        this.translations = translations;
        this.sourceGateway = sourceGateway;
        this.translator = translator;
        this.htmlProcessor = htmlProcessor;
        this.pdfParser = pdfParser;
        this.markdownParser = markdownParser;
        this.mapper = mapper;
        this.coordinatorExecutor = coordinatorExecutor;
        this.translationExecutor = translationExecutor;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTranslations() {
        Instant now = clock.instant();
        List<AtcoderProblemTranslation> interrupted = translations.findAll().stream()
                .filter(item -> isTransient(item.getStatus()))
                .toList();
        for (AtcoderProblemTranslation item : interrupted) {
            item.fail("服务曾在翻译过程中重启，请重新翻译该题", now);
        }
        if (!interrupted.isEmpty()) translations.saveAllAndFlush(interrupted);
    }

    public ProblemOverviewView publicOverview() {
        return publicOverview(null);
    }

    public ProblemOverviewView publicOverview(String contestId) {
        return overview(contestId, false);
    }

    public ProblemOverviewView adminOverview() {
        return adminOverview(null);
    }

    public ProblemOverviewView adminOverview(String contestId) {
        return overview(contestId, true);
    }

    public ProblemDetailView detail(String rawTaskId) {
        return detail(null, rawTaskId);
    }

    public ProblemDetailView detail(String rawContestId, String rawTaskId) {
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        String taskId = normalizeTaskId(rawTaskId);
        AtcoderStandings.Task task = tasks(config).stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("当前比赛中没有这道题"));
        Optional<AtcoderProblemTranslation> stored = translations
                .findByContestIdAndTaskId(config.getContestId(), taskId);
        ProblemTaskView taskView = stored
                .map(item -> toTaskView(config.getContestId(), task, item, false))
                .orElseGet(() -> toTaskView(config.getContestId(), task, null, false));
        return new ProblemDetailView(toContestView(config), taskView,
                stored.map(this::publicSourceHtml).orElse(null),
                stored.filter(item -> item.getStatus() == AtcoderProblemTranslation.Status.READY)
                        .map(AtcoderProblemTranslation::getTranslatedHtml)
                        .map(htmlProcessor::prepareStatementDisplay).orElse(null),
                stored.map(AtcoderProblemTranslation::getSourceFetchedAt).orElse(null),
                stored.map(AtcoderProblemTranslation::getTranslatedAt).orElse(null));
    }

    public AdminProblemDetailView adminDetail(String rawTaskId) {
        return adminDetail(null, rawTaskId);
    }

    public AdminProblemDetailView adminDetail(String rawContestId, String rawTaskId) {
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        String taskId = normalizeTaskId(rawTaskId);
        AtcoderStandings.Task task = requireTask(config, taskId);
        Optional<AtcoderProblemTranslation> stored = translations
                .findByContestIdAndTaskId(config.getContestId(), taskId);
        ProblemTaskView taskView = stored
                .map(item -> toTaskView(config.getContestId(), task, item, true))
                .orElseGet(() -> toTaskView(config.getContestId(), task, null, true));
        String editorHtml = stored.map(this::editorHtml).orElse(null);
        return new AdminProblemDetailView(toContestView(config), taskView,
                stored.map(AtcoderProblemTranslation::getSourceHtml).orElse(null),
                stored.map(AtcoderProblemTranslation::getTranslatedHtml).orElse(null),
                stored.map(AtcoderProblemTranslation::getDraftHtml).orElse(null),
                editorHtml,
                stored.map(AtcoderProblemTranslation::getSourceFetchedAt).orElse(null),
                stored.map(AtcoderProblemTranslation::getTranslatedAt).orElse(null));
    }

    public AdminProblemDetailView saveManualTranslation(String rawTaskId, String editedHtml) {
        return saveManualTranslation(null, rawTaskId, editedHtml);
    }

    public AdminProblemDetailView saveManualTranslation(String rawContestId, String rawTaskId,
                                                        String editedHtml) {
        if (editedHtml != null && editedHtml.length() > 1_000_000) {
            throw new IllegalArgumentException("译文内容过长");
        }
        return saveManual(rawContestId, rawTaskId, editedHtml, false);
    }

    public AdminProblemDetailView saveStructuredManualTranslation(String rawTaskId, String manualText) {
        return saveStructuredManualTranslation(null, rawTaskId, manualText);
    }

    public AdminProblemDetailView saveStructuredManualTranslation(String rawContestId, String rawTaskId,
                                                                  String manualText) {
        if (manualText != null && manualText.length() > 1_000_000) {
            throw new IllegalArgumentException("手动题面内容过长");
        }
        return saveManual(rawContestId, rawTaskId, manualText, true);
    }

    private AdminProblemDetailView saveManual(String rawContestId, String rawTaskId,
                                              String content, boolean structured) {
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        String taskId = normalizeTaskId(rawTaskId);
        List<AtcoderStandings.Task> contestTasks = tasks(config);
        AtcoderStandings.Task task = contestTasks.stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("当前比赛中没有这道题"));
        lifecycleLock.lock();
        try {
            if (hasActiveJob()) {
                throw new IllegalStateException("翻译任务正在运行，请结束后再编辑");
            }
            AtcoderProblemTranslation entity = translations
                    .findByContestIdAndTaskId(config.getContestId(), taskId)
                    .orElseGet(() -> new AtcoderProblemTranslation(
                            config.getContestId(), task, contestTasks.indexOf(task), clock.instant()));
            if (htmlProcessor.isMarkdownSource(entity.getSourceHtml())) {
                entity.refreshImportedTask(task, contestTasks.indexOf(task), entity.getTaskName());
            } else {
                entity.refreshTask(task, contestTasks.indexOf(task));
            }
            String translated;
            if (structured) {
                translated = htmlProcessor.prepareStructuredManualTranslation(content);
            } else if (entity.getSourceHtml() == null || entity.getSourceHtml().isBlank()
                    || htmlProcessor.isImportedSource(entity.getSourceHtml())) {
                translated = htmlProcessor.prepareManualTranslation(content);
            } else {
                translated = htmlProcessor.prepareEditedTranslation(entity.getSourceHtml(), content);
            }
            entity.ready(translated, clock.instant());
            translations.saveAndFlush(entity);
            return adminDetail(config.getContestId(), taskId);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView startAll(boolean force) {
        return startAll(null, force);
    }

    public ProblemOverviewView startAll(String rawContestId, boolean force) {
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        List<AtcoderStandings.Task> tasks = tasks(config);
        if (tasks.isEmpty()) throw new IllegalStateException("当前比赛没有可翻译的题目");
        translator.requireConfigured();

        lifecycleLock.lock();
        try {
            if (hasActiveJob()) return activeOrThrow(config);
            long token = generation.incrementAndGet();

            Map<String, AtcoderProblemTranslation> existing = rowsByTask(config.getContestId());
            List<String> queuedTaskIds = new ArrayList<>();
            Instant now = clock.instant();
            for (int index = 0; index < tasks.size(); index++) {
                AtcoderStandings.Task task = tasks.get(index);
                AtcoderProblemTranslation entity = existing.get(task.id());
                if (entity == null) entity = new AtcoderProblemTranslation(config.getContestId(), task, index, now);
                else entity.refreshTask(task, index);

                if (force || entity.getStatus() != AtcoderProblemTranslation.Status.READY) {
                    entity.queue(now, true);
                    translations.saveAndFlush(entity);
                    queuedTaskIds.add(task.id());
                }
            }
            if (queuedTaskIds.isEmpty()) return adminOverview(config.getContestId());
            activeGeneration.set(token);
            activeContestId = config.getContestId();
            submitCoordinator(config.getContestId(), token, queuedTaskIds);
            return adminOverview(config.getContestId());
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView retryTask(String rawTaskId) {
        return retryTask(null, rawTaskId);
    }

    public ProblemOverviewView retryTask(String rawContestId, String rawTaskId) {
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        translator.requireConfigured();
        String taskId = normalizeTaskId(rawTaskId);
        List<AtcoderStandings.Task> tasks = tasks(config);
        AtcoderStandings.Task task = tasks.stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("当前比赛中没有这道题"));

        lifecycleLock.lock();
        try {
            if (hasActiveJob()) return activeOrThrow(config);
            long token = generation.incrementAndGet();
            Instant now = clock.instant();
            AtcoderProblemTranslation entity = translations
                    .findByContestIdAndTaskId(config.getContestId(), taskId)
                    .orElseGet(() -> new AtcoderProblemTranslation(
                            config.getContestId(), task, tasks.indexOf(task), now));
            entity.refreshTask(task, tasks.indexOf(task));
            if (htmlProcessor.isMarkdownSource(entity.getSourceHtml())) {
                String sourceFilename = Optional.ofNullable(
                        htmlProcessor.markdownSourceFilename(entity.getSourceHtml()))
                        .orElse(config.getContestId() + "_ALL.md");
                String sourceMarkdown = htmlProcessor.markdownSourceText(entity.getSourceHtml());
                AtcoderContestMarkdownParser.ParsedProblem problem = markdownParser.parse(
                        sourceMarkdown.getBytes(StandardCharsets.UTF_8), taskId + ".md").problems().get(0);
                validateMarkdownTask(config, task, problem);
                entity.refreshImportedTask(task, tasks.indexOf(task), problem.title());
                String renderedSource = htmlProcessor.renderMarkdownProblem(problem.sourceMarkdown());
                entity.queue(now, true);
                entity.translating(htmlProcessor.prepareMarkdownSource(problem, sourceFilename), now);
                translations.saveAndFlush(entity);
                activeGeneration.set(token);
                activeContestId = config.getContestId();
                submitMarkdownCoordinator(config.getContestId(), token,
                        Map.of(taskId, new MarkdownTranslationJob(problem, renderedSource, sourceFilename)));
                return adminOverview(config.getContestId());
            }
            entity.queue(now, true);
            translations.saveAndFlush(entity);
            activeGeneration.set(token);
            activeContestId = config.getContestId();
            submitCoordinator(config.getContestId(), token, List.of(taskId));
            return adminOverview(config.getContestId());
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView importPdf(String filename, byte[] bytes) {
        return importPdf(null, filename, bytes);
    }

    public ProblemOverviewView importPdf(String rawContestId, String filename, byte[] bytes) {
        if (bytes != null && bytes.length > 25 * 1024 * 1024) {
            throw new IllegalArgumentException("PDF 文件不能超过 25MB");
        }
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        List<AtcoderStandings.Task> tasks = tasks(config);
        if (tasks.isEmpty()) throw new IllegalStateException("当前比赛没有可导入的题目");
        translator.requireConfigured();
        AtcoderContestPdfParser.ParsedContestPdf parsed = pdfParser.parse(bytes, filename);
        Map<String, AtcoderContestPdfParser.ParsedProblem> byLabel = new HashMap<>();
        for (AtcoderContestPdfParser.ParsedProblem problem : parsed.problems()) {
            byLabel.put(problem.label().toUpperCase(Locale.ROOT), problem);
        }
        List<String> missing = tasks.stream()
                .map(AtcoderStandings.Task::label)
                .filter(label -> !byLabel.containsKey(label.toUpperCase(Locale.ROOT)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("PDF 未识别到当前比赛的题目：" + String.join("、", missing));
        }
        List<String> configuredLabels = tasks.stream()
                .map(task -> task.label().toUpperCase(Locale.ROOT)).toList();
        List<String> extra = parsed.problems().stream().map(AtcoderContestPdfParser.ParsedProblem::label)
                .filter(label -> !configuredLabels.contains(label.toUpperCase(Locale.ROOT))).toList();
        if (!extra.isEmpty()) {
            throw new IllegalArgumentException("PDF 中包含不属于当前比赛的题目：" + String.join("、", extra));
        }
        for (AtcoderStandings.Task task : tasks) {
            AtcoderContestPdfParser.ParsedProblem problem = byLabel.get(task.label().toUpperCase(Locale.ROOT));
            if (!sameTitle(task.name(), problem.title())) {
                throw new IllegalArgumentException("PDF 题目 " + task.label() + " 标题为“" + problem.title()
                        + "”，与当前比赛“" + task.name() + "”不一致");
            }
        }

        lifecycleLock.lock();
        try {
            if (hasActiveJob()) throw new IllegalStateException("已有题面翻译任务正在运行，请稍后再上传");
            long token = generation.incrementAndGet();
            Map<String, AtcoderProblemTranslation> existing = rowsByTask(config.getContestId());
            Map<String, AtcoderContestPdfParser.ParsedProblem> queued = new HashMap<>();
            Instant now = clock.instant();
            for (int index = 0; index < tasks.size(); index++) {
                AtcoderStandings.Task task = tasks.get(index);
                AtcoderContestPdfParser.ParsedProblem problem = byLabel.get(task.label().toUpperCase(Locale.ROOT));
                AtcoderProblemTranslation entity = existing.get(task.id());
                if (entity == null) entity = new AtcoderProblemTranslation(config.getContestId(), task, index, now);
                else entity.refreshTask(task, index);
                entity.queue(now, true);
                String sourceHtml = htmlProcessor.preparePdfSource(problem.label(), problem.title(),
                        problem.startPage(), problem.endPage(), problem.sourceText());
                entity.translating(sourceHtml, now);
                translations.saveAndFlush(entity);
                queued.put(task.id(), problem);
            }
            activeGeneration.set(token);
            activeContestId = config.getContestId();
            submitPdfCoordinator(config.getContestId(), token, queued);
            return adminOverview(config.getContestId());
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView importMarkdown(String filename, byte[] bytes) {
        return importMarkdown(null, filename, bytes);
    }

    public ProblemOverviewView importMarkdown(String rawContestId, String filename, byte[] bytes) {
        if (bytes != null && bytes.length > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("Markdown 文件不能超过 5MB");
        }
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        List<AtcoderStandings.Task> tasks = tasks(config);
        if (tasks.isEmpty()) throw new IllegalStateException("当前比赛没有可导入的题目");
        AtcoderContestMarkdownParser.ParsedContestMarkdown parsed = markdownParser.parse(bytes, filename);
        if (!config.getContestId().equalsIgnoreCase(parsed.contestId())) {
            throw new IllegalArgumentException("Markdown 属于比赛 " + parsed.contestId()
                    + "，与当前配置的 " + config.getContestId() + " 不一致");
        }

        Map<String, AtcoderContestMarkdownParser.ParsedProblem> byLabel = new LinkedHashMap<>();
        for (AtcoderContestMarkdownParser.ParsedProblem problem : parsed.problems()) {
            byLabel.put(problem.label().toUpperCase(Locale.ROOT), problem);
        }
        validateImportedLabels("Markdown", tasks, byLabel.keySet());

        Map<String, MarkdownTranslationJob> jobs = new LinkedHashMap<>();
        for (AtcoderStandings.Task task : tasks) {
            AtcoderContestMarkdownParser.ParsedProblem problem = byLabel.get(task.label().toUpperCase(Locale.ROOT));
            validateMarkdownTask(config, task, problem);
            jobs.put(task.id(), new MarkdownTranslationJob(
                    problem, htmlProcessor.renderMarkdownProblem(problem.sourceMarkdown()), parsed.filename()));
        }

        lifecycleLock.lock();
        try {
            if (hasActiveJob()) {
                throw new IllegalStateException("已有题面翻译任务正在运行，请稍后再上传");
            }
            Map<String, AtcoderProblemTranslation> existing = rowsByTask(config.getContestId());
            Instant now = clock.instant();
            for (int index = 0; index < tasks.size(); index++) {
                AtcoderStandings.Task task = tasks.get(index);
                MarkdownTranslationJob job = jobs.get(task.id());
                AtcoderProblemTranslation entity = existing.get(task.id());
                if (entity == null) entity = new AtcoderProblemTranslation(config.getContestId(), task, index, now);
                entity.refreshImportedTask(task, index, job.problem().title());
                entity.imported(htmlProcessor.prepareMarkdownSource(job.problem(), parsed.filename()), now);
                translations.saveAndFlush(entity);
            }
            updateMarkdownTaskNames(config, tasks, jobs, now);
            return adminOverview(config.getContestId());
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView translateImportedMarkdownAll() {
        return translateImportedMarkdownAll(null);
    }

    public ProblemOverviewView translateImportedMarkdownAll(String rawContestId) {
        AtcoderLeaderboardConfig config = requireConfig(rawContestId);
        translator.requireConfigured();
        List<AtcoderStandings.Task> tasks = tasks(config);

        lifecycleLock.lock();
        try {
            if (hasActiveJob()) return activeOrThrow(config);
            long token = generation.incrementAndGet();
            Map<String, AtcoderProblemTranslation> stored = rowsByTask(config.getContestId());
            Map<String, MarkdownTranslationJob> jobs = new LinkedHashMap<>();
            for (AtcoderStandings.Task task : tasks) {
                AtcoderProblemTranslation entity = stored.get(task.id());
                if (entity == null || entity.getStatus() == AtcoderProblemTranslation.Status.READY
                        || !htmlProcessor.isMarkdownSource(entity.getSourceHtml())) continue;
                String sourceFilename = Optional.ofNullable(
                        htmlProcessor.markdownSourceFilename(entity.getSourceHtml()))
                        .orElse(config.getContestId() + "_ALL.md");
                String sourceMarkdown = htmlProcessor.markdownSourceText(entity.getSourceHtml());
                AtcoderContestMarkdownParser.ParsedProblem problem = markdownParser.parse(
                        sourceMarkdown.getBytes(StandardCharsets.UTF_8), task.id() + ".md").problems().get(0);
                validateMarkdownTask(config, task, problem);
                jobs.put(task.id(), new MarkdownTranslationJob(problem,
                        htmlProcessor.renderMarkdownProblem(problem.sourceMarkdown()), sourceFilename));
            }
            if (jobs.isEmpty()) throw new IllegalStateException("没有待翻译的 Markdown 题目");

            Instant now = clock.instant();
            for (Map.Entry<String, MarkdownTranslationJob> entry : jobs.entrySet()) {
                AtcoderProblemTranslation entity = stored.get(entry.getKey());
                entity.queue(now, true);
                entity.translating(htmlProcessor.prepareMarkdownSource(
                        entry.getValue().problem(), entry.getValue().filename()), now);
                translations.saveAndFlush(entity);
            }
            activeGeneration.set(token);
            activeContestId = config.getContestId();
            submitMarkdownCoordinator(config.getContestId(), token, jobs);
            return adminOverview(config.getContestId());
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void submitCoordinator(String contestId, long token, List<String> taskIds) {
        try {
            coordinatorExecutor.execute(() -> runBatch(contestId, token, taskIds));
        } catch (RuntimeException ex) {
            for (String taskId : taskIds) fail(contestId, token, taskId, "翻译任务队列已满，请稍后重试");
            finishJob(token);
            throw ex;
        }
    }

    private void submitPdfCoordinator(String contestId, long token,
                                      Map<String, AtcoderContestPdfParser.ParsedProblem> problems) {
        try {
            coordinatorExecutor.execute(() -> runPdfBatch(contestId, token, problems));
        } catch (RuntimeException ex) {
            for (String taskId : problems.keySet()) {
                fail(contestId, token, taskId, "PDF 翻译任务队列已满，请稍后重试");
            }
            finishJob(token);
            throw ex;
        }
    }

    private void submitMarkdownCoordinator(String contestId, long token,
                                           Map<String, MarkdownTranslationJob> jobs) {
        try {
            coordinatorExecutor.execute(() -> runMarkdownBatch(contestId, token, jobs));
        } catch (RuntimeException ex) {
            for (String taskId : jobs.keySet()) {
                fail(contestId, token, taskId, "Markdown 翻译任务队列已满，请稍后重试");
            }
            finishJob(token);
            throw ex;
        }
    }

    private void runBatch(String contestId, long token, List<String> taskIds) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        try {
            for (String taskId : taskIds) {
                if (!isCurrent(contestId, token)) break;
                if (!update(contestId, token, taskId, item -> item.fetching(clock.instant()))) break;
                try {
                    String page = sourceGateway.fetchTaskPage(contestId, taskId);
                    String sourceHtml = htmlProcessor.extractEnglish(page);
                    if (!update(contestId, token, taskId,
                            item -> item.translating(sourceHtml, clock.instant()))) break;
                    pending.add(CompletableFuture.runAsync(
                            () -> translate(contestId, token, taskId, sourceHtml), translationExecutor));
                } catch (RuntimeException ex) {
                    fail(contestId, token, taskId, readableError(ex));
                }
                pauseBetweenAtcoderRequests();
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        } finally {
            finishJob(token);
        }
    }

    private void translate(String contestId, long token, String taskId, String sourceHtml) {
        try {
            AtcoderProblemHtmlProcessor.TranslationInput input = htmlProcessor.prepareTranslationInput(sourceHtml);
            String output = translator.translateToChinese(input.html());
            String draftHtml = htmlProcessor.prepareDraft(input, output);
            update(contestId, token, taskId,
                    item -> item.draft(draftHtml, clock.instant()));
            String translatedHtml = htmlProcessor.prepareTranslation(sourceHtml, input, output);
            update(contestId, token, taskId,
                    item -> item.ready(translatedHtml, clock.instant()));
        } catch (RuntimeException ex) {
            fail(contestId, token, taskId, readableError(ex));
        }
    }

    private void runPdfBatch(String contestId, long token,
                             Map<String, AtcoderContestPdfParser.ParsedProblem> problems) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        try {
            for (Map.Entry<String, AtcoderContestPdfParser.ParsedProblem> entry : problems.entrySet()) {
                if (!isCurrent(contestId, token)) break;
                pending.add(CompletableFuture.runAsync(
                        () -> translatePdf(contestId, token, entry.getKey(), entry.getValue()),
                        translationExecutor));
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        } finally {
            finishJob(token);
        }
    }

    private void translatePdf(String contestId, long token, String taskId,
                              AtcoderContestPdfParser.ParsedProblem problem) {
        try {
            String output = translator.translatePdfTextToChinese(
                    problem.label(), problem.title(), problem.sourceText());
            String draftHtml = htmlProcessor.preparePdfDraft(output);
            update(contestId, token, taskId, item -> item.draft(draftHtml, clock.instant()));
            String translatedHtml = htmlProcessor.preparePdfTranslation(output, problem.samplePairCount());
            update(contestId, token, taskId, item -> item.ready(translatedHtml, clock.instant()));
        } catch (RuntimeException ex) {
            fail(contestId, token, taskId, readableError(ex));
        }
    }

    private void runMarkdownBatch(String contestId, long token,
                                  Map<String, MarkdownTranslationJob> jobs) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        try {
            for (Map.Entry<String, MarkdownTranslationJob> entry : jobs.entrySet()) {
                if (!isCurrent(contestId, token)) break;
                pending.add(CompletableFuture.runAsync(
                        () -> translateMarkdown(contestId, token, entry.getKey(), entry.getValue()),
                        translationExecutor));
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        } finally {
            finishJob(token);
        }
    }

    private void translateMarkdown(String contestId, long token, String taskId,
                                   MarkdownTranslationJob job) {
        try {
            AtcoderProblemHtmlProcessor.TranslationInput input =
                    htmlProcessor.prepareTranslationInput(job.renderedSourceHtml());
            String output = translator.translateMarkdownToChinese(
                    job.problem().label(), job.problem().title(), input.html());
            String draftHtml = htmlProcessor.prepareDraft(input, output);
            update(contestId, token, taskId, item -> item.draft(draftHtml, clock.instant()));
            String translatedHtml = htmlProcessor.prepareMarkdownTranslation(
                    job.renderedSourceHtml(), input, output, job.problem().samplePairCount());
            update(contestId, token, taskId, item -> item.ready(translatedHtml, clock.instant()));
        } catch (RuntimeException ex) {
            fail(contestId, token, taskId, readableError(ex));
        }
    }

    private static void validateMarkdownTask(AtcoderLeaderboardConfig config, AtcoderStandings.Task task,
                                             AtcoderContestMarkdownParser.ParsedProblem problem) {
        if (!config.getContestId().equalsIgnoreCase(problem.contestId())) {
            throw new IllegalArgumentException("Markdown 题目 " + problem.label() + " 的 Contest 为 "
                    + problem.contestId() + "，与当前比赛不一致");
        }
        if (!task.id().equalsIgnoreCase(problem.problemId())) {
            throw new IllegalArgumentException("Markdown 题目 " + problem.label() + " 的 Problem 为 "
                    + problem.problemId() + "，应为 " + task.id());
        }
    }

    private static void validateImportedLabels(String sourceName, List<AtcoderStandings.Task> tasks,
                                               Set<String> importedLabels) {
        List<String> missing = tasks.stream().map(AtcoderStandings.Task::label)
                .filter(label -> !importedLabels.contains(label.toUpperCase(Locale.ROOT))).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(sourceName + " 未识别到当前比赛的题目：" + String.join("、", missing));
        }
        List<String> configured = tasks.stream().map(task -> task.label().toUpperCase(Locale.ROOT)).toList();
        List<String> extra = importedLabels.stream().filter(label -> !configured.contains(label)).toList();
        if (!extra.isEmpty()) {
            throw new IllegalArgumentException(sourceName + " 中包含不属于当前比赛的题目：" + String.join("、", extra));
        }
    }

    private static boolean sameTitle(String configured, String extracted) {
        String left = configured == null ? "" : configured.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String right = extracted == null ? "" : extracted.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return !left.isBlank() && left.equals(right);
    }

    private void fail(String contestId, long token, String taskId, String message) {
        update(contestId, token, taskId,
                item -> item.fail(cleanError(message), clock.instant()));
    }

    private boolean update(String contestId, long token, String taskId,
                           Consumer<AtcoderProblemTranslation> mutation) {
        lifecycleLock.lock();
        try {
            if (!isCurrent(contestId, token)) return false;
            Optional<AtcoderProblemTranslation> found = translations.findByContestIdAndTaskId(contestId, taskId);
            if (found.isEmpty()) return false;
            mutation.accept(found.get());
            translations.saveAndFlush(found.get());
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private boolean isCurrent(String contestId, long token) {
        return generation.get() == token
                && activeGeneration.get() == token
                && contestId.equals(activeContestId)
                && configs.findByContestIdIgnoreCase(contestId).isPresent();
    }

    private ProblemOverviewView overview(String rawContestId, boolean includeErrors) {
        Optional<AtcoderLeaderboardConfig> found = AtcoderContestSupport.select(configs, rawContestId);
        if (found.isEmpty()) {
            return new ProblemOverviewView(false, null, "NOT_CONFIGURED", 0, 0, 0,
                    false, null, List.of(), List.of());
        }
        AtcoderLeaderboardConfig config = found.get();
        List<AtcoderStandings.Task> configuredTasks = tasks(config);
        Map<String, AtcoderProblemTranslation> stored = rowsByTask(config.getContestId());
        List<ProblemTaskView> taskViews = configuredTasks.stream()
                .map(task -> toTaskView(config.getContestId(), task, stored.get(task.id()), includeErrors))
                .toList();
        int ready = (int) taskViews.stream().filter(task -> "READY".equals(task.status())).count();
        int failed = (int) taskViews.stream().filter(task -> "FAILED".equals(task.status())).count();
        List<AtcoderProblemTranslation> markdownRows = stored.values().stream()
                .filter(item -> htmlProcessor.isMarkdownSource(item.getSourceHtml())).toList();
        ImportedBundleView importedBundle = markdownRows.isEmpty() ? null : new ImportedBundleView(
                "MARKDOWN",
                Optional.ofNullable(htmlProcessor.markdownSourceFilename(markdownRows.get(0).getSourceHtml()))
                        .orElse(config.getContestId() + "_ALL.md"),
                markdownRows.size());
        boolean running = taskViews.stream().anyMatch(task -> isTransient(task.status()))
                || (hasActiveJob() && config.getContestId().equals(activeContestId));
        String status;
        if (configuredTasks.isEmpty()) status = "NOT_STARTED";
        else if (running) status = "RUNNING";
        else if (ready == configuredTasks.size()) status = "READY";
        else if (importedBundle != null && ready == 0 && failed == 0) status = "IMPORTED";
        else if (importedBundle != null) status = "PARTIAL";
        else if (ready > 0) status = "PARTIAL";
        else if (ready == 0 && failed == 0) status = "NOT_STARTED";
        else status = "FAILED";
        return new ProblemOverviewView(true, toContestView(config), status, configuredTasks.size(),
                ready, failed, running, importedBundle, taskViews,
                AtcoderContestSupport.options(configs, config.getContestId(), clock));
    }

    private Map<String, AtcoderProblemTranslation> rowsByTask(String contestId) {
        Map<String, AtcoderProblemTranslation> result = new HashMap<>();
        for (AtcoderProblemTranslation item : translations.findAllByContestIdOrderByTaskOrderAscIdAsc(contestId)) {
            result.put(item.getTaskId(), item);
        }
        return result;
    }

    private ProblemTaskView toTaskView(String contestId, AtcoderStandings.Task task,
                                       AtcoderProblemTranslation stored, boolean includeError) {
        String sourceType = stored == null || stored.getSourceHtml() == null ? null
                : (htmlProcessor.isMarkdownSource(stored.getSourceHtml()) ? "MARKDOWN"
                : (htmlProcessor.isPdfSource(stored.getSourceHtml()) ? "PDF" : "ATCODER"));
        return new ProblemTaskView(task.id(), task.label(),
                stored == null || stored.getTaskName() == null || stored.getTaskName().isBlank()
                        ? task.name() : stored.getTaskName(),
                stored == null ? "NOT_STARTED" : stored.getStatus().name(),
                officialTaskUrl(contestId, task.id()),
                stored == null ? null : stored.getUpdatedAt(),
                includeError && stored != null ? stored.getErrorMessage() : null,
                sourceType,
                stored != null && stored.getSourceHtml() != null,
                stored != null && stored.getDraftHtml() != null,
                stored != null && stored.getTranslatedHtml() != null);
    }

    private AtcoderStandings.Task requireTask(AtcoderLeaderboardConfig config, String taskId) {
        return tasks(config).stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("当前比赛中没有这道题"));
    }

    private String editorHtml(AtcoderProblemTranslation item) {
        String source = item.getSourceHtml();
        String candidate = item.getTranslatedHtml() != null
                ? item.getTranslatedHtml() : item.getDraftHtml();
        if (source == null || source.isBlank() || htmlProcessor.isImportedSource(source)) {
            return candidate == null || candidate.isBlank() ? null : htmlProcessor.prepareManualTranslation(candidate);
        }
        if (candidate == null || candidate.isBlank()) return source;
        try {
            return htmlProcessor.prepareEditedTranslation(source, candidate);
        } catch (RuntimeException ignored) {
            return source;
        }
    }

    private static ProblemContestView toContestView(AtcoderLeaderboardConfig config) {
        return new ProblemContestView(config.getContestId(), config.getDisplayTitle(),
                config.getOfficialTitle(), "https://atcoder.jp/contests/" + config.getContestId());
    }

    private List<AtcoderStandings.Task> tasks(AtcoderLeaderboardConfig config) {
        try {
            return mapper.readValue(config.getTasksJson(), new TypeReference<List<AtcoderStandings.Task>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("比赛题目列表读取失败", ex);
        }
    }

    private AtcoderLeaderboardConfig requireConfig(String contestId) {
        return AtcoderContestSupport.select(configs, contestId)
                .orElseThrow(() -> new IllegalStateException("排行榜尚未配置比赛"));
    }

    private boolean hasActiveJob() {
        return activeGeneration.get() != NO_ACTIVE_JOB;
    }

    private ProblemOverviewView activeOrThrow(AtcoderLeaderboardConfig config) {
        if (config.getContestId().equals(activeContestId)) {
            return adminOverview(config.getContestId());
        }
        throw new IllegalStateException("另一场比赛的题面翻译任务正在运行，请结束后再操作");
    }

    private void finishJob(long token) {
        if (activeGeneration.compareAndSet(token, NO_ACTIVE_JOB)) activeContestId = null;
    }

    private static String normalizeTaskId(String value) {
        String taskId = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!taskId.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("题目 ID 格式不正确");
        }
        return taskId;
    }

    private static String officialTaskUrl(String contestId, String taskId) {
        return "https://atcoder.jp/contests/" + contestId + "/tasks/" + taskId + "?lang=en";
    }

    private static boolean isTransient(AtcoderProblemTranslation.Status status) {
        return status == AtcoderProblemTranslation.Status.QUEUED
                || status == AtcoderProblemTranslation.Status.FETCHING
                || status == AtcoderProblemTranslation.Status.TRANSLATING;
    }

    private static boolean isTransient(String status) {
        return "QUEUED".equals(status) || "FETCHING".equals(status) || "TRANSLATING".equals(status);
    }

    private static String readableError(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static String cleanError(String message) {
        String value = message == null || message.isBlank() ? "翻译失败，请稍后重试" : message.trim();
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private static void pauseBetweenAtcoderRequests() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String publicSourceHtml(AtcoderProblemTranslation item) {
        String source = item.getSourceHtml();
        if (!htmlProcessor.isMarkdownSource(source)) return htmlProcessor.prepareStatementDisplay(source);
        try {
            return htmlProcessor.renderMarkdownProblem(htmlProcessor.markdownSourceText(source));
        } catch (RuntimeException ignored) {
            return source;
        }
    }

    private void updateMarkdownTaskNames(AtcoderLeaderboardConfig config, List<AtcoderStandings.Task> tasks,
                                         Map<String, MarkdownTranslationJob> jobs, Instant now) {
        List<AtcoderStandings.Task> renamed = tasks.stream().map(task -> {
            MarkdownTranslationJob job = jobs.get(task.id());
            return job == null ? task : new AtcoderStandings.Task(
                    task.id(), task.label(), job.problem().title(), task.maxScore());
        }).toList();
        try {
            config.update(config.getContestId(), config.getDisplayTitle(), config.getOfficialTitle(),
                    config.getStartAt(), config.getEndAt(), mapper.writeValueAsString(renamed), now);
            configs.saveAndFlush(config);
        } catch (Exception ex) {
            throw new IllegalStateException("保存 Markdown 题目名称失败", ex);
        }
    }

    private record MarkdownTranslationJob(AtcoderContestMarkdownParser.ParsedProblem problem,
                                          String renderedSourceHtml, String filename) {}
}
