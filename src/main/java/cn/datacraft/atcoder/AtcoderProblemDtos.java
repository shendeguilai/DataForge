package cn.datacraft.atcoder;

import java.time.Instant;
import java.util.List;

public final class AtcoderProblemDtos {
    private AtcoderProblemDtos() {}

    public record ProblemContestView(String id, String title, String officialTitle, String url) {}

    public record ProblemTaskView(String id, String label, String name, String status,
                                  String officialUrl, Instant updatedAt, String error,
                                  boolean hasSource, boolean hasDraft, boolean hasTranslation) {}

    public record ProblemOverviewView(boolean configured, ProblemContestView contest, String status,
                                      int totalCount, int readyCount, int failedCount, boolean running,
                                      List<ProblemTaskView> tasks) {}

    public record ProblemDetailView(ProblemContestView contest, ProblemTaskView task,
                                    String sourceHtml, String translatedHtml,
                                    Instant sourceFetchedAt, Instant translatedAt) {}

    public record AdminProblemDetailView(ProblemContestView contest, ProblemTaskView task,
                                         String sourceHtml, String translatedHtml, String draftHtml, String editorHtml,
                                         Instant sourceFetchedAt, Instant translatedAt) {}
}
