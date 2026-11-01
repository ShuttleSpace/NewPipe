package org.schabi.newpipe.util;


import androidx.annotation.Nullable;

import io.nekohasekai.libbox.AutoRedirectHandler;
import io.nekohasekai.libbox.AutoRedirectSession;
import io.nekohasekai.libbox.BridgeOptions;
import io.nekohasekai.libbox.BridgeSession;
import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.PlatformUser;
import io.nekohasekai.libbox.ShellSession;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;


/**
 * Minimal {@link PlatformInterface} implementation for the built-in sing-box (libbox)
 * use-case: NewPipe only needs an HTTP/SOCKS outbound proxy, so no TUN, no VPN service,
 * no root bridge and no shell are required.
 *
 * <p>Methods that libbox will not invoke for a proxy-only config return safe defaults /
 * throw {@link IllegalStateException} so a misconfiguration surfaces loudly instead of
 * silently producing broken behaviour.</p>
 */
public final class SingBoxPlatformInterface implements PlatformInterface {

    private static final String TAG = "SingBoxPlatformInterface";

    @Nullable
    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public void autoDetectInterfaceControl(final int fd) {
        // no-op: we do not open a TUN interface
    }

    @Override
    public int openTun(final TunOptions options) {
        throw new IllegalStateException("android: tun inbound is not supported by NewPipe");
    }

    @Override
    public ConnectionOwner findConnectionOwner(final int ipProtocol,
                                                final String sourceAddress,
                                                final int sourcePort,
                                                final String destinationAddress,
                                                final int destinationPort) throws Exception {
        return Libbox.findConnectionOwner(
                ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort);
    }

    @Override
    public void startDefaultInterfaceMonitor(final InterfaceUpdateListener listener) {
        // no-op: no default-interface monitor in proxy-only mode
    }

    @Override
    public void closeDefaultInterfaceMonitor(final InterfaceUpdateListener listener) {
        // no-op
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        return new EmptyNetworkInterfaceIterator();
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Nullable
    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void clearDNSCache() {
        // no-op
    }

    @Override
    public void sendNotification(final io.nekohasekai.libbox.Notification notification) {
        // no-op: NewPipe does not surface libbox notifications
    }

    @Override
    public void cancelNotification(final String identifier, final int typeID) {
        // no-op
    }

    @Override
    public void startNeighborMonitor(final NeighborUpdateListener listener) {
        // no-op
    }

    @Override
    public void closeNeighborMonitor(final NeighborUpdateListener listener) {
        // no-op
    }

    @Override
    public void registerMyInterface(final String name) {
        // no-op
    }

    @Override
    public boolean usePlatformShell() {
        return false;
    }

    @Override
    public void checkPlatformShell() {
        // no shell available in NewPipe
    }

    @Override
    public ShellSession openShellSession(final PlatformUser user, final String command,
                                         final StringIterator environ, final String term,
                                         final int rows, final int cols) {
        throw new IllegalStateException("android: shell is not supported by NewPipe");
    }

    @Override
    public PlatformUser lookupUser(final String username) {
        throw new IllegalStateException("android: lookupUser is not supported by NewPipe");
    }

    @Override
    public String lookupSFTPServer() {
        throw new IllegalStateException("android: SFTP is not supported by NewPipe");
    }

    @Override
    public String readSystemSSHHostKey() {
        throw new IllegalStateException("android: SSH host key is not supported by NewPipe");
    }

    @Override
    public String tailscaleHostname() {
        return "";
    }

    @Override
    public boolean usePlatformBridge() {
        return false;
    }

    @Override
    public BridgeSession createBridge(final BridgeOptions options) {
        throw new IllegalStateException("android: bridge is not supported by NewPipe");
    }

    @Override
    public boolean usePlatformAutoRedirect() {
        return false;
    }

    @Override
    public AutoRedirectSession createAutoRedirect(final byte[] options,
                                                  final AutoRedirectHandler handler) {
        throw new IllegalStateException("android: auto-redirect is not supported by NewPipe");
    }

    private static final class EmptyNetworkInterfaceIterator implements NetworkInterfaceIterator {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public io.nekohasekai.libbox.NetworkInterface next() {
            return null;
        }
    }
}
