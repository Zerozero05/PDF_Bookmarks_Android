package cn.local.pdfbookmarks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 基于导入后实际PDF目标页试算；只更改工作副本，不重读、改写或放宽原JSON校验。 */
public final class PageAdjustments {
    public static final class Segment {
        public final int originalStart, originalEnd, targetStart;

        public Segment(int originalStart, int originalEnd, int targetStart) {
            if (originalStart < 1 || originalEnd < originalStart || targetStart < 1) {
                throw new IllegalArgumentException("分段起止页及新起始页必须为有效的PDF实际页。");
            }
            this.originalStart = originalStart;
            this.originalEnd = originalEnd;
            this.targetStart = targetStart;
        }
    }

    private final List<TocParser.Row> originalRows;
    private final int pageCount;
    private List<TocParser.Row> appliedRows;

    public PageAdjustments(List<TocParser.Row> rows, int actualPageCount) {
        if (actualPageCount < 1 || rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("需要已读取的目录和实际PDF总页数。");
        }
        pageCount = actualPageCount;
        originalRows = Collections.unmodifiableList(new ArrayList<>(rows));
        for (TocParser.Row row : originalRows) {
            if (row == null || row.title == null || row.level < 1) {
                throw new IllegalArgumentException("导入目录无效，请重新读取。");
            }
            checkedPage(row.pdfPage, row.title);
        }
        appliedRows = originalRows;
    }

    public List<TocParser.Row> originalRows() { return originalRows; }
    public List<TocParser.Row> rows() { return appliedRows; }
    public void reset() { appliedRows = originalRows; }

    public boolean isAdjusted() {
        for (int i = 0; i < originalRows.size(); i++) {
            if (originalRows.get(i).pdfPage != appliedRows.get(i).pdfPage) return true;
        }
        return false;
    }

    /** 每次均以原导入结果试算，重复点击不会把偏移累加。 */
    public List<TocParser.Row> previewOffset(int offset) {
        List<TocParser.Row> result = new ArrayList<>();
        for (TocParser.Row row : originalRows) {
            result.add(withPage(row, checkedPage((long) row.pdfPage + offset, row.title)));
        }
        return Collections.unmodifiableList(result);
    }

    /** 按原PDF实际页区间移动；未覆盖的原目标保持不变。 */
    public List<TocParser.Row> previewSegments(List<Segment> requestedSegments) {
        if (requestedSegments == null || requestedSegments.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一个页码分段，或恢复导入映射。");
        }
        List<Segment> segments = new ArrayList<>(requestedSegments);
        for (Segment segment : segments) {
            if (segment == null) throw new IllegalArgumentException("页码分段不能为空。");
            if (segment.originalEnd > pageCount
                    || (long) segment.targetStart + segment.originalEnd - segment.originalStart > pageCount) {
                throw new IllegalArgumentException("分段的完整映射范围超出PDF实际总页数。");
            }
        }
        segments.sort(Comparator.comparingInt(s -> s.originalStart));
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).originalStart <= segments.get(i - 1).originalEnd) {
                throw new IllegalArgumentException("分段的原PDF实际页范围不能重叠。");
            }
        }
        List<TocParser.Row> result = new ArrayList<>();
        for (TocParser.Row row : originalRows) {
            long page = row.pdfPage;
            for (Segment segment : segments) {
                if (row.pdfPage >= segment.originalStart && row.pdfPage <= segment.originalEnd) {
                    page = (long) segment.targetStart + row.pdfPage - segment.originalStart;
                    break;
                }
            }
            result.add(withPage(row, checkedPage(page, row.title)));
        }
        return Collections.unmodifiableList(result);
    }

    /** 只有完整试算且结构仍对应原目录时才提交；失败不会覆盖先前有效工作副本。 */
    public void apply(List<TocParser.Row> preview) {
        if (preview == null || preview.size() != originalRows.size()) {
            throw new IllegalArgumentException("试算目录不完整，请重新试算。");
        }
        List<TocParser.Row> candidate = new ArrayList<>(preview);
        for (int i = 0; i < originalRows.size(); i++) {
            TocParser.Row original = originalRows.get(i), row = candidate.get(i);
            if (row == null || row.level != original.level || !original.title.equals(row.title)
                    || !Objects.equals(original.printedPage, row.printedPage)) {
                throw new IllegalArgumentException("试算目录与导入目录不一致，请重新读取。");
            }
            checkedPage(row.pdfPage, row.title);
        }
        appliedRows = Collections.unmodifiableList(candidate);
    }

    private int checkedPage(long page, String title) {
        if (page < 1 || page > pageCount) {
            throw new IllegalArgumentException("书签“" + title + "”试算为PDF第 " + page
                    + " 页，超出 1–" + pageCount + "；原目录和上次应用结果保持。");
        }
        return (int) page;
    }

    private static TocParser.Row withPage(TocParser.Row source, int page) {
        return source.pdfPage == page ? source
                : new TocParser.Row(source.level, source.title, source.printedPage, page);
    }
}
