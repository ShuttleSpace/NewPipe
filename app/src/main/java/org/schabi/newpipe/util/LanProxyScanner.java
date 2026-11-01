package org.schabi.newpipe.util;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Discovers Hiddify (or any HTTP/mixed proxy) instances on the local /24 subnet by probing a
 * TCP port, and checks whether a given endpoint is reachable.
 */
public final class LanProxyScanner {

    private static final int CONNECT_TIMEOUT_MS = 400;
    private static final int THREADS = 32;
    private static final int LAST_OCTET_MAX = 254;

    public interface Callback {
        /**
         * Called on the main thread when the scan finishes.
         *
         * @param hosts reachable hosts on the local subnet, sorted by address
         */
        void onFinished(@NonNull List<String> hosts);
    }

    private LanProxyScanner() {
    }

    /**
     * Scans the /24 subnet of the current IPv4 address for hosts accepting TCP connections on
     * the given port. The callback is always invoked on the main thread.
     *
     * @param port the proxy port to probe
     * @param callback receives the reachable hosts
     */
    public static void scan(final int port, @NonNull final Callback callback) {
        new Thread(() -> {
            final List<String> hosts = new ArrayList<>();
            final InetAddress local = findLocalIpv4();
            if (local != null) {
                final byte[] ip = local.getAddress();
                final String prefix = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "."
                        + (ip[2] & 0xFF) + ".";
                final String self = local.getHostAddress();
                final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
                final CountDownLatch latch = new CountDownLatch(LAST_OCTET_MAX);
                for (int i = 1; i <= LAST_OCTET_MAX; i++) {
                    final String host = prefix + i;
                    pool.execute(() -> {
                        try {
                            if (!host.equals(self) && canConnect(host, port)) {
                                synchronized (hosts) {
                                    hosts.add(host);
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                pool.shutdown();
                try {
                    latch.await(20, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            synchronized (hosts) {
                Collections.sort(hosts, new Comparator<String>() {
                    @Override
                    public int compare(final String first, final String second) {
                        return Integer.compare(lastOctet(first), lastOctet(second));
                    }
                });
            }
            final List<String> result = new ArrayList<>(hosts);
            new Handler(Looper.getMainLooper()).post(() -> callback.onFinished(result));
        }, "lan-proxy-scan").start();
    }

    /**
     * Checks whether a TCP connection to the given endpoint can be established.
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @return {@code true} if the port is reachable
     */
    public static boolean probe(@NonNull final String host, final int port) {
        return canConnect(host, port);
    }

    private static boolean canConnect(@NonNull final String host, final int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    private static int lastOctet(@NonNull final String host) {
        final int dot = host.lastIndexOf('.');
        try {
            return Integer.parseInt(host.substring(dot + 1));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static InetAddress findLocalIpv4() {
        try {
            final Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                final NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (final InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                    final InetAddress inetAddress = address.getAddress();
                    if (inetAddress instanceof Inet4Address && inetAddress.isSiteLocalAddress()) {
                        return inetAddress;
                    }
                }
            }
        } catch (final Exception ignored) {
            // fall through: no local IPv4 address found
        }
        return null;
    }
}
