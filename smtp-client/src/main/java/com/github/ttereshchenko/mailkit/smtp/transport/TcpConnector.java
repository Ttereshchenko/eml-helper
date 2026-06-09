package com.github.ttereshchenko.mailkit.smtp.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a hostname to addresses, filters them by {@link IpFamily}, optionally binds to a
 * specific local interface / port, and connects the socket with a timeout.
 *
 * <p>When multiple addresses match (e.g. AUTO mode returning both v4 and v6 for {@code localhost})
 * the connector tries them in order, returning the first one that connects. This matches the
 * "happy-eyeballs" intent of a generic TCP client without the full RFC 8305 dual-stack timing.
 */
public final class TcpConnector {

    @FunctionalInterface
    public interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final AddressResolver DEFAULT_RESOLVER = InetAddress::getAllByName;

    private final AddressResolver resolver;

    public TcpConnector() {
        this(DEFAULT_RESOLVER);
    }

    public TcpConnector(AddressResolver resolver) {
        this.resolver = resolver == null ? DEFAULT_RESOLVER : resolver;
    }

    public Socket connect(String host, int port, Duration timeout, TransportConfig config) throws IOException {
        var candidates = pickCandidates(host, config);
        if (candidates.isEmpty()) {
            throw new IOException("no addresses match " + config.ipFamily() + " for host " + host);
        }
        var timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        IOException last = null;
        for (var candidate : candidates) {
            var socket = new Socket();
            try {
                socket.setSoTimeout(timeoutMillis);
                if (config.localInterface() != null || config.localPort() != null) {
                    var localAddress =
                            config.localInterface() == null ? null : InetAddress.getByName(config.localInterface());
                    var localBind =
                            new InetSocketAddress(localAddress, config.localPort() == null ? 0 : config.localPort());
                    socket.bind(localBind);
                }
                socket.connect(new InetSocketAddress(candidate, port), timeoutMillis);
                return socket;
            } catch (IOException failure) {
                last = failure;
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already failing, drop secondary close failure
                }
            }
        }
        throw last == null ? new IOException("unreachable") : last;
    }

    private List<InetAddress> pickCandidates(String host, TransportConfig config) throws UnknownHostException {
        var all = resolver.resolve(host);
        var matched = new ArrayList<InetAddress>(all.length);
        for (var address : all) {
            if (config.useMxRouting()
                    && (address.isLoopbackAddress()
                            || address.isLinkLocalAddress()
                            || address.isSiteLocalAddress()
                            || address.isAnyLocalAddress())) {
                if (!Boolean.getBoolean("mailkit.test.allow_local_mx")) {
                    continue;
                }
            }
            if (config.ipFamily().matches(address)) {
                matched.add(address);
            }
        }
        return matched;
    }
}
