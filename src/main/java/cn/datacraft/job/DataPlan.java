package cn.datacraft.job;

import java.util.ArrayList;
import java.util.List;

public class DataPlan {
    private String summary;
    private String estimatedSize;
    private List<PlanGroup> groups = new ArrayList<>();
    private String generatorCode;
    private boolean aiGenerated;

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getEstimatedSize() { return estimatedSize; }
    public void setEstimatedSize(String estimatedSize) { this.estimatedSize = estimatedSize; }
    public List<PlanGroup> getGroups() { return groups; }
    public void setGroups(List<PlanGroup> groups) { this.groups = groups; }
    public String getGeneratorCode() { return generatorCode; }
    public void setGeneratorCode(String generatorCode) { this.generatorCode = generatorCode; }
    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean aiGenerated) { this.aiGenerated = aiGenerated; }

    public static class PlanGroup {
        private String range;
        private String purpose;
        public PlanGroup() {}
        public PlanGroup(String range, String purpose) { this.range = range; this.purpose = purpose; }
        public String getRange() { return range; }
        public void setRange(String range) { this.range = range; }
        public String getPurpose() { return purpose; }
        public void setPurpose(String purpose) { this.purpose = purpose; }
    }
}
