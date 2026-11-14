package net.kdt.pojavlaunch.kprojects;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import net.kdt.pojavlaunch.R;

/**
 * The K-Projects emblem and wordmark with a looping neon animation: a pulsing aura, an orbiting
 * ring, light sweeps and twinkling sparkles. Every effect runs on one clock, so the loop has no
 * visible seam. When animations are off in the system settings the logo is drawn once, still.
 *
 * <p>Wide and short sizes (landscape) put the wordmark next to the emblem, everything else puts
 * it underneath.
 */
public class AnimatedLogoView extends View {
    private static final long LOOP_MS = 8000L;
    private static final float REST_PHASE = 0.2f;
    private static final float RING_TILT_DEG = -14f;
    private static final float SWEEP_TILT_DEG = 20f;
    private static final float HEAD_FRACTION = 0.80f;
    private static final float WORD_WIDTH_RATIO = 1.22f;

    private static final int MAGENTA = 0xFFE804E1;
    private static final int GLOW = 0xFFFDE5FD;

    private static final int[] AURA_COLORS = {0x99E804E1, 0x557303D5, 0x007303D5};
    private static final float[] AURA_STOPS = {0f, 0.55f, 1f};
    private static final int[] RING_COLORS = {0x00E804E1, 0x70E804E1, 0xFFF04CF2, 0x00B62EFD, 0x00E804E1};
    private static final float[] RING_STOPS = {0f, 0.45f, HEAD_FRACTION, 0.86f, 1f};
    private static final int[] SWEEP_COLORS = {0x00FFFFFF, 0xB8FFFFFF, 0x00FFFFFF};
    private static final float[] SWEEP_STOPS = {0f, 0.5f, 1f};

    /** x, y (fractions of the emblem box), size (fraction of the emblem side), cycles per loop, phase offset. */
    private static final float[][] SPARKLES = {
            {0.10f, 0.34f, 0.080f, 2f, 0.00f},
            {0.94f, 0.42f, 0.065f, 3f, 0.35f},
            {0.80f, 0.84f, 0.075f, 2f, 0.60f},
            {0.58f, 0.03f, 0.060f, 4f, 0.15f},
            {0.18f, 0.82f, 0.055f, 3f, 0.80f},
    };

    private final ValueAnimator mAnimator = ValueAnimator.ofFloat(0f, 1f);
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mAuraPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOrbitBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mOrbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mStar = new Path();
    private final Matrix mMatrix = new Matrix();
    private final RectF mEmblemBox = new RectF();
    private final RectF mWordBox = new RectF();
    private final RectF mRingOval = new RectF();
    private final Rect mEmblemSrc = new Rect();
    private final Rect mWordSrc = new Rect();

    private Bitmap mEmblem;
    private Bitmap mWordmark;
    private SweepGradient mOrbitShader;
    private LinearGradient mEmblemSweep;
    private LinearGradient mWordSweep;
    private float mDensity = 1f;
    private float mPhase = REST_PHASE;
    private float mAuraRadius;
    private float mEmblemBand;
    private float mWordBand;
    private boolean mReady;

    public AnimatedLogoView(Context context) {
        super(context);
        init(context);
    }

    public AnimatedLogoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AnimatedLogoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mDensity = context.getResources().getDisplayMetrics().density;

        mEmblem = BitmapFactory.decodeResource(getResources(), R.drawable.kp_emblem);
        mWordmark = BitmapFactory.decodeResource(getResources(), R.drawable.kp_wordmark);
        if (mEmblem != null) mEmblemSrc.set(0, 0, mEmblem.getWidth(), mEmblem.getHeight());
        if (mWordmark != null) mWordSrc.set(0, 0, mWordmark.getWidth(), mWordmark.getHeight());

        mOrbitBasePaint.setStyle(Paint.Style.STROKE);
        mOrbitBasePaint.setStrokeWidth(mDensity);
        mOrbitBasePaint.setColor(MAGENTA);
        mOrbitBasePaint.setAlpha(60);

        mOrbitPaint.setStyle(Paint.Style.STROKE);
        mOrbitPaint.setStrokeWidth(2.2f * mDensity);
        mOrbitPaint.setStrokeCap(Paint.Cap.ROUND);

        mSweepPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        mSparklePaint.setColor(GLOW);

        // four-pointed star with concave sides, drawn at unit size and scaled per sparkle
        mStar.moveTo(0f, -1f);
        mStar.quadTo(0.10f, -0.10f, 1f, 0f);
        mStar.quadTo(0.10f, 0.10f, 0f, 1f);
        mStar.quadTo(-0.10f, 0.10f, -1f, 0f);
        mStar.quadTo(-0.10f, -0.10f, 0f, -1f);
        mStar.close();

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
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize((int) (240f * mDensity), widthMeasureSpec),
                resolveSize((int) (160f * mDensity), heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mEmblemBox.setEmpty();
        if (mEmblem == null || mWordmark == null || w <= 0 || h <= 0) return;

        final float pad = 4f * mDensity;
        final float availW = Math.max(1f, w - 2f * pad);
        final float availH = Math.max(1f, h - 2f * pad);
        final float wordAspect = (float) mWordmark.getWidth() / (float) mWordmark.getHeight();
        final float side;

        if ((float) w / (float) h >= 2.4f) {
            // wide and short: emblem on the left, wordmark on the right
            side = availH;
            final float gap = side * 0.02f;
            final float wordW = Math.max(side, Math.min(availW - side - gap, side * 2.3f));
            final float wordH = wordW / wordAspect;
            final float left = (w - (side + gap + wordW)) / 2f;
            mEmblemBox.set(left, pad, left + side, pad + side);
            final float wordTop = pad + (side - wordH) / 2f + side * 0.04f;
            mWordBox.set(left + side + gap, wordTop, left + side + gap + wordW, wordTop + wordH);
        } else {
            // emblem on top, wordmark underneath
            side = Math.min(availW / WORD_WIDTH_RATIO, availH / (1f + WORD_WIDTH_RATIO / wordAspect));
            final float wordW = side * WORD_WIDTH_RATIO;
            final float wordH = wordW / wordAspect;
            final float top = pad + (availH - side - wordH) / 2f;
            mEmblemBox.set((w - side) / 2f, top, (w + side) / 2f, top + side);
            final float wordTop = mEmblemBox.bottom - side * 0.02f;
            mWordBox.set((w - wordW) / 2f, wordTop, (w + wordW) / 2f, wordTop + wordH);
        }

        final float cx = mEmblemBox.centerX();
        final float cy = mEmblemBox.centerY();
        mAuraRadius = side * 0.72f;
        mAuraPaint.setShader(new RadialGradient(cx, cy, mAuraRadius, AURA_COLORS, AURA_STOPS, Shader.TileMode.CLAMP));
        mRingOval.set(cx - side * 0.56f, cy - side * 0.27f, cx + side * 0.56f, cy + side * 0.27f);
        mOrbitShader = new SweepGradient(cx, cy, RING_COLORS, RING_STOPS);
        mOrbitPaint.setShader(mOrbitShader);
        mEmblemBand = side * 0.34f;
        mEmblemSweep = new LinearGradient(0f, 0f, mEmblemBand, 0f, SWEEP_COLORS, SWEEP_STOPS, Shader.TileMode.CLAMP);
        mWordBand = mWordBox.width() * 0.16f;
        mWordSweep = new LinearGradient(0f, 0f, mWordBand, 0f, SWEEP_COLORS, SWEEP_STOPS, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mEmblem == null || mWordmark == null || mEmblemBox.isEmpty()) return;

        final float p = mPhase;
        final float breathe = (float) Math.sin(2.0 * Math.PI * 2.0 * p); // one breath every 4 s
        final float side = mEmblemBox.width();
        final float cx = mEmblemBox.centerX();
        final float cy = mEmblemBox.centerY();

        // 1. aura behind everything
        mAuraPaint.setAlpha(Math.round(255f * (0.62f + 0.38f * breathe)));
        canvas.drawCircle(cx, cy, mAuraRadius, mAuraPaint);

        // 2. orbiting ring
        drawRing(canvas, p, cx, cy);

        // 3. emblem: breathing scale, gentle float, light sweep
        final float scale = 1f + 0.025f * breathe;
        canvas.save();
        canvas.translate(0f, -0.010f * side * breathe);
        canvas.scale(scale, scale, cx, cy);
        drawShine(canvas, mEmblem, mEmblemSrc, mEmblemBox, mEmblemSweep, mEmblemBand, segment(p, 0.50f, 0.74f));
        canvas.restore();

        // 4. sparkles
        drawSparkles(canvas, p, side);

        // 5. wordmark with a slightly later light sweep
        drawShine(canvas, mWordmark, mWordSrc, mWordBox, mWordSweep, mWordBand, segment(p, 0.66f, 0.90f));
    }

