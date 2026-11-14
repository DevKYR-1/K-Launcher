package net.kdt.pojavlaunch.kprojects;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/** Small helper shared by the animated K-Projects views. */
final class Motion {
    private Motion() {}

    /**
     * Whether looping decorative animations should run. It is false when the user turned
     * animations off (animator duration scale 0) or when Battery Saver disabled them.
     */
    static boolean animationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
}
