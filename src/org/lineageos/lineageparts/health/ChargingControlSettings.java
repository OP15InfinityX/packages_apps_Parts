/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.health;

import static lineageos.health.HealthInterface.MODE_AUTO;
import static lineageos.health.HealthInterface.MODE_LIMIT;
import static lineageos.health.HealthInterface.MODE_MANUAL;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.app.IGameSpaceService;

import lineageos.health.HealthInterface;
import lineageos.preference.LineageSystemSettingListPreference;
import lineageos.preference.LineageSystemSettingMainSwitchPreference;
import lineageos.providers.LineageSettings;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;
import org.lineageos.lineageparts.search.BaseSearchIndexProvider;
import org.lineageos.lineageparts.search.Searchable;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

public class ChargingControlSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener, Searchable {
    private static final String TAG = ChargingControlSettings.class.getSimpleName();

    private static final String CHARGING_CONTROL_PREF = "charging_control";
    private static final String CHARGING_CONTROL_ENABLED_PREF = "charging_control_enabled";
    private static final String CHARGING_CONTROL_MODE_PREF = "charging_control_mode";
    private static final String CHARGING_CONTROL_START_TIME_PREF = "charging_control_start_time";
    private static final String CHARGING_CONTROL_TARGET_TIME_PREF = "charging_control_target_time";
    private static final String CHARGING_CONTROL_LIMIT_PREF = "charging_control_charging_limit";
    private static final String BYPASS_CHARGING_PREF = "bypass_charging";
    private static final String BYPASS_CHARGE_ACTIVE = "bypass_charge_active";
    private static final String BYPASS_SAVED_ENABLED = "bypass_charge_saved_enabled";
    private static final String BYPASS_SAVED_MODE = "bypass_charge_saved_mode";
    private static final String BYPASS_SAVED_LIMIT = "bypass_charge_saved_limit";

    private LineageSystemSettingMainSwitchPreference mChargingControlEnabledPref;
    private LineageSystemSettingListPreference mChargingControlModePref;
    private StartTimePreference mChargingControlStartTimePref;
    private TargetTimePreference mChargingControlTargetTimePref;
    private ChargingLimitPreference mChargingControlLimitPref;
    private SwitchPreferenceCompat mBypassChargingPref;

    private HealthInterface mHealthInterface;

    private static final int MENU_RESET = Menu.FIRST;

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Resources res = getResources();

        addPreferencesFromResource(R.xml.charging_control_settings);
        requireActivity().getActionBar().setTitle(R.string.charging_control_title);

        mHealthInterface = HealthInterface.getInstance(getActivity());

        final PreferenceScreen prefSet = getPreferenceScreen();

        mChargingControlEnabledPref = prefSet.findPreference(CHARGING_CONTROL_ENABLED_PREF);
        mChargingControlEnabledPref.setOnPreferenceChangeListener(this);
        mChargingControlModePref = prefSet.findPreference(CHARGING_CONTROL_MODE_PREF);
        mChargingControlModePref.setOnPreferenceChangeListener(this);
        mChargingControlStartTimePref = prefSet.findPreference(CHARGING_CONTROL_START_TIME_PREF);
        mChargingControlTargetTimePref = prefSet.findPreference(CHARGING_CONTROL_TARGET_TIME_PREF);
        mChargingControlLimitPref = prefSet.findPreference(CHARGING_CONTROL_LIMIT_PREF);
        mBypassChargingPref = prefSet.findPreference(BYPASS_CHARGING_PREF);
        mBypassChargingPref.setOnPreferenceChangeListener(this);
        if (mChargingControlLimitPref != null) {
            if (mHealthInterface.allowFineGrainedSettings()) {
                mChargingControlModePref.setEntries(concatStringArrays(
                        mChargingControlModePref.getEntries(),
                        res.getStringArray(
                                R.array.charging_control_mode_entries_fine_grained_control)));
                mChargingControlModePref.setEntryValues(concatStringArrays(
                        mChargingControlModePref.getEntryValues(),
                        res.getStringArray(
                                R.array.charging_control_mode_values_fine_grained_control)));
            }
        }

        setHasOptionsMenu(true);

        refreshValues();

        watch(LineageSettings.System.getUriFor(LineageSettings.System.CHARGING_CONTROL_ENABLED));
        watch(Settings.Global.getUriFor(BYPASS_CHARGE_ACTIVE));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshValues() {
        final boolean bypassActive = isBypassChargingActive();
        final int chargingControlMode = bypassActive
                ? getSavedChargingControlMode() : mHealthInterface.getMode();

        if (mChargingControlEnabledPref != null) {
            mChargingControlEnabledPref.setChecked(bypassActive
                    ? getSavedChargingControlEnabled() : mHealthInterface.getEnabled());
            mChargingControlEnabledPref.setEnabled(!bypassActive);
            mChargingControlEnabledPref.setTitle(R.string.charging_control_enable_title);
            mChargingControlEnabledPref.setSummary((CharSequence) null);
        }

        if (mBypassChargingPref != null) {
            mBypassChargingPref.setChecked(bypassActive);
            mBypassChargingPref.setSummary(bypassActive
                    ? R.string.bypass_charging_active_summary
                    : R.string.bypass_charging_summary);
        }

        if (mChargingControlModePref != null) {
            mChargingControlModePref.setValue(Integer.toString(chargingControlMode));
            mChargingControlModePref.setEnabled(!bypassActive);
            refreshUi(chargingControlMode);
        }

        if (mChargingControlStartTimePref != null) {
            mChargingControlStartTimePref.setValue(
                    mChargingControlStartTimePref.getTimeSetting());
            mChargingControlStartTimePref.setEnabled(!bypassActive);
        }

        if (mChargingControlTargetTimePref != null) {
            mChargingControlTargetTimePref.setValue(
                    mChargingControlTargetTimePref.getTimeSetting());
            mChargingControlTargetTimePref.setEnabled(!bypassActive);
        }

        if (mChargingControlLimitPref != null) {
            mChargingControlLimitPref.setValue(bypassActive
                    ? getSavedChargingControlLimit() : mChargingControlLimitPref.getSetting());
            mChargingControlLimitPref.setEnabled(!bypassActive);
        }
    }