    private void drawRing(Canvas canvas, float p, float cx, float cy) {
        final float angle = 360f * p; // one orbit per loop
        mMatrix.setRotate(angle, cx, cy);
        mOrbitShader.setLocalMatrix(mMatrix);

        canvas.save();
        canvas.rotate(RING_TILT_DEG, cx, cy);
        canvas.drawOval(mRingOval, mOrbitBasePaint);
        canvas.drawOval(mRingOval, mOrbitPaint);

        // bright head of the comet, placed where the gradient is brightest
        final double theta = Math.toRadians(angle + 360f * HEAD_FRACTION);
        final double cos = Math.cos(theta);
        final double sin = Math.sin(theta);
        final double a = mRingOval.width() / 2f;
        final double b = mRingOval.height() / 2f;
        final double r = a * b / Math.sqrt((b * cos) * (b * cos) + (a * sin) * (a * sin));
        final float hx = cx + (float) (r * cos);
        final float hy = cy + (float) (r * sin);
        mHeadPaint.setColor(MAGENTA);
        mHeadPaint.setAlpha(110);
        canvas.drawCircle(hx, hy, 5f * mDensity, mHeadPaint);
        mHeadPaint.setColor(GLOW);
        canvas.drawCircle(hx, hy, 2.2f * mDensity, mHeadPaint);
        canvas.restore();
    }

    /**
     * Draws a bitmap and, while {@code progress} is within 0..1, a soft white diagonal band that
     * crosses it. The band only lights up opaque pixels (SRC_ATOP inside an offscreen layer).
     */
    private void drawShine(Canvas canvas, Bitmap bitmap, Rect src, RectF dst,
                           LinearGradient shader, float band, float progress) {
        if (progress < 0f) {
            canvas.drawBitmap(bitmap, src, dst, mBitmapPaint);
            return;
        }
        final double tilt = Math.toRadians(SWEEP_TILT_DEG);
        final float start = dst.left - band / (float) Math.cos(tilt) - 1f;
        final float end = dst.right + dst.height() * (float) Math.tan(tilt) + 1f;
        final float eased = progress * progress * (3f - 2f * progress);
        final float x = start + (end - start) * eased;

        final int saved = canvas.saveLayer(dst, null);
        canvas.drawBitmap(bitmap, src, dst, mBitmapPaint);
        mMatrix.setRotate(SWEEP_TILT_DEG);
        mMatrix.postTranslate(x, dst.top);
        shader.setLocalMatrix(mMatrix);
        mSweepPaint.setShader(shader);
        canvas.drawRect(dst, mSweepPaint);
        canvas.restoreToCount(saved);
    }

    private void drawSparkles(Canvas canvas, float p, float side) {
        for (float[] s : SPARKLES) {
            final float t = (p * s[3] + s[4]) % 1f;
            final float pulse = (float) Math.sin(Math.PI * t);
            final float b = pulse * pulse * pulse * pulse;
            if (b < 0.03f) continue;
            final float radius = s[2] * side * (0.4f + 0.6f * b);
            mSparklePaint.setAlpha(Math.round(255f * b));
            canvas.save();
            canvas.translate(mEmblemBox.left + s[0] * side, mEmblemBox.top + s[1] * side);
            canvas.rotate(30f * b);
            canvas.scale(radius, radius);
            canvas.drawPath(mStar, mSparklePaint);
            canvas.restore();
        }
    }

    /** Maps p from [start, end] to [0, 1]; returns -1 outside of that range. */
    private static float segment(float p, float start, float end) {
        if (p < start || p > end) return -1f;
        return (p - start) / (end - start);
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
                mPhase = REST_PHASE;
                invalidate();
            }
        }
    }
}
