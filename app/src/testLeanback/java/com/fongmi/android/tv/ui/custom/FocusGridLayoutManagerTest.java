package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class FocusGridLayoutManagerTest {

    private ActivityController<Activity> controller;
    private RecyclerView grid;
    private FocusGridLayoutManager manager;

    @Before
    public void setUp() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        controller = Robolectric.buildActivity(Activity.class).setup();
        grid = new RecyclerView(controller.get());
        manager = new FocusGridLayoutManager(controller.get(), 5);
        grid.setLayoutManager(manager);
        grid.setItemAnimator(null);
        grid.setPadding(0, 10, 0, 20);
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                View card = new View(parent.getContext());
                card.setFocusable(true);
                card.setFocusableInTouchMode(true);
                card.setLayoutParams(new RecyclerView.LayoutParams(100, 200));
                return new RecyclerView.ViewHolder(card) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

            @Override
            public int getItemCount() {
                return 50;
            }
        });
        controller.get().setContentView(grid, new ViewGroup.LayoutParams(500, 360));
        controller.visible();
        grid.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, 500, 360);
        drainFrames();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    @Test
    public void immediateRectangleRequestDoesNotJumpAndStopsAtBottomEdge() {
        View card = focus(5);
        int before = card.getTop();
        assertTrue(manager.requestChildRectangleOnScreen(grid, card, new Rect(0, 0, 100, 200), true, false));
        assertEquals("No synchronous jump on an immediate focus request", before, card.getTop());
        drainFrames();
        assertEquals(grid.getHeight() - grid.getPaddingBottom(), card.getBottom());
        assertTrue(card.getTop() > grid.getPaddingTop());
    }

    @Test
    public void visibleCardDoesNotScroll() {
        View card = focus(0);
        int before = card.getTop();
        assertFalse(manager.requestChildRectangleOnScreen(grid, card, new Rect(0, 0, 100, 200), true, false));
        drainFrames();
        assertEquals(before, card.getTop());
    }

    @Test
    public void smallTitleRectangleStillRevealsWholeCard() {
        View card = focus(5);
        manager.requestChildRectangleOnScreen(grid, card, new Rect(0, 0, 100, 20), true, false);
        drainFrames();
        assertEquals(grid.getHeight() - grid.getPaddingBottom(), card.getBottom());
    }

    @Test
    public void reversingFocusCancelsPreviousScroll() {
        focus(5);
        View first = focus(0);
        int before = first.getTop();
        drainFrames();
        assertEquals(before, first.getTop());
        assertTrue(first.hasFocus());
    }

    @Test
    public void retargetDuringAnimationDoesNotPublishIdle() {
        List<Integer> states = new ArrayList<>();
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recycler, int state) {
                states.add(state);
            }
        });
        focus(5);
        idleFor(48);
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, grid.getScrollState());
        states.clear();

        View next = focus(6);
        assertFalse("Retargeting must not trigger idle-time result updates",
                states.contains(RecyclerView.SCROLL_STATE_IDLE));
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, grid.getScrollState());
        drainFrames();
        assertEquals(grid.getHeight() - grid.getPaddingBottom(), next.getBottom());
        assertTrue(next.hasFocus());
        assertEquals(RecyclerView.SCROLL_STATE_IDLE, grid.getScrollState());
    }

    @Test
    public void reversingAfterAnimationStartsRevealsPreviousRow() {
        View first = focus(0);
        focus(5);
        idleFor(48);
        assertTrue(first.getTop() < grid.getPaddingTop());

        assertTrue(first.requestFocus());
        assertEquals(RecyclerView.SCROLL_STATE_SETTLING, grid.getScrollState());
        drainFrames();
        assertEquals(grid.getPaddingTop(), first.getTop());
        assertTrue(first.hasFocus());
    }

    @Test
    public void movingUpRevealsOnlyClippedTop() {
        focus(5);
        drainFrames();
        View card = focus(0);
        int before = card.getTop();
        assertTrue(before < grid.getPaddingTop());
        drainFrames();
        assertEquals(grid.getPaddingTop(), card.getTop());
    }

    @Test
    public void downAcrossSeveralRowsKeepsColumnAndScrollsToNearestEdge() {
        View card = focus(0);
        for (int position = 5; position <= 20; position += 5) {
            View next = grid.focusSearch(card, View.FOCUS_DOWN);
            assertEquals(position, grid.getChildAdapterPosition(next));
            int before = next.getTop();
            assertTrue(next.requestFocus());
            assertEquals("Changing rows must not jump before the next frame", before, next.getTop());
            drainFrames();
            assertTrue(next.hasFocus());
            assertEquals(grid.getHeight() - grid.getPaddingBottom(), next.getBottom());
            card = next;
        }
    }

    private View focus(int position) {
        View card = manager.findViewByPosition(position);
        assertTrue(card != null && card.requestFocus());
        return card;
    }

    private void drainFrames() {
        idleFor(300);
    }

    private void idleFor(long millis) {
        ShadowLooper looper = Shadow.extract(Looper.getMainLooper());
        looper.idleFor(Duration.ofMillis(millis));
    }
}
