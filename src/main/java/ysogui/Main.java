package ysogui;

/**
 * 独立入口类，避免 JavaFX 在某些打包环境下的 ClassLoader 问题
 */
public class Main {
    public static void main(String[] args) {
        App.main(args);
    }
}
