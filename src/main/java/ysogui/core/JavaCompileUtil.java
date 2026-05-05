package ysogui.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class JavaCompileUtil {

    private JavaCompileUtil() {
    }

    public static String deriveClassName(File file) {
        String name = file.getName();

        String fullName = name.replace(".class", "");
        File dir = file.getParentFile();
        String[] packageRoots = {"com", "org", "net", "io", "me", "cn"};
        List<String> parts = new ArrayList<>();
        parts.add(0, fullName);

        File current = dir;
        while (current != null) {
            File[] subDirs = current.listFiles(File::isDirectory);
            if (subDirs != null) {
                for (String root : packageRoots) {
                    for (File sd : subDirs) {
                        if (sd.getName().equals(root)) {
                            List<String> pkgParts = new ArrayList<>();
                            File p = dir;
                            while (p != null && !p.equals(current)) {
                                pkgParts.add(0, p.getName());
                                p = p.getParentFile();
                            }
                            pkgParts.addAll(parts);
                            return String.join(".", pkgParts);
                        }
                    }
                }
            }
            parts.add(0, current.getName());
            current = current.getParentFile();
        }
        return fullName;
    }

    public static void copyInnerClasses(File classFile, Path destDir) throws IOException {
        String baseName = classFile.getName().replace(".class", "");
        File parentDir = classFile.getParentFile();
        File[] innerClasses = parentDir.listFiles(f ->
            f.getName().startsWith(baseName + "$") && f.getName().endsWith(".class"));
        if (innerClasses != null) {
            for (File ic : innerClasses) {
                Path icDest = destDir.resolve(ic.getName());
                Files.copy(ic.toPath(), icDest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static String readHelpResource(String name) {
        InputStream is = JavaCompileUtil.class.getClassLoader()
            .getResourceAsStream("help/" + name);
        if (is == null) return "帮助文件未找到: help/" + name;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "读取帮助文件失败: " + e.getMessage();
        }
    }

}
