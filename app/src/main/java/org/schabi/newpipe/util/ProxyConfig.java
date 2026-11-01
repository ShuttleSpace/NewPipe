package org.schabi.newpipe.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Reads the HTTP proxy settings from {@link SharedPreferences} and exposes the configured
 * {@link Proxy}, an OkHttp {@link Authenticator} for proxy basic-auth, and a pre-built
 * {@code Proxy-Authorization} header value for {@link java.net.HttpURLConnection}-based clients.
 *
 * <p>The proxy is applied to:
 * <ul>
 *     <li>the metadata/extractor {@code DownloaderImpl} (OkHttp) and therefore also Coil
 *     thumbnails,</li>
 *     <li>ExoPlayer's {@code DefaultHttpDataSource} / {@code YoutubeHttpDataSource}
 *     (playback),</li>
 *     <li>the giga download manager (saving streams to disk).</li>
 * </ul>
 * </p>
 */
public final class ProxyConfig {

    private ProxyConfig() {
    }

    public static boolean isEnabled(@NonNull final Context context) {
        // In http/lan mode the "enabled" state is implied by the mode itself.
        return isHttpMode(context) || isLanMode(context);
    }

    /**
     * Active proxy mode: {@code off}, {@code http} or {@code singbox}.
     * These three are mutually exclusive peer features.
     *
     * @param context context used to read the preferences
     * @return the current proxy mode, never {@code null}
     */
    @NonNull
    public static String getMode(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String mode = prefs.getString(context.getString(R.string.proxy_mode_key),
                context.getString(R.string.proxy_mode_off_value));
        return mode == null ? context.getString(R.string.proxy_mode_off_value) : mode;
    }

    public static boolean isHttpMode(@NonNull final Context context) {
        return context.getString(R.string.proxy_mode_http_value).equals(getMode(context));
    }

    public static boolean isSingBoxMode(@NonNull final Context context) {
        return context.getString(R.string.proxy_mode_singbox_value).equals(getMode(context));
    }

    public static boolean isLanMode(@NonNull final Context context) {
        return context.getString(R.string.proxy_mode_lan_value).equals(getMode(context));
    }

    /**
     * Reads the LAN address of the computer running Hiddify.
     *
     * @param context context used to read the preferences
     * @return the configured host, or an empty string
     */
    @NonNull
    public static String getLanHost(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String host = prefs.getString(context.getString(R.string.lan_host_key), "");
        return host == null ? "" : host.trim();
    }

    /**
     * Reads the Hiddify port on the LAN computer.
     *
     * @param context context used to read the preferences
     * @return the configured port, or 12334 when unset or invalid
     */
    public static int getLanPort(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String portStr = prefs.getString(context.getString(R.string.lan_port_key),
                context.getString(R.string.lan_port_default_value));
        if (portStr == null || portStr.trim().isEmpty()) {
            return 12334;
        }
        try {
            final int port = Integer.parseInt(portStr.trim());
            return port > 0 && port <= 65535 ? port : 12334;
        } catch (final NumberFormatException e) {
            return 12334;
        }
    }

    /**
     * Reads the pasted sing-box config.json content.
     *
     * @param context context used to read the preferences
     * @return the config JSON, or an empty string when nothing was pasted
     */
    @NonNull
    public static String getSingBoxConfig(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String cfg = prefs.getString(context.getString(R.string.singbox_config_key), "");
        return cfg == null ? "" : cfg;
    }

    /**
     * Reads the protocol of the remote proxy that sing-box forwards to.
     *
     * @param context context used to read the preferences
     * @return {@code http} or {@code socks}
     */
    @NonNull
    public static String getSingBoxOutboundType(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String type = prefs.getString(
                context.getString(R.string.singbox_outbound_type_key),
                context.getString(R.string.singbox_outbound_default_value));
        final String socks = context.getString(R.string.singbox_outbound_socks_value);
        return socks.equals(type) ? socks
                : context.getString(R.string.singbox_outbound_http_value);
    }

