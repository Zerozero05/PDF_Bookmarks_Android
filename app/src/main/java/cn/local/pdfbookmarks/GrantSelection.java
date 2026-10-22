package cn.local.pdfbookmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selection and revocation of exact persisted grants; never operates on documents. */
public final class GrantSelection {
    public static final int READ = 1;
    public static final int WRITE = 2;

    public static final class Grant {
        public final String uri;
        public final int flags;

        public Grant(String uri, int flags) {
            if (uri == null || !uri.startsWith("content://") || uri.length() == 10) {
                throw new IllegalArgumentException("授权位置必须是原始 content URI");
            }
            if (flags <= 0 || (flags & ~(READ | WRITE)) != 0) {
                throw new IllegalArgumentException("授权标记必须包含实际读取或写入权限");
            }
            this.uri = uri;
            this.flags = flags;
        }
    }

    public interface Backend {
        List<Grant> current() throws Exception;
        void release(String uri, int flags) throws Exception;
    }

    public static final class Failure {
        public final Grant grant;
        public final String message;

        private Failure(Grant grant, Exception error) {
            this.grant = grant;
            String detail = error.getMessage();
            this.message = detail == null || detail.isEmpty()
                    ? error.getClass().getSimpleName() : detail;
        }
    }

    public static final class BatchResult {
        public final boolean changed;
        public final List<Grant> released;
        public final List<Grant> missing;
        public final List<Grant> unprocessed;
        public final List<Failure> failed;
        public final String verificationError;

        private BatchResult(boolean changed, List<Grant> released, List<Grant> missing,
                            List<Grant> unprocessed, List<Failure> failed, String verificationError) {
            this.changed = changed;
            this.released = immutable(released);
            this.missing = immutable(missing);
            this.unprocessed = immutable(unprocessed);
            this.failed = immutable(failed);
            this.verificationError = verificationError;
        }

        public boolean complete() {
            return !changed && verificationError == null && missing.isEmpty()
                    && unprocessed.isEmpty() && failed.isEmpty();
        }
    }

    private Map<String, Grant> current = new LinkedHashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selectionMode;

    public GrantSelection() { }

    public GrantSelection(List<Grant> grants) {
        replaceCurrent(grants);
    }

    public void replaceCurrent(List<Grant> grants) {
        Map<String, Grant> next = indexed(grants);
        current = next;
        selected.retainAll(next.keySet());
    }

    public List<Grant> currentSnapshot() {
        return immutable(new ArrayList<>(current.values()));
    }

    public void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) selected.clear();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public boolean toggle(String uri) {
        if (!selectionMode) throw new IllegalStateException("请先进入选择模式");
        if (!current.containsKey(uri)) throw new IllegalArgumentException("该位置不在当前授权列表中");
        if (selected.remove(uri)) return false;
        selected.add(uri);
        return true;
    }

    public void selectAll() {
        selectionMode = true;
        selected.clear();
        selected.addAll(current.keySet());
    }

    public void clearSelection() {
        selected.clear();
    }

    public List<Grant> selectedSnapshot() {
        List<Grant> result = new ArrayList<>();
        for (Grant grant : current.values()) {
            if (selected.contains(grant.uri)) result.add(grant);
        }
        return immutable(result);
    }

    /** The caller confirms this snapshot; later URI or flag changes require new confirmation. */
    public static BatchResult revoke(Backend backend, List<Grant> confirmed) throws Exception {
        if (backend == null) throw new IllegalArgumentException("缺少授权读取接口");
        List<Grant> requested = new ArrayList<>(indexed(confirmed).values());
        List<Grant> released = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        if (requested.isEmpty()) {
            return new BatchResult(false, released, Collections.emptyList(),
                    Collections.emptyList(), failed, null);
        }
        Map<String, Grant> actual = indexed(backend.current());
        for (int i = 0; i < requested.size(); i++) {
            if (i > 0) {
                try {
                    actual = indexed(backend.current());
                } catch (Exception error) {
                    String detail = error.getMessage();
                    return new BatchResult(false, released, Collections.emptyList(),
                            requested.subList(i, requested.size()), failed,
                            detail == null || detail.isEmpty()
                                    ? error.getClass().getSimpleName() : detail);
                }
            }
            List<Grant> missing = new ArrayList<>();
            List<Grant> unprocessed = new ArrayList<>();
            boolean changed = false;
            for (int j = i; j < requested.size(); j++) {
                Grant expected = requested.get(j);
                Grant now = actual.get(expected.uri);
                if (now == null) {
                    missing.add(expected);
                    changed = true;
                } else {
                    unprocessed.add(expected);
                    if (now.flags != expected.flags) changed = true;
                }
            }
            if (changed) return new BatchResult(true, released, missing, unprocessed, failed, null);
            Grant grant = requested.get(i);
            try {
                backend.release(grant.uri, grant.flags);
                released.add(grant);
            } catch (Exception error) {
                failed.add(new Failure(grant, error));
            }
        }
        return new BatchResult(false, released, Collections.emptyList(),
                Collections.emptyList(), failed, null);
    }

    private static Map<String, Grant> indexed(List<Grant> grants) {
        if (grants == null) throw new IllegalArgumentException("缺少授权列表");
        Map<String, Grant> result = new LinkedHashMap<>();
        for (Grant grant : grants) {
            if (grant == null) throw new IllegalArgumentException("授权列表含有空位置");
            if (result.put(grant.uri, grant) != null) {
                throw new IllegalArgumentException("授权列表含有重复 URI");
            }
        }
        return result;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
