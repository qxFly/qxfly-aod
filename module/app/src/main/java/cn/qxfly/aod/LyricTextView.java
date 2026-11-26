package cn.qxfly.aod;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * A TextView that can draw an outline behind the glyphs.
 *
 * <p>Outlined lyrics are one of the appearance options the original module
 * offered; Android has no stroke attribute for text, so the outline is drawn
 * as a stroked pass underneath the normal fill.
 *
 * <p>Two details make the outline look right:
 * <ul>
 *   <li><b>Width means "how far the outline sticks out"</b>. Skia's
 *       {@link Paint.Style#STROKE} is centred on the glyph outline, so half of
 *       the stroke hides under the fill. The stroke pass therefore uses
 *       {@code 2 × width}, which leaves exactly {@code width} visible on the
 *       outside.</li>
 *   <li><b>The stroked pass copies the live {@link android.text.TextPaint}</b>
 *       (typeface, size, letter spacing, scaleX/skewX, locale, font features,
 *       hinting, fake bold) instead of setting three fields by hand; otherwise
 *       the outline drifts away from the fill glyphs and looks doubled.</li>
 * </ul>
 */
public final class LyricTextView extends TextView {

    /** Stroke pass; its state is refreshed from {@link #getPaint()} on every draw. */
    @NonNull
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Background panel behind all lyric lines (optional, off by default). */
    @NonNull
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Outline width in px, measured OUTSIDE the glyph outline. */
    private float mStrokeWidth;

    private int mStrokeColor = android.graphics.Color.BLACK;

    private boolean mBgEnabled;

    /** Pill margin around the text, in px; mirrors the extra padding. */
    private int mBgPadX;
    private int mBgPadY;

    /** Corner radius in px, already resolved from the style's dp value. */
    private float mBgRadiusPx = dp(24);

    public LyricTextView(@NonNull Context context) {
        super(context);
        mBgPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * @param width outline width in px, outside the glyph (0 disables it);
     *              callers convert the style's dp value with the display density
     */
    public void setStroke(float width, int color) {
        mStrokeWidth = Math.max(0f, width);
        mStrokeColor = color;
        invalidate();
    }

    /** True when the outline is currently drawn (used by diagnostics). */
    public boolean hasStroke() {
        return mStrokeWidth > 0f;
    }


    /**
     * Rounded background pill behind each line. When enabled the view gets
     * extra padding equal to the pill margin, so the pill stays inside the
     * view's bounds (the overlay clips at the window edge otherwise).
     *
     * @param alpha    0..1 opacity multiplier applied to {@code color}'s alpha
     * @param radiusDp corner radius in dp (0 = square panel)
     */
    public void setLyricBackground(boolean enabled, int color, float alpha,
                                   float radiusDp) {
        mBgEnabled = enabled;
        float clamped = LyricStyle.clampBgRadiusDp(radiusDp);
        mBgRadiusPx = dp(clamped);
        if (enabled) {
            mBgPaint.setColor(color);
            int a = Math.round(android.graphics.Color.alpha(color)
                    * Math.max(0f, Math.min(1f, alpha)));
            mBgPaint.setAlpha(a);
            mBgPadX = dp(20);
            mBgPadY = dp(12);
            if (getPaddingLeft() != mBgPadX || getPaddingTop() != mBgPadY) {
                setPadding(mBgPadX, mBgPadY, mBgPadX, mBgPadY);
            }
        } else {
            mBgPadX = 0;
            mBgPadY = 0;
            if (getPaddingLeft() != 0 || getPaddingTop() != 0) {
                setPadding(0, 0, 0, 0);
            }
        }
        invalidate();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * Reports the FULL text height, ignoring a clamped measure spec.
     *
     * <p>The theme's content column hands children a fixed height; at the
     * maximum font size that clamps this view to one line and the wrapped
     * rest of the text is cut off at the view's own bounds - the "max font
     * size is cropped" complaint. The AOD window has empty room below the
     * column, so measuring unconstrained and reporting the natural height
     * lets the column overflow into it (containers along the chain have
     * clipChildren disabled by {@code relaxClipping}).
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int clampedHeight = getMeasuredHeight();
        super.onMeasure(widthMeasureSpec,
                android.view.View.MeasureSpec.makeMeasureSpec(0,
                        android.view.View.MeasureSpec.UNSPECIFIED));
        int naturalHeight = getMeasuredHeight();
        if (naturalHeight > clampedHeight) {
            setMeasuredDimension(getMeasuredWidth(), naturalHeight);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        // Background: ONE rounded rect enclosing every wrapped line - the
        // union of all line bounds expanded by the pill margin - so the
        // original and translation lines sit inside a single panel instead
        // of one pill per line. Layout coordinates are relative to the text
        // origin (after the view paddings), same convention the stroke pass
        // uses.
        if (mBgEnabled) {
            android.text.Layout layout = getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                float padL = getTotalPaddingLeft();
                float padT = getExtendedPaddingTop();
                float left = Float.MAX_VALUE;
                float right = -Float.MAX_VALUE;
                float top = Float.MAX_VALUE;
                float bottom = -Float.MAX_VALUE;
                for (int i = 0; i < layout.getLineCount(); i++) {
                    left = Math.min(left, padL + layout.getLineLeft(i));
                    right = Math.max(right, padL + layout.getLineRight(i));
                    top = Math.min(top, padT + layout.getLineTop(i));
                    bottom = Math.max(bottom, padT + layout.getLineBottom(i));
                }
                left -= mBgPadX;
                right += mBgPadX;
                top -= mBgPadY;
                bottom += mBgPadY;
                // Never let the radius exceed half the short side: Skia would
                // otherwise distort the corners on a very thin panel.
                float r = Math.min(mBgRadiusPx,
                        Math.min(right - left, bottom - top) / 2f);
                canvas.drawRoundRect(left, top, right, bottom, r, r, mBgPaint);
            }
        }
        if (mStrokeWidth > 0f) {
            android.text.Layout layout = getLayout();
            if (layout != null && layout.getLineCount() > 0) {
                // Copy the live TextPaint so the outline follows the exact same
                // glyph metrics as the fill pass (letter spacing, scaleX/skew,
                // locale, font features, fake bold...). Setting only typeface +
                // size made the outline drift and look doubled.
                mStrokePaint.set(getPaint());
                mStrokePaint.setStyle(Paint.Style.STROKE);
                // Centred stroke: ask for twice the width so exactly mStrokeWidth
                // ends up visible outside the glyphs (the inner half hides
                // under the fill pass).
                mStrokePaint.setStrokeWidth(mStrokeWidth * 2f);
                mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
                mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
                mStrokePaint.setColor(mStrokeColor);
                // Draw per wrapped line at the layout's own line boxes, so long
                // lyrics at a large font size get an outline on every line.
                // ALIGN_LEFT + getLineLeft() is the position the layout already
                // resolved; keeping the fill paint's align would shift lines.
                mStrokePaint.setTextAlign(Paint.Align.LEFT);
                float dx = getTotalPaddingLeft();
                float dy = getExtendedPaddingTop();
                for (int i = 0; i < layout.getLineCount(); i++) {
                    int start = layout.getLineStart(i);
                    int end = layout.getLineEnd(i);
                    if (end <= start) {
                        continue;
                    }
                    canvas.drawText(layout.getText(), start, end,
                            dx + layout.getLineLeft(i),
                            dy + layout.getLineBaseline(i),
                            mStrokePaint);
                }
            }
        }
        super.onDraw(canvas);
    }
}
