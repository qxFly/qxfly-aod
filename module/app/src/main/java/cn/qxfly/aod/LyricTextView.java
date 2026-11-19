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
 * offered; Android has no stroke attribute for text, so it is drawn twice -
 * once as a stroked pass, then the normal fill on top.
 */
public final class LyricTextView extends TextView {

    @NonNull
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Background panel behind all lyric lines (optional, off by default). */
    @NonNull
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mStrokeWidth;

    private boolean mBgEnabled;

    /** Pill margin around the text, in px; mirrors the extra padding. */
    private int mBgPadX;
    private int mBgPadY;

    public LyricTextView(@NonNull Context context) {
        super(context);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeJoin(Paint.Join.ROUND);
        mBgPaint.setStyle(Paint.Style.FILL);
    }

    /** @param width stroke width in px; 0 disables the outline */
    public void setStroke(float width, int color) {
        mStrokeWidth = Math.max(0f, width);
        mStrokePaint.setStrokeWidth(mStrokeWidth);
        mStrokePaint.setColor(color);
        invalidate();
    }

    /**
     * Rounded background pill behind each line. When enabled the view gets
     * extra padding equal to the pill margin, so the pill stays inside the
     * view's bounds (the overlay clips at the window edge otherwise).
     *
     * @param alpha 0..1 opacity multiplier applied to {@code color}'s alpha
     */
    public void setLyricBackground(boolean enabled, int color, float alpha) {
        mBgEnabled = enabled;
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
                float r = dp(24);
                canvas.drawRoundRect(left, top, right, bottom, r, r, mBgPaint);
            }
        }
        if (mStrokeWidth > 0f) {
            mStrokePaint.setTypeface(getTypeface());
            mStrokePaint.setTextSize(getTextSize());
            // Keep the stroke pass aligned with the fill pass.
            mStrokePaint.setTextAlign(getPaint().getTextAlign());
            // Draw per wrapped line so long lyrics at a large font size get a
            // stroke on every line, not just the first one. Coordinates are
            // layout-relative: the fill pass (super.onDraw) translates the
            // canvas by the paddings, so apply them manually here.
            android.text.Layout layout = getLayout();
            if (layout != null) {
                float dx = getTotalPaddingLeft();
                float dy = getExtendedPaddingTop();
                for (int i = 0; i < layout.getLineCount(); i++) {
                    canvas.drawText(layout.getText(),
                            layout.getLineStart(i), layout.getLineEnd(i),
                            dx + layout.getLineLeft(i), dy + layout.getLineBaseline(i),
                            mStrokePaint);
                }
            }
        }
        super.onDraw(canvas);
    }
}