    private void refreshUi() {
        final int chargingControlMode = isBypassChargingActive()
                ? getSavedChargingControlMode() : mHealthInterface.getMode();

        refreshUi(chargingControlMode);
    }

    private void refreshUi(final int chargingControlMode) {
        String summary = null;
        boolean isChargingControlStartTimePrefVisible = false;
        boolean isChargingControlTargetTimePrefVisible = false;
        boolean isChargingControlLimitPrefVisible = false;

        final Resources res = getResources();

        switch (chargingControlMode) {
            case MODE_AUTO:
                summary = res.getString(R.string.charging_control_mode_auto_summary);
                break;
            case MODE_MANUAL:
                summary = res.getString(R.string.charging_control_mode_custom_summary);
                isChargingControlStartTimePrefVisible = true;
                isChargingControlTargetTimePrefVisible = true;
                break;
            case MODE_LIMIT:
                summary = res.getString(R.string.charging_control_mode_limit_summary);
                isChargingControlLimitPrefVisible = true;
                break;
            default:
                return;
        }

        mChargingControlModePref.setSummary(summary);

        if (mChargingControlStartTimePref != null) {
            mChargingControlStartTimePref.setVisible(isChargingControlStartTimePrefVisible);
        }

        if (mChargingControlTargetTimePref != null) {
            mChargingControlTargetTimePref.setVisible(isChargingControlTargetTimePrefVisible);
        }

        if (mChargingControlLimitPref != null) {
            mChargingControlLimitPref.setVisible(isChargingControlLimitPrefVisible);
        }

        requireActivity().invalidateOptionsMenu();
    }

    private boolean isBypassChargingActive() {
        return Settings.Global.getInt(requireContext().getContentResolver(),
                BYPASS_CHARGE_ACTIVE, 0) == 1;
    }

    private boolean getSavedChargingControlEnabled() {
        return Settings.Global.getInt(requireContext().getContentResolver(),
                BYPASS_SAVED_ENABLED, 0) == 1;
    }

    private int getSavedChargingControlMode() {
        return Settings.Global.getInt(requireContext().getContentResolver(),
                BYPASS_SAVED_MODE, MODE_LIMIT);
    }

    private int getSavedChargingControlLimit() {
        return Settings.Global.getInt(requireContext().getContentResolver(),
                BYPASS_SAVED_LIMIT, 100);
    }

    private void setBypassCharging(final boolean enabled) {
        IGameSpaceService service = IGameSpaceService.Stub.asInterface(
                ServiceManager.getService("game_space"));
        if (service == null) {
            return;
        }
        try {
            service.setBypassCharge(enabled);
            refreshValues();
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void onSettingsChanged(Uri contentUri) {
        super.onSettingsChanged(contentUri);
        refreshValues();
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        menu.add(0, MENU_RESET, 0, R.string.reset)
                .setIcon(R.drawable.ic_settings_backup_restore)
                .setEnabled(!isBypassChargingActive())
                .setAlphabeticShortcut('r')
                .setShowAsActionFlags(
                        MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == MENU_RESET) {
            resetToDefaults();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(final Preference preference, final Object objValue) {
        if (preference == mBypassChargingPref) {
            setBypassCharging((Boolean) objValue);
            return false;
        }
        if (isBypassChargingActive()) {
            return false;
        }
        if (preference == mChargingControlEnabledPref) {
            mHealthInterface.setEnabled((Boolean) objValue);
        } else if (preference == mChargingControlModePref) {
            final int chargingControlMode = Integer.parseInt((String) objValue);
            mHealthInterface.setMode(chargingControlMode);
            refreshUi(chargingControlMode);
        }
        return true;
    }

    private void resetToDefaults() {
        if (isBypassChargingActive()) {
            return;
        }
        mHealthInterface.reset();

        refreshValues();
    }

    private CharSequence[] concatStringArrays(CharSequence[] array1, CharSequence[] array2) {
        return Stream.concat(Arrays.stream(array1), Arrays.stream(array2)).toArray(size ->
                (CharSequence[]) Array.newInstance(CharSequence.class, size));
    }

    public static final SummaryProvider SUMMARY_PROVIDER = (context, key) -> {
        if (Settings.Global.getInt(context.getContentResolver(), BYPASS_CHARGE_ACTIVE, 0) == 1) {
            return context.getString(R.string.bypass_charging_active);
        }
        if (HealthInterface.isChargingControlSupported(context)) {
            HealthInterface healthInterface = HealthInterface.getInstance(context);
            if (healthInterface.getEnabled()) {
                return context.getString(R.string.enabled);
            }
        }
        return context.getString(R.string.disabled);
    };

    public static final Searchable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider() {

        @Override
        public Set<String> getNonIndexableKeys(Context context) {
            final Set<String> result = new ArraySet<>();

            if (!HealthInterface.isChargingControlSupported(context)) {
                result.add(CHARGING_CONTROL_PREF);
                result.add(CHARGING_CONTROL_ENABLED_PREF);
                result.add(CHARGING_CONTROL_MODE_PREF);
                result.add(CHARGING_CONTROL_START_TIME_PREF);
                result.add(CHARGING_CONTROL_TARGET_TIME_PREF);
                result.add(CHARGING_CONTROL_LIMIT_PREF);
            }
            return result;
        }
    };
}
