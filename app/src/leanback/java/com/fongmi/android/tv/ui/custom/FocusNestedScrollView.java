package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

public class FocusNestedScrollView extends NestedScrollView {

    private static final long FOCUS_SCROLL_DURATION = 180;

    private boolean inLayout;
    private boolean waitingForLayout;
    private View pendingFocusedView;
    private int pendingStartY;
    private int pendingTargetY;
    private ValueAnimator focusScrollAnimator;
    private final Rect focusRect = new Rect();
    private final Runnable focusScrollAction = this::applyPendingFocusScroll;

    public FocusNestedScrollView(@NonNull Context context) {
        super(context);
    }

    public FocusNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FocusNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        cancelFocusScroll();
        pendingFocusedView = focused;
        pendingStartY = getScrollY();
        waitingForLayout = inLayout || isLayoutRequested();
        super.requestChildFocus(child, focused);
        if (!waitingForLayout) captureFocusScroll();
    }

    @Override
    public void clearChildFocus(View child) {
        cancelFocusScroll();
        pendingFocusedView = null;
        waitingForLayout = false;
        super.clearChildFocus(child);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        inLayout = true;
        try {
            super.onLayout(changed, left, top, right, bottom);
        } finally {
            inLayout = false;
        }
        if (waitingForLayout) {
            waitingForLayout = false;
            captureFocusScroll();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelFocusScroll();
        pendingFocusedView = null;
        waitingForLayout = false;
        super.onDetachedFromWindow();
    }

    private void captureFocusScroll() {
        super.scrollTo(getScrollX(), pendingStartY);
        pendingStartY = getScrollY();
        View focused = pendingFocusedView;
        if (focused == null || !focused.hasFocus()) {
            pendingFocusedView = null;
            return;
        }
        focused.getDrawingRect(focusRect);
        offsetDescendantRectToMyCoords(focused, focusRect);
        pendingTargetY = pendingStartY + computeScrollDeltaToGetChildRectOnScreen(focusRect);
        removeCallbacks(focusScrollAction);
        if (pendingStartY != pendingTargetY) postOnAnimation(focusScrollAction);
        else pendingFocusedView = null;
    }

    private void applyPendingFocusScroll() {
        View focused = pendingFocusedView;
        pendingFocusedView = null;
        if (focused == null || !focused.hasFocus()) return;
        focusScrollAnimator = ValueAnimator.ofInt(getScrollY(), pendingTargetY);
        focusScrollAnimator.setDuration(FOCUS_SCROLL_DURATION);
        focusScrollAnimator.addUpdateListener(animation -> super.scrollTo(getScrollX(), (int) animation.getAnimatedValue()));
        focusScrollAnimator.start();
    }

    private void cancelFocusScroll() {
        removeCallbacks(focusScrollAction);
        if (focusScrollAnimator != null) focusScrollAnimator.cancel();
        focusScrollAnimator = null;
    }
}
