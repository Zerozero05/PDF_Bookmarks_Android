package cn.local.pdfbookmarks;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/** Directory review and explicit page adjustment; all file operations remain with the Activity. */
// This programmatic widget requires the Activity's rendering and plan-invalidation callbacks.
@SuppressLint("ViewConstructor")
public final class ReviewWorkspace extends LinearLayout {
    private final Activity activity;
    private final Runnable onRowsApplied;
    private final BiConsumer<Integer, String> requestPage;
    private final SharedPreferences preferences;
    private UiAppearance appearance;
    private DirectoryViewModel directory;
    private PageAdjustments adjustments;
    private List<DirectoryViewModel.VisibleRow> displayed = Collections.emptyList();
    private final EditText search;
    private final Button clearSearch, more, zoomIn, zoomOut, zoomFit, fullscreen;
    private final TextView counts, pageLabel, pageContext, empty, titleHeading, pageHeading;
    private final LinearLayout directoryPane, pagePane, searchBar, heading;
    private final ListView list;
    private final BaseAdapter adapter;
    private final ResizablePreviewLayout panes;
    private final ZoomImageView image;
    private final PageControls pageControls;
    private boolean busy, internalQuery, windowConfigured;
    private int pageCount, currentPage, refreshGeneration, windowWidth, windowHeight;
    private String currentTitle = "";
    private Bitmap bitmap;
    private Dialog fullDialog;
    private LinearLayout fullShell;
    private ZoomImageView fullImage;
    private TextView fullLabel, fullContext;
    private PageControls fullPageControls;
    private Button[] fullZoomButtons;
    private AdjustmentDialog adjustmentDialog;
    private PopupMenu openMenu;
    private AlertDialog detailDialog;
    private Runnable onStateChanged;
    private boolean restoringState;

    /** UI-only draft state. File identity and the user's verification remain the caller's responsibility. */
    public static final class State {
        public final String query, previewTitle;
        public final int[] collapsedSourceIndices, targetPages;
        public final int selectedSourceIndex, previewPage, firstListPosition, listTopOffset;

        public State(String query, int[] collapsedSourceIndices, int selectedSourceIndex,
                     int previewPage, String previewTitle, int firstListPosition, int listTopOffset,
                     int[] targetPages) {
            this.query = query == null ? "" : query;
            this.collapsedSourceIndices = collapsedSourceIndices == null ? new int[0] : collapsedSourceIndices.clone();
            this.selectedSourceIndex = selectedSourceIndex;
            this.previewPage = previewPage;
            this.previewTitle = previewTitle == null ? "" : previewTitle;
            this.firstListPosition = firstListPosition;
            this.listTopOffset = listTopOffset;
            this.targetPages = targetPages == null ? new int[0] : targetPages.clone();
        }
    }

