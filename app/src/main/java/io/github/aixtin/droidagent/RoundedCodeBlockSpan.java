package io.github.aixtin.nyral;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;

import androidx.annotation.NonNull;

import io.noties.markwon.core.MarkwonTheme;

/**
 * 圆角代码块背景 span（替代 markwon 默认 CodeBlockSpan 的直角整行背景）
 * - 整块四角圆角：首行圆上两角、末行圆下两角，中间行直角，构成整体圆角块
 * - 文本选中：本行处于 Selection 范围时背景淡化，让系统选中高亮透出（默认实现用不透明背景把高亮盖死）
 */
public class RoundedCodeBlockSpan extends MetricAffectingSpan implements LeadingMarginSpan {

    private final MarkwonTheme theme;
    private final float radius;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private final Path path = new Path();
    private final float[] radii = new float[8];

    public RoundedCodeBlockSpan(@NonNull MarkwonTheme theme, float density) {
        this.theme = theme;
        this.radius = 0f;
        bgPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void updateMeasureState(TextPaint p) {
        apply(p);
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        apply(ds);
    }

    private void apply(TextPaint p) {
        theme.applyCodeBlockTextStyle(p);
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return theme.getCodeBlockMargin();
    }

    @Override
    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                                  int bottom, CharSequence text, int start, int end,
                                  boolean first, Layout layout) {

        final int color = theme.getCodeBlockBackgroundColor(p);

        // 行（整段文本的全文坐标）与文本选区是否相交 -> 相交则淡化背景让系统选中高亮可见
        boolean selected = false;
        if (text instanceof Spanned) {
            final Spanned sp = (Spanned) text;
            final int ss = Selection.getSelectionStart(sp);
            final int se = Selection.getSelectionEnd(sp);
            if (ss != -1 && se != -1 && ss < end && se > start) {
                selected = true;
            }
        }

        int bg = selected
                ? Color.argb(0x40, Color.red(color), Color.green(color), Color.blue(color))
                : color;

        final int left;
        final int right;
        if (dir > 0) {
            left = x;
            right = (int) Math.max(left + 1, c.getWidth() - radius);
        } else {
            left = (int) (x - c.getWidth() + radius);
            right = x;
        }

        // span 覆盖的全文范围：用于判断首行/末行以决定四角圆角
        int spanStart = 0;
        int spanEnd = 0;
        if (text instanceof Spanned) {
            final Spanned sp = (Spanned) text;
            spanStart = sp.getSpanStart(this);
            spanEnd = sp.getSpanEnd(this);
        }
        final boolean isFirstRow = start <= spanStart && end > spanStart;
        final boolean isLastRow = end >= spanEnd && start < spanEnd;

        final float r = radius;
        radii[0] = isFirstRow ? r : 0f; // 左上
        radii[1] = isFirstRow ? r : 0f;
        radii[2] = isFirstRow ? r : 0f; // 右上
        radii[3] = isFirstRow ? r : 0f;
        radii[4] = isLastRow ? r : 0f;  // 右下
        radii[5] = isLastRow ? r : 0f;
        radii[6] = isLastRow ? r : 0f;  // 左下
        radii[7] = isLastRow ? r : 0f;

        rectF.set(left, top, right, bottom);
        path.reset();
        path.addRoundRect(rectF, radii, Path.Direction.CW);

        bgPaint.setColor(bg);
        c.drawPath(path, bgPaint);
    }
}
