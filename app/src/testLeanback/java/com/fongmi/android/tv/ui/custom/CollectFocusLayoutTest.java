package com.fongmi.android.tv.ui.custom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.databinding.AdapterSearchBinding;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class CollectFocusLayoutTest {

    @Test
    public void twoRowsRetainTheirPositionWhenFocusMovesDown() {
        checkFocus(false, 1f, false);
    }

    @Test
    @Config(qualifiers = "w960dp-h720dp-land-mdpi")
    public void threeRowsRetainTheirPositionWhenFocusMovesDown() {
        checkFocus(false, 1f, false);
    }

    @Test
    @Config(qualifiers = "w960dp-h360dp-land-mdpi")
    public void partiallyVisibleRowScrollsOnlyToBottomEdge() {
        checkFocus(false, 1f, false);
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    public void higherDensityUsesActualViewportHeight() {
        checkFocus(false, 1f, false);
    }

    @Test
    public void largerTitlesAndAppendedResultsKeepFocusPosition() {
        checkFocus(false, 1.4f, true);
    }

    @Test
    public void baselineMeasurementReproducesReportedRowJump() {
        checkFocus(true, 1f, false);
    }

    private void checkFocus(boolean reproduceBug, float titleScale, boolean appendResults) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        try {
            Activity activity = controller.get();
            activity.setTheme(com.google.android.material.R.style.Theme_MaterialComponents_DayNight_NoActionBar);
            ActivityCollectBinding page = ActivityCollectBinding.inflate(activity.getLayoutInflater());
            page.result.setText("Search result: example");
            RecyclerView grid = page.recycler;
            if (reproduceBug) ((LinearLayoutCompat) grid.getParent()).setBaselineAligned(true);
            List<Integer> layoutHeights = new ArrayList<>();
            FocusGridLayoutManager manager = new FocusGridLayoutManager(activity, 5) {
                @Override
                public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
                    layoutHeights.add(getHeight());
                    super.onLayoutChildren(recycler, state);
                }
            };
            grid.setLayoutManager(manager);
            grid.setHasFixedSize(true);
            grid.setItemAnimator(null);
            grid.setItemViewCacheSize(15);
            float density = activity.getResources().getDisplayMetrics().density;
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
            int width = (screenWidth - Math.round(252 * density)) / 5;
            int imageHeight = (int) (width / .72f);
            int[] itemCount = {50};
            grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                    AdapterSearchBinding card = AdapterSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
                    card.getRoot().getLayoutParams().width = width;
                    card.getRoot().getLayoutParams().height = imageHeight + Math.round(34 * density);
                    card.image.getLayoutParams().height = imageHeight;
                    card.name.setSingleLine(true);
                    card.name.setHorizontallyScrolling(true);
                    card.name.setMarqueeRepeatLimit(-1);
                    card.name.setTextSize(16 * titleScale);
                    card.getRoot().setOnFocusChangeListener((view, focused) -> {
                        card.name.setEllipsize(focused ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                        card.name.setSelected(focused);
                    });
                    return new RecyclerView.ViewHolder(card.getRoot()) {};
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    AdapterSearchBinding card = AdapterSearchBinding.bind(holder.itemView);
                    card.name.setText("A long movie title which starts marquee when focused " + position);
                    card.name.setEllipsize(holder.itemView.hasFocus() ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                    card.name.setSelected(holder.itemView.hasFocus());
                }

                @Override
                public int getItemCount() {
                    return itemCount[0];
                }
            });
            activity.setContentView(page.getRoot(), new ViewGroup.LayoutParams(screenWidth, screenHeight));
            controller.visible();
            page.getRoot().measure(View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY));
            page.getRoot().layout(0, 0, screenWidth, screenHeight);
            idle();
            View first = manager.findViewByPosition(2);
            assertNotNull(first);
            assertTrue(first.requestFocus());
            idle();
            layoutHeights.clear();
            View next = grid.focusSearch(first, View.FOCUS_DOWN);
            assertEquals(7, grid.getChildAdapterPosition(next));
            int viewportBottom = grid.getHeight() - grid.getPaddingBottom();
            int expectedTop = next.getTop() - Math.max(0, next.getBottom() - viewportBottom);
            assertTrue(next.requestFocus());
            if (appendResults) {
                itemCount[0] += 15;
                grid.getAdapter().notifyItemRangeInserted(50, 15);
            }
            idle();
            assertTrue(next.hasFocus());
            if (reproduceBug) {
                assertTrue("Old baseline measurement lays out at zero height", layoutHeights.contains(0));
                assertTrue("Old baseline measurement moves the focused row above its intended position", next.getTop() < expectedTop);
            } else {
                assertTrue("Focus-induced layout must use a nonzero viewport", layoutHeights.stream().allMatch(value -> value > 0));
                assertEquals("Keep a visible row in place, otherwise reveal only its clipped bottom", expectedTop, next.getTop());
                assertTrue(next.getBottom() <= viewportBottom);
                // Move back up and repeat down while the animation is still running.
                View previous = grid.focusSearch(next, View.FOCUS_UP);
                assertEquals(2, grid.getChildAdapterPosition(previous));
                assertTrue(previous.requestFocus());
                assertTrue(next.requestFocus());
                idle();
                assertEquals(expectedTop, next.getTop());
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private void idle() {
        ShadowLooper looper = Shadow.extract(Looper.getMainLooper());
        looper.idleFor(Duration.ofMillis(350));
    }
}