    public ReviewWorkspace(Activity activity, UiAppearance appearance, Runnable onRowsApplied,
                           BiConsumer<Integer, String> requestPage) {
        super(activity);
        this.activity = activity;
        this.appearance = appearance;
        this.onRowsApplied = onRowsApplied;
        this.requestPage = requestPage;
        preferences = activity.getSharedPreferences("review_ui", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        panes = new ResizablePreviewLayout(activity);
        directoryPane = column(activity);
        searchBar = row(activity);
        search = input(activity, R.string.review_search_hint, false);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setContentDescription(getString(R.string.review_search_hint));
        searchBar.addView(search, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clearSearch = button(activity, R.string.review_clear, () -> search.setText(""));
        more = button(activity, R.string.review_more, this::showMenu);
        clearSearch.setText(R.string.review_v12_clear_symbol);
        clearSearch.setContentDescription(getString(R.string.review_clear));
        more.setText(R.string.review_v12_menu_symbol);
        more.setContentDescription(getString(R.string.review_v12_actions));
        clearSearch.setMinWidth(0);
        more.setMinWidth(0);
        pad(clearSearch, 4, 8, 4, 8);
        pad(more, 4, 8, 4, 8);
        searchBar.addView(clearSearch, new LayoutParams(dp(48), -2));
        searchBar.addView(more, new LayoutParams(dp(48), -2));
        directoryPane.addView(searchBar);
        counts = label(activity, R.string.review_empty, 13);
        pad(counts, 8, 4, 8, 6);
        directoryPane.addView(counts);
        heading = row(activity);
        pad(heading, 12, 10, 12, 10);
        titleHeading = label(activity, R.string.review_title_column, 14);
        titleHeading.setTypeface(null, Typeface.BOLD);
        heading.addView(titleHeading, new LayoutParams(0, -2, 1));
        pageHeading = label(activity, R.string.review_page_column, 14);
        pageHeading.setTypeface(null, Typeface.BOLD);
        pageHeading.setGravity(Gravity.END);
        heading.addView(pageHeading, new LayoutParams(dp(72), -2));
        directoryPane.addView(heading);
        FrameLayout listFrame = new FrameLayout(activity);
        list = new ReviewList(activity);
        list.setItemsCanFocus(true);
        list.setDividerHeight(dp(1));
        list.setVerticalScrollBarEnabled(true);
        list.setScrollbarFadingEnabled(false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return displayed.size(); }
            @Override public Object getItem(int position) { return displayed.get(position); }
            @Override public long getItemId(int position) { return displayed.get(position).sourceIndex; }
            @Override public boolean hasStableIds() { return true; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout item;
                if (recycled instanceof LinearLayout) item = (LinearLayout) recycled;
                else {
                    item = row(activity);
                    Button fold = button(activity, R.string.review_expand, () -> { });
                    fold.setFocusable(false);
                    fold.setMinWidth(0);
                    item.addView(fold, new LayoutParams(dp(48), -1));
                    TextView title = label(activity, R.string.review_title_column, 15);
                    title.setMaxLines(2);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    item.addView(title, new LayoutParams(0, -2, 1));
                    TextView target = label(activity, R.string.review_page_column, 15);
                    target.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                    item.addView(target, new LayoutParams(dp(72), -1));
                }
                DirectoryViewModel.VisibleRow visible = displayed.get(position);
                Button fold = (Button) item.getChildAt(0);
                appearance.styleButton(fold, false);
                fold.setPadding(dp(4), dp(6), dp(4), dp(6));
                fold.setBackground(new RippleDrawable(ColorStateList.valueOf(
                        (appearance.accent & 0x00ffffff) | 0x29000000), null, new ColorDrawable(-1)));
                fold.setText(visible.expanded ? R.string.review_collapse : R.string.review_expand);
                fold.setContentDescription(getString(visible.expanded
                        ? R.string.review_collapse_title : R.string.review_expand_title, visible.row.title));
                fold.setVisibility(visible.hasChildren && !directory.isSearching() ? VISIBLE : INVISIBLE);
                fold.setEnabled(!busy);
                fold.setOnClickListener(v -> {
                    if (!busy && directory != null && directory.toggle(visible.sourceIndex)) {
                        refreshRows(false);
                        notifyStateChanged();
                    }
                });
                TextView title = (TextView) item.getChildAt(1);
                appearance.styleText(title, 15);
                title.setText(visible.row.title);
                title.setTextColor(visible.matched ? appearance.accent : appearance.text);
                title.setPadding(dp(appearance.spacingDp(Math.min(visible.row.level - 1, 8) * 8)),
                        dp(appearance.spacingDp(8)), dp(appearance.spacingDp(6)), dp(appearance.spacingDp(8)));
                TextView target = (TextView) item.getChildAt(2);
                appearance.styleText(target, 15);
                target.setText(getString(R.string.review_number, visible.row.pdfPage));
                pad(target, 4, 8, 8, 8);
                target.setContentDescription(getString(R.string.review_actual_page, visible.row.pdfPage));
                item.setMinimumHeight(dp(56));
                item.setBackgroundColor(visible.selected ? appearance.selectedRow : appearance.surface);
                item.setContentDescription(getString(R.string.review_row_description, visible.row.title,
                        visible.row.level, visible.row.pdfPage));
                item.setOnClickListener(v -> selectRow(visible.sourceIndex));
                item.setOnLongClickListener(v -> showRowDetails(visible.sourceIndex));
                return item;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < displayed.size()) selectRow(displayed.get(position).sourceIndex);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> position >= 0
                && position < displayed.size() && showRowDetails(displayed.get(position).sourceIndex));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_IDLE) notifyStateChanged();
            }
            @Override public void onScroll(AbsListView view, int first, int count, int total) { }
        });
        empty = label(activity, R.string.review_empty, 14);
        empty.setGravity(Gravity.CENTER);
        pad(empty, 16, 16, 16, 16);
        listFrame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        listFrame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        directoryPane.addView(listFrame, new LayoutParams(-1, 0, 1));

        pagePane = column(activity);
        pageLabel = label(activity, R.string.review_preview_hint, 14);
        pageLabel.setMaxLines(3);
        pageLabel.setEllipsize(TextUtils.TruncateAt.END);
        pad(pageLabel, 8, 6, 8, 6);
        pagePane.addView(pageLabel);
        pageContext = label(activity, R.string.review_preview_hint, 13);
        pad(pageContext, 8, 0, 8, 4);
        pageContext.setVisibility(GONE);
        pagePane.addView(pageContext);
        image = new ZoomImageView(activity);
        image.setZoomListener(value -> updatePageLabels());
        LinearLayout zoomBar = row(activity);
        zoomOut = button(activity, R.string.review_zoom_out, () -> image.zoomOut());
        zoomIn = button(activity, R.string.review_zoom_in, () -> image.zoomIn());
        zoomFit = button(activity, R.string.review_fit, () -> image.resetZoom());
        fullscreen = button(activity, R.string.review_fullscreen, this::showFullscreen);
        zoomOut.setContentDescription(getString(R.string.preview_zoom_out));
        zoomIn.setContentDescription(getString(R.string.preview_zoom_in));
        for (Button control : new Button[]{zoomOut, zoomIn, zoomFit, fullscreen}) {
            control.setMinWidth(0);
            zoomBar.addView(control, new LayoutParams(0, -2, 1));
        }
        pagePane.addView(zoomBar);
        pagePane.addView(image, new LayoutParams(-1, 0, 1));
        pageControls = new PageControls(activity);
        pagePane.addView(pageControls.container);
        panes.setPanes(directoryPane, pagePane);
        panes.setOnRatioChangedListener(value -> persistUi());
        addView(panes, new LayoutParams(-1, dp(520)));

        search.addTextChangedListener(watcher(() -> {
            if (internalQuery || directory == null) return;
            directory.setQuery(search.getText().toString());
            refreshRows(false);
            notifyStateChanged();
        }));
        search.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                if (!busy && directory != null) {
                    for (DirectoryViewModel.VisibleRow row : displayed) {
                        if (row.matched || !directory.isSearching()) { selectRow(row.sourceIndex); break; }
                    }
                }
                hideKeyboard(search);
                return true;
            }
            return false;
        });
        applyAppearance(appearance);
        onWindowChanged(getResources().getConfiguration().screenWidthDp,
                getResources().getConfiguration().screenHeightDp);
        updateControls();
    }

    public void setDocument(TocParser.Parsed parsed, int actualPageCount) {
        if (parsed == null) { clearDocument(); return; }
        dismissDocumentDialogs();
        adjustments = new PageAdjustments(parsed.rows, actualPageCount);
        directory = new DirectoryViewModel(parsed.rows);
        pageCount = actualPageCount;
        currentPage = 0;
        currentTitle = "";
        clearBitmap();
        setSearchText("");
        refreshRows(false);
        updatePageLabels();
        updateControls();
    }

    public void clearDocument() {
        dismissDocumentDialogs();
        directory = null;
        adjustments = null;
        pageCount = 0;
        currentPage = 0;
        currentTitle = "";
        clearBitmap();
        setSearchText("");
        refreshRows(false);
        updatePageLabels();
        updateControls();
    }

    public List<TocParser.Row> rows() { return adjustments == null ? Collections.emptyList() : adjustments.rows(); }
    public boolean isAdjusted() { return adjustments != null && adjustments.isAdjusted(); }

    public void setOnStateChangedListener(Runnable listener) { onStateChanged = listener; }

    public State snapshotState() {
        int[] collapsed = directory == null ? new int[0] : directory.collapsedSourceIndices().stream()
                .mapToInt(Integer::intValue).sorted().toArray();
        List<TocParser.Row> applied = rows();
        int[] targets = new int[applied.size()];
        for (int i = 0; i < targets.length; i++) targets[i] = applied.get(i).pdfPage;
        View first = displayed.isEmpty() ? null : list.getChildAt(0);
        int firstPosition = Math.max(0, Math.min(list.getFirstVisiblePosition(), displayed.size() - 1));
        return new State(directory == null ? "" : directory.query(), collapsed,
                directory == null ? -1 : directory.selectedSourceIndex(), currentPage, currentTitle,
                firstPosition, first == null ? 0 : first.getTop(), targets);
    }

    /** Call only after reading and validating the draft's original PDF and JSON identities. */
    public void restoreState(State state) {
        if (state == null || directory == null || adjustments == null) return;
        List<TocParser.Row> original = adjustments.originalRows();
        if (state.targetPages.length != original.size() || state.selectedSourceIndex < -1
                || state.selectedSourceIndex >= original.size() || state.previewPage < 0
                || state.previewPage > pageCount) {
            throw new IllegalArgumentException(getString(R.string.review_v12_draft_mismatch));
        }
        for (int index : state.collapsedSourceIndices) {
            if (index < 0 || index >= original.size() || !directory.hasChildren(index)) {
                throw new IllegalArgumentException(getString(R.string.review_v12_draft_mismatch));
            }
        }
        List<TocParser.Row> restored = new ArrayList<>();
        boolean targetsChanged = false;
        for (int i = 0; i < original.size(); i++) {
            TocParser.Row row = original.get(i);
            restored.add(new TocParser.Row(row.level, row.title, row.printedPage, state.targetPages[i]));
            targetsChanged |= state.targetPages[i] != adjustments.rows().get(i).pdfPage;
        }
        restoringState = true;
        try {
            // apply validates every page against the original baseline before committing any changes.
            adjustments.apply(restored);
            directory = new DirectoryViewModel(adjustments.rows());
            if (state.selectedSourceIndex >= 0) directory.select(state.selectedSourceIndex);
            for (int index : state.collapsedSourceIndices) if (directory.isExpanded(index)) directory.toggle(index);
            directory.setQuery(state.query);
            setSearchText(directory.query());
            refreshRows(false);
            final int generation = ++refreshGeneration;
            list.post(() -> {
                if (generation != refreshGeneration) return;
                restoringState = true;
                try {
                    int first = Math.max(0, Math.min(state.firstListPosition, displayed.size() - 1));
                    list.setSelectionFromTop(first, state.listTopOffset);
                } finally { restoringState = false; }
            });
            currentPage = state.previewPage;
            currentTitle = state.previewTitle;
            if (targetsChanged) onRowsApplied.run();
            if (state.previewPage > 0) requestPage.accept(state.previewPage, state.previewTitle);
            else { clearBitmap(); updatePageLabels(); }
        } finally { restoringState = false; }
    }

    /** Consumes the closest transient review UI before the Activity handles leaving the workflow. */
    public boolean handleBack() {
        Dialog active = showing(detailDialog) ? detailDialog
                : adjustmentDialog != null && showing(adjustmentDialog.dialog) ? adjustmentDialog.dialog
                : showing(fullDialog) ? fullDialog : null;
        View focused = active == null ? activity.getCurrentFocus() : active.getCurrentFocus();
        View root = active != null && active.getWindow() != null
                ? active.getWindow().getDecorView() : getRootView();
        if (keyboardVisible(root)) {
            hideKeyboard(focused == null ? root : focused);
            if (focused instanceof EditText) focused.clearFocus();
            return true;
        }
        if (showing(detailDialog)) { detailDialog.dismiss(); return true; }
        if (openMenu != null) { PopupMenu menu = openMenu; openMenu = null; menu.dismiss(); return true; }
        if (adjustmentDialog != null && showing(adjustmentDialog.dialog)) { adjustmentDialog.dialog.dismiss(); return true; }
        if (showing(fullDialog)) { fullDialog.dismiss(); return true; }
        return false;
    }

    private static boolean showing(Dialog dialog) { return dialog != null && dialog.isShowing(); }

    private boolean keyboardVisible(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) return insets.isVisible(WindowInsets.Type.ime());
        }
        Rect visible = new Rect();
        root.getWindowVisibleDisplayFrame(visible);
        return root.getHeight() - visible.height() > dp(160);
    }

    private void notifyStateChanged() {
        if (!restoringState && directory != null && onStateChanged != null) onStateChanged.run();
    }

    public void setBusy(boolean value) {
        busy = value;
        if (value && openMenu != null) openMenu.dismiss();
        panes.setEnabled(!value);
        image.setEnabled(!value);
        if (fullImage != null) fullImage.setEnabled(!value);
        updateControls();
        adapter.notifyDataSetChanged();
        if (adjustmentDialog != null) adjustmentDialog.updateButtons();
    }

    public void focusSearch() {
        if (busy || directory == null || hasModalUi()) return;
        search.requestFocus();
        search.selectAll();
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    public void applyAppearance(UiAppearance value) {
        appearance = value;
        if (openMenu != null) openMenu.dismiss();
        setBackgroundColor(value.surface);
        styleTree(this);
        heading.setBackgroundColor(value.surfaceVariant);
        counts.setTextColor(value.secondaryText);
        empty.setTextColor(value.secondaryText);
        pageLabel.setTextColor(value.secondaryText);
        pageContext.setTextColor(value.secondaryText);
        panes.setDividerColor(value.accent);
        list.setDivider(new ColorDrawable(value.outline));
        list.setDividerHeight(dp(1));
        image.setBackgroundColor(value.surfaceVariant);
        if (fullShell != null) {
            fullShell.setBackgroundColor(value.page);
            styleTree(fullShell);
            if (fullContext != null) fullContext.setTextColor(value.secondaryText);
            if (fullImage != null) fullImage.setBackgroundColor(value.surfaceVariant);
            if (fullDialog != null && fullDialog.getWindow() != null) {
                fullDialog.getWindow().setBackgroundDrawable(new ColorDrawable(value.page));
            }
        }
        if (adjustmentDialog != null) adjustmentDialog.applyAppearance();
        adapter.notifyDataSetChanged();
        if (windowConfigured) onWindowChanged(windowWidth, windowHeight);
    }

    public void onWindowChanged(int widthDp, int heightDp) {
        windowWidth = Math.max(1, widthDp);
        windowHeight = Math.max(1, heightDp);
        float textScale = Math.max(1f, getResources().getConfiguration().fontScale * appearance.textSp(1));
        int directoryWidth = Math.round(252 * textScale), pageWidth = Math.round(280 * textScale);
        boolean wide = windowWidth >= Math.max(700, directoryWidth + pageWidth + 48);
        int directoryHeight = Math.max(248, Math.round(132 * textScale) + Math.round(112 * textScale));
        int pageHeight = Math.round(168 * textScale) + 144;
        panes.setPaneMinimums(wide ? directoryWidth : directoryHeight, wide ? pageWidth : pageHeight);
        if (!windowConfigured || panes.isWide() != wide) {
            if (windowConfigured) persistUi();
            panes.setWide(wide);
            panes.setSplitRatio(preferences.getFloat(wide ? "wide_ratio" : "stacked_ratio", .5f));
            windowConfigured = true;
        }
        // The surrounding page can scroll on a short window; each pane still retains its controls.
        // Taller windows give review a stable share of the actual viewport instead of a fixed cap.
        int minimumHeight = wide ? Math.max(directoryHeight, pageHeight) : directoryHeight + pageHeight + 48;
        int height = Math.max(minimumHeight, Math.round(windowHeight * .72f));
        ViewGroup.LayoutParams params = panes.getLayoutParams();
        if (params.height != dp(height)) {
            params.height = dp(height);
            panes.setLayoutParams(params);
        }
        pageControls.updateLayout(textScale);
        if (fullPageControls != null) fullPageControls.updateLayout(textScale);
        if (adjustmentDialog != null) adjustmentDialog.updateSegmentViewport();
        refreshRows(false);
    }

    public void persistUi() {
        if (windowConfigured) preferences.edit().putFloat(panes.isWide() ? "wide_ratio" : "stacked_ratio",
                panes.getSplitRatio()).apply();
    }

    public boolean hasModalUi() {
        // Dialog visibility changes before its queued OnDismissListener clears our reference.
        return showing(detailDialog) || openMenu != null || showing(fullDialog)
                || (adjustmentDialog != null && showing(adjustmentDialog.dialog));
    }

    public void showLoading(int page, String title) {
        clearBitmap();
        currentPage = page;
        currentTitle = title == null ? "" : title;
        pageLabel.setText(getString(R.string.review_loading, page));
        if (fullLabel != null) fullLabel.setText(getString(R.string.review_loading, page));
        if (adjustmentDialog != null) adjustmentDialog.showLoading(page);
        updateControls();
        notifyStateChanged();
    }

    /** The caller owns bitmap lifetime; references are cleared before it may recycle. */
    public void showBitmap(Bitmap value, int page, String title) {
        bitmap = value;
        currentPage = page;
        currentTitle = title == null ? "" : title;
        image.setImageBitmap(value);
        if (fullImage != null) fullImage.setImageBitmap(value);
        if (adjustmentDialog != null) adjustmentDialog.showBitmap(value, page);
        updatePageLabels();
        updateControls();
        notifyStateChanged();
    }

    public void clearBitmap() {
        image.setImageDrawable(null);
        if (fullImage != null) fullImage.setImageDrawable(null);
        if (adjustmentDialog != null) adjustmentDialog.trialImage.setImageDrawable(null);
        bitmap = null;
        updateControls();
    }

    public void showPageError(String message) {
        clearBitmap();
        pageLabel.setText(getString(R.string.review_page_error, message));
        if (fullLabel != null) fullLabel.setText(getString(R.string.review_page_error, message));
        if (adjustmentDialog != null) adjustmentDialog.showPageError(message);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        View focus = activity.getCurrentFocus();
        if (!busy && bitmap != null && !(focus instanceof EditText)
                && event.getAction() == KeyEvent.ACTION_DOWN && !event.isCtrlPressed() && !event.isAltPressed()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_PLUS || event.getKeyCode() == KeyEvent.KEYCODE_EQUALS
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ADD) { image.zoomIn(); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MINUS
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) { image.zoomOut(); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_0
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_0) { image.resetZoom(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        refreshGeneration++;
        persistUi();
        dismissDocumentDialogs();
        clearBitmap();
        super.onDetachedFromWindow();
    }

    /** Reveals a current imported source row. Removed entries from an old tree have no source index here. */
    public boolean revealSourceRow(int sourceIndex) {
        return selectRow(sourceIndex, true);
    }

    private void selectRow(int sourceIndex) { selectRow(sourceIndex, false); }

    private boolean selectRow(int sourceIndex, boolean reveal) {
        if (busy || directory == null || adjustments == null
                || sourceIndex < 0 || sourceIndex >= directory.sourceCount()) return false;
        directory.select(sourceIndex);
        setSearchText(directory.query());
        refreshRows(reveal);
        TocParser.Row row = adjustments.rows().get(sourceIndex);
        hideKeyboard(search);
        requestPage.accept(row.pdfPage, row.title);
        notifyStateChanged();
        if (reveal) {
            final int generation = refreshGeneration;
            list.post(() -> {
                if (generation == refreshGeneration && directory != null
                        && directory.selectedSourceIndex() == sourceIndex) {
                    list.requestRectangleOnScreen(new Rect(0, 0, list.getWidth(),
                            Math.min(list.getHeight(), dp(112))), false);
                }
            });
        }
        return true;
    }

    private void refreshRows(boolean reveal) {
        int first = list.getFirstVisiblePosition();
        View firstView = list.getChildAt(0);
        int top = firstView == null ? 0 : firstView.getTop();
        int anchor = first >= 0 && first < displayed.size() ? displayed.get(first).sourceIndex : -1;
        displayed = directory == null ? Collections.emptyList() : directory.visibleRows();
        adapter.notifyDataSetChanged();
        int position = -1;
        int wanted = reveal && directory != null ? directory.selectedSourceIndex() : anchor;
        for (int i = 0; i < displayed.size(); i++) if (displayed.get(i).sourceIndex == wanted) { position = i; break; }
        final int restore = position < 0 ? 0 : position;
        final int restoreTop = reveal ? 0 : top;
        int generation = ++refreshGeneration;
        list.post(() -> {
            if (generation == refreshGeneration && !displayed.isEmpty()) list.setSelectionFromTop(restore, restoreTop);
        });
        if (directory == null) counts.setText(R.string.review_empty);
        else {
            String summary = directory.isSearching() ? getString(R.string.review_v12_search_counts,
                    directory.matchCount(), displayed.size(), directory.sourceCount())
                    : getString(R.string.review_v12_counts, directory.sourceCount(), displayed.size());
            counts.setText(getString(R.string.workflow_review_summary, summary, getString(R.string.review_v12_list_hint)));
            counts.setContentDescription(directory.isSearching()
                    ? getString(R.string.review_search_counts, directory.matchCount(), displayed.size(), directory.sourceCount())
                    : getString(R.string.review_counts, displayed.size(), directory.sourceCount()));
        }
        empty.setText(directory == null ? R.string.review_empty : R.string.review_no_matches);
        updateControls();
    }

    private void setSearchText(String value) {
        if (search.getText().toString().equals(value)) return;
        internalQuery = true;
        search.setText(value);
        search.setSelection(search.length());
        internalQuery = false;
    }

    private void showMenu() {
        if (busy || directory == null) return;
        PopupMenu menu = new PopupMenu(appearance.dialogContext(activity), more);
        openMenu = menu;
        menu.getMenu().add(0, 1, 0, R.string.review_expand_all).setEnabled(!directory.isSearching());
        menu.getMenu().add(0, 2, 1, R.string.review_roots_only).setEnabled(!directory.isSearching());
        menu.getMenu().add(0, 3, 2, R.string.review_locate_selected).setEnabled(directory.selectedSourceIndex() >= 0);
        menu.getMenu().add(0, 4, 3, R.string.review_adjust_pages);
        menu.getMenu().add(0, 5, 4, R.string.review_restore_mapping).setEnabled(isAdjusted());
        menu.setOnMenuItemClickListener(item -> {
            if (busy || directory == null) return true;
            switch (item.getItemId()) {
                case 1: directory.expandAll(); refreshRows(false); break;
                case 2: directory.topLevelOnly(); refreshRows(false); break;
                case 3:
                    directory.revealSelected(); setSearchText(directory.query()); refreshRows(true); break;
                case 4: showAdjustment(); break;
                case 5: restoreMapping(); break;
                default: return false;
            }
            notifyStateChanged();
            return true;
        });
        menu.setOnDismissListener(ignored -> { if (openMenu == menu) openMenu = null; });
        menu.show();
    }

    private void restoreMapping() {
        if (busy || adjustments == null || !adjustments.isAdjusted()) return;
        adjustments.reset();
        directory.updateRows(adjustments.rows());
        refreshRows(false);
        onRowsApplied.run();
        notifyStateChanged();
        if (directory.selectedSourceIndex() >= 0) {
            TocParser.Row selected = adjustments.rows().get(directory.selectedSourceIndex());
            requestPage.accept(selected.pdfPage, selected.title);
        }
    }

    private void updatePageLabels() {
        if (currentPage < 1) {
            pageLabel.setText(R.string.review_preview_hint);
            if (fullLabel != null) fullLabel.setText(R.string.review_preview_hint);
        } else {
            pageLabel.setText(getString(R.string.review_page_title, currentTitle, currentPage,
                    Math.round(image.getZoom() * 100)));
            if (fullLabel != null) fullLabel.setText(getString(R.string.review_page_title, currentTitle,
                    currentPage, Math.round(fullImage == null ? 100 : fullImage.getZoom() * 100)));
        }
        updatePageContext();
    }

    private void updatePageContext() {
        int selected = directory == null ? -1 : directory.selectedSourceIndex();
        int target = selected < 0 || adjustments == null ? 0 : adjustments.rows().get(selected).pdfPage;
        boolean different = currentPage > 0 && target > 0 && currentPage != target;
        pageContext.setVisibility(different ? VISIBLE : GONE);
        if (different) pageContext.setText(getString(R.string.review_browsing_other_page, currentPage, target));
        if (fullContext != null) {
            fullContext.setVisibility(different ? VISIBLE : GONE);
            if (different) fullContext.setText(getString(R.string.review_browsing_other_page, currentPage, target));
        }
    }

    private void updateControls() {
        boolean loaded = directory != null, enabled = loaded && !busy;
        search.setEnabled(enabled);
        clearSearch.setEnabled(enabled);
        clearSearch.setVisibility(search.length() == 0 ? GONE : VISIBLE);
        more.setEnabled(enabled);
        list.setEnabled(enabled);
        boolean previewEnabled = enabled && bitmap != null;
        for (Button button : new Button[]{zoomIn, zoomOut, zoomFit, fullscreen}) button.setEnabled(previewEnabled);
        if (fullZoomButtons != null) for (Button button : fullZoomButtons) button.setEnabled(previewEnabled);
        pageControls.update();
        if (fullPageControls != null) fullPageControls.update();
        updatePageContext();
    }

    private void showFullscreen() {
        if (busy || bitmap == null || fullDialog != null) return;
        Dialog dialog = new Dialog(appearance.dialogContext(activity));
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        fullDialog = dialog;
        fullShell = column(dialog.getContext());
        pad(fullShell, 12, 8, 12, 12);
        fullShell.setBackgroundColor(appearance.page);
        LinearLayout header = row(dialog.getContext());
        fullLabel = label(dialog.getContext(), R.string.review_preview_hint, 14);
        header.addView(fullLabel, new LayoutParams(0, -2, 1));
        Button close = button(dialog.getContext(), R.string.review_close, dialog::dismiss);
        header.addView(close);
        fullShell.addView(header);
        fullContext = label(dialog.getContext(), R.string.review_preview_hint, 13);
        fullContext.setTextColor(appearance.secondaryText);
        fullContext.setVisibility(GONE);
        fullShell.addView(fullContext);
        LinearLayout zoomBar = row(dialog.getContext());
        Button out = button(dialog.getContext(), R.string.review_zoom_out, () -> fullImage.zoomOut());
        Button in = button(dialog.getContext(), R.string.review_zoom_in, () -> fullImage.zoomIn());
        Button fit = button(dialog.getContext(), R.string.review_fit, () -> fullImage.resetZoom());
        fullZoomButtons = new Button[]{out, in, fit};
        for (Button control : fullZoomButtons) zoomBar.addView(control, new LayoutParams(0, -2, 1));
        fullShell.addView(zoomBar);
        fullImage = new ZoomImageView(dialog.getContext());
        fullImage.setBackgroundColor(appearance.surfaceVariant);
        fullImage.setZoomListener(value -> updatePageLabels());
        fullImage.setImageBitmap(bitmap);
        fullShell.addView(fullImage, new LayoutParams(-1, 0, 1));
        fullPageControls = new PageControls(dialog.getContext());
        fullShell.addView(fullPageControls.container);
        dialog.setContentView(fullShell);
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) return handleBack();
            if (busy || bitmap == null || dialog.getCurrentFocus() instanceof EditText
                    || event.getAction() != KeyEvent.ACTION_DOWN || event.isCtrlPressed() || event.isAltPressed()) return false;
            if (keyCode == KeyEvent.KEYCODE_PLUS || keyCode == KeyEvent.KEYCODE_EQUALS
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ADD) { fullImage.zoomIn(); return true; }
            if (keyCode == KeyEvent.KEYCODE_MINUS
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) { fullImage.zoomOut(); return true; }
            if (keyCode == KeyEvent.KEYCODE_0
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_0) { fullImage.resetZoom(); return true; }
            return false;
        });
        dialog.setOnDismissListener(ignored -> {
            if (fullImage != null) fullImage.setImageDrawable(null);
            fullImage = null; fullLabel = null; fullContext = null; fullShell = null; fullPageControls = null;
            fullZoomButtons = null; fullDialog = null;
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(appearance.page));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        updatePageLabels();
        fullPageControls.updateLayout(Math.max(1f, getResources().getConfiguration().fontScale * appearance.textSp(1)));
        fullPageControls.update();
    }

    private void showAdjustment() {
        if (busy || adjustments == null || adjustmentDialog != null) return;
        hideKeyboard(search);
        adjustmentDialog = new AdjustmentDialog();
        adjustmentDialog.show();
    }

    private void dismissDocumentDialogs() {
        if (detailDialog != null) detailDialog.dismiss();
        if (openMenu != null) openMenu.dismiss();
        if (fullDialog != null) fullDialog.dismiss();
        if (adjustmentDialog != null) adjustmentDialog.dialog.dismiss();
    }

    private boolean showRowDetails(int sourceIndex) {
        if (busy || adjustments == null || sourceIndex < 0 || sourceIndex >= rows().size()) return false;
        hideKeyboard(search);
        if (detailDialog != null) detailDialog.dismiss();
        TocParser.Row current = rows().get(sourceIndex), original = adjustments.originalRows().get(sourceIndex);
        Context context = appearance.dialogContext(activity);
        LinearLayout content = column(context);
        pad(content, 24, 8, 24, 12);
        TextView meta = label(context, R.string.review_v12_row_meta, 13);
        meta.setText(getString(R.string.review_v12_row_meta, sourceIndex + 1, current.level));
        meta.setTextColor(appearance.secondaryText);
        content.addView(meta);
        TextView fullTitle = label(context, R.string.review_title_column, 19);
        fullTitle.setText(current.title);
        fullTitle.setTypeface(null, Typeface.BOLD);
        fullTitle.setTextIsSelectable(true);
        pad(fullTitle, 0, 8, 0, 20);
        content.addView(fullTitle);
        LinearLayout targets = row(context);
        for (boolean before : new boolean[]{true, false}) {
            LinearLayout cell = column(context);
            TextView label = label(context, before ? R.string.review_v12_original_page
                    : R.string.review_v12_current_page, 13);
            label.setTextColor(appearance.secondaryText);
            cell.addView(label);
            TextView number = label(context, R.string.review_v12_page_value, 17);
            number.setText(getString(R.string.review_v12_page_value, before ? original.pdfPage : current.pdfPage));
            pad(number, 0, 8, 0, 8);
            cell.addView(number);
            targets.addView(cell, new LayoutParams(0, -2, 1));
        }
        content.addView(targets);
        TextView mapping = label(context, R.string.review_v12_mapping_unchanged, 13);
        mapping.setText(original.pdfPage == current.pdfPage ? getString(R.string.review_v12_mapping_unchanged)
                : getString(R.string.review_v12_mapping, original.pdfPage, current.pdfPage));
        mapping.setTextColor(appearance.secondaryText);
        content.addView(mapping);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.review_v12_row_details)
                .setView(scroll).setNegativeButton(R.string.review_close, null)
                .setNeutralButton(R.string.review_v12_copy_title, null)
                .setPositiveButton(R.string.review_v12_copy_page, null).create();
        detailDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (detailDialog == dialog) detailDialog = null; });
        dialog.setOnKeyListener((ignored, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP && handleBack());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                copyDetail(current.title, R.string.review_v12_copied_title));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                copyDetail(getString(R.string.review_number, current.pdfPage), R.string.review_v12_copied_page));
        appearance.styleDialog(dialog);
        return true;
    }

    private void copyDetail(String value, int message) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.review_v12_row_details), value));
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private final class PageControls {
        final LinearLayout container;
        final EditText number;
        final Button previous, next, go;

        PageControls(Context context) {
            container = row(context);
            previous = button(context, R.string.review_previous, () -> navigate(currentPage - 1));
            number = input(context, R.string.review_page_input, true);
            number.setSelectAllOnFocus(true);
            number.setSingleLine(true);
            number.setGravity(Gravity.CENTER);
            number.setContentDescription(getString(R.string.review_page_input_description));
            number.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            go = button(context, R.string.review_go, this::go);
            next = button(context, R.string.review_next, () -> navigate(currentPage < 1 ? 1 : currentPage + 1));
            for (Button button : new Button[]{previous, go, next}) button.setMinWidth(0);
            pad(previous, 4, 8, 4, 8);
            pad(next, 4, 8, 4, 8);
            container.addView(previous, new LayoutParams(dp(48), -2));
            container.addView(number, new LayoutParams(0, -2, 1));
            container.addView(go, new LayoutParams(dp(56), -2));
            container.addView(next, new LayoutParams(dp(48), -2));
            number.setOnEditorActionListener((view, action, event) -> {
                if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) { go(); return true; }
                return false;
            });
        }

        void go() {
            if (busy || directory == null) return;
            try { navigate(Integer.parseInt(number.getText().toString().trim())); }
            catch (NumberFormatException failure) { number.setError(getString(R.string.review_page_range, pageCount)); }
        }

        void navigate(int page) {
            if (busy || directory == null) return;
            if (page < 1 || page > pageCount) { number.setError(getString(R.string.review_page_range, pageCount)); return; }
            number.setError(null);
            hideKeyboard(number);
            number.clearFocus();
            requestPage.accept(page, getString(R.string.review_manual_preview));
        }

        void updateLayout(float textScale) {
            ViewGroup.LayoutParams params = go.getLayoutParams();
            int width = dp(Math.max(56, Math.round(34 * textScale) + 24));
            if (params.width != width) { params.width = width; go.setLayoutParams(params); }
        }

        void update() {
            boolean enabled = !busy && directory != null;
            number.setEnabled(enabled);
            go.setEnabled(enabled);
            previous.setEnabled(enabled && currentPage > 1);
            next.setEnabled(enabled && currentPage < pageCount);
            if (!number.hasFocus()) number.setText(currentPage < 1 ? "" : getString(R.string.review_number, currentPage));
        }
    }

    private final class AdjustmentDialog {
        final AlertDialog dialog;
        final LinearLayout form, segmentRows, segmentColumns, overallBox, segmentBox, candidatesBox;
        final ScrollView segmentScroll;
        final RadioGroup mode;
        final RadioButton overallMode, segmentMode;
        final EditText offset;
        final TextView trialSummary;
        final Button addSegment, trial;
        final ZoomImageView trialImage;
        final TextView trialPageLabel;
        final PageAdjustments sourceAdjustment;
        final List<SegmentInputs> segments = new ArrayList<>();
        List<TocParser.Row> candidate;
        int trialPage;
        boolean settingUp = true;

        AdjustmentDialog() {
            sourceAdjustment = adjustments;
            Context context = appearance.dialogContext(activity);
            form = column(context);
            pad(form, 16, 8, 16, 12);
            TextView intro = label(context, R.string.review_adjust_explanation, 14);
            pad(intro, 0, 0, 0, 8);
            form.addView(intro);
            mode = new RadioGroup(context);
            mode.setOrientation(HORIZONTAL);
            overallMode = new RadioButton(context);
            overallMode.setId(View.generateViewId());
            overallMode.setText(R.string.review_overall_offset);
            segmentMode = new RadioButton(context);
            segmentMode.setId(View.generateViewId());
            segmentMode.setText(R.string.review_segments);
            mode.addView(overallMode, new RadioGroup.LayoutParams(0, -2, 1));
            mode.addView(segmentMode, new RadioGroup.LayoutParams(0, -2, 1));
            form.addView(mode);
            overallBox = column(context);
            overallBox.addView(label(context, R.string.review_offset_label, 14));
            offset = input(context, R.string.review_offset_hint, true);
            offset.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            offset.setText(R.string.review_zero);
            overallBox.addView(offset);
            form.addView(overallBox);
            segmentBox = column(context);
            segmentBox.addView(label(context, R.string.review_segment_explanation, 13));
            segmentColumns = row(context);
            pad(segmentColumns, 0, 8, 0, 4);
            for (int resource : new int[]{R.string.review_original_start, R.string.review_original_end,
                    R.string.review_target_start}) {
                TextView column = label(context, resource, 13);
                column.setGravity(Gravity.CENTER);
                column.setTypeface(null, Typeface.BOLD);
                segmentColumns.addView(column, new LayoutParams(0, -2, 1));
            }
            segmentColumns.addView(new View(context), new LayoutParams(dp(48), 1));
            segmentBox.addView(segmentColumns);
            segmentRows = column(context);
            segmentScroll = new ScrollView(context);
            segmentScroll.setNestedScrollingEnabled(true);
            segmentScroll.setVerticalScrollBarEnabled(true);
            segmentScroll.setScrollbarFadingEnabled(false);
            segmentScroll.addView(segmentRows);
            segmentBox.addView(segmentScroll, new LayoutParams(-1, -2));
            addSegment = button(context, R.string.review_add_segment, this::addSegment);
            segmentBox.addView(addSegment);
            form.addView(segmentBox);
            trial = button(context, R.string.review_try_mapping, this::calculate);
            form.addView(trial);
            trialSummary = label(context, R.string.review_trial_required, 14);
            pad(trialSummary, 0, 8, 0, 4);
            form.addView(trialSummary);
            candidatesBox = column(context);
            form.addView(candidatesBox);
            trialPageLabel = label(context, R.string.review_trial_preview_hint, 14);
            trialPageLabel.setVisibility(GONE);
            form.addView(trialPageLabel);
            trialImage = new ZoomImageView(context);
            trialImage.setVisibility(GONE);
            trialImage.setBackgroundColor(appearance.surfaceVariant);
            form.addView(trialImage, new LayoutParams(-1, dp(340)));
            ScrollView scroll = new ScrollView(context);
            scroll.setFillViewport(true);
            scroll.addView(form);
            dialog = new AlertDialog.Builder(context).setTitle(R.string.review_adjust_pages).setView(scroll)
                    .setNegativeButton(R.string.review_cancel, null)
                    .setNeutralButton(R.string.review_restore_mapping, null)
                    .setPositiveButton(R.string.review_apply_mapping, null).create();
            dialog.setOnKeyListener((ignored, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP && handleBack());
            dialog.setOnDismissListener(ignored -> {
                trialImage.setImageDrawable(null);
                if (adjustmentDialog == this) adjustmentDialog = null;
            });
            offset.addTextChangedListener(watcher(this::invalidateTrial));
            mode.setOnCheckedChangeListener((group, id) -> {
                overallBox.setVisibility(id == overallMode.getId() ? VISIBLE : GONE);
                segmentBox.setVisibility(id == segmentMode.getId() ? VISIBLE : GONE);
                invalidateTrial();
            });
            mode.check(overallMode.getId());
            addSegment();
            settingUp = false;
            applyAppearance();
        }

        void show() {
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (busy || candidate == null || adjustments != sourceAdjustment || directory == null) return;
                try {
                    adjustments.apply(candidate);
                    directory.updateRows(adjustments.rows());
                    refreshRows(false);
                    onRowsApplied.run();
                    notifyStateChanged();
                    dialog.dismiss();
                } catch (IllegalArgumentException failure) { showFailure(failure.getMessage()); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                if (busy) return;
                restoreMapping();
                dialog.dismiss();
            });
            updateButtons();
            appearance.styleDialog(dialog);
            Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        void addSegment() {
            SegmentInputs inputs = new SegmentInputs(form.getContext());
            segments.add(inputs);
            segmentRows.addView(inputs.container);
            updateSegmentViewport();
            invalidateTrial();
        }

        void updateSegmentViewport() {
            float textScale = Math.max(1f, getResources().getConfiguration().fontScale * appearance.textSp(1));
            // Keep the column labels outside this bounded scroll area when many ranges are entered.
            int height = segments.size() > 3 ? dp(Math.round(56 * textScale) * 3) : LayoutParams.WRAP_CONTENT;
            ViewGroup.LayoutParams params = segmentScroll.getLayoutParams();
            if (params.height != height) { params.height = height; segmentScroll.setLayoutParams(params); }
            for (int i = 0; i < segments.size(); i++) {
                SegmentInputs inputs = segments.get(i);
                inputs.remove.setContentDescription(getString(R.string.review_remove_segment_number, i + 1));
                int[] labels = {R.string.review_original_start, R.string.review_original_end, R.string.review_target_start};
                EditText[] fields = {inputs.start, inputs.end, inputs.target};
                for (int j = 0; j < fields.length; j++) {
                    fields[j].setContentDescription(getString(R.string.review_segment_field, i + 1, getString(labels[j])));
                }
            }
        }

        void invalidateTrial() {
            candidate = null;
            trialPage = 0;
            trialImage.setImageDrawable(null);
            trialImage.setVisibility(GONE);
            trialPageLabel.setVisibility(GONE);
            candidatesBox.removeAllViews();
            trialSummary.setText(R.string.review_trial_required);
            trialSummary.setTextColor(appearance.secondaryText);
            if (!settingUp) updateButtons();
        }

        void calculate() {
            if (busy || adjustments != sourceAdjustment) return;
            candidate = null;
            trialPage = 0;
            trialImage.setImageDrawable(null);
            trialImage.setVisibility(GONE);
            trialPageLabel.setVisibility(GONE);
            candidatesBox.removeAllViews();
            try {
                if (mode.getCheckedRadioButtonId() == overallMode.getId()) {
                    candidate = adjustments.previewOffset(parseInteger(offset, true));
                } else {
                    List<PageAdjustments.Segment> ranges = new ArrayList<>();
                    for (SegmentInputs segment : segments) ranges.add(new PageAdjustments.Segment(
                            parseInteger(segment.start, false), parseInteger(segment.end, false),
                            parseInteger(segment.target, false)));
                    candidate = adjustments.previewSegments(ranges);
                }
                int changed = 0;
                for (int i = 0; i < candidate.size(); i++) {
                    if (candidate.get(i).pdfPage != adjustments.originalRows().get(i).pdfPage) changed++;
                }
                trialSummary.setText(getString(R.string.review_trial_summary, candidate.size(), changed));
                trialSummary.setTextColor(appearance.text);
                // A short sample remains compact; any actual page can also be checked through page navigation.
                List<Integer> sample = new ArrayList<>();
                for (int i = 0; i < candidate.size() && sample.size() < 6; i++) {
                    if (candidate.get(i).pdfPage != adjustments.originalRows().get(i).pdfPage) sample.add(i);
                }
                for (int i = 0; i < candidate.size() && sample.size() < 6; i++) if (!sample.contains(i)) sample.add(i);
                for (int index : sample) {
                    TocParser.Row original = adjustments.originalRows().get(index), mapped = candidate.get(index);
                    Button check = button(form.getContext(), R.string.review_check_target, () -> {
                        if (busy || candidate == null || adjustments != sourceAdjustment) return;
                        trialPage = mapped.pdfPage;
                        trialPageLabel.setVisibility(VISIBLE);
                        trialImage.setVisibility(VISIBLE);
                        trialPageLabel.setText(getString(R.string.review_loading, mapped.pdfPage));
                        trialPageLabel.post(() -> trialPageLabel.requestRectangleOnScreen(
                                new android.graphics.Rect(0, 0, trialPageLabel.getWidth(), dp(300)), false));
                        directory.select(index);
                        setSearchText(directory.query());
                        refreshRows(false);
                        requestPage.accept(mapped.pdfPage, mapped.title);
                        hideKeyboard(offset);
                    });
                    check.setText(getString(R.string.review_trial_row, mapped.title, original.pdfPage, mapped.pdfPage));
                    check.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    candidatesBox.addView(check, new LayoutParams(-1, -2));
                }
            } catch (IllegalArgumentException failure) { showFailure(failure.getMessage()); }
            updateButtons();
        }

        void showFailure(String message) {
            candidate = null;
            trialPage = 0;
            trialImage.setImageDrawable(null);
            trialImage.setVisibility(GONE);
            trialPageLabel.setVisibility(GONE);
            candidatesBox.removeAllViews();
            trialSummary.setText(getString(R.string.review_mapping_error, message));
            trialSummary.setTextColor(appearance.error);
            updateButtons();
        }

        void showLoading(int page) {
            if (trialPage == page && candidate != null) trialPageLabel.setText(getString(R.string.review_loading, page));
        }

        void showBitmap(Bitmap value, int page) {
            if (trialPage == page && candidate != null && adjustments == sourceAdjustment) {
                trialImage.setImageBitmap(value);
                trialPageLabel.setText(getString(R.string.review_trial_preview_page, page));
            }
        }

        void showPageError(String message) {
            if (trialPage > 0 && candidate != null) trialPageLabel.setText(getString(R.string.review_page_error, message));
        }

        void updateButtons() {
            boolean enabled = !busy && adjustments == sourceAdjustment;
            offset.setEnabled(enabled);
            overallMode.setEnabled(enabled);
            segmentMode.setEnabled(enabled);
            addSegment.setEnabled(enabled);
            trial.setEnabled(enabled);
            trialImage.setEnabled(enabled);
            for (SegmentInputs row : segments) row.setEnabled(enabled);
            for (int i = 0; i < candidatesBox.getChildCount(); i++) candidatesBox.getChildAt(i).setEnabled(enabled);
            Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (apply != null) apply.setEnabled(enabled && candidate != null);
            Button reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (reset != null) reset.setEnabled(enabled && isAdjusted());
        }

        void applyAppearance() {
            form.setBackgroundColor(appearance.surface);
            styleTree(form);
            appearance.styleChoice(overallMode);
            appearance.styleChoice(segmentMode);
            segmentColumns.setBackgroundColor(appearance.surfaceVariant);
            trialImage.setBackgroundColor(appearance.surfaceVariant);
            appearance.styleDialog(dialog);
            if (candidate == null) trialSummary.setTextColor(appearance.secondaryText);
            updateSegmentViewport();
        }

        final class SegmentInputs {
            final LinearLayout container;
            final EditText start, end, target;
            final Button remove;

            SegmentInputs(Context context) {
                container = row(context);
                pad(container, 0, 4, 0, 4);
                start = input(context, R.string.review_original_start, true);
                end = input(context, R.string.review_original_end, true);
                target = input(context, R.string.review_target_start, true);
                for (EditText input : new EditText[]{start, end, target}) {
                    input.setMinWidth(0);
                    input.setSingleLine(true);
                    input.setSelectAllOnFocus(true);
                    input.setGravity(Gravity.CENTER);
                    input.setHint(null);
                    container.addView(input, new LayoutParams(0, -2, 1));
                    input.addTextChangedListener(watcher(AdjustmentDialog.this::invalidateTrial));
                }
                remove = button(context, R.string.review_remove_segment, () -> {
                    if (busy) return;
                    segments.remove(this);
                    segmentRows.removeView(container);
                    updateSegmentViewport();
                    invalidateTrial();
                });
                remove.setText(R.string.review_remove_segment_symbol);
                remove.setMinWidth(0);
                remove.setMinimumWidth(0);
                pad(remove, 4, 8, 4, 8);
                container.addView(remove, new LayoutParams(dp(48), -2));
            }

            void setEnabled(boolean enabled) {
                start.setEnabled(enabled); end.setEnabled(enabled); target.setEnabled(enabled); remove.setEnabled(enabled);
            }
        }
    }

    private int parseInteger(EditText input, boolean signed) {
        String value = input.getText().toString().trim();
        if (!value.matches(signed ? "[+-]?[0-9]+" : "[0-9]+")) {
            throw new IllegalArgumentException(getString(R.string.review_integer_required));
        }
        try { return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(getString(R.string.review_integer_range)); }
    }

    private LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(VERTICAL);
        return layout;
    }

    private LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(Context context, int resource, float size) {
        TextView text = new TextView(context);
        text.setText(resource);
        appearance.styleText(text, size);
        return text;
    }

    private EditText input(Context context, int hint, boolean number) {
        EditText input = new EditText(context);
        input.setHint(hint);
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        appearance.styleInput(input);
        return input;
    }

    private Button button(Context context, int resource, Runnable action) {
        Button button = new Button(context);
        button.setText(resource);
        if (resource == R.string.review_zoom_in) button.setContentDescription(getString(R.string.preview_zoom_in));
        else if (resource == R.string.review_zoom_out) button.setContentDescription(getString(R.string.preview_zoom_out));
        else if (resource == R.string.review_previous) button.setContentDescription(getString(R.string.review_previous_page));
        else if (resource == R.string.review_next) button.setContentDescription(getString(R.string.review_next_page));
        appearance.styleButton(button, false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void styleTree(View view) {
        if (view instanceof RadioButton) appearance.styleChoice((RadioButton) view);
        else if (view instanceof Button) appearance.styleButton((Button) view, false);
        else if (view instanceof EditText) appearance.styleInput((EditText) view);
        else if (view instanceof TextView) {
            Object base = view.getTag(R.id.appearance_base_text_size);
            appearance.styleText((TextView) view, base instanceof Number ? ((Number) base).floatValue() : 15);
        }
        Object padding = view.getTag();
        if (padding instanceof int[]) {
            int[] p = (int[]) padding;
            view.setPadding(dp(appearance.spacingDp(p[0])), dp(appearance.spacingDp(p[1])),
                    dp(appearance.spacingDp(p[2])), dp(appearance.spacingDp(p[3])));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleTree(group.getChildAt(i));
        }
    }

    private void pad(View view, int left, int top, int right, int bottom) {
        view.setTag(new int[]{left, top, right, bottom});
        view.setPadding(dp(appearance.spacingDp(left)), dp(appearance.spacingDp(top)),
                dp(appearance.spacingDp(right)), dp(appearance.spacingDp(bottom)));
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private String getString(int id, Object... arguments) { return getResources().getString(id, arguments); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { action.run(); }
        };
    }

    private static final class ReviewList extends ListView {
        private float lastY;
        ReviewList(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (getParent() != null) {
                if (action == MotionEvent.ACTION_DOWN) {
                    lastY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1));
                } else if (action == MotionEvent.ACTION_MOVE) {
                    int direction = event.getY() < lastY ? 1 : -1;
                    getParent().requestDisallowInterceptTouchEvent(canScrollVertically(direction));
                    lastY = event.getY();
                }
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (getParent() != null && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return handled;
        }
    }
}
