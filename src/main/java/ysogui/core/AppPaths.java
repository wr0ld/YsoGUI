package ysogui.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 项目内数据路径约定。
 *
 * 所有用户可写数据统一落在当前项目目录下，避免写入 C 盘用户目录或注册表。
 */
public final class AppPaths {

    private AppPaths() {}

    public static Path projectRoot() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    public static Path configDir() {
        return projectRoot().resolve("config");
    }

    public static Path appStateFile() {
        return configDir().resolve("app-state.properties");
    }

    public static Path customGraphsFile() {
        return projectRoot().resolve("custom-graphs.json");
    }

    public static Path chainMetaDir() {
        return projectRoot().resolve("chain-meta");
    }

    public static Path chainMetaFile() {
        return chainMetaDir().resolve("custom.json");
    }

    public static Path memshellDir() {
        return projectRoot().resolve("memshells");
    }

    public static Path memshellConfigFile() {
        return memshellDir().resolve("custom.json");
    }

    public static Path memshellClassDir() {
        return memshellDir().resolve("classes");
    }

    public static Path payloadDir() {
        return projectRoot().resolve("payloads");
    }
}
