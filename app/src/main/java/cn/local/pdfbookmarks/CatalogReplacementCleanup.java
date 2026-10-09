package cn.local.pdfbookmarks;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** 新目录校验成功后，仅丢弃本次目录替换的旧件，不处理 PDF 备份。 */
public final class CatalogReplacementCleanup {
    private CatalogReplacementCleanup() { }

    public interface Action {
        void run() throws IOException;
    }

    public static void discard(BackupCleanup.Item item, Action saveProgress,
                               Action showPlan, BackupCleanup.Guard guard,
                               Action finish) throws IOException {
        if (item == null || saveProgress == null || showPlan == null
                || guard == null || finish == null) {
            throw new IOException("缺少目录替换清理步骤");
        }
        List<BackupCleanup.Item> plan = Collections.singletonList(item);
        BackupCleanup.verifyAll(plan, false);
        saveProgress.run();
        showPlan.run();
        BackupCleanup.removeAll(plan, false, guard);
        finish.run();
    }
}
