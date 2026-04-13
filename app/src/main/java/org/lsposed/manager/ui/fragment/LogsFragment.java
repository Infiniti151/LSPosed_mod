/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager.ui.fragment;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.FragmentPagerBinding;
import org.lsposed.manager.databinding.ItemLogTextviewBinding;
import org.lsposed.manager.databinding.SwiperefreshRecyclerviewBinding;
import org.lsposed.manager.receivers.LSPManagerServiceHolder;
import org.lsposed.manager.ui.widget.EmptyStateRecyclerView;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import rikka.material.app.LocaleDelegate;
import rikka.recyclerview.RecyclerViewKt;
import rikka.core.util.ResourceUtils;

public class LogsFragment extends BaseFragment implements MenuProvider {
    private FragmentPagerBinding binding;
    private LogPageAdapter adapter;
    private MenuItem wordWrap;
    private OnBackPressedCallback backPressedCallback;

    interface OptionsItemSelectListener {
        boolean onOptionsItemSelected(@NonNull MenuItem item);
    }

    private OptionsItemSelectListener optionsItemSelectListener;

    private final ActivityResultLauncher<String> saveLogsLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"),
            uri -> {
                if (uri == null) return;
                runAsync(() -> {
                    var context = requireContext();
                    var cr = context.getContentResolver();
                    try (var zipFd = cr.openFileDescriptor(uri, "wt")) {
                        showHint(context.getString(R.string.logs_saving), false);
                        LSPManagerServiceHolder.getService().getLogs(zipFd);
                        showHint(context.getString(R.string.logs_saved), true);
                    } catch (Throwable e) {
                        var cause = e.getCause();
                        var message = cause == null ? e.getMessage() : cause.getMessage();
                        var text = context.getString(R.string.logs_save_failed2, message);
                        showHint(text, false);
                        Log.w(App.TAG, "save log", e);
                    }
                });
            });

    private void copyToClipboard(String text) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("lsposed_logs", text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentPagerBinding.inflate(inflater, container, false);
        binding.appBar.setLiftable(true);
        setupToolbar(binding.toolbar, binding.clickView, R.string.Logs, R.menu.menu_logs);
        binding.toolbar.setNavigationIcon(null);
        binding.toolbar.setSubtitle(ConfigManager.isVerboseLogEnabled() ? R.string.enabled_verbose_log : R.string.disabled_verbose_log);
        
        adapter = new LogPageAdapter(this);
        binding.viewPager.setAdapter(adapter);
        new TabLayoutMediator(binding.tabLayout, binding.viewPager, (tab, position) -> tab.setText((int) adapter.getItemId(position))).attach();

        var backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                int currentPos = binding.viewPager.getCurrentItem();
                long itemId = adapter.getItemId(currentPos);
                Fragment f = getChildFragmentManager().findFragmentByTag("f" + itemId);

                if (f instanceof LogFragment logFragment && !logFragment.adaptor.selectedPositions.isEmpty()) {
                    logFragment.adaptor.selectedPositions.clear();
                    logFragment.adaptor.notifyDataSetChanged();
                    return;
                }

                if (adapter.isCopyModeEnabled()) {
                    adapter.setCopyMode(false);
                    MenuItem copyItem = binding.toolbar.getMenu().findItem(R.id.menu_copy_mode);
                    if (copyItem != null) copyItem.setIcon(R.drawable.ic_copy);
                    
                    this.setEnabled(false);
                    return;
                }

                this.setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
        this.backPressedCallback = backCallback;

        binding.tabLayout.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup vg = (ViewGroup) binding.tabLayout.getChildAt(0);
            int tabLayoutWidth = IntStream.range(0, binding.tabLayout.getTabCount()).map(i -> vg.getChildAt(i).getWidth()).sum();
            if (tabLayoutWidth <= binding.getRoot().getWidth()) {
                binding.tabLayout.setTabMode(TabLayout.MODE_FIXED);
                binding.tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
            }
        });

        return binding.getRoot();
    }

    public void setOptionsItemSelectListener(OptionsItemSelectListener optionsItemSelectListener) {
        this.optionsItemSelectListener = optionsItemSelectListener;
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        var itemId = item.getItemId();
        if (itemId == R.id.menu_copy_mode) {
            boolean isCurrentlyEnabled = adapter.isCopyModeEnabled();
            
            if (isCurrentlyEnabled) {
                String textToCopy = adapter.getSelectedTextFromActiveFragment(); 
                if (!textToCopy.isEmpty()) {
                    copyToClipboard(textToCopy);
                    showHint(R.string.copied_to_clipboard, true);
                }
            }
            
            boolean nextState = !isCurrentlyEnabled;
            adapter.setCopyMode(!isCurrentlyEnabled);
            item.setIcon(!isCurrentlyEnabled ? R.drawable.ic_check : R.drawable.ic_copy);
            if (backPressedCallback != null) {
                backPressedCallback.setEnabled(nextState);
            }
            return true;
        } else if (itemId == R.id.menu_save) {
            save();
            return true;
        } else if (itemId == R.id.menu_word_wrap) {
            item.setChecked(!item.isChecked());
            App.getPreferences().edit().putBoolean("enable_word_wrap", item.isChecked()).apply();
            binding.viewPager.setUserInputEnabled(item.isChecked());
            adapter.refresh();
            return true;
        }
        if (optionsItemSelectListener != null) {
            return optionsItemSelectListener.onOptionsItemSelected(item);
        }
        return false;
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        wordWrap = menu.findItem(R.id.menu_word_wrap);
        wordWrap.setChecked(App.getPreferences().getBoolean("enable_word_wrap", false));
        binding.viewPager.setUserInputEnabled(wordWrap.isChecked());
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void save() {
        LocalDateTime now = LocalDateTime.now();
        String filename = String.format(LocaleDelegate.getDefaultLocale(), "LSPosed_%s.zip", now.toString());
        try {
            saveLogsLauncher.launch(filename);
        } catch (ActivityNotFoundException e) {
            showHint(R.string.enable_documentui, true);
        }
    }

    public static class LogFragment extends BaseFragment {
        public static final int SCROLL_THRESHOLD = 1;
        protected boolean verbose;
        protected SwiperefreshRecyclerviewBinding binding;
        protected LogAdaptor adaptor;
        protected LinearLayoutManager layoutManager;

        private final Handler fadeHandler = new Handler(Looper.getMainLooper());
        private final Runnable hideActions = () -> {
            if (binding != null) {
                binding.btnScrollTop.animate().alpha(0f).setDuration(300)
                        .withEndAction(() -> {
                            if (binding != null) binding.btnScrollTop.setVisibility(View.GONE);
                        });
                binding.btnScrollBottom.animate().alpha(0f).setDuration(300)
                        .withEndAction(() -> {
                            if (binding != null) binding.btnScrollBottom.setVisibility(View.GONE);
                        });
            }
        };

        private boolean isCopyMode() {
            var parent = getParentFragment();
            if (parent instanceof LogsFragment logsFragment) {
                return logsFragment.adapter.isCopyModeEnabled();
            }
            return false;
        }

        public String getSelectedText() {
            return adaptor.selectedPositions.stream()
                    .sorted()
                    .map(pos -> adaptor.log.get(pos).toString())
                    .collect(Collectors.joining("\n"));
        }

        private void silentScrollTo(int position) {
            if (binding == null) return;
            if (position == 0) {
                if (layoutManager.findFirstVisibleItemPosition() > SCROLL_THRESHOLD) {
                    binding.recyclerView.scrollToPosition(0);
                } else {
                    binding.recyclerView.smoothScrollToPosition(0);
                }
            } else {
                int end = Math.max(adaptor.getItemCount() - 1, 0);
                if (adaptor.getItemCount() - layoutManager.findLastVisibleItemPosition() > SCROLL_THRESHOLD) {
                    binding.recyclerView.scrollToPosition(end);
                } else {
                    binding.recyclerView.smoothScrollToPosition(end);
                }
            }
        }

        class LogAdaptor extends EmptyStateRecyclerView.EmptyStateAdapter<LogAdaptor.ViewHolder> {
            List<CharSequence> log = Collections.emptyList();
            private boolean isLoaded = false;
            private int anchorPosition = -1;
            final Set<Integer> selectedPositions = new HashSet<>();

            @NonNull
            @Override
            public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new ViewHolder(ItemLogTextviewBinding.inflate(getLayoutInflater(), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                final int currentPos = holder.getBindingAdapterPosition();
                if (currentPos == RecyclerView.NO_POSITION) return;

                holder.item.setText(log.get(currentPos));
                boolean copyMode = isCopyMode();

                if (copyMode) {
                    int backgroundColor = selectedPositions.contains(currentPos) 
                        ? ResourceUtils.resolveColor(holder.itemView.getContext().getTheme(), com.google.android.material.R.attr.colorPrimaryContainer) 
                        : 0;
                    holder.itemView.setBackgroundColor(backgroundColor);

                    View.OnLongClickListener rangeListener = v -> {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        if (anchorPosition != -1 && anchorPosition != currentPos) {
                            int start = Math.min(anchorPosition, currentPos);
                            int end = Math.max(anchorPosition, currentPos);
                            for (int i = start; i <= end; i++) {
                                selectedPositions.add(i);
                            }
                            notifyItemRangeChanged(start, (end - start) + 1);
                            anchorPosition = currentPos; 
                            return true; 
                        }
                        return false;
                    };

                    holder.itemView.setOnLongClickListener(rangeListener);
                    holder.item.setOnLongClickListener(rangeListener);
                    
                    holder.itemView.setOnClickListener(v -> {
                        if (selectedPositions.contains(currentPos)) {
                            selectedPositions.remove(currentPos);
                            if (anchorPosition == currentPos) anchorPosition = -1;
                        } else {
                            selectedPositions.add(currentPos);
                            anchorPosition = currentPos;
                        }
                        notifyItemChanged(currentPos);
                    });

                    holder.item.setTextIsSelectable(false);
                    holder.item.setFocusable(false);
                    holder.item.setLongClickable(true);
                    holder.item.setClickable(false);
                } else {
                    holder.itemView.setBackgroundColor(0);
                    holder.itemView.setOnClickListener(null);
                    holder.itemView.setOnLongClickListener(null);
                    
                    holder.item.setFocusable(true);
                    holder.item.setLongClickable(true);
                    holder.item.setTextIsSelectable(true);
                    holder.item.setClickable(false); 
                }
            }

            @Override
            public int getItemCount() {
                return log.size();
            }

            @SuppressLint("NotifyDataSetChanged")
            void refresh(List<CharSequence> log) {
                runOnUiThread(() -> {
                    isLoaded = true;
                    this.log = log;
                    this.selectedPositions.clear();
                    notifyDataSetChanged();
                });
            }

            void fullRefresh() {
                runAsync(() -> {
                    isLoaded = false;
                    List<CharSequence> tmp;
                    try (var parcelFileDescriptor = ConfigManager.getLog(verbose);
                         var br = new BufferedReader(new InputStreamReader(new FileInputStream(parcelFileDescriptor != null ? parcelFileDescriptor.getFileDescriptor() : null)))) {
                        tmp = br.lines().parallel().collect(Collectors.toList());
                    } catch (Throwable e) {
                        tmp = Arrays.asList(Log.getStackTraceString(e).split("\n"));
                    }
                    refresh(tmp);
                });
            }

            @Override
            public boolean isLoaded() {
                return isLoaded;
            }

            class ViewHolder extends RecyclerView.ViewHolder {
                final MaterialTextView item;

                public ViewHolder(ItemLogTextviewBinding binding) {
                    super(binding.getRoot());
                    item = binding.logItem;

                    item.setTextIsSelectable(true);
                    item.setFocusable(true);
                    item.setLongClickable(true);
                }
            }
        }

        protected LogAdaptor createAdaptor() {
            return new LogAdaptor();
        }

        @Override
        public void onDestroyView() {
            fadeHandler.removeCallbacks(hideActions);
            super.onDestroyView();
            binding = null;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            binding = SwiperefreshRecyclerviewBinding.inflate(getLayoutInflater(), container, false);
            var arguments = getArguments();
            if (arguments == null) return null;
            verbose = arguments.getBoolean("verbose");
            adaptor = createAdaptor();
            binding.recyclerView.setAdapter(adaptor);
            layoutManager = new LinearLayoutManager(requireActivity());
            binding.recyclerView.setLayoutManager(layoutManager);      
            binding.recyclerView.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            binding.swipeRefreshLayout.setProgressViewEndTarget(true, binding.swipeRefreshLayout.getProgressViewEndOffset());
            RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
            binding.swipeRefreshLayout.setOnRefreshListener(adaptor::fullRefresh);

            binding.btnScrollTop.setOnClickListener(v -> silentScrollTo(0));
            binding.btnScrollBottom.setOnClickListener(v -> silentScrollTo(1));
            
            binding.btnScrollTop.setVisibility(View.GONE);
            binding.btnScrollBottom.setVisibility(View.GONE);
            if (binding.btnScrollTop != null && binding.btnScrollBottom != null) {
                ColorStateList topTint = binding.btnScrollTop.getBackgroundTintList();
                if (topTint != null) {
                    binding.btnScrollTop.setBackgroundTintList(topTint.withAlpha(153));
                }
                binding.btnScrollTop.setCompatElevation(0f);
                binding.btnScrollTop.setImageAlpha(153);

                ColorStateList bottomTint = binding.btnScrollBottom.getBackgroundTintList();
                if (bottomTint != null) {
                    binding.btnScrollBottom.setBackgroundTintList(bottomTint.withAlpha(153));
                }
                binding.btnScrollBottom.setCompatElevation(0f);
                binding.btnScrollBottom.setImageAlpha(153);
                
                binding.btnScrollTop.setAlpha(0.6f);
                binding.btnScrollBottom.setAlpha(0.6f);
            }

            binding.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (recyclerView.computeVerticalScrollOffset() > 0) {
                        if (binding.btnScrollTop.getVisibility() != View.VISIBLE) {
                            showButtons();
                        }
                    } else {
                        hideButtons();
                    }
                }

                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        fadeHandler.postDelayed(hideActions, 2000);
                    } else {
                        fadeHandler.removeCallbacks(hideActions);
                    }
                }
            });

            adaptor.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onChanged() {
                    binding.swipeRefreshLayout.setRefreshing(!adaptor.isLoaded());
                }
            });
            adaptor.fullRefresh();
            return binding.getRoot();
        }

        private void showButtons() {
            if (binding == null) return;
            binding.btnScrollTop.setVisibility(View.VISIBLE);
            binding.btnScrollBottom.setVisibility(View.VISIBLE);
            binding.btnScrollTop.animate().alpha(1f).setDuration(200).start();
            binding.btnScrollBottom.animate().alpha(1f).setDuration(200).start();
        }

        private void hideButtons() {
            if (binding == null) return;
            binding.btnScrollTop.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> {
                        if (binding != null) binding.btnScrollTop.setVisibility(View.GONE);
                    });
            binding.btnScrollBottom.animate().alpha(0f).setDuration(300)
                    .withEndAction(() -> {
                        if (binding != null) binding.btnScrollBottom.setVisibility(View.GONE);
                    });
        }

        public void scrollToTop(LogsFragment logsFragment) {
            logsFragment.binding.appBar.setExpanded(true, true);
            if (layoutManager.findFirstVisibleItemPosition() > SCROLL_THRESHOLD) {
                binding.recyclerView.scrollToPosition(0);
            } else {
                binding.recyclerView.smoothScrollToPosition(0);
            }
        }

        public void scrollToBottom(LogsFragment logsFragment) {
            logsFragment.binding.appBar.setExpanded(false, true);
            var end = Math.max(adaptor.getItemCount() - 1, 0);
            if (adaptor.getItemCount() - layoutManager.findLastVisibleItemPosition() > SCROLL_THRESHOLD) {
                binding.recyclerView.scrollToPosition(end);
            } else {
                binding.recyclerView.smoothScrollToPosition(end);
            }
        }

        void attachListeners() {
            var parent = getParentFragment();
            if (parent instanceof LogsFragment logsFragment) {
                logsFragment.binding.appBar.setLifted(!binding.recyclerView.getBorderViewDelegate().isShowingTopBorder());
                binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> 
                    logsFragment.binding.appBar.setLifted(!top));

                logsFragment.setOptionsItemSelectListener(item -> {
                    int itemId = item.getItemId();
                    
                    if (itemId == R.id.menu_clear) {
                        if (ConfigManager.clearLogs(verbose)) {
                            logsFragment.showHint(R.string.logs_cleared, true);
                            adaptor.fullRefresh();
                        } else {
                            logsFragment.showHint(R.string.logs_clear_failed_2, true);
                        }
                        return true;
                    }
                    
                    return false;
                });

                View.OnClickListener l = v -> scrollToTop(logsFragment);
                logsFragment.binding.clickView.setOnClickListener(l);
                logsFragment.binding.toolbar.setOnClickListener(l);
            }
        }

        void detachListeners() {
            binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener(null);
        }

        @Override
        public void onStart() {
            super.onStart();
            attachListeners();
        }

        @Override
        public void onResume() {
            super.onResume();
            attachListeners();
        }


        @Override
        public void onPause() {
            super.onPause();
            detachListeners();
        }

        @Override
        public void onStop() {
            super.onStop();
            detachListeners();
        }
    }

    public static class UnwrapLogFragment extends LogFragment {

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            var root = super.onCreateView(inflater, container, savedInstanceState);
            binding.swipeRefreshLayout.removeView(binding.recyclerView);
            
            HorizontalScrollView hsv = new HorizontalScrollView(getContext());
            hsv.setFillViewport(true);
            hsv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.MATCH_PARENT));
            
            binding.swipeRefreshLayout.addView(hsv);
            hsv.addView(binding.recyclerView);

            ViewGroup.LayoutParams lp = binding.recyclerView.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT; 
            binding.recyclerView.setLayoutParams(lp);

            binding.recyclerView.setVerticalScrollBarEnabled(true);
            binding.recyclerView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            
            return root;
        }

        @Override
        protected LogAdaptor createAdaptor() {
            return new LogAdaptor() {
                @Override
                public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                    super.onBindViewHolder(holder, position);

                    var view = holder.item;
                    view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    int desiredWidth = view.getMeasuredWidth();

                    ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                    if (layoutParams.width != desiredWidth) {
                        layoutParams.width = desiredWidth;
                        binding.recyclerView.requestLayout();
                    }
                }
            };
        }
    }

    class LogPageAdapter extends FragmentStateAdapter {
        private boolean copyModeEnabled = false;

        public LogPageAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        public String getSelectedTextFromActiveFragment() {
            int currentPos = binding.viewPager.getCurrentItem();
            long itemId = getItemId(currentPos);
            Fragment f = getChildFragmentManager().findFragmentByTag("f" + itemId);
            
            if (f instanceof LogFragment logFragment) {
                return logFragment.getSelectedText();
            }
            return "";
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            var bundle = new Bundle();
            bundle.putBoolean("verbose", verbose(position));
            var f = getItemViewType(position) == 0 ? new LogFragment() : new UnwrapLogFragment();
            f.setArguments(bundle);
            return f;
        }

        @Override
        public int getItemCount() {
            return 2;
        }

        @Override
        public long getItemId(int position) {
            return verbose(position) ? R.string.nav_item_logs_verbose : R.string.nav_item_logs_module;
        }

        @Override
        public boolean containsItem(long itemId) {
            return itemId == R.string.nav_item_logs_verbose || itemId == R.string.nav_item_logs_module;
        }

        public boolean verbose(int position) {
            return position != 0;
        }

        @Override
        public int getItemViewType(int position) {
            return wordWrap.isChecked() ? 0 : 1;
        }

        public void refresh() {
            runOnUiThread(this::notifyDataSetChanged);
        }

        public boolean isCopyModeEnabled() {
            return copyModeEnabled;
        }

        public void setCopyMode(boolean enabled) {
        this.copyModeEnabled = enabled;
        for (int i = 0; i < getItemCount(); i++) {
            Fragment f = getChildFragmentManager().findFragmentByTag("f" + getItemId(i));
            if (f instanceof LogFragment logFragment && logFragment.adaptor != null) {
                if (!enabled) {
                    logFragment.adaptor.selectedPositions.clear();
                    logFragment.adaptor.anchorPosition = -1;
                }
                logFragment.adaptor.notifyDataSetChanged();
            }
        }
    }
    }
    }
}
