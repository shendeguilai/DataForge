package cn.datacraft.tools;

import java.util.ArrayList;
import java.util.List;

public class AtcoderStatsResponse {
    public String username;
    public String generatedAt;
    public List<ContestStats> contests = new ArrayList<>();

    public static class ContestStats {
        public String contestId;
        public String contestName;
        public String contestUrl;
        public String startTime;
        public String endTime;
        public Boolean rated;
        public Integer place;
        public Integer oldRating;
        public Integer newRating;
        public Integer ratingDelta;
        public Integer performance;
        public int acCount;
        public int submissionCount;
        public List<ProblemStats> problems = new ArrayList<>();
        public List<SubmissionStats> submissions = new ArrayList<>();
    }

    public static class ProblemStats {
        public String problemId;
        public String problemIndex;
        public String title;
        public String url;
        public boolean accepted;
        public int submissionCount;
        public boolean globalStatsAvailable;
        public int globalAcceptedCount;
        public int globalSubmissionUserCount;
        public String firstAcceptedAt;
        public String firstAcceptedElapsed;
    }

    public static class SubmissionStats {
        public long id;
        public String problemId;
        public String problemIndex;
        public String problemTitle;
        public String submittedAt;
        public long elapsedSeconds;
        public String elapsedText;
        public String result;
        public String language;
        public Double point;
        public String url;
    }
}
