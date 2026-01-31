package com.ras.data.connection

import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Factory interface for creating DatagramSocket instances.
 *
 * This abstraction allows:
 * - Dependency injection for testability (mock sockets in tests)
 * - Platform-specific implementations (e.g., VPN-aware socket creation)
 * - Centralized socket configuration
 *
 * Usage:
 * - Production: Use DefaultDatagramSocketFactory
 * - Tests: Inject a mock factory that returns mock sockets
 */
interface DatagramSocketFactory {
    /**
     * Create an unbound DatagramSocket.
     *
     * The caller is responsible for connecting or binding as needed.
     */
    fun create(): DatagramSocket

    /**
     * Create a DatagramSocket bound to a specific local address.
     *
     * @param localAddress The local address to bind to (IP:port)
     */
    fun createBound(localAddress: InetSocketAddress): DatagramSocket

    /**
     * Create a DatagramSocket connected to a remote address.
     *
     * Connected UDP sockets help with VPN routing on Android by
     * associating the socket with a specific destination before sending.
     *
     * @param remoteAddress The remote address to connect to
     */
    fun createConnected(remoteAddress: InetSocketAddress): DatagramSocket
}

/**
 * Default implementation using standard Java DatagramSocket.
 *
 * This is the production implementation used for real network connections.
 */
class DefaultDatagramSocketFactory : DatagramSocketFactory {

    override fun create(): DatagramSocket {
        return DatagramSocket()
    }

    override fun createBound(localAddress: InetSocketAddress): DatagramSocket {
        return DatagramSocket(localAddress)
    }

    override fun createConnected(remoteAddress: InetSocketAddress): DatagramSocket {
        val socket = DatagramSocket()
        socket.connect(remoteAddress)
        return socket
    }
}
