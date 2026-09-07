package cn.datacraft.atcoder;

import cn.datacraft.atcoder.AtcoderLeaderboardDtos.AdminConfigView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.ContestView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.EntryView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.LeaderboardView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.MovementView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.ParticipantView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.TaskResultView;
import cn.datacraft.atcoder.AtcoderLeaderboardDtos.TaskView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class AtcoderLeaderboardService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final Duration CACHE_TTL = Duration.ofSeconds(55);
    private static final Duration MANUAL_REFRESH_COOLDOWN = Duration.ofSeconds(10);
    private static final int CLIENT_REFRESH_SECONDS = 60;
    private static final int MAX_BULK_PARTICIPANTS = 200;

    private final AtcoderLeaderboardConfigRepository configs;
    private final AtcoderLeaderboardSnapshotRepository snapshots;
    private final AtcoderLeaderboardParticipantRepository participants;
    private final AtcoderStandingsGateway gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    @Autowired
    public AtcoderLeaderboardService(AtcoderLeaderboardConfigRepository configs,
                                     AtcoderLeaderboardSnapshotRepository snapshots,
                                     AtcoderLeaderboardParticipantRepository participants,
                                     AtcoderStandingsGateway gateway,
                                     ObjectMapper mapper) {
        this(configs, snapshots, participants, gateway, mapper, Clock.systemUTC());
    }

    AtcoderLeaderboardService(AtcoderLeaderboardConfigRepository configs,
                              AtcoderLeaderboardSnapshotRepository snapshots,
                              AtcoderLeaderboardParticipantRepository participants,
                              AtcoderStandingsGateway gateway,
                              ObjectMapper mapper, Clock clock) {
        this.configs = configs;
        this.snapshots = snapshots;
        this.participants = participants;
        this.gateway = gateway;
        this.mapper = mapper;
        this.clock = clock;
    }

    public LeaderboardView currentLeaderboard() { return currentLeaderboard(null); }

    public LeaderboardView currentLeaderboard(String contestId) {
        return refreshLeaderboard(contestId, false);
    }

    public LeaderboardView manualRefresh() { return manualRefresh(null); }

    public LeaderboardView manualRefresh(String contestId) {
        return refreshLeaderboard(contestId, true);
    }

    public AdminConfigView getConfig() { return getConfig(null); }

    public AdminConfigView getConfig(String contestId) {
        Optional<AtcoderLeaderboardConfig> selected = AtcoderContestSupport.select(configs, contestId);
        if (selected.isEmpty()) return new AdminConfigView(
                false, null, null, null, null, null, null,
                gateway.cookieStatus().name(), gateway.cookieSource(), gateway.cookieUpdatedAt(),
                List.of(), List.of()
        );
        return toConfigView(selected.get());
    }

    public AdminConfigView updateCookie(String cookie) { return updateCookie(cookie, null); }

    public AdminConfigView updateCookie(String cookie, String contestId) {
        Optional<AtcoderLeaderboardConfig> selected = AtcoderContestSupport.select(configs, contestId);
        String selectedContestId = selected.map(AtcoderLeaderboardConfig::getContestId).orElse(null);
        AtcoderStandings.Snapshot standings = gateway.updateCookie(cookie, selectedContestId);
        if (selected.isPresent() && standings != null) {
            persistSuccessfulSnapshot(selectedContestId, standings, clock.instant());
        }
        return getConfig(selectedContestId);
    }

    public AdminConfigView clearManagedCookie() { return clearManagedCookie(null); }

    public AdminConfigView clearManagedCookie(String contestId) {
        gateway.clearManagedCookie();
        return getConfig(contestId);
    }

    @Transactional
    public AdminConfigView saveConfig(String rawContestId, String rawDisplayTitle) {
        String contestId = AtcoderContestSupport.normalizeContestId(rawContestId);
        String requestedTitle = normalizeOptionalTitle(rawDisplayTitle);
        Optional<AtcoderLeaderboardConfig> existingConfig = configs.findByContestIdIgnoreCase(contestId);

        AtcoderStandings.Snapshot standings = gateway.fetchStandings(contestId);
        AtcoderStandings.ContestMetadata metadata = gateway.fetchMetadata(contestId);
        String officialTitle = normalizeOfficialTitle(metadata.title(), contestId);
        String displayTitle = requestedTitle.isBlank() ? officialTitle : requestedTitle;
        Instant now = clock.instant();
        List<AtcoderStandings.Task> savedTasks = tasksWithConfiguredNameFallback(
                contestId, standings.tasks(), existingConfig.orElse(null));
        String tasksJson = writeTasks(savedTasks);

        AtcoderLeaderboardConfig config = existingConfig
                .orElseGet(() -> new AtcoderLeaderboardConfig(
                        contestId, displayTitle, officialTitle,
                        metadata.startAt(), metadata.endAt(), tasksJson, now
                ));
        config.update(contestId, displayTitle, officialTitle,
                metadata.startAt(), metadata.endAt(), tasksJson, now);
        configs.saveAndFlush(config);
        persistSuccessfulSnapshot(contestId, standings, now);
        return toConfigView(config);
    }

    public List<ParticipantView> getParticipants() {
        return participants.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toParticipantView).toList();
    }

    public ParticipantView addParticipant(String rawDisplayName, String rawUsername) {
        String displayName = normalizeDisplayName(rawDisplayName);
        String username = normalizeUsername(rawUsername);
        String usernameKey = username.toLowerCase(Locale.ROOT);
        if (participants.findByAtcoderUsernameKey(usernameKey).isPresent()) {
            throw new IllegalArgumentException("该 AtCoder ID 已在排行榜中");
        }
        int order = participants.findTopByOrderBySortOrderDesc()
                .map(item -> item.getSortOrder() + 1).orElse(1);
        AtcoderLeaderboardParticipant entity = new AtcoderLeaderboardParticipant(
                displayName, username, usernameKey, order, clock.instant()
        );
        try {
            entity = participants.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("该 AtCoder ID 已在排行榜中", ex);
        }
        return toParticipantView(entity);
    }

    @Transactional
    public List<ParticipantView> addParticipants(List<AtcoderLeaderboardDtos.BulkParticipantRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("请至少提供一条选手数据");
        }
        if (requests.size() > MAX_BULK_PARTICIPANTS) {
            throw new IllegalArgumentException("单次最多批量添加 200 名选手");
        }

        List<AtcoderLeaderboardParticipant> existing = participants.findAllByOrderBySortOrderAscIdAsc();
        Set<String> existingKeys = existing.stream()
                .map(AtcoderLeaderboardParticipant::getAtcoderUsernameKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> batchKeys = new HashSet<>();
        int nextOrder = existing.stream().mapToInt(AtcoderLeaderboardParticipant::getSortOrder)
                .max().orElse(0) + 1;
        Instant now = clock.instant();
        List<AtcoderLeaderboardParticipant> entities = new ArrayList<>();

        for (int index = 0; index < requests.size(); index++) {
            AtcoderLeaderboardDtos.BulkParticipantRequest request = requests.get(index);
            int row = index + 1;
            if (request == null) throw new IllegalArgumentException("第 " + row + " 条：数据不能为空");
            String displayName;
            String username;
            try {
                displayName = normalizeDisplayName(request.realName);
                username = normalizeUsername(request.atcoderId);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("第 " + row + " 条：" + ex.getMessage(), ex);
            }
            String usernameKey = username.toLowerCase(Locale.ROOT);
            if (existingKeys.contains(usernameKey)) {
                throw new IllegalArgumentException("第 " + row + " 条：AtCoder ID " + username + " 已在排行榜中");
            }
            if (!batchKeys.add(usernameKey)) {
                throw new IllegalArgumentException("第 " + row + " 条：AtCoder ID " + username + " 在本批次中重复");
            }
            entities.add(new AtcoderLeaderboardParticipant(
                    displayName, username, usernameKey, nextOrder++, now
            ));
        }

        try {
            entities = participants.saveAllAndFlush(entities);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("批量数据中存在已录入的 AtCoder ID", ex);
        }
        return entities.stream().map(this::toParticipantView).toList();
    }

    public ParticipantView updateParticipant(Long id, String rawDisplayName, String rawUsername) {
        AtcoderLeaderboardParticipant entity = participants.findById(id)
                .orElseThrow(() -> new NoSuchElementException("排行榜选手不存在"));
        String displayName = normalizeDisplayName(rawDisplayName);
        String username = normalizeUsername(rawUsername);
        String usernameKey = username.toLowerCase(Locale.ROOT);
        participants.findByAtcoderUsernameKey(usernameKey)
                .filter(item -> !Objects.equals(item.getId(), id))
                .ifPresent(item -> { throw new IllegalArgumentException("该 AtCoder ID 已在排行榜中"); });
        entity.update(displayName, username, usernameKey);
        try {
            entity = participants.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("该 AtCoder ID 已在排行榜中", ex);
        }
        return toParticipantView(entity);
    }

    public void deleteParticipant(Long id) {
        if (!participants.existsById(id)) throw new NoSuchElementException("排行榜选手不存在");
        participants.deleteById(id);
        participants.flush();
    }

    private LeaderboardView refreshLeaderboard(String rawContestId, boolean force) {
        Optional<AtcoderLeaderboardConfig> found = AtcoderContestSupport.select(configs, rawContestId);
        if (found.isEmpty()) return emptyLeaderboard();
        AtcoderLeaderboardConfig config = found.get();
        Instant now = clock.instant();
        AtcoderLeaderboardSnapshot stored = snapshots.findById(config.getContestId()).orElse(null);
        ReentrantLock refreshLock = refreshLocks.computeIfAbsent(
                config.getContestId(), ignored -> new ReentrantLock());

        if (!force && !shouldAutoRefresh(config, stored, now)) return storedView(config, stored, false, 0);
        if (force && stored != null) {
            int cooldown = remainingCooldown(stored, now);
            if (cooldown > 0) return storedView(config, stored, false, cooldown);
        }

        if (!refreshLock.tryLock()) {
            if (stored != null && stored.getStandingsJson() != null) {
                return storedView(config, stored, true, 0);
            }
            refreshLock.lock();
        }
        try {
            now = clock.instant();
            config = AtcoderContestSupport.select(configs, config.getContestId()).orElseThrow();
            stored = snapshots.findById(config.getContestId()).orElse(null);
            if (!force && !shouldAutoRefresh(config, stored, now)) return storedView(config, stored, false, 0);
            if (force && stored != null) {
                int cooldown = remainingCooldown(stored, now);
                if (cooldown > 0) return storedView(config, stored, false, cooldown);
            }

            AtcoderStandings.Snapshot previous = readStandings(stored);
            Map<Long, Integer> previousRanks = ranks(previous == null
                    ? null : buildLeaderboard(config, previous, Map.of(),
                    stored.getSyncedAt(), false, false, null, 0));
            AtcoderLeaderboardSnapshot target = stored == null
                    ? new AtcoderLeaderboardSnapshot(config.getContestId()) : stored;
            target.attempted(now);
            snapshots.saveAndFlush(target);
            try {
                AtcoderStandings.Snapshot standings = gateway.fetchStandings(config.getContestId());
                target.succeeded(writeStandings(standings), now);
                snapshots.saveAndFlush(target);
                return buildLeaderboard(config, standings, previousRanks, now,
                        false, false, null, 0);
            } catch (RuntimeException ex) {
                String error = publicError(ex);
                target.failed(error, now);
                snapshots.saveAndFlush(target);
                if (previous != null) {
                    return buildLeaderboard(config, previous, previousRanks, target.getSyncedAt(),
                            true, false, error, 0);
                }
                return errorLeaderboard(config, error, 0);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean shouldAutoRefresh(AtcoderLeaderboardConfig config,
                                      AtcoderLeaderboardSnapshot snapshot, Instant now) {
        String status = AtcoderContestSupport.status(config, now);
        if ("RUNNING".equals(status)) {
            if (snapshot == null || snapshot.getLastAttemptAt() == null) return true;
            Instant reference = snapshot.getSyncedAt() == null
                    ? snapshot.getLastAttemptAt() : snapshot.getSyncedAt();
            return remainingCooldown(snapshot, now) == 0
                    && (config.getUpdatedAt().isAfter(reference)
                    || !reference.plus(CACHE_TTL).isAfter(now));
        }
        if ("FINISHED".equals(status)) {
            return config.getEndAt() != null && (snapshot == null || snapshot.getLastAttemptAt() == null
                    || snapshot.getLastAttemptAt().isBefore(config.getEndAt()));
        }
        return false;
    }

    private LeaderboardView storedView(AtcoderLeaderboardConfig config,
                                        AtcoderLeaderboardSnapshot snapshot,
                                        boolean refreshing, int cooldown) {
        AtcoderStandings.Snapshot standings = readStandings(snapshot);
        if (standings == null) {
            String error = snapshot == null ? null : snapshot.getLastError();
            return errorLeaderboard(config,
                    error == null ? "尚无可用的排行榜快照" : error, cooldown);
        }
        String error = snapshot.getLastError();
        return buildLeaderboard(config, standings, Map.of(), snapshot.getSyncedAt(),
                error != null, refreshing, error, cooldown);
    }

    private LeaderboardView buildLeaderboard(AtcoderLeaderboardConfig config,
                                             AtcoderStandings.Snapshot standings,
                                             Map<Long, Integer> previousRanks,
                                             Instant syncedAt,
                                             boolean stale, boolean refreshing,
                                             String error, int cooldown) {
        List<AtcoderLeaderboardParticipant> participantList = participants.findAllByOrderBySortOrderAscIdAsc();
        List<Candidate> candidates = participantList.stream()
                .map(participant -> new Candidate(participant,
                        standings.entriesByUsernameKey().get(participant.getAtcoderUsernameKey())))
                .sorted(candidateComparator())
                .toList();

        List<AtcoderStandings.Task> displayTasks = tasksWithConfiguredNameFallback(
                config.getContestId(), standings.tasks(), config);
        List<TaskView> tasks = displayTasks.stream()
                .map(task -> toTaskView(config.getContestId(), task)).toList();
        List<EntryView> entries = new ArrayList<>();
        Candidate previousCandidate = null;
        int position = 0;
        int classRank = 0;
        int rankedCount = 0;
        for (Candidate candidate : candidates) {
            position++;
            Integer assignedRank = null;
            if (candidate.entry != null) {
                rankedCount++;
                if (previousCandidate == null || !sameStanding(previousCandidate.entry, candidate.entry)) {
                    classRank = position;
                }
                assignedRank = classRank;
                previousCandidate = candidate;
            }
            entries.add(toEntryView(candidate, assignedRank,
                    previousRanks.get(candidate.participant.getId()), displayTasks));
        }

        ContestView contest = toContestView(config);
        boolean autoRefresh = "RUNNING".equals(contest.status());
        return new LeaderboardView(true, true, contest,
                AtcoderContestSupport.options(configs, config.getContestId(), clock),
                tasks, List.copyOf(entries), entries.size(), rankedCount, syncedAt,
                stale, refreshing, error, autoRefresh,
                autoRefresh ? CLIENT_REFRESH_SECONDS : 0, cooldown);
    }

    private EntryView toEntryView(Candidate candidate, Integer classRank, Integer previousRank,
                                  List<AtcoderStandings.Task> tasks) {
        AtcoderLeaderboardParticipant participant = candidate.participant;
        AtcoderStandings.Entry entry = candidate.entry;
        if (entry == null) {
            return new EntryView(participant.getId(), null, participant.getDisplayName(),
                    participant.getAtcoderUsername(), BigDecimal.ZERO, 0, "--:--:--", 0,
                    null, new MovementView("NONE", 0), "NOT_STARTED",
                    tasks.stream().map(task -> emptyTaskResult(task.id())).toList());
        }

        List<TaskResultView> taskResults = tasks.stream()
                .map(task -> toTaskResult(task.id(), entry.taskResults().get(task.id())))
                .toList();
        int failures = taskResults.stream().mapToInt(TaskResultView::failure).sum();
        int wrongAttempts = Math.max(entry.penalty(), failures);
        long elapsedSeconds = nanosToSeconds(entry.elapsedNanos());
        return new EntryView(participant.getId(), classRank, participant.getDisplayName(),
                participant.getAtcoderUsername(), entry.totalScore(), elapsedSeconds,
                formatElapsed(elapsedSeconds), wrongAttempts, entry.officialRank(),
                movement(previousRank, classRank), "RANKED", taskResults);
    }

    private static TaskResultView toTaskResult(String taskId, AtcoderStandings.TaskResult result) {
        if (result == null) return emptyTaskResult(taskId);
        String state;
        if (result.frozen()) state = "FROZEN";
        else if (result.pending()) state = "PENDING";
        else if (result.score().compareTo(BigDecimal.ZERO) > 0) state = "AC";
        else if (result.count() > 0 || result.failure() > 0 || result.penalty() > 0
                || (result.status() != null && !result.status().isBlank() && !"0".equals(result.status()))) state = "FAILED";
        else state = "EMPTY";
        long elapsedSeconds = nanosToSeconds(result.elapsedNanos());
        return new TaskResultView(taskId, state, result.score(), elapsedSeconds,
                formatElapsed(elapsedSeconds), result.failure(), result.penalty(),
                result.pending(), result.frozen());
    }

    private static TaskResultView emptyTaskResult(String taskId) {
        return new TaskResultView(taskId, "EMPTY", BigDecimal.ZERO, 0,
                "--:--", 0, 0, false, false);
    }

    private AdminConfigView toConfigView(AtcoderLeaderboardConfig config) {
        List<TaskView> tasks = readTasks(config.getTasksJson()).stream()
                .map(task -> toTaskView(config.getContestId(), task)).toList();
        return new AdminConfigView(true, config.getContestId(), config.getDisplayTitle(),
                config.getOfficialTitle(), config.getStartAt(), config.getEndAt(), config.getUpdatedAt(),
                gateway.cookieStatus().name(), gateway.cookieSource(), gateway.cookieUpdatedAt(), tasks,
                AtcoderContestSupport.options(configs, config.getContestId(), clock));
    }

    private ContestView toContestView(AtcoderLeaderboardConfig config) {
        return new ContestView(config.getContestId(), config.getDisplayTitle(), config.getOfficialTitle(),
                config.getStartAt(), config.getEndAt(), AtcoderContestSupport.status(config, clock.instant()),
                "https://atcoder.jp/contests/" + config.getContestId() + "/standings");
    }

    private static TaskView toTaskView(String contestId, AtcoderStandings.Task task) {
        return new TaskView(task.id(), task.label(), task.name(), task.maxScore(),
                "https://atcoder.jp/contests/" + contestId + "/tasks/" + task.id());
    }

    private List<AtcoderStandings.Task> tasksWithConfiguredNameFallback(
            String contestId, List<AtcoderStandings.Task> liveTasks, AtcoderLeaderboardConfig config) {
        if (config == null || !contestId.equalsIgnoreCase(config.getContestId())) return liveTasks;
        Map<String, AtcoderStandings.Task> configured = new HashMap<>();
        for (AtcoderStandings.Task task : readTasks(config.getTasksJson())) configured.put(task.id(), task);
        return liveTasks.stream().map(task -> {
            AtcoderStandings.Task stored = configured.get(task.id());
            if (!isUnavailableTaskName(task) || stored == null || isUnavailableTaskName(stored)) return task;
            return new AtcoderStandings.Task(task.id(), task.label(), stored.name(), task.maxScore());
        }).toList();
    }

    private static boolean isUnavailableTaskName(AtcoderStandings.Task task) {
        if (task == null || task.name() == null || task.name().isBlank()) return true;
        String name = task.name().strip();
        if (name.equalsIgnoreCase(task.id()) || name.equalsIgnoreCase(task.label())) return true;
        return name.matches("(?i)(?:task|problem)\\s*" + Pattern.quote(task.label()));
    }

    private ParticipantView toParticipantView(AtcoderLeaderboardParticipant entity) {
        return new ParticipantView(entity.getId(), entity.getDisplayName(), entity.getAtcoderUsername(),
                entity.getSortOrder(), entity.getCreatedAt());
    }

    private LeaderboardView emptyLeaderboard() {
        return new LeaderboardView(false, false, null, List.of(), List.of(), List.of(), 0, 0,
                null, false, false, null, false, 0, 0);
    }

    private LeaderboardView errorLeaderboard(AtcoderLeaderboardConfig config, String error, int cooldown) {
        List<TaskView> tasks = readTasks(config.getTasksJson()).stream()
                .map(task -> toTaskView(config.getContestId(), task)).toList();
        List<EntryView> entries = participants.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(participant -> new EntryView(participant.getId(), null, participant.getDisplayName(),
                        participant.getAtcoderUsername(), BigDecimal.ZERO, 0, "--:--:--", 0,
                        null, new MovementView("NONE", 0), "NOT_STARTED",
                        tasks.stream().map(task -> emptyTaskResult(task.id())).toList()))
                .toList();
        ContestView contest = toContestView(config);
        boolean autoRefresh = "RUNNING".equals(contest.status());
        return new LeaderboardView(true, false, contest,
                AtcoderContestSupport.options(configs, config.getContestId(), clock),
                tasks, entries, entries.size(), 0, null, false, false, error,
                autoRefresh, autoRefresh ? CLIENT_REFRESH_SECONDS : 0, cooldown);
    }

    private Comparator<Candidate> candidateComparator() {
        return (first, second) -> {
            if (first.entry == null && second.entry != null) return 1;
            if (first.entry != null && second.entry == null) return -1;
            if (first.entry != null) {
                Integer aRank = first.entry.officialRank();
                Integer bRank = second.entry.officialRank();
                if (aRank != null && bRank != null) {
                    int rank = Integer.compare(aRank, bRank);
                    if (rank != 0) return rank;
                } else {
                    int score = second.entry.totalScore().compareTo(first.entry.totalScore());
                    if (score != 0) return score;
                    int elapsed = Long.compare(first.entry.elapsedNanos(), second.entry.elapsedNanos());
                    if (elapsed != 0) return elapsed;
                    int penalty = Integer.compare(first.entry.penalty(), second.entry.penalty());
                    if (penalty != 0) return penalty;
                }
            }
            int order = Integer.compare(first.participant.getSortOrder(), second.participant.getSortOrder());
            if (order != 0) return order;
            return Long.compare(first.participant.getId(), second.participant.getId());
        };
    }

    private static boolean sameStanding(AtcoderStandings.Entry first, AtcoderStandings.Entry second) {
        if (first == null || second == null) return false;
        if (first.officialRank() != null && second.officialRank() != null) {
            return first.officialRank().equals(second.officialRank());
        }
        return first.totalScore().compareTo(second.totalScore()) == 0
                && first.elapsedNanos() == second.elapsedNanos()
                && first.penalty() == second.penalty();
    }

    private static MovementView movement(Integer previousRank, Integer currentRank) {
        if (currentRank == null) return new MovementView("NONE", 0);
        if (previousRank == null) return new MovementView("NEW", 0);
        int delta = previousRank - currentRank;
        if (delta > 0) return new MovementView("UP", delta);
        if (delta < 0) return new MovementView("DOWN", -delta);
        return new MovementView("SAME", 0);
    }

    private static Map<Long, Integer> ranks(LeaderboardView view) {
        Map<Long, Integer> result = new HashMap<>();
        if (view == null) return result;
        for (EntryView entry : view.entries()) {
            if (entry.classRank() != null) result.put(entry.participantId(), entry.classRank());
        }
        return result;
    }

    private int remainingCooldown(AtcoderLeaderboardSnapshot snapshot, Instant now) {
        if (snapshot.getLastAttemptAt() == null) return 0;
        Instant readyAt = snapshot.getLastAttemptAt().plus(MANUAL_REFRESH_COOLDOWN);
        if (!readyAt.isAfter(now)) return 0;
        long millis = Duration.between(now, readyAt).toMillis();
        return (int) Math.max(1, (millis + 999) / 1000);
    }

    private void persistSuccessfulSnapshot(String contestId, AtcoderStandings.Snapshot standings, Instant now) {
        AtcoderLeaderboardSnapshot snapshot = snapshots.findById(contestId)
                .orElseGet(() -> new AtcoderLeaderboardSnapshot(contestId));
        snapshot.succeeded(writeStandings(standings), now);
        snapshots.saveAndFlush(snapshot);
    }

    private String writeTasks(List<AtcoderStandings.Task> tasks) {
        try { return mapper.writeValueAsString(tasks); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("比赛题目列表保存失败", ex); }
    }

    private List<AtcoderStandings.Task> readTasks(String json) {
        try { return mapper.readValue(json, new TypeReference<List<AtcoderStandings.Task>>() {}); }
        catch (Exception ex) { return List.of(); }
    }

    private String writeStandings(AtcoderStandings.Snapshot standings) {
        try { return mapper.writeValueAsString(standings); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("排行榜快照保存失败", ex); }
    }

    private AtcoderStandings.Snapshot readStandings(AtcoderLeaderboardSnapshot snapshot) {
        if (snapshot == null || snapshot.getStandingsJson() == null || snapshot.getStandingsJson().isBlank()) {
            return null;
        }
        try { return mapper.readValue(snapshot.getStandingsJson(), AtcoderStandings.Snapshot.class); }
        catch (Exception ex) { return null; }
    }

    private static String normalizeUsername(String value) {
        String username = value == null ? "" : value.trim();
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("AtCoder ID 只能包含字母、数字和下划线，最长 32 位");
        }
        return username;
    }

    private static String normalizeDisplayName(String value) {
        String displayName = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (displayName.isEmpty() || displayName.length() > 40) {
            throw new IllegalArgumentException("展示昵称需要 1 至 40 个字符");
        }
        if (displayName.indexOf('\0') >= 0) throw new IllegalArgumentException("展示昵称包含无效字符");
        return displayName;
    }

    private static String normalizeOptionalTitle(String value) {
        String title = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (title.length() > 160) throw new IllegalArgumentException("展示标题不能超过 160 个字符");
        return title;
    }

    private static String normalizeOfficialTitle(String value, String contestId) {
        String title = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (title.isBlank()) title = contestId.toUpperCase(Locale.ROOT);
        return title.length() > 240 ? title.substring(0, 240) : title;
    }

    private static String publicError(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "AtCoder 榜单暂时无法刷新" : message;
    }

    private static long nanosToSeconds(long nanos) {
        return Math.max(0, nanos / 1_000_000_000L);
    }

    private static String formatElapsed(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, rest);
    }

    private record Candidate(AtcoderLeaderboardParticipant participant, AtcoderStandings.Entry entry) {}
}