    /**
     * Builds a sing-box config from the shared upstream fields ({@code host}, {@code port},
     * {@code user}, {@code password}) and the local listen port: an HTTP inbound on
     * {@code 127.0.0.1} forwarding to the remote proxy.
     *
     * @param context context used to read the preferences
     * @return the generated config, or an empty string when host/port are missing
     */
    @NonNull
    public static String buildSingBoxConfig(@NonNull final Context context) {
        final String host = getHost(context);
        final int port = getPort(context);
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return "";
        }

        final StringBuilder outbound = new StringBuilder(128);
        outbound.append("{\"type\":\"").append(getSingBoxOutboundType(context))
                .append("\",\"tag\":\"proxy\",\"server\":\"").append(escapeJson(host))
                .append("\",\"server_port\":").append(port);

        final String user = getUser(context);
        final String password = getPassword(context);
        if (!user.isEmpty() && !password.isEmpty()) {
            outbound.append(",\"username\":\"").append(escapeJson(user))
                    .append("\",\"password\":\"").append(escapeJson(password)).append('"');
        }
        outbound.append('}');

        return "{\"inbounds\":[{\"type\":\"http\",\"tag\":\"http-in\",\"listen\":\"127.0.0.1\","
                + "\"listen_port\":" + getSingBoxLocalPort(context) + "}],"
                + "\"outbounds\":[" + outbound + "],"
                + "\"route\":{\"final\":\"proxy\"}}";
    }

    /**
     * Returns the config sing-box should actually run: the hand-written JSON when present,
     * otherwise the one generated from the shared upstream fields.
     *
     * @param context context used to read the preferences
     * @return the effective config, possibly empty when nothing is configured
     */
    @NonNull
    public static String getEffectiveSingBoxConfig(@NonNull final Context context) {
        final String custom = getSingBoxConfig(context).trim();
        return custom.isEmpty() ? buildSingBoxConfig(context) : custom;
    }

    @NonNull
    private static String escapeJson(@NonNull final String value) {
        final StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Reads the local port sing-box should listen on.
     *
     * @param context context used to read the preferences
     * @return the configured port, or 2333 when unset or invalid
     */
    public static int getSingBoxLocalPort(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String portStr = prefs.getString(context.getString(R.string.singbox_local_port_key),
                context.getString(R.string.singbox_local_port_default_value));
        if (portStr == null || portStr.trim().isEmpty()) {
            return 2333;
        }
        try {
            final int port = Integer.parseInt(portStr.trim());
            return port > 0 && port <= 65535 ? port : 2333;
        } catch (final NumberFormatException e) {
            return 2333;
        }
    }

    @NonNull
    public static String getHost(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String host = prefs.getString(context.getString(R.string.proxy_host_key), "");
        return host == null ? "" : host.trim();
    }

    public static int getPort(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String portStr = prefs.getString(context.getString(R.string.proxy_port_key), "");
        if (portStr == null || portStr.trim().isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(portStr.trim());
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    @NonNull
    public static String getUser(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String user = prefs.getString(context.getString(R.string.proxy_user_key), "");
        return user == null ? "" : user.trim();
    }

    @NonNull
    public static String getPassword(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String password = prefs.getString(context.getString(R.string.proxy_password_key), "");
        return password == null ? "" : password.trim();
    }

    /**
     * @param context the context used to read the proxy settings
     * @return a fully configured {@link Proxy} of type HTTP, or {@code null} if the proxy is
     * disabled or misconfigured (host empty or port out of range).
     */
    @Nullable
    public static Proxy getProxy(@NonNull final Context context) {
        final String host;
        final int port;
        if (isHttpMode(context)) {
            host = getHost(context);
            port = getPort(context);
        } else if (isLanMode(context)) {
            host = getLanHost(context);
            port = getLanPort(context);
        } else {
            return null;
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return null;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    /**
     * @param context the context used to read the proxy settings
     * @return a pre-built {@code Proxy-Authorization: Basic <credentials>} header value, or
     * {@code null} if no username/password is configured.
     */
    @Nullable
    public static String getProxyAuthorizationHeader(@NonNull final Context context) {
        if (!isHttpMode(context)) {
            // The LAN/Hiddify endpoint does not use the HTTP-mode credentials.
            return null;
        }
        final String user = getUser(context);
        final String password = getPassword(context);
        if (user.isEmpty() || password.isEmpty()) {
            return null;
        }
        final String credential = user + ":" + password;
        final String encoded = Base64.encodeToString(
                credential.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "Basic " + encoded;
    }

    /**
     * @param context the context used to read the proxy settings
     * @return an OkHttp {@link Authenticator} that replies to proxy auth challenges, or
     * {@code null} if no credentials are configured.
     */
    @Nullable
    public static Authenticator getOkHttpAuthenticator(@NonNull final Context context) {
        final String header = getProxyAuthorizationHeader(context);
        if (header == null) {
            return null;
        }
        return new Authenticator() {
            @Nullable
            @Override
            public Request authenticate(final Route route, @NonNull final Response response) {
                // Only answer proxy (407) challenges, not regular server (401) ones.
                if (response.code() != 407) {
                    return null;
                }
                return response.request().newBuilder()
                        .header("Proxy-Authorization", header)
                        .build();
            }
        };
    }

    /**
     * Parses a free-form, multi-line configuration block and writes the recognised values into the
     * shared preferences. This lets users paste a single block instead of filling each field by
     * hand.
     *
     * <p>Accepted format (one entry per line, {@code key=value}):
     * <pre>
     * host=192.168.1.10
     * port=8080
     * user=alice
     * password=secret
     * </pre>
     * Keys are case-insensitive, tolerate a leading {@code proxy.}/{@code proxy_}/{@code http.}/
     * {@code http_} prefix and spaces around the {@code =}, and accept common aliases
     * (e.g. {@code address}/{@code ip} for host, {@code username} for user,
     * {@code pass}/{@code pwd} for password).
     * </p>
     *
     * <p>When a usable endpoint (non-empty host and a valid port) is found, the proxy is enabled
     * automatically.</p>
     *
     * @param context the context used to read and write the proxy settings
     * @param text the multi-line, {@code key=value} configuration block to parse
     * @return the number of recognised fields that were written (0 if nothing matched).
     */
    public static int parseConfigText(@NonNull final Context context, @NonNull final String text) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final SharedPreferences.Editor editor = prefs.edit();

        String host = null;
        int port = -1;
        String user = null;
        String password = null;
        boolean hostSet = false;
        boolean portSet = false;

        for (final String rawLine : text.split("\\r?\\n")) {
            final String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT)
                    .replaceFirst("^(proxy|http)[._-]", "");
            final String value = line.substring(eq + 1).trim();
            switch (key) {
                case "host":
                case "address":
                case "ip":
                case "server":
                case "hostname":
                    host = value;
                    hostSet = true;
                    break;
                case "port":
                    try {
                        port = Integer.parseInt(value);
                        portSet = true;
                    } catch (final NumberFormatException ignored) {
                        // ignore invalid port values
                    }
                    break;
                case "user":
                case "username":
                case "account":
                case "login":
                    user = value;
                    break;
                case "password":
                case "pass":
                case "pwd":
                case "passwd":
                    password = value;
                    break;
                default:
                    break;
            }
        }

        int count = 0;
        if (hostSet) {
            editor.putString(context.getString(R.string.proxy_host_key), host);
            editor.putString(context.getString(R.string.proxy_mode_key),
                    context.getString(R.string.proxy_mode_http_value));
            count++;
        }
        if (portSet) {
            editor.putString(context.getString(R.string.proxy_port_key), String.valueOf(port));
            count++;
        }
        if (user != null) {
            editor.putString(context.getString(R.string.proxy_user_key), user);
            count++;
        }
        if (password != null) {
            editor.putString(context.getString(R.string.proxy_password_key), password);
            count++;
        }

        // Enable the proxy automatically once we have a usable endpoint.
        if (hostSet && host != null && !host.isEmpty()
                && portSet && port > 0 && port <= 65535) {
            editor.putBoolean(context.getString(R.string.proxy_enabled_key), true);
        }

        editor.apply();
        return count;
    }
}
