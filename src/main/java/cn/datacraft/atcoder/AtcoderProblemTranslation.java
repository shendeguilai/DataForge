package cn.datacraft.atcoder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "atcoder_problem_translations", uniqueConstraints =
        @UniqueConstraint(name = "uk_atcoder_problem_translation_task", columnNames = {"contest_id", "task_id"}))
class AtcoderProblemTranslation {
    enum Status { IMPORTED, QUEUED, FETCHING, TRANSLATING, READY, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contest_id", nullable = false, length = 64)
    private String contestId;

    @Column(name = "task_id", nullable = false, length = 128)
    private String taskId;

    @Column(name = "task_label", nullable = false, length = 16)
    private String taskLabel;

    @Column(name = "task_name", nullable = false, length = 240)
    private String taskName;

    @Column(name = "task_order", nullable = false)
    private int taskOrder;

    @Column(name = "source_html", columnDefinition = "TEXT")
    private String sourceHtml;

    @Column(name = "translated_html", columnDefinition = "TEXT")
    private String translatedHtml;

    @Column(name = "draft_html", columnDefinition = "TEXT")
    private String draftHtml;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 24)
    private Status status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "source_fetched_at")
    private Instant sourceFetchedAt;

    @Column(name = "translated_at")
    private Instant translatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AtcoderProblemTranslation() {}

    AtcoderProblemTranslation(String contestId, AtcoderStandings.Task task, int taskOrder, Instant now) {
        this.contestId = contestId;
        this.taskId = task.id();
        this.taskLabel = task.label();
        this.taskName = task.name();
        this.taskOrder = taskOrder;
        queue(now, true);
    }

    void refreshTask(AtcoderStandings.Task task, int order) {
        this.taskLabel = task.label();
        this.taskName = task.name();
        this.taskOrder = order;
    }

    void queue(Instant now, boolean clearContent) {
        status = Status.QUEUED;
        errorMessage = null;
        if (clearContent) {
            sourceHtml = null;
            translatedHtml = null;
            draftHtml = null;
            sourceFetchedAt = null;
            translatedAt = null;
        }
        updatedAt = now;
    }

    void fetching(Instant now) {
        status = Status.FETCHING;
        errorMessage = null;
        updatedAt = now;
    }

    void translating(String sourceHtml, Instant now) {
        this.sourceHtml = sourceHtml;
        sourceFetchedAt = now;
        translatedHtml = null;
        translatedAt = null;
        status = Status.TRANSLATING;
        errorMessage = null;
        updatedAt = now;
    }

    void imported(String sourceHtml, Instant now) {
        this.sourceHtml = sourceHtml;
        sourceFetchedAt = now;
        translatedHtml = null;
        draftHtml = null;
        translatedAt = null;
        status = Status.IMPORTED;
        errorMessage = null;
        updatedAt = now;
    }

    void ready(String translatedHtml, Instant now) {
        this.translatedHtml = translatedHtml;
        translatedAt = now;
        status = Status.READY;
        errorMessage = null;
        updatedAt = now;
    }

    void draft(String draftHtml, Instant now) {
        this.draftHtml = draftHtml;
        updatedAt = now;
    }

    void fail(String message, Instant now) {
        status = Status.FAILED;
        errorMessage = message;
        updatedAt = now;
    }

    Long getId() { return id; }
    String getContestId() { return contestId; }
    String getTaskId() { return taskId; }
    String getTaskLabel() { return taskLabel; }
    String getTaskName() { return taskName; }
    int getTaskOrder() { return taskOrder; }
    String getSourceHtml() { return sourceHtml; }
    String getTranslatedHtml() { return translatedHtml; }
    String getDraftHtml() { return draftHtml; }
    Status getStatus() { return status; }
    String getErrorMessage() { return errorMessage; }
    Instant getSourceFetchedAt() { return sourceFetchedAt; }
    Instant getTranslatedAt() { return translatedAt; }
    Instant getUpdatedAt() { return updatedAt; }
}
