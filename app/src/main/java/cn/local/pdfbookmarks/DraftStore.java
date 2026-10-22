package cn.local.pdfbookmarks;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 仅使用调用方的应用内部 filesDir；不会读取、改写或删除原 PDF / JSON。 */
public final class DraftStore {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final AtomicFile file;

    public static final class LoadResult {
        public final ReviewDraft draft;
        public final String error;

        private LoadResult(ReviewDraft draft, String error) {
            this.draft = draft;
            this.error = error;
        }
    }

    public DraftStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("缺少应用内部草稿目录");
        file = new AtomicFile(new File(filesDir, "review-draft-v1.json"));
    }

    /** 应从串行后台 IO 调用；缺少草稿与草稿损坏可由 error 明确区分。 */
    public synchronized LoadResult load() {
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return new LoadResult(null, null);
        }
        try (FileInputStream stream = file.openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("核对草稿超过大小限制");
                bytes.write(buffer, 0, count);
            }
            return new LoadResult(ReviewDraft.fromJson(new String(bytes.toByteArray(), StandardCharsets.UTF_8)), null);
        } catch (IOException | IllegalArgumentException e) {
            return new LoadResult(null, "未恢复核对草稿：" + e.getMessage());
        }
    }

    /** 原子写入后回读校验；失败时保留 AtomicFile 的恢复信息并向调用方报告。 */
    public synchronized void save(ReviewDraft draft) throws IOException {
        if (draft == null) throw new IllegalArgumentException("核对草稿不能为空");
        String json = draft.toJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("核对草稿超过大小限制");
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (IOException e) {
            if (stream != null) file.failWrite(stream);
            throw new IOException("保存核对草稿失败", e);
        }
        LoadResult actual = load();
        if (actual.draft == null || !json.equals(actual.draft.toJson())) {
            throw new IOException(actual.error == null ? "保存核对草稿后校验失败" : actual.error);
        }
    }

    /** 只清除本工具的一个草稿，不清除文件选择、备份或任务记录。 */
    public synchronized void clear() throws IOException {
        file.delete();
        if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()
                || new File(file.getBaseFile().getPath() + ".new").exists()) {
            throw new IOException("无法清除旧核对草稿");
        }
    }
}
