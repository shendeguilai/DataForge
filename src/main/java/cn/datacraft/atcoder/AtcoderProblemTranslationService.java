package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderProblemDtos.ProblemContestView;
import cn.datacraft.atcoder.AtcoderProblemDtos.AdminProblemDetailView;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
    private final ObjectMapper mapper;
    private final Executor coordinatorExecutor;
    private final Executor translationExecutor;
    private final Clock clock;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicLong activeGeneration = new AtomicLong(NO_ACTIVE_JOB);

    @Autowired
    public AtcoderProblemTranslationService(
            AtcoderLeaderboardConfigRepository configs,
            AtcoderProblemTranslationRepository translations,
            AtcoderProblemSourceGateway sourceGateway,
            AtcoderProblemTranslator translator,
            AtcoderProblemHtmlProcessor htmlProcessor,
            ObjectMapper mapper,
            @Qualifier("translationCoordinatorExecutor") Executor coordinatorExecutor,
            @Qualifier("translationExecutor") Executor translationExecutor) {
        this(configs, translations, sourceGateway, translator, htmlProcessor, mapper,
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
        this.configs = configs;
        this.translations = translations;
        this.sourceGateway = sourceGateway;
        this.translator = translator;
        this.htmlProcessor = htmlProcessor;
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
        return overview(false);
    }

    public ProblemOverviewView adminOverview() {
        return overview(true);
    }

    public ProblemDetailView detail(String rawTaskId) {
        AtcoderLeaderboardConfig config = requireConfig();
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
                stored.map(AtcoderProblemTranslation::getSourceHtml).orElse(null),
                stored.filter(item -> item.getStatus() == AtcoderProblemTranslation.Status.READY)
                        .map(AtcoderProblemTranslation::getTranslatedHtml).orElse(null),
                stored.map(AtcoderProblemTranslation::getSourceFetchedAt).orElse(null),
                stored.map(AtcoderProblemTranslation::getTranslatedAt).orElse(null));
    }

    public AdminProblemDetailView adminDetail(String rawTaskId) {
        AtcoderLeaderboardConfig config = requireConfig();
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
        if (editedHtml != null && editedHtml.length() > 1_000_000) {
            throw new IllegalArgumentException("译文内容过长");
        }
        AtcoderLeaderboardConfig config = requireConfig();
        String taskId = normalizeTaskId(rawTaskId);
        requireTask(config, taskId);
        lifecycleLock.lock();
        try {
            if (activeGeneration.get() == generation.get()) {
                throw new IllegalStateException("翻译任务正在运行，请结束后再编辑");
            }
            AtcoderProblemTranslation entity = translations
                    .findByContestIdAndTaskId(config.getContestId(), taskId)
                    .orElseThrow(() -> new IllegalStateException("请先获取该题英文题面"));
            if (entity.getSourceHtml() == null || entity.getSourceHtml().isBlank()) {
                throw new IllegalStateException("英文题面尚未获取，无法保存人工译文");
            }
            String translated = htmlProcessor.prepareEditedTranslation(entity.getSourceHtml(), editedHtml);
            entity.ready(translated, clock.instant());
            translations.saveAndFlush(entity);
            return adminDetail(taskId);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView startAll(boolean force) {
        AtcoderLeaderboardConfig config = requireConfig();
        List<AtcoderStandings.Task> tasks = tasks(config);
        if (tasks.isEmpty()) throw new IllegalStateException("当前比赛没有可翻译的题目");
        translator.requireConfigured();

        lifecycleLock.lock();
        try {
            long token = generation.get();
            if (activeGeneration.get() == token) return adminOverview();

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
            if (queuedTaskIds.isEmpty()) return adminOverview();
            activeGeneration.set(token);
            submitCoordinator(config.getContestId(), token, queuedTaskIds);
            return adminOverview();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ProblemOverviewView retryTask(String rawTaskId) {
        AtcoderLeaderboardConfig config = requireConfig();
        translator.requireConfigured();
        String taskId = normalizeTaskId(rawTaskId);
        List<AtcoderStandings.Task> tasks = tasks(config);
        AtcoderStandings.Task task = tasks.stream()
                .filter(candidate -> candidate.id().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("当前比赛中没有这道题"));

        lifecycleLock.lock();
        try {
            long token = generation.get();
            if (activeGeneration.get() == token) return adminOverview();
            Instant now = clock.instant();
            AtcoderProblemTranslation entity = translations
                    .findByContestIdAndTaskId(config.getContestId(), taskId)
                    .orElseGet(() -> new AtcoderProblemTranslation(
                            config.getContestId(), task, tasks.indexOf(task), now));
            entity.refreshTask(task, tasks.indexOf(task));
            entity.queue(now, true);
            translations.saveAndFlush(entity);
            activeGeneration.set(token);
            submitCoordinator(config.getContestId(), token, List.of(taskId));
            return adminOverview();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void onContestChanged(String previousContestId, String currentContestId) {
        if (Objects.equals(previousContestId, currentContestId)) return;
        lifecycleLock.lock();
        try {
            generation.incrementAndGet();
            activeGeneration.set(NO_ACTIVE_JOB);
            translations.deleteAllInBatch();
            translations.flush();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void submitCoordinator(String contestId, long token, List<String> taskIds) {
        try {
            coordinatorExecutor.execute(() -> runBatch(contestId, token, taskIds));
        } catch (RuntimeException ex) {
            activeGeneration.compareAndSet(token, NO_ACTIVE_JOB);
            for (String taskId : taskIds) fail(contestId, token, taskId, "翻译任务队列已满，请稍后重试");
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
            activeGeneration.compareAndSet(token, NO_ACTIVE_JOB);
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
        if (generation.get() != token) return false;
        return configs.findById(AtcoderLeaderboardConfig.SINGLETON_ID)
                .map(config -> config.getContestId().equals(contestId))
                .orElse(false);
    }

    private ProblemOverviewView overview(boolean includeErrors) {
        Optional<AtcoderLeaderboardConfig> found = configs.findById(AtcoderLeaderboardConfig.SINGLETON_ID);
        if (found.isEmpty()) {
            return new ProblemOverviewView(false, null, "NOT_CONFIGURED", 0, 0, 0, false, List.of());
        }
        AtcoderLeaderboardConfig config = found.get();
        List<AtcoderStandings.Task> configuredTasks = tasks(config);
        Map<String, AtcoderProblemTranslation> stored = rowsByTask(config.getContestId());
        List<ProblemTaskView> taskViews = configuredTasks.stream()
                .map(task -> toTaskView(config.getContestId(), task, stored.get(task.id()), includeErrors))
                .toList();
        int ready = (int) taskViews.stream().filter(task -> "READY".equals(task.status())).count();
        int failed = (int) taskViews.stream().filter(task -> "FAILED".equals(task.status())).count();
        boolean running = taskViews.stream().anyMatch(task -> isTransient(task.status()))
                || activeGeneration.get() == generation.get();
        String status;
        if (configuredTasks.isEmpty() || (ready == 0 && failed == 0 && !running)) status = "NOT_STARTED";
        else if (running) status = "RUNNING";
        else if (ready == configuredTasks.size()) status = "READY";
        else if (ready > 0) status = "PARTIAL";
        else status = "FAILED";
        return new ProblemOverviewView(true, toContestView(config), status, configuredTasks.size(),
                ready, failed, running, taskViews);
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
        return new ProblemTaskView(task.id(), task.label(), task.name(),
                stored == null ? "NOT_STARTED" : stored.getStatus().name(),
                officialTaskUrl(contestId, task.id()),
                stored == null ? null : stored.getUpdatedAt(),
                includeError && stored != null ? stored.getErrorMessage() : null,
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
        if (source == null || source.isBlank()) return null;
        String candidate = item.getTranslatedHtml() != null
                ? item.getTranslatedHtml() : item.getDraftHtml();
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

    private AtcoderLeaderboardConfig requireConfig() {
        return configs.findById(AtcoderLeaderboardConfig.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("排行榜尚未配置比赛"));
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
}
