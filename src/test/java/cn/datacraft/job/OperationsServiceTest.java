package cn.datacraft.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationsServiceTest {
    @TempDir Path root;
    @Test void cleanupKeepsArchivesReportsAndActiveJobs()throws Exception {
        var storage=new ArtifactStorage(root.toString());var jobs=mock(JobRepository.class);
        var operations=new OperationsService(storage,jobs,mock(JobRecordJpaRepository.class),mock(GenerationTaskRepository.class),30,false);
        var old=Instant.now().minus(Duration.ofDays(40));var job=new GenerationJob();
        job.restore(JobStatus.COMPLETED,100,"完成",null,old);
        when(jobs.find(job.getId())).thenReturn(Optional.of(job));
        Path dir=Files.createDirectories(root.resolve(job.getId().toString()));
        Path program=Files.writeString(dir.resolve("standard"),"binary");
        Path report=Files.writeString(dir.resolve("manifest.json"),"{}");
        Path archive=Files.writeString(storage.finalZip(job.getId()),"archive");
        Files.setLastModifiedTime(program,FileTime.from(old));
        var preview=operations.preview("admin");assertThat(preview.get("count")).isEqualTo(1);
        assertThat(operations.cleanup("admin",String.valueOf(preview.get("token")))).isEqualTo(1);
        assertThat(program).doesNotExist();assertThat(report).exists();assertThat(archive).exists();
        Files.writeString(program,"binary");Files.setLastModifiedTime(program,FileTime.from(old));
        preview=operations.preview("admin");job.update(JobStatus.QUEUED,22,"重新生成");
        assertThat(operations.cleanup("admin",String.valueOf(preview.get("token")))).isZero();assertThat(program).exists();
    }
    @Test void symlinksAndChangedFilesAreNeverRemoved()throws Exception {
        var storage=new ArtifactStorage(root.toString());var jobs=mock(JobRepository.class);
        var operations=new OperationsService(storage,jobs,mock(JobRecordJpaRepository.class),mock(GenerationTaskRepository.class),30,false);
        var old=Instant.now().minus(Duration.ofDays(40));var job=new GenerationJob();job.restore(JobStatus.FAILED,10,"失败",null,old);
        when(jobs.find(job.getId())).thenReturn(Optional.of(job));Path dir=Files.createDirectory(root.resolve(job.getId().toString()));
        Path keep=Files.writeString(root.resolve("keep"),"keep");Files.createSymbolicLink(dir.resolve("standard"),keep);
        assertThat(operations.preview("admin").get("count")).isEqualTo(0);
        Path temporary=Files.writeString(dir.resolve("process.err"),"old");Files.setLastModifiedTime(temporary,FileTime.from(old));
        var preview=operations.preview("admin");Files.writeString(temporary,"new contents");
        assertThat(operations.cleanup("admin",String.valueOf(preview.get("token")))).isZero();assertThat(keep).hasContent("keep");
    }
    @Test void generatedFilesAreKeptIfArchiveDisappearsAfterPreview()throws Exception {
        var storage=new ArtifactStorage(root.toString());var jobs=mock(JobRepository.class);
        var operations=new OperationsService(storage,jobs,mock(JobRecordJpaRepository.class),mock(GenerationTaskRepository.class),30,false);
        var old=Instant.now().minus(Duration.ofDays(40));var job=new GenerationJob();job.restore(JobStatus.COMPLETED,100,"完成",null,old);
        when(jobs.find(job.getId())).thenReturn(Optional.of(job));
        Path data=Files.createDirectories(root.resolve(job.getId().toString()).resolve("data"));
        Path generated=Files.writeString(data.resolve("1.in"),"test");Files.setLastModifiedTime(generated,FileTime.from(old));
        Path archive=Files.writeString(storage.finalZip(job.getId()),"archive");
        var preview=operations.preview("admin");assertThat(preview.get("count")).isEqualTo(1);
        Files.delete(archive);
        assertThat(operations.cleanup("admin",String.valueOf(preview.get("token")))).isZero();
        assertThat(generated).hasContent("test");
    }
}
