package cn.datacraft.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:generation-queue;DB_CLOSE_DELAY=-1","dataforge.homework.scheduling-enabled=false"})
class GenerationQueueIntegrationTest {
    @Autowired JobService jobs;
    @Autowired JobRepository repository;
    @Autowired GenerationTaskRepository tasks;
    @Autowired JobRecordJpaRepository jobRecords;
    @Autowired org.springframework.beans.factory.ObjectProvider<JobService> provider;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    static final java.nio.file.Path RUNTIME;
    static { try { RUNTIME=java.nio.file.Files.createTempDirectory("generation-queue-test-"); } catch(Exception e){throw new ExceptionInInitializerError(e);} }
    @org.springframework.test.context.DynamicPropertySource static void properties(org.springframework.test.context.DynamicPropertyRegistry r){r.add("dataforge.runtime-dir",RUNTIME::toString);}
    @BeforeEach void clearFixtures(){tasks.deleteAll();jobRecords.deleteAll();}
    GenerationRequest input(){var r=new GenerationRequest();r.setStatement("# 求和");r.setRequirements("随机整数");r.setStandardCode("int main(){return 0;}");return r;}
    @Test void newJobIsDurablyQueuedAndCancelledWriterCannotResurrectIt() {
        var job=jobs.create(input(),1L,10000);
        assertThat(tasks.findById(job.getId().toString()).orElseThrow().state).isEqualTo("QUEUED");
        var stale=repository.find(job.getId()).orElseThrow();
        jobs.cancel(job.getId());
        stale.update(JobStatus.GENERATING,50,"迟到的写入");
        assertThatThrownBy(()->repository.save(stale)).isInstanceOf(GenerationCancelledException.class);
        assertThat(tasks.findById(job.getId().toString()).orElseThrow().state).isEqualTo("CANCELLED");
        assertThat(repository.find(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
        jobs.retryPlan(job.getId());
        assertThat(tasks.findById(job.getId().toString()).orElseThrow().phase).isEqualTo("ANALYZE");
    }
    @Test void retryGenerationKeepsTheConfirmedPlanAndOwnershipIsChecked() {
        var job=new GenerationJob();job.setUserId(1L);job.setRequest(input());
        var plan=new DataPlan();plan.setGeneratorCode("int main(){return 0;}");job.setPlan(plan);
        job.restore(JobStatus.FAILED,50,"失败","运行超时",Instant.now());repository.save(job);
        assertThatThrownBy(()->jobs.requireOwned(job.getId(),999L)).isInstanceOf(java.util.NoSuchElementException.class);
        var resumed=jobs.retryGeneration(job.getId());
        assertThat(resumed.getPlan().getGeneratorCode()).isEqualTo(plan.getGeneratorCode());
        assertThat(tasks.findById(job.getId().toString()).orElseThrow().phase).isEqualTo("GENERATE");
    }
    @Test void expiredLeaseIsRecoveredAfterDispatcherRestart() {
        var job=jobs.create(input(),1L,10000);
        var old=tasks.findById(job.getId().toString()).orElseThrow();old.state="RUNNING";old.token="old-worker";
        old.leaseUntil=Instant.now().minusSeconds(5);tasks.saveAndFlush(old);
        var restarted=new GenerationTaskQueue(tasks,provider,Runnable::run,transactions,true);
        restarted.dispatch();
        assertThat(repository.find(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.WAITING_CONFIRMATION);
        assertThat(tasks.findById(job.getId().toString()).orElseThrow().state).isEqualTo("DONE");
    }
    private GenerationJob generationFixture(String validator,String brute) {
        var request=input();request.setCaseCount(1);
        request.setStandardCode("#include<iostream>\nint main(){std::cout<<42<<'\\n';}");
        request.setValidatorCode(validator);request.setBruteCode(brute);
        var plan=new DataPlan();plan.setGeneratorCode("#include<iostream>\nint main(){std::cout<<1<<'\\n';}");
        var job=new GenerationJob();job.setUserId(1L);job.setRequest(request);job.setPlan(plan);
        job.update(JobStatus.QUEUED,22,"测试数据生成");repository.save(job);return job;
    }
    @Test void invalidInputFailsWithReproducibleSeed() {
        var job=generationFixture("int main(){return 1;}",null);
        jobs.generate(job.getId());var failed=repository.find(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getError()).contains("Validator","第 1 组","seed=");
        assertThat(failed.getArtifact()).isNull();
    }
    @Test void mismatchedBruteOutputFailsInsteadOfPublishingAnArchive() {
        var job=generationFixture(null,"#include<iostream>\nint main(){std::cout<<43<<'\\n';}");
        jobs.generate(job.getId());var failed=repository.find(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getError()).contains("对拍不一致","第 1 组","seed=");
        assertThat(failed.getArtifact()).isNull();
    }
}
