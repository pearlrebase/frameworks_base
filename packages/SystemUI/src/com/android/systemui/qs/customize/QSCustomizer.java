/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toolbar;
import android.widget.Toolbar.OnMenuItemClickListener;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.KeyguardMonitor.Callback;

import java.util.ArrayList;
import java.util.List;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout implements OnMenuItemClickListener {

    private static final int MENU_RESET = Menu.FIRST;
    private static final String EXTRA_QS_CUSTOMIZING = "qs_customizing";

    private final QSDetailClipper mClipper;
    private final LightBarController mLightBarController;
    private final TileQueryHelper mTileQueryHelper;

    private boolean isShown;
    private QSTileHost mHost;
    private RecyclerView mRecyclerView;
    private TileAdapter mTileAdapter;
    private Toolbar mToolbar;
    private boolean mCustomizing;
    private NotificationsQuickSettingsContainer mNotifQsContainer;
    private QS mQs;
    private int mX;
    private int mY;
    private boolean mOpening;
    private boolean mIsShowingNavBackdrop;
    private GridLayoutManager mGlm;
    private int mDefaultColumns;
    private View mTopMarginView;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, R.style.edit_theme), attrs);

        LayoutInflater.from(getContext()).inflate(R.layout.qs_customize_panel_content, this);
        mClipper = new QSDetailClipper(findViewById(R.id.customize_container));
        mToolbar = findViewById(com.android.internal.R.id.action_bar);
        mTopMarginView = findViewById(R.id.customize_top_margin);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        mToolbar.setNavigationIcon(
                getResources().getDrawable(value.resourceId, mContext.getTheme()));
        mToolbar.setNavigationOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide((int) v.getX() + v.getWidth() / 2, (int) v.getY() + v.getHeight() / 2);
            }
        });
        mToolbar.setOnMenuItemClickListener(this);
        mToolbar.getMenu().add(Menu.NONE, MENU_RESET, 0,
                mContext.getString(com.android.internal.R.string.reset));
        mToolbar.setTitle(R.string.qs_edit);
        int accentColor = Utils.getColorAttr(context, android.R.attr.colorAccent);
        mDefaultColumns = Math.max(1, mContext.getResources().getInteger(R.integer.quick_settings_num_columns));
        mToolbar.setTitleTextColor(accentColor);
        mToolbar.getNavigationIcon().setTint(accentColor);
        mToolbar.getOverflowIcon().setTint(accentColor);
        mRecyclerView = findViewById(android.R.id.list);
        mTileAdapter = new TileAdapter(getContext());
        mTileQueryHelper = new TileQueryHelper(context, mTileAdapter);
        mRecyclerView.setAdapter(mTileAdapter);
        mTileAdapter.getItemTouchHelper().attachToRecyclerView(mRecyclerView);
        mGlm = new GridLayoutManager(getContext(), mDefaultColumns);
        mGlm.setSpanSizeLookup(mTileAdapter.getSizeLookup());
        mRecyclerView.setLayoutManager(mGlm);
        mRecyclerView.addItemDecoration(mTileAdapter.getItemDecoration());
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setMoveDuration(TileAdapter.MOVE_DURATION);
        mRecyclerView.setItemAnimator(animator);
        mLightBarController = Dependency.get(LightBarController.class);
        updateNavBackDrop(getResources().getConfiguration());

        updateResources();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateNavBackDrop(newConfig);
    }

    private void updateNavBackDrop(Configuration newConfig) {
        View navBackdrop = findViewById(R.id.nav_bar_background);
        mIsShowingNavBackdrop = newConfig.smallestScreenWidthDp >= 600
                || newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE;
        if (navBackdrop != null) {
            navBackdrop.setVisibility(mIsShowingNavBackdrop ? View.VISIBLE : View.GONE);
        }
        updateNavColors();
        updateResources();
    }

    private void updateNavColors() {
        mLightBarController.setQsCustomizing(mIsShowingNavBackdrop && isShown);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mTileAdapter.setHost(host);
    }

    public void setContainer(NotificationsQuickSettingsContainer notificationsQsContainer) {
        mNotifQsContainer = notificationsQsContainer;
    }

    public void setQs(QS qs) {
        mQs = qs;
    }

    public void show(int x, int y) {
        if (!isShown) {
            mX = x;
            mY = y;
            MetricsLogger.visible(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = true;
            mOpening = true;
            setTileSpecs();
            setVisibility(View.VISIBLE);
            mClipper.animateCircularClip(x, y, true, mExpandAnimationListener);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(true);
            Dependency.get(KeyguardMonitor.class).addCallback(mKeyguardCallback);
            updateNavColors();
        }
    }


    public void showImmediately() {
        if (!isShown) {
            setVisibility(VISIBLE);
            mClipper.showBackground();
            isShown = true;
            setTileSpecs();
            setCustomizing(true);
            queryTiles();
            mNotifQsContainer.setCustomizerAnimating(false);
            mNotifQsContainer.setCustomizerShowing(true);
            Dependency.get(KeyguardMonitor.class).addCallback(mKeyguardCallback);
            updateNavColors();
        }
    }

    private void queryTiles() {
        mTileQueryHelper.queryTiles(mHost);
    }

    public void hide(int x, int y) {
        if (isShown) {
            MetricsLogger.hidden(getContext(), MetricsProto.MetricsEvent.QS_EDIT);
            isShown = false;
            mToolbar.dismissPopupMenus();
            setCustomizing(false);
            save();
            mClipper.animateCircularClip(mX, mY, false, mCollapseAnimationListener);
            mNotifQsContainer.setCustomizerAnimating(true);
            mNotifQsContainer.setCustomizerShowing(false);
            Dependency.get(KeyguardMonitor.class).removeCallback(mKeyguardCallback);
            updateNavColors();
        }
    }

    public boolean isShown() {
        return isShown;
    }

    private void setCustomizing(boolean customizing) {
        mCustomizing = customizing;
        mQs.notifyCustomizeChanged();
    }

    public boolean isCustomizing() {
        return mCustomizing || mOpening;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_RESET:
                MetricsLogger.action(getContext(), MetricsProto.MetricsEvent.ACTION_QS_EDIT_RESET);
                reset();
                break;
        }
        updateResources();
        return false;
    }

    private void reset() {
        ArrayList<String> tiles = new ArrayList<>();
        String defTiles = mContext.getString(R.string.quick_settings_tiles_default);
        for (String tile : defTiles.split(",")) {
            tiles.add(tile);
        }
        mTileAdapter.resetTileSpecs(mHost, tiles);
    }

    private void setTileSpecs() {
        List<String> specs = new ArrayList<>();
        for (QSTile tile : mHost.getTiles()) {
            specs.add(tile.getTileSpec());
        }
        mTileAdapter.setTileSpecs(specs);
        mRecyclerView.setAdapter(mTileAdapter);
    }

    private void save() {
        if (mTileQueryHelper.isFinished()) {
            mTileAdapter.saveSpecs(mHost);
        }
    }


    public void saveInstanceState(Bundle outState) {
        if (isShown) {
            Dependency.get(KeyguardMonitor.class).removeCallback(mKeyguardCallback);
        }
        outState.putBoolean(EXTRA_QS_CUSTOMIZING, mCustomizing);
    }

    public void restoreInstanceState(Bundle savedInstanceState) {
        boolean customizing = savedInstanceState.getBoolean(EXTRA_QS_CUSTOMIZING);
        if (customizing) {
            setVisibility(VISIBLE);
            addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft,
                        int oldTop, int oldRight, int oldBottom) {
                    removeOnLayoutChangeListener(this);
                    showImmediately();
                }
            });
        }
    }

    public void setEditLocation(int x, int y) {
        mX = x;
        mY = y;
    }

    private final Callback mKeyguardCallback = () -> {
        if (!isAttachedToWindow()) return;
        if (Dependency.get(KeyguardMonitor.class).isShowing() && !mOpening) {
            hide(0, 0);
        }
    };

    private final AnimatorListener mExpandAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (isShown) {
                setCustomizing(true);
                updateResources();
            }
            mOpening = false;
            mNotifQsContainer.setCustomizerAnimating(false);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            mOpening = false;
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    private final AnimatorListener mCollapseAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
            mRecyclerView.setAdapter(mTileAdapter);
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (!isShown) {
                setVisibility(View.GONE);
            }
            mNotifQsContainer.setCustomizerAnimating(false);
        }
    };

    public void updateResources() {
        final int columns;
        if (mContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            columns = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_COLUMNS_PORTRAIT, mDefaultColumns,
                    UserHandle.USER_CURRENT);
        } else {
            columns = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.QS_COLUMNS_LANDSCAPE, mDefaultColumns,
                    UserHandle.USER_CURRENT);
        }
        mTileAdapter.setColumns(columns);
        mGlm.setSpanCount(columns);
    }

    public void updateTopMargin() {
        // move down if we show a header image
        boolean headerImageEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        int topMargin = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) + (headerImageEnabled ?
                mContext.getResources().getDimensionPixelSize(R.dimen.qs_header_image_offset) : 0);
        ViewGroup.LayoutParams lp = mTopMarginView.getLayoutParams();
        lp.height = topMargin;
        mTopMarginView.setLayoutParams(lp);
    }
}
