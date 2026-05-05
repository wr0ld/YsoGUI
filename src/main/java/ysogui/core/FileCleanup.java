package ysogui.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class FileCleanup {

    private FileCleanup() {
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    public static void deleteRecursivelyQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (Exception ignored) {
        }
    }
}
