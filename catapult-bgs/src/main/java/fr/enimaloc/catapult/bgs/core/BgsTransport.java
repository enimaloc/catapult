package fr.enimaloc.catapult.bgs.core;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/** The byte pipe under a {@link BgsSession}: lets tests inject an in-memory transport. */
public interface BgsTransport extends AutoCloseable {

    InputStream in();

    OutputStream out();

    @Override
    void close() throws IOException;

    /** How long the TCP connect may take. */
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** How long the TLS handshake may wait for the server: a silent peer must not hang the session's opener. */
    Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    /** Plain TLS with the default trust store, SNI = host and hostname verification. */
    static BgsTransport tls(String host, int port) throws IOException {
        return tls(host, port, HANDSHAKE_TIMEOUT);
    }

    /** {@link #tls(String, int)} with another handshake timeout; reads block without limit once it is done. */
    static BgsTransport tls(String host, int port, Duration handshakeTimeout) throws IOException {
        Socket raw = new Socket();
        SSLSocket socket;
        try {
            raw.connect(new InetSocketAddress(host, port), (int) CONNECT_TIMEOUT.toMillis());
            raw.setSoTimeout((int) handshakeTimeout.toMillis());
            socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            // the session loop reads frames as they come: no read timeout once connected
            socket.setSoTimeout(0);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
        return new BgsTransport() {
            @Override
            public InputStream in() {
                try {
                    return socket.getInputStream();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public OutputStream out() {
                try {
                    return socket.getOutputStream();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
    }
}
