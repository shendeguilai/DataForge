package cn.datacraft.cspsim;

import cn.datacraft.job.ArtifactStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class CspDiskDataTest {
    @TempDir Path runtime;
    CspFiles files() throws IOException { return new CspFiles(new ArtifactStorage(runtime.toString())); }
    @Test void accepts500MbBoundaryAndRejectsOversizedTotalsBeforeOpeningStreams() throws Exception {
        assertThatCode(()->CspFiles.dataSize(500L*1024*1024)).doesNotThrowAnyException();
        assertThatThrownBy(()->CspFiles.dataSize(500L*1024*1024+1)).hasMessageContaining("500MB");
        AtomicBoolean opened=new AtomicBoolean();
        CspFiles.InputSource source=()->{opened.set(true);return InputStream.nullInputStream();};
        assertThatThrownBy(()->files().stageFiles(List.of(new CspFiles.DataUpload("1.in",CspFiles.MAX_DATA_UPLOAD,source),new CspFiles.DataUpload("1.out",1,source))))
                .hasMessageContaining("500MB");
        assertThat(opened).isFalse();
    }
    @Test void multiFilesLargerThanOldLimitAreStreamedAndPublishedWithoutByteArrays() throws Exception {
        Path input=runtime.resolve("large.in"); long size=30L*1024*1024;
        try (var file=new RandomAccessFile(input.toFile(),"rw")) { file.setLength(size); }
        CspFiles files=files(); Path temporary;
        try (var staged=files.stageFiles(List.of(new CspFiles.DataUpload("large.in",size,()->Files.newInputStream(input)),
                new CspFiles.DataUpload("large.out",0,InputStream::nullInputStream)))) {
            temporary=staged.entries.get("large.in");
            assertThat(Files.size(temporary)).isEqualTo(size);
            String blob=files.put(temporary);
            assertThat(files.size(blob)).isEqualTo(size);
            assertThat(temporary).doesNotExist();
        }
        try (var directories=Files.list(runtime.resolve("csp-sim"))) { assertThat(directories.filter(p->p.getFileName().toString().startsWith("upload-")).count()).isZero(); }
    }
    @Test void compressedDataAboveOldExpandedFileLimitIsStreamedAndUnsafeArchivesCleanUp() throws Exception {
        Path archive=runtime.resolve("data.zip"); byte[] block=new byte[65536];
        try(var output=new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("data/1.in"));for(int i=0;i<480;i++)output.write(block);output.closeEntry();
            output.putNextEntry(new ZipEntry("data/1.out"));output.write(49);output.closeEntry();
        }
        CspFiles files=files();
        try(var staged=files.stageZip(Files.newInputStream(archive),Files.size(archive))) { assertThat(Files.size(staged.entries.get("data/1.in"))).isEqualTo(30L*1024*1024); }
        byte[] unsafe=CspFiles.zip(Map.of("../1.in",new byte[1]));
        assertThatThrownBy(()->files.stageZip(new ByteArrayInputStream(unsafe),unsafe.length)).hasMessageContaining("不安全");
        try(var directories=Files.list(runtime.resolve("csp-sim"))) { assertThat(directories.filter(p->p.getFileName().toString().startsWith("upload-")).count()).isZero(); }
    }
}
