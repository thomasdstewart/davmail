package davmail.http;

import org.apache.commons.httpclient.protocol.SSLProtocolSocketFactory;
import org.apache.commons.httpclient.protocol.Protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.MalformedURLException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import davmail.Settings;
import davmail.tray.DavGatewayTray;

/**
 * Manual Socket Factory.
 * Let user choose to accept or reject certificate
 */
public class DavGatewaySSLProtocolSocketFactory extends SSLProtocolSocketFactory {
    /**
     * Register custom Socket Factory to let user accept or reject certificate
     */
    public static void register() {
        String urlString = Settings.getProperty("davmail.url");
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol();
            if ("https".equals(protocol)) {
                int port = url.getPort();
                if (port < 0) {
                    port = 443;
                }
                Protocol.registerProtocol(url.getProtocol(),
                        new Protocol(protocol, new DavGatewaySSLProtocolSocketFactory(), port));
            }
        } catch (MalformedURLException e) {
            DavGatewayTray.error("Exception handling url: " + urlString);
        }
    }

    private SSLContext sslcontext = null;

    private SSLContext createSSLContext() throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        SSLContext context = SSLContext.getInstance("SSL");
        context.init(
                null,
                new TrustManager[]{new DavGatewayX509TrustManager()},
                null);
        return context;
    }

    private SSLContext getSSLContext() throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        if (this.sslcontext == null) {
            this.sslcontext = createSSLContext();
        }
        return this.sslcontext;
    }


    public Socket createSocket(String host, int port, InetAddress clientHost, int clientPort) throws IOException {
        try {
            return getSSLContext().getSocketFactory().createSocket(
                    host,
                    port,
                    clientHost,
                    clientPort
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } catch (KeyManagementException e) {
            throw new IOException(e);
        } catch (KeyStoreException e) {
            throw new IOException(e);
        }
    }


    public Socket createSocket(String host, int port) throws IOException {
        try {
            return getSSLContext().getSocketFactory().createSocket(
                    host,
                    port
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } catch (KeyManagementException e) {
            throw new IOException(e);
        } catch (KeyStoreException e) {
            throw new IOException(e);
        }
    }


    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        try {
            return getSSLContext().getSocketFactory().createSocket(socket, host, port, autoClose
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } catch (KeyManagementException e) {
            throw new IOException(e);
        } catch (KeyStoreException e) {
            throw new IOException(e);
        }
    }
}
