package cn.local.pdfbookmarks;

import java.util.List;
import org.junit.Test;
import static cn.local.pdfbookmarks.TaskOutcome.Part.*;
import static cn.local.pdfbookmarks.TaskOutcome.Status.*;
import static cn.local.pdfbookmarks.TaskOutcome.Overall.EMPTY;
import static cn.local.pdfbookmarks.TaskOutcome.Overall.IN_PROGRESS;
import static cn.local.pdfbookmarks.TaskOutcome.Overall.COMPLETE;
import static cn.local.pdfbookmarks.TaskOutcome.Overall.PARTIAL;
import static cn.local.pdfbookmarks.TaskOutcome.Overall.WAITING_ACTION;
import static org.junit.Assert.*;

public class TaskOutcomeTest {
    @Test public void noRequestedStageHasNoSuccessOrError() {
        TaskOutcome result = new TaskOutcome(); assertEquals(EMPTY, result.overall());
        result.begin("生成并保存"); assertEquals(EMPTY, result.overall());
    }
    @Test public void optionalStagesDoNotTurnSuccessfulSaveIntoAnError() {
        TaskOutcome result = new TaskOutcome(); result.begin("另存PDF");
        result.set(PDF, SUCCESS, "已生成"); result.set(SAVE, SUCCESS, "保存在选定位置");
        assertEquals(COMPLETE, result.overall());
        assertEquals(NOT_REQUESTED, result.entries().get(2).status);
    }
    @Test public void catalogFailurePreservesBothSuccessfulPdfStagesAndJsonRetention() {
        TaskOutcome result = new TaskOutcome(); result.begin("生成并替换");
        result.set(PDF, SUCCESS, "已生成并核验"); result.set(SAVE, SUCCESS, "原路径已替换");
        result.set(CATALOG, FAILED, "目录文件已变化"); result.set(JSON, RETAINED, "同步未完成，JSON保留");
        result.set(CLEANUP, SKIPPED, "未清理备份");
        assertEquals(PARTIAL, result.overall());
        assertEquals(SUCCESS, result.entries().get(0).status); assertEquals(SUCCESS, result.entries().get(1).status);
        assertEquals("原路径已替换", result.entries().get(1).detail);
        assertTrue(result.summary().contains("部分完成"));
    }
    @Test public void laterDeletionFailureCannotEraseSuccessfulDirectorySync() {
        TaskOutcome result = new TaskOutcome(); result.begin("同步并清理");
        result.set(CATALOG, SUCCESS, "目录已同步"); result.set(CLEANUP, FAILED, "删除权限失效，备份保留");
        assertEquals(PARTIAL, result.overall()); assertEquals(SUCCESS, result.entries().get(2).status);
    }
    @Test public void waitingOrRunningStagesPreventPrematureComplete() {
        TaskOutcome result = new TaskOutcome(); result.begin("生成并保存");
        result.set(PDF, SUCCESS, "生成完成"); result.set(SAVE, WAITING, "待选择保存位置");
        assertEquals(IN_PROGRESS, result.overall());
        result.set(SAVE, RUNNING, "正在保存"); assertEquals(IN_PROGRESS, result.overall());
        result.set(SAVE, SUCCESS, "保存完成"); assertEquals(COMPLETE, result.overall());
    }
    @Test public void failureStillRequiresExplicitHandlingOfUnexecutedStages() {
        TaskOutcome result = new TaskOutcome(); result.begin("生成并替换");
        result.set(PDF, FAILED, "输入已变化"); result.set(SAVE, WAITING, "尚未写入");
        assertEquals(IN_PROGRESS, result.overall());
        result.set(SAVE, SKIPPED, "生成失败，原PDF保留"); result.set(JSON, RETAINED, "未删除");
        assertEquals(TaskOutcome.Overall.FAILED, result.overall());
    }
    @Test public void skippedOrRetainedOptionalWorkDoesNotPretendToHaveFailed() {
        TaskOutcome result = new TaskOutcome(); result.begin("核对已有目录");
        result.set(PDF, SKIPPED, "目录已相同"); result.set(JSON, RETAINED, "按设置保留");
        assertEquals(COMPLETE, result.overall());
    }
    @Test public void cancelledSaveWaitsForUserAndKeepsVerifiedPdfAvailable() {
        TaskOutcome result = new TaskOutcome(); result.begin("另存PDF");
        result.set(PDF, SUCCESS, "PDF已生成并核验");
        result.set(SAVE, AWAITING_USER, "已取消另存，可继续保存");
        result.set(JSON, RETAINED, "JSON保留");
        assertEquals(WAITING_ACTION, result.overall());
        assertTrue(result.summary().contains("等待"));
        assertEquals(SUCCESS, result.entries().get(0).status);
        assertEquals("PDF已生成并核验", result.entries().get(0).detail);
        assertEquals(AWAITING_USER, result.entries().get(1).status);
    }
    @Test public void retryAfterCancelledSaveCompletesTheSameTask() {
        TaskOutcome result = new TaskOutcome(); int task = result.begin("另存PDF");
        result.set(PDF, SUCCESS, "已生成并核验"); result.set(SAVE, AWAITING_USER, "待继续保存");
        assertEquals(WAITING_ACTION, result.overall());
        assertTrue(result.setIfCurrent(task, SAVE, RUNNING, "正在保存"));
        assertEquals(IN_PROGRESS, result.overall());
        assertTrue(result.setIfCurrent(task, SAVE, SUCCESS, "已保存并重新核验"));
        assertEquals(COMPLETE, result.overall()); assertEquals(task, result.generation());
        assertEquals(SUCCESS, result.entries().get(0).status);
    }
    @Test public void saveFailurePreservesGeneratedPdfAndRetainedJson() {
        TaskOutcome result = new TaskOutcome(); result.begin("另存PDF");
        result.set(PDF, SUCCESS, "可重试保存的生成副本");
        result.set(SAVE, FAILED, "保存位置无法写入"); result.set(JSON, RETAINED, "未删除JSON");
        assertEquals(PARTIAL, result.overall());
        assertEquals(SUCCESS, result.entries().get(0).status);
        assertEquals("可重试保存的生成副本", result.entries().get(0).detail);
        assertEquals(RETAINED, result.entries().get(3).status);
    }
    @Test public void catalogOnlySyncCompletesWithoutPretendingPdfWasWritten() {
        TaskOutcome result = new TaskOutcome(); result.begin("只同步享做目录");
        result.set(PDF, RETAINED, "PDF与笔迹保留");
        result.set(CATALOG, SUCCESS, "目录已同步并核验"); result.set(JSON, RETAINED, "JSON保留");
        assertEquals(COMPLETE, result.overall());
        assertEquals(RETAINED, result.entries().get(0).status);
        assertEquals(NOT_REQUESTED, result.entries().get(1).status);
    }
    @Test public void pendingRecoveryTakesPrecedenceOverFailureAndUserChoice() {
        TaskOutcome result = new TaskOutcome(); result.begin("生成并替换");
        result.set(PDF, SUCCESS, "新PDF已生成"); result.set(SAVE, FAILED, "替换未完成");
        result.set(CLEANUP, RECOVERY_REQUIRED, "存在待恢复记录，保留备份");
        result.set(JSON, AWAITING_USER, "等待处理恢复事项");
        assertEquals(TaskOutcome.Overall.RECOVERY_REQUIRED, result.overall());
        assertTrue(result.summary().contains("恢复"));
        assertEquals(SUCCESS, result.entries().get(0).status);
        assertEquals(FAILED, result.entries().get(1).status);
        result.set(CLEANUP, RETAINED, "恢复已完成，备份保留");
        assertEquals(PARTIAL, result.overall());
    }
    @Test public void failedStageRemainsVisibleWhileAnotherStageAwaitsUser() {
        TaskOutcome result = new TaskOutcome(); result.begin("同步并清理");
        result.set(CATALOG, SUCCESS, "目录已同步"); result.set(JSON, FAILED, "JSON删除失败");
        result.set(CLEANUP, AWAITING_USER, "等待选择清理方式");
        assertEquals(PARTIAL, result.overall());
        result.set(CATALOG, RETAINED, "已有目录保留");
        assertEquals(TaskOutcome.Overall.FAILED, result.overall());
    }
    @Test public void newTaskRejectsOldCallbackAndResetsStages() {
        TaskOutcome result = new TaskOutcome(); int old = result.begin("上次任务");
        result.set(PDF, SUCCESS, "旧结果"); int current = result.begin("本次任务");
        assertFalse(result.setIfCurrent(old, SAVE, SUCCESS, "过期保存"));
        assertEquals(EMPTY, result.overall()); assertEquals("本次任务", result.title());
        assertTrue(result.setIfCurrent(current, PDF, RUNNING, "本次读取")); assertEquals(IN_PROGRESS, result.overall());
    }
    @Test public void entriesAreImmutableOrderedSnapshots() {
        TaskOutcome result = new TaskOutcome(); result.begin("测试"); result.set(SAVE, RUNNING, "正在保存");
        List<TaskOutcome.Entry> before = result.entries();
        assertEquals(PDF, before.get(0).part); assertEquals(SAVE, before.get(1).part); assertEquals(CLEANUP, before.get(4).part);
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        result.set(SAVE, SUCCESS, "保存完成"); assertEquals(RUNNING, before.get(1).status); assertEquals("正在保存", before.get(1).detail);
        List<TaskOutcome.Entry> saved = result.entries(); result.begin("下一次");
        assertEquals(SUCCESS, saved.get(1).status); assertEquals("保存完成", saved.get(1).detail);
        assertEquals(NOT_REQUESTED, result.entries().get(1).status);
    }
}
