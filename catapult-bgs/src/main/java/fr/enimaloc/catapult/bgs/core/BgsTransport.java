package fr.enimaloc.catapult.bgs.core;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** The byte pipe under a {@link BgsSession}: lets tests inject an in-memory transport. */
public interface BgsTransport extends AutoCloseable {

    InputStream in();

    OutputStream out();

    @Override
    void close() throws IOException;

    /** Plain TLS with the default trust store, SNI = host and hostname verification. */
    static BgsTransport tls(String host, int port) throws IOException {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 10_000);
        SSLSocket socket;
        try {
            socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
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
