package net.kdt.pojavlaunch.kprojects;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * A thin neon line whose magenta-to-violet gradient drifts sideways. One gradient tile is as wide as
 * the view and starts and ends with the same color, so the loop has no visible seam.
 */
public class NeonStripView extends View {
    private static final long LOOP_MS = 6000L;
    private static final int[] COLORS = {0xFFE804E1, 0xFFB62EFD, 0xFF4A12E0, 0xFFB62EFD, 0xFFE804E1};
    private static final float[] STOPS = {0f, 0.25f, 0.5f, 0.75f, 1f};

    private final ValueAnimator mAnimator = ValueAnimator.ofFloat(0f, 1f);
    private final Paint mPaint = new Paint();
    private final Matrix mMatrix = new Matrix();
    private LinearGradient mShader;
    private float mPhase;
    private boolean mReady;

    public NeonStripView(Context context) {
        super(context);
        init();
    }

    public NeonStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NeonStripView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mAnimator.setDuration(LOOP_MS);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.addUpdateListener(animation -> {
            mPhase = animation.getAnimatedFraction();
            invalidate();
        });
        mReady = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) {
            mShader = null;
            return;
        }
        mShader = new LinearGradient(0f, 0f, w, 0f, COLORS, STOPS, Shader.TileMode.REPEAT);
        mPaint.setShader(mShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mShader == null) return;
        mMatrix.setTranslate(getWidth() * mPhase, 0f);
        mShader.setLocalMatrix(mMatrix);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), mPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mReady) mAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncAnimation();
    }

    private void syncAnimation() {
        if (!mReady) return;
        final boolean enabled = Motion.animationsEnabled(getContext());
        final boolean shouldRun = enabled
                && isAttachedToWindow()
                && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE;
        if (shouldRun) {
            if (!mAnimator.isStarted()) mAnimator.start();
        } else {
            if (mAnimator.isStarted()) mAnimator.cancel();
            if (!enabled) {
                mPhase = 0f;
                invalidate();
            }
        }
    }
}
