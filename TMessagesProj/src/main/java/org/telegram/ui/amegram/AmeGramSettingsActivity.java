/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (c) 2026 Umi.
 */
package org.telegram.ui.amegram;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.amegram.AmeGramConfig;
import org.telegram.messenger.amegram.AmeGramBackup;
import org.telegram.messenger.amegram.AmeGramSettings;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.FiltersSetupActivity;
import org.telegram.ui.NotificationsSettingsActivity;
import org.telegram.ui.PrivacySettingsActivity;
import org.telegram.ui.ThemeActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** A small, solid-surface entry point using Telegram's existing navigation stack. */
public final class AmeGramSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int EXPORT_SETTINGS = 12071;
    private static final int IMPORT_SETTINGS = 12072;
    private final ArrayList<TextView> bodyViews = new ArrayList<>();
    private final ArrayList<TextView> headingViews = new ArrayList<>();
    private final ArrayList<View> surfaceViews = new ArrayList<>();
    private AmeGramSettings settings;
    private FrameLayout root;
    private LinearLayout content;
    private Switch oledSwitch;
    private final ArrayList<TextView> backupActions = new ArrayList<>();
    private TextView backupStatus;
    private boolean busy;
    private int busyMessage;
    private boolean updatingControls;
    private boolean destroyed;

    @Override
    public boolean onFragmentCreate() {
        destroyed = false;
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) applyPalette();
    }

    @Override
    public View createView(Context context) {
        settings = new AmeGramSettings(context);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.AmeGramSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        root = new FrameLayout(context);
        fragmentView = root;
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        bodyViews.clear();
        headingViews.clear();
        surfaceViews.clear();
        backupActions.clear();

        addText(context, R.string.AmeGramUnofficial, false);
        addText(context, R.string.AmeGramAppearance, true);
        addLink(context, R.string.AmeGramTelegramAppearance, () -> new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
        oledSwitch = new Switch(context);
        oledSwitch.setText(LocaleController.getString(R.string.AmeGramOledBackground));
        oledSwitch.setTextSize(16);
        oledSwitch.setGravity(Gravity.CENTER_VERTICAL);
        oledSwitch.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        oledSwitch.setMinHeight(AndroidUtilities.dp(56));
        oledSwitch.setChecked(settings.usesOledDarkBackground());
        content.addView(oledSwitch, new LinearLayout.LayoutParams(-1, -2));
        bodyViews.add(oledSwitch);
        surfaceViews.add(oledSwitch);
        oledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (updatingControls) return;
            settings.setOledDarkBackground(checked);
            applyPalette();
        });
        addText(context, R.string.AmeGramOledBackgroundInfo, false);

        addText(context, R.string.AmeGramChatOrganization, true);
        addLink(context, R.string.AmeGramTelegramFolders, FiltersSetupActivity::new);
        addText(context, R.string.AmeGramNotifications, true);
        addLink(context, R.string.AmeGramTelegramNotifications, NotificationsSettingsActivity::new);
        addText(context, R.string.AmeGramPrivacy, true);
        addLink(context, R.string.AmeGramTelegramPrivacy, PrivacySettingsActivity::new);
        addText(context, R.string.AmeGramLab, true);
        addText(context, R.string.AmeGramLabUnavailable, false);
        addText(context, R.string.AmeGramBackup, true);
        addText(context, R.string.AmeGramBackupInfo, false);
        backupActions.add(addAction(context, R.string.AmeGramBackupExport, () -> openBackupPicker(true)));
        backupActions.add(addAction(context, R.string.AmeGramBackupImport, () -> openBackupPicker(false)));
        backupActions.add(addAction(context, R.string.AmeGramBackupReset, this::confirmReset));
        backupStatus = addText(context, R.string.AmeGramBackupReading, false);
        backupStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        setBusy(busy, busyMessage);
        addText(context, R.string.AmeGramAbout, true);
        TextView version = addText(context, R.string.AmeGramUnofficial, false);
        version.setText(LocaleController.formatString(R.string.AmeGramAboutDetails,
                AmeGramConfig.VERSION, AmeGramConfig.DEVELOPER,
                LocaleController.getString(R.string.AmeGramSlogan),
                LocaleController.getString(R.string.AmeGramUnofficial)));
        applyPalette();
        return fragmentView;
    }

    private TextView addText(Context context, int stringId, boolean heading) {
        TextView view = new TextView(context);
        view.setText(LocaleController.getString(stringId));
        view.setTextSize(heading ? 16 : 14);
        view.setGravity(Gravity.START);
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(heading ? 20 : 12),
                AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        if (heading) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            androidx.core.view.ViewCompat.setAccessibilityHeading(view, true);
        }
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        (heading ? headingViews : bodyViews).add(view);
        return view;
    }

    private void addLink(Context context, int title, java.util.function.Supplier<BaseFragment> factory) {
        addAction(context, title, () -> {
            BaseFragment target = factory.get();
            target.setCurrentAccount(currentAccount);
            presentFragment(target);
        });
    }

    private TextView addAction(Context context, int title, Runnable action) {
        TextView view = addText(context, title, false);
        view.setTextSize(16);
        view.setMinHeight(AndroidUtilities.dp(56));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        view.setOnClickListener(v -> action.run());
        surfaceViews.add(view);
        return view;
    }

    private void openBackupPicker(boolean export) {
        if (busy || getParentActivity() == null) return;
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(export ? "application/json" : "*/*");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "AmeGram-settings.json");
        setBusy(true, R.string.AmeGramBackupChooseFile);
        try {
            startActivityForResult(intent, export ? EXPORT_SETTINGS : IMPORT_SETTINGS);
        } catch (RuntimeException error) {
            setBusy(false, 0);
            showBackupMessage(R.string.AmeGramBackupPickerFailed);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (destroyed || (requestCode != EXPORT_SETTINGS && requestCode != IMPORT_SETTINGS)) return;
        if (resultCode != Activity.RESULT_OK) {
            setBusy(false, 0);
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme()) || getParentActivity() == null) {
            setBusy(false, 0);
            showBackupMessage(R.string.AmeGramBackupPickerFailed);
            return;
        }
        ContentResolver resolver = getParentActivity().getApplicationContext().getContentResolver();
        if (requestCode == EXPORT_SETTINGS) {
            AmeGramBackup.Snapshot snapshot = settings.snapshot();
            runBackup(R.string.AmeGramBackupSaving, R.string.AmeGramBackupWriteFailed, () -> {
                byte[] bytes = AmeGramBackup.encode(snapshot);
                try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException();
                    output.write(bytes);
                    output.flush();
                }
                return snapshot;
            }, result -> backupToast(R.string.AmeGramBackupExported));
        } else {
            runBackup(R.string.AmeGramBackupReading, R.string.AmeGramBackupReadFailed, () -> {
                try (InputStream input = resolver.openInputStream(uri)) {
                    if (input == null) throw new IOException();
                    return AmeGramBackup.decode(input);
                }
            }, this::confirmImport);
        }
    }

    private void confirmImport(AmeGramBackup.Snapshot snapshot) {
        if (destroyed || getParentActivity() == null) return;
        String preview = LocaleController.formatString(R.string.AmeGramBackupImportPreview,
                LocaleController.getString(snapshot.oledDarkBackground
                        ? R.string.AmeGramBackupOledOn : R.string.AmeGramBackupOledOff));
        showBackupDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.AmeGramBackupImport))
                .setMessage(preview)
                .setPositiveButton(LocaleController.getString(R.string.AmeGramBackupRestore), (dialog, which) -> restoreBackup(snapshot))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null));
    }

    private void confirmReset() {
        if (busy || getParentActivity() == null) return;
        showBackupDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.AmeGramBackupReset))
                .setMessage(LocaleController.getString(R.string.AmeGramBackupResetInfo))
                .setPositiveButton(LocaleController.getString(R.string.AmeGramBackupResetAction),
                        (dialog, which) -> restoreBackup(new AmeGramBackup.Snapshot(false)))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null));
    }

    private void restoreBackup(AmeGramBackup.Snapshot snapshot) {
        if (busy || destroyed) return;
        AmeGramSettings store = settings;
        runBackup(R.string.AmeGramBackupSaving, R.string.AmeGramBackupWriteFailed, () -> {
            if (!store.restore(snapshot)) throw new IOException();
            return snapshot;
        }, result -> backupToast(R.string.AmeGramBackupRestored));
    }

    private <T> void runBackup(int progress, int failure, Callable<T> work, Consumer<T> completed) {
        setBusy(true, progress);
        // A document provider may be slow; never block UI or Telegram's shared queues.
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                T result = work.call();
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed) return;
                    setBusy(false, 0);
                    refreshSettings();
                    completed.accept(result);
                });
            } catch (Exception error) {
                // No URI, file contents, parser errors or configuration values in logs.
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed) return;
                    setBusy(false, 0);
                    refreshSettings();
                    showBackupMessage(failure);
                });
            }
        }, "AmeBackup").start();
    }

    private void setBusy(boolean value, int message) {
        busy = value;
        busyMessage = message;
        if (oledSwitch != null) oledSwitch.setEnabled(!busy);
        for (TextView action : backupActions) {
            action.setEnabled(!busy);
            action.setAlpha(busy ? 0.5f : 1f);
        }
        if (backupStatus != null) {
            if (busy) backupStatus.setText(LocaleController.getString(message));
            backupStatus.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
    }

    private void showBackupMessage(int message) {
        if (destroyed || getParentActivity() == null) return;
        showBackupDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.AmeGramBackup))
                .setMessage(LocaleController.getString(message))
                .setPositiveButton(LocaleController.getString(R.string.OK), null));
    }

    private void showBackupDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        boolean dark = Theme.isCurrentThemeDark();
        dialog.setBlurParams(1f, false, false);
        dialog.setBackgroundColor(AmeGramPalette.surface(dark, settings.usesOledDarkBackground()));
        if (showDialog(dialog) != null) {
            dialog.setTextColor(AmeGramPalette.text(dark));
            for (int type : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
                View button = dialog.getButton(type);
                if (button instanceof TextView) {
                    ((TextView) button).setTextColor(AmeGramPalette.accessibleAccent(dark));
                }
            }
        }
    }

    private void backupToast(int message) {
        if (getParentActivity() != null) Toast.makeText(getParentActivity(), LocaleController.getString(message), Toast.LENGTH_SHORT).show();
    }

    private void refreshSettings() {
        if (oledSwitch == null) return;
        updatingControls = true;
        oledSwitch.setChecked(settings.usesOledDarkBackground());
        updatingControls = false;
        applyPalette();
    }

    private void applyPalette() {
        if (root == null) return;
        boolean dark = Theme.isCurrentThemeDark();
        boolean oled = settings.usesOledDarkBackground();
        int background = AmeGramPalette.background(dark, oled);
        int surface = AmeGramPalette.surface(dark, oled);
        int accent = AmeGramPalette.accessibleAccent(dark);
        root.setBackgroundColor(background);
        for (TextView view : bodyViews) {
            view.setTextColor(AmeGramPalette.secondaryText(dark));
        }
        for (TextView view : headingViews) view.setTextColor(accent);
        for (View view : surfaceViews) {
            view.setBackground(Theme.createSelectorDrawable(dark ? 0x33A78BFA : 0x227C3AED, 2));
            // Selector + solid backing are drawn together, without blur or translucency of the surface.
            android.graphics.drawable.Drawable selector = view.getBackground();
            view.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(surface), selector}));
            ((TextView) view).setTextColor(AmeGramPalette.text(dark));
        }
        oledSwitch.setThumbTintList(ColorStateList.valueOf(accent));
        oledSwitch.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{accent, AmeGramPalette.secondaryText(dark)}));
        actionBar.setBackgroundColor(surface);
        actionBar.setTitleColor(AmeGramPalette.text(dark));
        actionBar.setItemsColor(accent, false);
        setNavigationBarColor(background);
        if (getParentActivity() != null) AndroidUtilities.setLightStatusBar(getParentActivity(), !dark);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshSettings();
    }

    @Override
    public int getNavigationBarColor() {
        return AmeGramPalette.background(Theme.isCurrentThemeDark(), settings != null && settings.usesOledDarkBackground());
    }

    @Override
    public boolean isLightStatusBar() {
        return !Theme.isCurrentThemeDark();
    }
}
