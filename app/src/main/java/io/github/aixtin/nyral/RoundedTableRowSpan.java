package io.github.aixtin.nyral;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;

import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.core.spans.TextLayoutSpan;
import io.noties.markwon.ext.tables.TableRowSpan;
import io.noties.markwon.ext.tables.TableSpan;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.AsyncDrawableSpan;
import io.noties.markwon.utils.LeadingMarginUtils;

/**
 * 圆角表格行 span：fork 自 markwon/ext-tables TableRowSpan，仅改造两处
 * - 行背景改为四角圆角（表格整块：首行圆上两角、末行圆下两角）
 * - 文本选中时淡化背景，让系统选中高亮透出
 * 布局 / 单元格 / 边框逻辑与官方实现保持一致
 */
public class RoundedTableRowSpan extends ReplacementSpan {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    @IntDef({ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Alignment {
    }

    public interface Invalidator {
        void invalidate();
    }

    private final TableTheme theme;
    private final List<TableRowSpan.Cell> cells;
    private final List<Layout> layouts;
    private final TextPaint textPaint;
    private final boolean header;
    private final boolean odd;
    private final float radius;

    private final Rect rect = new Rect();
    private final RectF rectF = new RectF();
    private final Path path = new Path();
    private final float[] radii = new float[8];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int width;
    private int height;
    private Invalidator invalidator;

    public RoundedTableRowSpan(
            @NonNull TableTheme theme,
            @NonNull List<TableRowSpan.Cell> cells,
            boolean header,
            boolean odd,
            float density) {
        this.theme = theme;
        this.cells = cells;
        this.layouts = new ArrayList<>(cells.size());
        this.textPaint = new TextPaint();
        this.header = header;
        this.odd = odd;
        this.radius = 0f;
    }

    @Override
    public int getSize(
            @NonNull Paint paint,
            CharSequence text,
            @IntRange(from = 0) int start,
            @IntRange(from = 0) int end,
            @Nullable Paint.FontMetricsInt fm) {

        // 修复: 首次测量(width 尚未经 draw 赋值)时, 用各 cell 内容宽度估算初始表格宽,
        // 避免 getSize 返回 0 导致 TextView 首测被压成窄条、单元格逐字竖排(恢复渲染必现)
        if (width <= 0) {
            final int cellPadding = theme.tableCellPadding() * 2;
            final int cellBorder = theme.tableBorderWidth(paint) * 2;
            int contentWidth = 0;
            for (int i = 0; i < cells.size(); i++) {
                final CharSequence c = cells.get(i).text();
                contentWidth += (int) paint.measureText(c, 0, c.length());
            }
            width = Math.max(1, contentWidth + cellPadding * cells.size() + cellBorder);
        }

        if (layouts.size() > 0) {

            if (fm != null) {

                int max = 0;
                for (Layout layout : layouts) {
                    final int h = layout.getHeight();
                    if (h > max) {
                        max = h;
                    }
                }

                height = max;

                final int padding = theme.tableCellPadding() * 2;

                fm.ascent = -(max + padding);
                fm.descent = 0;

                fm.top = fm.ascent;
                fm.bottom = 0;
            }
        }

        return width;
    }

    @Override
    public void draw(
            @NonNull Canvas canvas,
            CharSequence text,
            @IntRange(from = 0) int start,
            @IntRange(from = 0) int end,
            float x,
            int top,
            int y,
            int bottom,
            @NonNull Paint p) {

        final int spanWidth = io.noties.markwon.utils.SpanUtils.width(canvas, text);
        if (layouts.isEmpty() || recreateLayouts(spanWidth)) {
            width = spanWidth;
            if (p instanceof TextPaint) {
                textPaint.set((TextPaint) p);
            } else {
                textPaint.set(p);
            }
            makeNewLayouts();
        }

        int maxHeight = 0;

        final int padding = theme.tableCellPadding();

        final int size = layouts.size();

        final int w = cellWidth(size);

        final int roundingDiff = size > 0 ? w - (width / size) : 0;

        // 行是否处于文本选区范围 -> 淡化背景让系统选中高亮可见
        boolean selected = false;
        if (text instanceof Spanned) {
            final Spanned sp = (Spanned) text;
            final int ss = Selection.getSelectionStart(sp);
            final int se = Selection.getSelectionEnd(sp);
            if (ss != -1 && se != -1 && ss < end && se > start) {
                selected = true;
            }
        }

        // 判断本行是否表格整块的首行 / 末行（决定外框四角圆角）
        boolean isFirstTableRowGlobal = false;
        boolean isLastTableRowGlobal = false;
        if (text instanceof Spanned) {
            final Spanned sp = (Spanned) text;
            final TableSpan[] spans = sp.getSpans(0, sp.length(), TableSpan.class);
            if (spans != null && spans.length > 0) {
                final int tableStart = sp.getSpanStart(spans[0]);
                final int tableEnd = sp.getSpanEnd(spans[0]);
                isFirstTableRowGlobal = start <= tableStart && end > tableStart;
                isLastTableRowGlobal = end >= tableEnd && start < tableEnd;
            }
        }

        // draw backgrounds
        {
            if (header) {
                theme.applyTableHeaderRowStyle(paint);
            } else if (odd) {
                theme.applyTableOddRowStyle(paint);
            } else {
                theme.applyTableEvenRowStyle(paint);
            }

            if (paint.getColor() != 0) {
                int bg = paint.getColor();
                if (selected) {
                    bg = Color.argb(0x40, Color.red(bg), Color.green(bg), Color.blue(bg));
                }
                final float r = radius;
                radii[0] = isFirstTableRowGlobal ? r : 0f; // 左上
                radii[1] = isFirstTableRowGlobal ? r : 0f;
                radii[2] = isFirstTableRowGlobal ? r : 0f; // 右上
                radii[3] = isFirstTableRowGlobal ? r : 0f;
                radii[4] = isLastTableRowGlobal ? r : 0f;  // 右下
                radii[5] = isLastTableRowGlobal ? r : 0f;
                radii[6] = isLastTableRowGlobal ? r : 0f;  // 左下
                radii[7] = isLastTableRowGlobal ? r : 0f;

                rectF.set(0, 0, Math.max(0, width - radius), (float) (bottom - top));
                path.reset();
                path.addRoundRect(rectF, radii, Path.Direction.CW);

                final int save = canvas.save();
                try {
                    canvas.translate(x, top);
                    canvas.drawPath(path, paint);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        }

        // reset after applying background color
        paint.set(p);
        theme.applyTableBorderStyle(paint);

        final int borderWidth = theme.tableBorderWidth(paint);
        final boolean drawBorder = borderWidth > 0;

        final int heightDiff = (bottom - top - height) / 4;

        final boolean isFirstTableRow;

        if (drawBorder) {
            boolean first = false;
            {
                final Spanned spanned = (Spanned) text;
                final TableSpan[] spans = spanned.getSpans(start, end, TableSpan.class);
                if (spans != null && spans.length > 0) {
                    final TableSpan span = spans[0];
                    if (LeadingMarginUtils.selfStart(start, text, span)) {
                        first = true;
                        rect.set((int) x, top, width, top + borderWidth);
                        canvas.drawRect(rect, paint);
                    }
                }
            }

            rect.set((int) x, bottom - borderWidth, width, bottom);
            canvas.drawRect(rect, paint);

            isFirstTableRow = first;
        } else {
            isFirstTableRow = false;
        }

        final int borderWidthHalf = borderWidth / 2;

        final int borderTop = isFirstTableRow ? borderWidth : 0;
        final int borderBottom = bottom - top - borderWidth;

        Layout layout;
        for (int i = 0; i < size; i++) {
            layout = layouts.get(i);
            final int save = canvas.save();
            try {

                canvas.translate(x + (i * w), top);

                if (drawBorder) {
                    if (i == 0) {
                        rect.set(0, borderTop, borderWidth, borderBottom);
                    } else {
                        rect.set(-borderWidthHalf, borderTop, borderWidthHalf, borderBottom);
                    }

                    canvas.drawRect(rect, paint);

                    if (i == (size - 1)) {
                        rect.set(
                                w - borderWidth - roundingDiff,
                                borderTop,
                                w - roundingDiff,
                                borderBottom
                        );
                        canvas.drawRect(rect, paint);
                    }
                }

                canvas.translate(padding, padding + heightDiff);
                layout.draw(canvas);

                if (layout.getHeight() > maxHeight) {
                    maxHeight = layout.getHeight();
                }

            } finally {
                canvas.restoreToCount(save);
            }
        }

        if (height != maxHeight) {
            if (invalidator != null) {
                invalidator.invalidate();
            }
        }
    }

    private boolean recreateLayouts(int newWidth) {
        return width != newWidth;
    }

    private void makeNewLayouts() {

        textPaint.setFakeBoldText(header);

        final int columns = cells.size();
        final int padding = theme.tableCellPadding() * 2;
        final int w = cellWidth(columns) - padding;

        this.layouts.clear();

        for (int i = 0, size = cells.size(); i < size; i++) {
            makeLayout(i, w, cells.get(i));
        }
    }

    private void makeLayout(final int index, final int width, @NonNull final TableRowSpan.Cell cell) {

        final Runnable recreate = new Runnable() {
            @Override
            public void run() {
                final Invalidator invalidator = RoundedTableRowSpan.this.invalidator;
                if (invalidator != null) {
                    layouts.remove(index);
                    makeLayout(index, width, cell);
                    invalidator.invalidate();
                }
            }
        };

        final Spannable spannable;

        if (cell.text() instanceof Spannable) {
            spannable = (Spannable) cell.text();
        } else {
            spannable = new SpannableString(cell.text());
        }

        final Layout layout = new StaticLayout(
                spannable,
                textPaint,
                width,
                alignment(cell.alignment()),
                1.0F,
                0.0F,
                false
        );

        TextLayoutSpan.applyTo(spannable, layout);

        scheduleAsyncDrawables(spannable, recreate);

        layouts.add(index, layout);
    }

    private void scheduleAsyncDrawables(@NonNull Spannable spannable, @NonNull final Runnable recreate) {

        final AsyncDrawableSpan[] spans = spannable.getSpans(0, spannable.length(), AsyncDrawableSpan.class);
        if (spans != null
                && spans.length > 0) {

            for (AsyncDrawableSpan span : spans) {

                final AsyncDrawable drawable = span.getDrawable();

                if (drawable.isAttached()) {
                    continue;
                }

                drawable.setCallback2(new CallbackAdapter() {
                    @Override
                    public void invalidateDrawable(@NonNull Drawable who) {
                        recreate.run();
                    }
                });
            }
        }
    }

    @Nullable
    public Layout findLayoutForHorizontalOffset(int x) {
        final int size = layouts.size();
        final int w = cellWidth(size);
        final int i = x / w;
        if (i >= size) {
            return null;
        }
        return layouts.get(i);
    }

    /**
     * @since 4.6.0
     */
    public int cellWidth() {
        return cellWidth(layouts.size());
    }

    // @since 4.6.0
    protected int cellWidth(int size) {
        if (size <= 0) {
            return width;
        }
        return (int) (1F * width / size + 0.5F);
    }

    @SuppressLint("SwitchIntDef")
    private static Layout.Alignment alignment(@TableRowSpan.Alignment int alignment) {
        final Layout.Alignment out;
        switch (alignment) {
            case ALIGN_CENTER:
                out = Layout.Alignment.ALIGN_CENTER;
                break;
            case ALIGN_RIGHT:
                out = Layout.Alignment.ALIGN_OPPOSITE;
                break;
            default:
                out = Layout.Alignment.ALIGN_NORMAL;
                break;
        }
        return out;
    }

    public void invalidator(@Nullable Invalidator invalidator) {
        this.invalidator = invalidator;
    }

    private static class CallbackAdapter implements Drawable.Callback {

        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        }
    }
}
