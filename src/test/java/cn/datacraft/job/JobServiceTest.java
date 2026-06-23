package cn.datacraft.job;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobServiceTest {
    @Test
    void recognizesCppProgramAndRejectsMarkdownOrCodeFences() {
        assertThat(JobService.hasMainFunction("#include <bits/stdc++.h>\nint main() { return 0; }")).isTrue();
        assertThat(JobService.hasMainFunction("signed main(){ return 0; }")).isTrue();
        assertThat(JobService.hasMainFunction("# 问题描述\n这是题面，不是代码。")).isFalse();
        assertThat(JobService.hasMainFunction("```cpp\nint main(){}\n```")) .isFalse();
    }

    @Test
    void generatedSeedsAlwaysFitInPositiveIntRange() {
        UUID[] ids = {
                new UUID(Long.MIN_VALUE, Long.MAX_VALUE),
                new UUID(Long.MAX_VALUE, Long.MIN_VALUE),
                UUID.fromString("62af2a41-7f8b-4828-88f9-d2c1ff706a9f")
        };

        for (UUID id : ids) {
            for (int caseNumber = 1; caseNumber <= 100; caseNumber++) {
                long seed = JobService.createSeed(id, caseNumber);
                assertThat(seed).isPositive().isLessThanOrEqualTo(Integer.MAX_VALUE);
                assertThat(JobService.createSeed(id, caseNumber)).isEqualTo(seed);
            }
        }
    }
}
