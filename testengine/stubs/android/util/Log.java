package android.util;

/** 仅供宿主测试：Android 日志不参与 PDF 数据处理。此目录不进入 APK。 */
public final class Log {
    public static int v(String tag, String message) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static boolean isLoggable(String tag, int level) { return false; }
}
