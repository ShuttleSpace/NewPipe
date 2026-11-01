package org.schabi.newpipe.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.schabi.newpipe.BuildConfig;
import org.schabi.newpipe.R;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SetupOptions;

import java.io.File;

/**
 * Lifecycle wrapper around the built-in sing-box (libbox) Android AAR.
 *
 * <p>When the user selects the {@code sing-box} proxy mode, NewPipe starts a libbox
 * {@link CommandServer} in-process with the pasted config.json. The config is expected
 * to expose a local HTTP/SOCKS inbound (e.g. {@code 127.0.0.1:12334}); NewPipe then routes
 * its traffic through that local endpoint via the standard {@code http(s).proxy*}
 * system properties.</p>
 *
 * <p>The AAR ({@code io.nekohasekai.libbox}) is optional: if it is missing from
 * {@code app/libs} the app degrades gracefully instead of crashing.</p>
 */
public final class SingBoxManager {

    public interface Callback {
        void onStarted(int port);
        void onStopped();
        void onError(String message);
    }

    private static final String TAG = "SingBoxManager";
    private static final int DEFAULT_LOG_MAX_LINES = 3000;

    private static SingBoxManager instance;

    private final Context context;
    private final Callback callback;
    private CommandServer commandServer;
    private boolean setupDone;
    private boolean running;

    private SingBoxManager(final Context context, final Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public static synchronized SingBoxManager getInstance(final Context context,
                                                          final Callback callback) {
        if (instance == null) {
            instance = new SingBoxManager(context, callback);
        }
        return instance;
    }

    public static boolean isAvailable() {
        try {
            Class.forName("io.nekohasekai.libbox.Libbox");
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start(final String configContent) {
        if (!isAvailable()) {
            notifyError(context.getString(R.string.singbox_not_supported));
            return;
        }
        if (running) {
            stop();
        }
        try {
            setupLibbox();
            final SingBoxPlatformInterface platformInterface = new SingBoxPlatformInterface();
            commandServer = new CommandServer(new CommandServerHandlerImpl(), platformInterface);
            commandServer.start();

            final OverrideOptions overrideOptions = new OverrideOptions();
            commandServer.startOrReloadService(configContent, overrideOptions);
            running = true;
            notifyStarted(ProxyConfig.getSingBoxLocalPort(context));
        } catch (final Throwable t) {
            Log.e(TAG, "sing-box start failed", t);
            notifyError(context.getString(R.string.singbox_error, t.getMessage()));
            stop();
        }
    }

    public synchronized void stop() {
        if (commandServer != null) {
            try {
                commandServer.closeService();
            } catch (final Throwable t) {
                Log.w(TAG, "close service: " + t.getMessage());
            }
            try {
                commandServer.close();
            } catch (final Throwable t) {
                Log.w(TAG, "close command server: " + t.getMessage());
            }
            commandServer = null;
        }
        running = false;
        notifyStopped();
    }

    private void setupLibbox() throws Throwable {
        if (setupDone) {
            return;
        }
        final File filesDir = context.getFilesDir();
        final File workingDir = context.getExternalFilesDir("singbox");
        if (workingDir != null) {
            workingDir.mkdirs();
        }
        final File tempDir = context.getCacheDir();
        if (filesDir != null) {
            filesDir.mkdirs();
        }
        if (tempDir != null) {
            tempDir.mkdirs();
        }
        final SetupOptions options = new SetupOptions();
        options.setBasePath(filesDir != null ? filesDir.getPath() : "");
        options.setWorkingPath(workingDir != null ? workingDir.getPath() : "");
        options.setTempPath(tempDir != null ? tempDir.getPath() : "");
        options.setFixAndroidStack(true);
        options.setLogMaxLines(DEFAULT_LOG_MAX_LINES);
        options.setAppVersion(String.valueOf(BuildConfig.VERSION_CODE));
        options.setAppMarketingVersion(BuildConfig.VERSION_NAME);
        options.setCrashReportSource("NewPipe");
        Libbox.setup(options);
        setupDone = true;
    }

    private void notifyStarted(final int port) {
        if (callback != null) {
            callback.onStarted(port);
        }
    }

    private void notifyStopped() {
        if (callback != null) {
            callback.onStopped();
        }
    }

    private void notifyError(final String message) {
        if (callback != null) {
            callback.onError(message);
        }
    }

    private final class CommandServerHandlerImpl implements CommandServerHandler {
        @Override
        public int connectSSHAgent() {
            return -1;
        }

        @Override
        public void serviceStop() {
            stop();
        }

        @Override
        public void serviceReload() {
            // Reload is triggered externally via start() with the new config.
        }

        @Nullable
        @Override
        public io.nekohasekai.libbox.SystemProxyStatus getSystemProxyStatus() {
            return null;
        }

        @Override
        public void setSystemProxyEnabled(final boolean enabled) {
            // NewPipe manages its own proxy routing, ignore system-proxy requests.
        }

        @Override
        public void triggerNativeCrash() {
            // Intentionally ignored.
        }

        @Override
        public void writeDebugMessage(final String message) {
            Log.d(TAG, message);
        }
    }
}
