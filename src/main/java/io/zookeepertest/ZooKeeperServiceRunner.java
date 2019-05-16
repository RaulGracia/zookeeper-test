package io.zookeepertest;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import org.apache.zookeeper.server.NettyServerCnxnFactory;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.ZooKeeperServer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Helps run ZooKeeper Server in process.
 */
@RequiredArgsConstructor
@Slf4j
public class ZooKeeperServiceRunner implements AutoCloseable {

    private static final String LOOPBACK_ADDRESS = "localhost";
    private final AtomicReference<ZooKeeperServer> server = new AtomicReference<>();
    private final AtomicReference<ServerCnxnFactory> serverFactory = new AtomicReference<ServerCnxnFactory>();
    private final int zkPort;
    private final boolean secureZK;
    private final String keyStore;
    private final String keyStorePassword;
    private final String trustStore;
    private final String trustStorePassword;
    private final AtomicReference<File> tmpDir = new AtomicReference<>();

    @Override
    public void close() throws Exception {
        stop();

        File t = this.tmpDir.getAndSet(null);
        if (t != null) {
            log.info("Cleaning up " + t);
            FileUtils.deleteDirectory(t);
        }
    }

    void initialize() throws IOException {
        System.setProperty("zookeeper.4lw.commands.whitelist", "*"); // As of ZooKeeper 3.5 this is needed to not break start()
        if (this.tmpDir.compareAndSet(null, createTempDir("zookeeper", "inproc"))) {
            this.tmpDir.get().deleteOnExit();
        }
        if (secureZK) {
            //-Dzookeeper.serverCnxnFactory=org.apache.zookeeper.server.NettyServerCnxnFactory
            //-Dzookeeper.ssl.keyStore.location=/root/zookeeper/ssl/testKeyStore.jks
            //-Dzookeeper.ssl.keyStore.password=testpass
            //-Dzookeeper.ssl.trustStore.location=/root/zookeeper/ssl/testTrustStore.jks
            //-Dzookeeper.ssl.trustStore.password=testpass
            System.setProperty("zookeeper.serverCnxnFactory", "org.apache.zookeeper.server.NettyServerCnxnFactory");
            System.setProperty("zookeeper.ssl.keyStore.location", keyStore);
            System.setProperty("zookeeper.ssl.keyStore.password", keyStorePassword);
            System.setProperty("zookeeper.ssl.trustStore.location", trustStore);
            System.setProperty("zookeeper.ssl.trustStore.password", trustStorePassword);
        }
    }

    /**
     * Starts the ZooKeeper Service in process.
     *
     * @throws Exception If an exception occurred.
     */
    void start() throws Exception {
        Preconditions.checkState(this.tmpDir.get() != null, "Not Initialized.");
        val s = new ZooKeeperServer(this.tmpDir.get(), this.tmpDir.get(), ZooKeeperServer.DEFAULT_TICK_TIME);
        if (!this.server.compareAndSet(null, s)) {
            s.shutdown();
            throw new IllegalStateException("Already started.");
        }
        this.serverFactory.set(NettyServerCnxnFactory.createFactory());
        val address = LOOPBACK_ADDRESS + ":" + this.zkPort;
        log.info("Starting Zookeeper server at " + address + " ...");
        this.serverFactory.get().configure(new InetSocketAddress(LOOPBACK_ADDRESS, this.zkPort), 1000, secureZK);
        this.serverFactory.get().startup(s);

        if (!waitForServerUp(this.zkPort, this.secureZK, this.trustStore, this.keyStore, this.keyStorePassword, this.trustStorePassword)) {
            throw new IllegalStateException("ZooKeeper server failed to start");
        }
        StringWriter outputStream = new StringWriter();
        s.dumpConf(new PrintWriter(outputStream));
        System.err.println(outputStream.toString());
    }

    void stop() {
        try {
            ServerCnxnFactory sf = this.serverFactory.getAndSet(null);
            if (sf != null) {
                sf.closeAll();
                sf.shutdown();
            }
        } catch (Throwable e) {
            log.warn("Unable to cleanly shutdown ZooKeeper connection factory", e);
        }

        try {
            ZooKeeperServer zs = this.server.getAndSet(null);
            if (zs != null) {
                zs.shutdown();
                ZKDatabase zkDb = zs.getZKDatabase();
                if (zkDb != null) {
                    // make ZK server close its log files
                    zkDb.close();
                }
            }

        } catch (Throwable e) {
            log.warn("Unable to cleanly shutdown ZooKeeper server", e);
        }

        if (secureZK) {
            System.clearProperty("zookeeper.serverCnxnFactory");
            System.clearProperty("zookeeper.ssl.keyStore.location");
            System.clearProperty("zookeeper.ssl.keyStore.password");
            System.clearProperty("zookeeper.ssl.trustStore.location");
            System.clearProperty("zookeeper.ssl.trustStore.password");
        }
    }

