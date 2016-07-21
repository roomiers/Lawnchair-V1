package com.android.launcher3.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.launcher3.Utilities;

/**
 * A {@link ViewOutlineProvider} that has helper functions to create reveal animations.
 * This class should be extended so that subclasses can define the reveal shape as the
 * animation progresses from 0 to 1.
 */
public abstract class RevealOutlineAnimation extends ViewOutlineProvider {
    protected Rect mOutline;
    protected float mOutlineRadius;

    public RevealOutlineAnimation() {
        mOutline = new Rect();
    }

    /** Returns whether elevation should be removed for the duration of the reveal animation. */
    abstract boolean shouldRemoveElevationDuringAnimation();
    /** Sets the progress, from 0 to 1, of the reveal animation. */
    abstract void setProgress(float progress);

    public ValueAnimator createRevealAnimator(final View revealView) {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        final float elevation = revealView.getElevation();

        va.addListener(new AnimatorListenerAdapter() {
            public void onAnimationStart(Animator animation) {
                revealView.setOutlineProvider(RevealOutlineAnimation.this);
                revealView.setClipToOutline(true);
                if (shouldRemoveElevationDuringAnimation()) {
                    revealView.setTranslationZ(-elevation);
                }
            }

            public void onAnimationEnd(Animator animation) {
                revealView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                revealView.setClipToOutline(false);
                if (shouldRemoveElevationDuringAnimation()) {
                    revealView.setTranslationZ(0);
                }
            }

        });

        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator arg0) {
                float progress = arg0.getAnimatedFraction();
                setProgress(progress);
                revealView.invalidateOutline();
                if (!Utilities.ATLEAST_LOLLIPOP_MR1) {
                    revealView.invalidate();
                }
            }
        });
        return va;
    }

    @Override
    public void getOutline(View v, Outline outline) {
        outline.setRoundRect(mOutline, mOutlineRadius);
    }
}