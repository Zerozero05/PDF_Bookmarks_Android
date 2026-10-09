package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 仅供宿主测试；提供真实 PDFBox AAR 资源，不模拟 PDF 解析或写入。 */
public final class AssetManager {
    private final File directory;
    public AssetManager(File directory) { this.directory = directory; }
    public InputStream open(String path) throws IOException {
        return new FileInputStream(new File(directory, path));
    }
}
