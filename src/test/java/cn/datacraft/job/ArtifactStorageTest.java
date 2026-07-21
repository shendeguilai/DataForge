package cn.datacraft.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactStorageTest {
    @TempDir Path tempDir;

    @Test
    void storesOnlyPortableKeysAndPublishesAtomically() throws Exception {
        ArtifactStorage storage = new ArtifactStorage(tempDir.toString());
        UUID id = UUID.randomUUID();
        Path pending = storage.pendingZip(id);
        Files.write(pending, "zip-content".getBytes(StandardCharsets.UTF_8));

        Path published = storage.publish(id, pending);

        assertThat(published).isEqualTo(storage.finalZip(id));
        assertThat(pending).doesNotExist();
        assertThat(storage.toStoredKey(published)).isEqualTo(ArtifactStorage.keyFor(id));
        assertThat(storage.resolveStored(ArtifactStorage.keyFor(id))).isEqualTo(published);
    }

    @Test
    void rejectsPathsOutsideRuntimeRoot() throws Exception {
        ArtifactStorage storage = new ArtifactStorage(tempDir.resolve("runtime").toString());
        Path outside = tempDir.resolve("outside.zip");

        assertThatThrownBy(() -> storage.toStoredKey(outside))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resolveStored("../outside.zip"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.resolveStored(outside.toAbsolutePath().toString()))
                .isInstanceOf(IllegalStateException.class);
    }
}
