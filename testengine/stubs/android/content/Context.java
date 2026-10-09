package android.content;

import android.content.res.AssetManager;
import java.io.File;

/** 仅供宿主测试：将真实 AAR 的资源交给 PDFBoxResourceLoader。 */
public final class Context {
    private final AssetManager assets;
    public Context(File directory) { assets = new AssetManager(directory); }
    public Context getApplicationContext() { return this; }
    public AssetManager getAssets() { return assets; }
}
