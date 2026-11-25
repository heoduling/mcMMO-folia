package com.gmail.nossr50.util.blockmeta;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McMMOSimpleRegionFileProtectionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(ints = {0, 1000})
    void rejectsExistingShortFileWithoutChangingBytesAndReleasesHandle(int size) throws Exception {
        Path file = directory.resolve("existing.mcm");
        byte[] original = new byte[size];
        Arrays.fill(original, (byte) 0x5A);
        Files.write(file, original);
        assertThrows(RuntimeException.class, () -> new McMMOSimpleRegionFile(file.toFile(), 0, 0));
        assertArrayEquals(original, Files.readAllBytes(file));
        Files.move(file, directory.resolve("unlocked.mcm"));
    }

    @Test
    void initializesOnlyNewFileAndReopensWrittenChunk() throws Exception {
        Path file = directory.resolve("new.mcm");
        try (var region = new McMMOSimpleRegionFile(file.toFile(), 0, 0)) {
            try (var output = region.getOutputStream(0, 0)) {
                output.writeInt(42);
            }
        }
        try (var region = new McMMOSimpleRegionFile(file.toFile(), 0, 0);
                var input = region.getInputStream(0, 0)) {
            assertNotNull(input);
            assertEquals(42, input.readInt());
        }
    }
}
