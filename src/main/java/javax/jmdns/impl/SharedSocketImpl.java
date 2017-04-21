package javax.jmdns.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.impl.constants.DNSConstants;

/**
 * This allows the sharing of a MulticastSocket between multiple JmDNSImpl instances.
 */

final class SharedSocketImpl {
    private static Logger logger = LoggerFactory.getLogger(SharedSocketImpl.class.getSimpleName());

    private static final Map<String, SharedSocketImpl> SOCKET_IMPLS = new HashMap<String, SharedSocketImpl>();

    static SharedSocketImpl get(HostInfo hostInfo) throws IOException {
        SharedSocketImpl sharedSocketImpl;

        synchronized (SOCKET_IMPLS) {
            String groupAddress = (hostInfo.getInetAddress() instanceof Inet6Address)
                    ? DNSConstants.MDNS_GROUP_IPV6
                    : DNSConstants.MDNS_GROUP;

            NetworkInterface networkInterface = hostInfo.getInterface();
            String networkName = networkInterface != null ? networkInterface.getName() : "";
            String sharedName = groupAddress+"%"+networkName;

            sharedSocketImpl = SOCKET_IMPLS.get(sharedName);
            if (sharedSocketImpl == null) {
                sharedSocketImpl = new SharedSocketImpl(sharedName, groupAddress, networkName);
                SOCKET_IMPLS.put(sharedName, sharedSocketImpl);
            }
        }

        sharedSocketImpl.acquire();

        return sharedSocketImpl;
    }

    private final String _name;
    private final String _groupAddress;
    private final String _networkName;
    private int _refCount;

    /**
     * This is the multicast group, we are listening to for multicast DNS messages.
     */
    private volatile InetAddress _group;

    /**
     * This is our multicast socket.
     */
    private volatile MulticastSocket _socket;

    private SharedSocketImpl(String name, String groupAddress, String networkName) {
        this._name = name;
        this._groupAddress = groupAddress;
        this._networkName = networkName;
    }

    private synchronized void acquire() throws IOException {
        _refCount++;

        if (_refCount == 1) {
            openMulticastSocket();
        }
    }

    synchronized void release() {
        _refCount--;

        if (_refCount == 0) {
            closeMulticastSocket();

            synchronized (SOCKET_IMPLS) {
                SOCKET_IMPLS.remove(_name);
            }
        }
    }

    InetAddress getGroup() {
        return _group;
    }

    MulticastSocket getSocket() {
        return _socket;
    }

    /**
     * Send an outgoing multicast DNS message.
     *
     * @param out
     * @exception IOException
     */
    void send(DNSOutgoing out) throws IOException {
        if (!out.isEmpty()) {
            final InetAddress addr;
            final int port;

            if (out.getDestination() != null) {
                addr = out.getDestination().getAddress();
                port = out.getDestination().getPort();
            } else {
                addr = _group;
                port = DNSConstants.MDNS_PORT;
            }

            byte[] message = out.data();
            final DatagramPacket packet = new DatagramPacket(message, message.length, addr, port);

            if (logger.isTraceEnabled()) {
                try {
                    final DNSIncoming msg = new DNSIncoming(packet);
                    if (logger.isTraceEnabled()) {
                        logger.trace("send(" + this._name + ") JmDNS out:" + msg.print(true));
                    }
                } catch (final IOException e) {
                    logger.debug(getClass().toString(), "send(" + this._name + ") - JmDNS can not parse what it sends!!!", e);
                }
            }
            final MulticastSocket ms = _socket;
            if (ms != null && !ms.isClosed()) {
                ms.send(packet);
            }
        }
    }

    private void openMulticastSocket() throws IOException {
        if (_group == null) {
            _group = InetAddress.getByName(_groupAddress);
        }

        if (_socket != null) {
            this.closeMulticastSocket();
        }

        // SocketAddress address = new InetSocketAddress((hostInfo != null ? hostInfo.getInetAddress() : null), DNSConstants.MDNS_PORT);
        // System.out.println("Socket Address: " + address);
        // try {
        // _socket = new MulticastSocket(address);
        // } catch (Exception exception) {
        // logger.warn("openMulticastSocket() Open socket exception Address: " + address + ", ", exception);
        // // The most likely cause is a duplicate address lets open without specifying the address
        // _socket = new MulticastSocket(DNSConstants.MDNS_PORT);
        // }
        _socket = new MulticastSocket(DNSConstants.MDNS_PORT);
        if (_networkName.length() > 0) {
            try {
                NetworkInterface nwInterface = NetworkInterface.getByName(_networkName);
                if (nwInterface != null) {
                    _socket.setNetworkInterface(nwInterface);
                }
            } catch (SocketException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("openMulticastSocket() Set network interface exception: " + e.getMessage());
                }
            }
        }
        _socket.setTimeToLive(255);
        _socket.joinGroup(_group);
    }

    private void closeMulticastSocket() {
        if (logger.isDebugEnabled()) {
            logger.debug("closeMulticastSocket()");
        }
        if (_socket != null) {
            // close socket
            try {
                try {
                    _socket.leaveGroup(_group);
                } catch (SocketException exception) {
                    //
                }
                _socket.close();
            } catch (final Exception exception) {
                logger.warn("closeMulticastSocket() Close socket exception ", exception);
            }
            _socket = null;
        }
    }
}