    /**
     * Blocks the current thread and awaits ZooKeeper to start running locally on the given port.
     *
     * @param zkPort The ZooKeeper Port.
     * @param secureZk Flag to notify whether the ZK is secure.
     * @param trustStore Location of the trust store.
     * @param keyStore Location of the key store.
     * @param keyStorePassword Password path for key store.
     *                             Empty string if `secureZk` is false or a password does not exist.
     * @param trustStorePassword Password path for trust store.
     *                               Empty string if `secureZk` is false or a password does not exist.
     * @return True if ZooKeeper started within a specified timeout, false otherwise.
     */
    public static boolean waitForServerUp(int zkPort, boolean secureZk, String trustStore, String keyStore,
                                          String keyStorePassword, String trustStorePassword) {
        int retries = 30;
        val address = LOOPBACK_ADDRESS + ":" + zkPort;
        if (secureZk) {
            return waitForSSLServerUp(address, retries, trustStore, keyStore, keyStorePassword, trustStorePassword);
        } else {
            return waitForServerUp(zkPort);
        }
    }

    public static boolean waitForServerUp(int zkPort) {
        return waitForServerUp(zkPort, false, "", "", "", "");
    }

    private static boolean waitForSSLServerUp(String address, int retries, String trustStore, String keyStore,
                                              String keyStorePasswd, String trustStorePassword) {
        String[] split = address.split(":");
        String host = split[0];
        int port = Integer.parseInt(split[1]);

        while (true) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                TrustManagerFactory trustManager = getTrustManager(trustStore, trustStorePassword);
                KeyManagerFactory keyFactory = getKeyManager(keyStore, keyStorePasswd);
                context.init(keyFactory.getKeyManagers(), trustManager.getTrustManagers(), null);

                try (Socket sock = context.getSocketFactory().createSocket(new Socket(host, port), host, port, true);
                     OutputStream outstream = sock.getOutputStream()) {
                    outstream.write("stat".getBytes());
                    outstream.flush();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    String line = reader.readLine();
                    if (line != null && line.startsWith("Zookeeper version:")) {
                        log.info("Server UP");
                        return true;
                    }
                }
            } catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException
                    | KeyManagementException | UnrecoverableKeyException e) {
                // ignore as this is expected
                log.warn("server  {} not up.", address,  e);
            }

            if (retries > 30) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                log.error("Exception waiting on thread", e);
            }
            retries++;
        }
        return false;
    }

    private static TrustManagerFactory getTrustManager(String trustStore, String trustStorePassword)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException {
        try (FileInputStream myKeys = new FileInputStream(trustStore)) {

            KeyStore myTrustStore = KeyStore.getInstance("JKS");
            myTrustStore.load(myKeys, trustStorePassword.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(myTrustStore);
            return tmf;
        }
    }

    private static KeyManagerFactory getKeyManager(String keyStore, String keyStorePassword)
            throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException {
        KeyManagerFactory kmf = null;

        try (FileInputStream myKeys = new FileInputStream(keyStore)) {
            KeyStore myKeyStore = KeyStore.getInstance("JKS");
            myKeyStore.load(myKeys, keyStorePassword.toCharArray());
            kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(myKeyStore, keyStorePassword.toCharArray());
            return kmf;
        }
    }

    private File createTempDir(String prefix, String suffix) throws IOException {
        File tmpDir = File.createTempFile(prefix, suffix, null);
        if (!tmpDir.delete()) {
            throw new IOException("Couldn't delete directory " + tmpDir);
        }
        if (!tmpDir.mkdir()) {
            throw new IOException("Couldn't create directory " + tmpDir);
        }
        return tmpDir;
    }

    /**
     * Main method that can be used to start ZooKeeper out-of-process using BookKeeperServiceRunner.
     * This is used when invoking this class via ProcessStarter.
     *
     * @param args Args.
     * @throws Exception If an error occurred.
     */
    public static void main(String[] args) throws Exception {
        int zkPort;
        boolean secureZK;
        String zkKeyStore;
        String zkKeyStorePasswd;
        String zkTrustStore;
        String zkTrustStorePasswd;
        System.out.println("Expected parameters secureZK [true|false] zkPort [int] zkKeyStore [path to keystore]" +
                " zkKeyStorePasswd [keystore password] zkTrustStore [path to truststore] zkTrustStorePasswd [truststore password]");
        try {
            secureZK = Boolean.parseBoolean(args[0]);
            zkPort = Integer.parseInt(args[1]);
            zkKeyStore = args[2];
            zkKeyStorePasswd = args[3];
            zkTrustStore = args[4];
            zkTrustStorePasswd = args[5];
        } catch (Exception ex) {
            System.out.println("Invalid or missing arguments");
            System.exit(-1);
            return;
        }
        System.out.println("Parameters for the ZooKeeper server:");
        System.out.println("secureZK: " + secureZK);
        System.out.println("zkPort: " + zkPort);
        System.out.println("zkKeyStore: " + zkKeyStore);
        System.out.println("zkKeyStorePasswd: " + zkKeyStorePasswd);
        System.out.println("zkTrustStore: " + zkTrustStore);
        System.out.println("zkTrustStorePasswd: " + zkTrustStorePasswd);
        ZooKeeperServiceRunner runner = new ZooKeeperServiceRunner(zkPort, secureZK, zkKeyStore, zkKeyStorePasswd,
                zkTrustStore, zkTrustStorePasswd);
        runner.initialize();
        runner.start();
        Thread.sleep(Long.MAX_VALUE);
    }
}
