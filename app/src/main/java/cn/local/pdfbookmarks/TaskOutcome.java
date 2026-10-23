package cn.local.pdfbookmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** Transient result-card state; it does not own files or replace durable recovery records. */
public final class TaskOutcome {
    public enum Part { PDF, SAVE, CATALOG, JSON, CLEANUP }
    public enum Status {
        NOT_REQUESTED, WAITING, RUNNING, SUCCESS, RETAINED, SKIPPED, FAILED,
        AWAITING_USER, RECOVERY_REQUIRED
    }
    public enum Overall { EMPTY, IN_PROGRESS, COMPLETE, PARTIAL, FAILED, WAITING_ACTION, RECOVERY_REQUIRED }

    public static final class Entry {
        public final Part part;
        public final Status status;
        public final String detail;
        private Entry(Part part, Status status, String detail) {
            this.part = part; this.status = status; this.detail = detail;
        }
    }

    private final EnumMap<Part, Entry> values = new EnumMap<>(Part.class);
    private int generation;
    private String title = "";

    public TaskOutcome() { clearEntries(); }

    /** Start a separate task. Existing immutable snapshots keep the previous result. */
    public int begin(String title) {
        this.title = Objects.requireNonNull(title, "title");
        generation++; clearEntries(); return generation;
    }
    public int generation() { return generation; }
    public String title() { return title; }

    /** Update exactly one stage; successful earlier stages remain visible after a failure. */
    public void set(Part part, Status status, String detail) {
        Objects.requireNonNull(part, "part"); Objects.requireNonNull(status, "status");
        values.put(part, new Entry(part, status, detail == null ? "" : detail));
    }

    public boolean setIfCurrent(int expectedGeneration, Part part, Status status, String detail) {
        if (generation != expectedGeneration) return false;
        set(part, status, detail); return true;
    }

    public List<Entry> entries() { return Collections.unmodifiableList(new ArrayList<>(values.values())); }

    public Overall overall() {
        boolean requested = false, pending = false, failed = false, succeeded = false;
        boolean awaitingUser = false, recoveryRequired = false;
        for (Entry value : values.values()) {
            requested |= value.status != Status.NOT_REQUESTED;
            pending |= value.status == Status.WAITING || value.status == Status.RUNNING;
            failed |= value.status == Status.FAILED;
            succeeded |= value.status == Status.SUCCESS;
            awaitingUser |= value.status == Status.AWAITING_USER;
            recoveryRequired |= value.status == Status.RECOVERY_REQUIRED;
        }
        if (!requested) return Overall.EMPTY;
        if (pending) return Overall.IN_PROGRESS;
        if (recoveryRequired) return Overall.RECOVERY_REQUIRED;
        if (failed) return succeeded ? Overall.PARTIAL : Overall.FAILED;
        if (awaitingUser) return Overall.WAITING_ACTION;
        return Overall.COMPLETE;
    }

    public String summary() {
        switch (overall()) {
            case IN_PROGRESS: return "正在处理";
            case WAITING_ACTION: return "等待继续操作";
            case RECOVERY_REQUIRED: return "有待恢复事项，请先处理";
            case COMPLETE: return "已完成";
            case PARTIAL: return "部分完成，请查看未完成项";
            case FAILED: return "未完成，请查看错误信息";
            default: return "尚无处理结果";
        }
    }

    private void clearEntries() {
        for (Part part : Part.values()) values.put(part, new Entry(part, Status.NOT_REQUESTED, ""));
    }
}
