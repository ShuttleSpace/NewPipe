package org.schabi.newpipe.settings;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.LanProxyScanner;
import org.schabi.newpipe.util.ProxyConfig;

/**
 * Settings screen for the proxy used to reach YouTube (and other services) from restricted
 * networks, e.g. behind the Great Firewall.
 *
 * <p>The top {@link ListPreference} ({@code proxy_mode}) is the only toggle: choosing
 * {@code 关闭} hides every field below, choosing {@code HTTP 代理} shows only the HTTP-proxy
 * fields, and choosing {@code Sing-box} shows only the built-in sing-box fields. The two are
 * mutually exclusive peer features; switching the mode takes effect on the next app start.</p>
 */
public class ProxySettingsFragment extends BasePreferenceFragment {

    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();
        applyModeVisibility();
        updateSummary();
    }

    private void applyModeVisibility() {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }

        final String mode = ProxyConfig.getMode(requireContext());
        final boolean isHttp = mode.equals(
                requireContext().getString(R.string.proxy_mode_http_value));
        final boolean isSingBox = mode.equals(
                requireContext().getString(R.string.proxy_mode_singbox_value));
        final boolean isLan = mode.equals(
                requireContext().getString(R.string.proxy_mode_lan_value));

        // The upstream endpoint is shared: HTTP mode connects to it directly, sing-box
        // forwards to it. Import helpers only make sense for the HTTP fields.
        setSharedGroupVisible(screen, isHttp || isSingBox);
        setHttpGroupVisible(screen, isHttp);
        setLanGroupVisible(screen, isLan);
        setSingBoxGroupVisible(screen, isSingBox);
    }

    private void setSharedGroupVisible(final PreferenceScreen screen, final boolean visible) {
        setVisibility(screen, requireContext().getString(R.string.proxy_host_key), visible);
        setVisibility(screen, requireContext().getString(R.string.proxy_port_key), visible);
        setVisibility(screen, requireContext().getString(R.string.proxy_user_key), visible);
        setVisibility(screen, requireContext().getString(R.string.proxy_password_key), visible);
    }

    private void setHttpGroupVisible(final PreferenceScreen screen, final boolean visible) {
        setVisibility(screen, requireContext().getString(R.string.proxy_import_key), visible);
        setVisibility(screen, requireContext().getString(R.string.proxy_import_help_key), visible);
    }

    private void setLanGroupVisible(final PreferenceScreen screen, final boolean visible) {
        setVisibility(screen, requireContext().getString(R.string.lan_scan_key), visible);
        setVisibility(screen, requireContext().getString(R.string.lan_host_key), visible);
        setVisibility(screen, requireContext().getString(R.string.lan_port_key), visible);
        setVisibility(screen, requireContext().getString(R.string.lan_test_key), visible);
    }

    private void setSingBoxGroupVisible(final PreferenceScreen screen, final boolean visible) {
        setVisibility(screen, requireContext().getString(R.string.singbox_outbound_type_key),
                visible);
        setVisibility(screen, requireContext().getString(R.string.singbox_local_port_key), visible);
        setVisibility(screen, requireContext().getString(R.string.singbox_config_key), visible);
    }

    private void setVisibility(final PreferenceScreen screen,
                               final String key,
                               final boolean visible) {
        final Preference preference = findPreference(key);
        if (preference != null) {
            preference.setVisible(visible);
        }
    }

    private void updateSummary() {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }

        final ListPreference modePreference =
                findPreference(getString(R.string.proxy_mode_key));
        if (modePreference != null) {
            modePreference.setSummary(getModeSummary(modePreference.getValue()));
        }

        setValueSummary(getString(R.string.proxy_host_key),
                ProxyConfig.getHost(requireContext()), R.string.proxy_host_summary);
        final int port = ProxyConfig.getPort(requireContext());
        setValueSummary(getString(R.string.proxy_port_key),
                port > 0 ? String.valueOf(port) : "",
                R.string.proxy_port_summary);
        setConfiguredSummary(getString(R.string.proxy_user_key),
                !ProxyConfig.getUser(requireContext()).isEmpty(),
                R.string.proxy_user_summary);
        setConfiguredSummary(getString(R.string.proxy_password_key),
                !ProxyConfig.getPassword(requireContext()).isEmpty(),
                R.string.proxy_password_summary);

        setValueSummary(getString(R.string.lan_host_key),
                ProxyConfig.getLanHost(requireContext()), R.string.lan_host_summary);
        setValueSummary(getString(R.string.lan_port_key),
                String.valueOf(ProxyConfig.getLanPort(requireContext())),
                R.string.lan_port_summary);
        final ListPreference outboundPreference =
                findPreference(getString(R.string.singbox_outbound_type_key));
        if (outboundPreference != null) {
            final boolean socks = ProxyConfig.getSingBoxOutboundType(requireContext())
                    .equals(getString(R.string.singbox_outbound_socks_value));
            outboundPreference.setSummary(socks
                    ? R.string.singbox_outbound_socks
                    : R.string.singbox_outbound_http);
        }

        setValueSummary(getString(R.string.singbox_local_port_key),
                String.valueOf(ProxyConfig.getSingBoxLocalPort(requireContext())),
                R.string.singbox_local_port_summary);

        final String customConfig = ProxyConfig.getSingBoxConfig(requireContext()).trim();
        if (!customConfig.isEmpty()) {
            setValueSummary(getString(R.string.singbox_config_key),
                    getString(R.string.singbox_config_custom),
                    R.string.singbox_config_summary);
        } else {
            final String host = ProxyConfig.getHost(requireContext());
            final int upstreamPort = ProxyConfig.getPort(requireContext());
            final String generated = host.isEmpty() || upstreamPort <= 0
                    ? ""
                    : getString(R.string.singbox_config_generated, host + ":" + upstreamPort);
            setValueSummary(getString(R.string.singbox_config_key),
                    generated, R.string.singbox_config_summary);
        }
    }

    private String getModeSummary(@Nullable final String mode) {
        if (mode == null) {
            return getString(R.string.proxy_mode_off);
        }
        switch (mode) {
            case "http":
                return getString(R.string.proxy_mode_http);
            case "lan":
                return getString(R.string.proxy_mode_lan);
            case "singbox":
                return getString(R.string.proxy_mode_singbox);
            default:
                return getString(R.string.proxy_mode_off);
        }
    }

    private void setValueSummary(@NonNull final String key,
                                 @NonNull final String value,
                                 final int emptyResId) {
        final Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummary(value.isEmpty() ? getString(emptyResId) : value);
    }

    private void setConfiguredSummary(@NonNull final String key,
                                      final boolean configured,
                                      final int emptyResId) {
        final Preference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        preference.setSummary(configured
                ? getString(R.string.proxy_field_configured)
                : getString(emptyResId));
    }

    private boolean isProxyKey(@NonNull final String key) {
        return key.equals(getString(R.string.proxy_mode_key))
                || key.equals(getString(R.string.proxy_host_key))
                || key.equals(getString(R.string.proxy_port_key))
                || key.equals(getString(R.string.proxy_user_key))
                || key.equals(getString(R.string.proxy_password_key))
                || key.equals(getString(R.string.proxy_import_key))
                || key.equals(getString(R.string.lan_host_key))
                || key.equals(getString(R.string.lan_port_key))
                || key.equals(getString(R.string.singbox_outbound_type_key))
                || key.equals(getString(R.string.singbox_config_key))
                || key.equals(getString(R.string.singbox_local_port_key));
    }

    @Override
    public void onResume() {
        super.onResume();
        applyModeVisibility();
        updateSummary();
        listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                                                   final String key) {
                if (key.equals(getString(R.string.proxy_import_key))) {
                    handleImport(sharedPreferences, key);
                    return;
                }
                if (key.equals(getString(R.string.proxy_mode_key))) {
                    applyModeVisibility();
                    updateSummary();
                    Toast.makeText(requireContext(), R.string.proxy_restart_hint,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (isProxyKey(key)) {
                    updateSummary();
                    Toast.makeText(requireContext(), R.string.proxy_restart_hint,
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        defaultPreferences.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (getString(R.string.proxy_import_help_key).equals(preference.getKey())) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.proxy_import_help_title)
                    .setMessage(R.string.proxy_import_help_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        if (getString(R.string.lan_scan_key).equals(preference.getKey())) {
            startLanScan();
            return true;
        }
        if (getString(R.string.lan_test_key).equals(preference.getKey())) {
            testLanConnection();
            return true;
        }
        if (getString(R.string.proxy_restart_key).equals(preference.getKey())) {
            restartApp();
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void restartApp() {
        final Context context = requireContext();
        final Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        }
        // Proxy settings are read in App.onCreate, so a full process restart is required.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void startLanScan() {
        final Context context = requireContext();
        final int port = ProxyConfig.getLanPort(context);
        final ProgressDialog progress = new ProgressDialog(context);
        progress.setMessage(getString(R.string.lan_scanning));
        progress.setCancelable(false);
        progress.show();

        LanProxyScanner.scan(port, hosts -> {
            try {
                progress.dismiss();
            } catch (final IllegalArgumentException ignored) {
                // the activity may have been destroyed while the scan was running
            }
            if (!isAdded()) {
                return;
            }
            if (hosts.isEmpty()) {
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.lan_scan_title)
                        .setMessage(R.string.lan_scan_empty)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            final String[] items = hosts.toArray(new String[0]);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.lan_scan_found_title)
                    .setItems(items, (dialog, which) -> applyLanHost(items[which], port))
                    .show();
        });
    }

    private void applyLanHost(@NonNull final String host, final int port) {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(requireContext());
        prefs.edit()
                .putString(getString(R.string.lan_host_key), host)
                .putString(getString(R.string.lan_port_key), String.valueOf(port))
                .apply();
        updateSummary();
        Toast.makeText(requireContext(), R.string.proxy_restart_hint, Toast.LENGTH_LONG).show();
    }

    private void testLanConnection() {
        final Context appContext = requireContext().getApplicationContext();
        final String host = ProxyConfig.getLanHost(appContext);
        final int port = ProxyConfig.getLanPort(appContext);
        if (host.isEmpty()) {
            Toast.makeText(appContext, R.string.lan_test_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(appContext, R.string.lan_testing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean reachable = LanProxyScanner.probe(host, port);
            new Handler(Looper.getMainLooper()).post(() -> {
                final String message = appContext.getString(
                        reachable ? R.string.lan_test_ok : R.string.lan_test_fail, host, port);
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
            });
        }, "lan-proxy-test").start();
    }

    private void handleImport(@NonNull final SharedPreferences sharedPreferences,
                              @NonNull final String key) {
        final String text = sharedPreferences.getString(key, "");
        // The import field is single-use: clear it so the pasted text (which may contain the
        // password in clear) is not persisted.
        final androidx.preference.EditTextPreference importPreference = findPreference(key);
        if (importPreference != null) {
            importPreference.setText("");
        }
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final int count = ProxyConfig.parseConfigText(requireContext(), text);
        updateSummary();
        if (count > 0) {
            Toast.makeText(requireContext(), getString(R.string.proxy_import_success, count),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(requireContext(), R.string.proxy_import_failed, Toast.LENGTH_LONG)
                    .show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (listener != null) {
            defaultPreferences.unregisterOnSharedPreferenceChangeListener(listener);
            listener = null;
        }
    }
}
