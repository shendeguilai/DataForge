package cn.datacraft.job;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class GenerationRequest {
    @NotBlank @Size(max = 100000)
    private String statement;
    @NotBlank @Size(max = 100000)
    private String standardCode;
    @NotBlank @Size(max = 30000)
    private String requirements;
    @Min(1) @Max(100)
    private int caseCount = 10;
    private String cppStandard = "c++17";

    public String getStatement() { return statement; }
    public void setStatement(String statement) { this.statement = statement; }
    public String getStandardCode() { return standardCode; }
    public void setStandardCode(String standardCode) { this.standardCode = standardCode; }
    public String getRequirements() { return requirements; }
    public void setRequirements(String requirements) { this.requirements = requirements; }
    public int getCaseCount() { return caseCount; }
    public void setCaseCount(int caseCount) { this.caseCount = caseCount; }
    public String getCppStandard() { return cppStandard; }
    public void setCppStandard(String cppStandard) { this.cppStandard = cppStandard; }
}
