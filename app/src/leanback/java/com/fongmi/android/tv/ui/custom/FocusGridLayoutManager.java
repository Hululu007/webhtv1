package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Keeps remote focus scrolling at the nearest visible edge of the search grid. */
public class FocusGridLayoutManager extends GridLayoutManager {

    public FocusGridLayoutManager(Context context, int spanCount) {
        super(context, spanCount);
    }

    @Override
    public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect,
                                                boolean immediate, boolean focusedChildVisible) {
        if (focusedChildVisible || !child.hasFocus()) {
            return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible);
        }
        // A title can request its own rectangle after focus changes. Reveal the
        // whole card, and keep even immediate requests on the animated path.
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) child.getLayoutParams();
        int top = getDecoratedTop(child) - params.topMargin - getPaddingTop();
        int bottom = getDecoratedBottom(child) + params.bottomMargin - (getHeight() - getPaddingBottom());
        int dy = top < 0 ? top : Math.min(top, Math.max(0, bottom));
        if (dy == 0) {
            // A visible target needs no animation, including when reversing focus.
            parent.stopScroll();
            return false;
        }
        // Retarget the running animation without publishing an intermediate IDLE
        // state, which would flush pending search results during focus navigation.
        parent.smoothScrollBy(0, dy, null, 180);
        return true;
    }
}
