/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dempsy.cluster.zookeeper;

import static net.dempsy.utils.test.ConditionPoll.poll;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ServerConfig;
import org.apache.zookeeper.server.ZooKeeperServerMain;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;

import net.dempsy.util.SystemPropertyManager;
import net.dempsy.utils.test.ConditionPoll.Condition;

@Ignore
public class ZookeeperTestServer implements AutoCloseable {
    private final static Logger LOGGER = LoggerFactory.getLogger(ZookeeperTestServer.class);
    private File zkDir = null;
    private Properties zkConfig = null;
    private TestZookeeperServerIntern zkServer = null;

    private final SystemPropertyManager props = new SystemPropertyManager()
        .set("zookeeper.nio.numWorkerThreads", "0")
        .set("zookeeper.commitProcessor.numWorkerThreads", "0")
        .set("zookeeper.nio.numSelectorThreads", "1")

    ;

    public int port;

    public ZookeeperTestServer() throws IOException {
        port = findNextPort();
        start();
    }

    public ZookeeperTestServer(final Properties zkConfig) throws IOException {
        port = getPort(zkConfig);
        start(zkConfig);
    }

    public ZookeeperTestServer(final int port) throws IOException {
        this.port = port;
        start();
    }

    public String connectString() {
        for(boolean done = false; !done;) {
            if(zkServer != null) {
                if(!zkServer.serverSillRunning.get())
                    return null;
                try {
                    zkServer.waitForStart();
                } catch(NoSuchFieldException | IllegalArgumentException | IllegalAccessException e) {
                    LOGGER.error("FAILED", e);
                    return null;
                }
                done = true;
            }
        }
        return "127.0.0.1:" + port;
    }

    public static int getPort(final Properties zkConfig) {
        final String cps = zkConfig.getProperty("clientPort");
        if(cps == null)
            throw new IllegalArgumentException("Cannot start the zookeeper test server with properties file that doesn't contain the \"clientPort\"");
        return Integer.parseInt(cps);
    }

    public static String connectString(final Properties zkConfig) {
        return "127.0.0.1:" + getPort(zkConfig);
    }

    private static int findNextPort() throws IOException {
        // find an unused ehpemeral port
        final InetSocketAddress serverSocketAddress = new InetSocketAddress("0.0.0.0", 0);
        final ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true); // this allows the server port to be bound to even if it's in TIME_WAIT
        serverSocket.bind(serverSocketAddress);
        final int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }

    /**
     * cause a problem with the server running lets sever the connection according to the zookeeper faq we can force a session expired to occur by closing the
     * session from another client. see:
     * http://wiki.apache.org/hadoop/ZooKeeper/FAQ#A4
     */
    private static class KWatcher implements Watcher {
        AtomicReference<ZooKeeper> connection = new AtomicReference<ZooKeeper>(null);
        AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void process(final WatchedEvent event) {
            final ZooKeeper tcon = connection.get();
            if(tcon != null && event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                try {
                    tcon.close();
                    closed.set(true);
                } catch(final InterruptedException ie) {}
            }
        }
    }

    public void forceSessionExpiration(final ZookeeperSession session) throws InterruptedException, IOException {
        forceSessionExpiration(session.zkref.get(), port);
    }

    public static void forceSessionExpiration(final ZooKeeper origZk, final int port) throws InterruptedException, IOException {
        final Condition<ZooKeeper> condition = o -> {
            try {
                return (o.getState() == ZooKeeper.States.CONNECTED) && o.exists("/", true) != null;
            } catch(final Exception ke) {
                return false;
            }
        };

        assertTrue(poll(5000, origZk, condition));

        boolean done = false;
        while(!done) {
            final long sessionid = origZk.getSessionId();
            final byte[] pw = origZk.getSessionPasswd();
            final KWatcher kwatcher = new KWatcher();
            final ZooKeeper killer = new ZooKeeper("127.0.0.1:" + port, 5000, kwatcher, sessionid, pw);
            kwatcher.connection.set(killer);

            // wait until we get a close
            final boolean calledBack = poll(5000, kwatcher, o -> o.closed.get());

            if(!calledBack)
                killer.close();

            final AtomicBoolean isExpired = new AtomicBoolean(false);
            final ZooKeeper check = new ZooKeeper("127.0.0.1:" + port, 5000, event -> {
                if(event.getState() == Watcher.Event.KeeperState.Expired)
                    isExpired.set(true);
            }, sessionid, pw);

            done = poll(5000, isExpired, o -> o.get());

            check.close();
        }
    }

    static class TestZookeeperServerIntern extends ZooKeeperServerMain {
        final AtomicBoolean serverSillRunning = new AtomicBoolean(true);
        final ZookeeperTestServer server;

        TestZookeeperServerIntern(final ZookeeperTestServer server) {
            this.server = server;
        }

        @Override
        public void shutdown() {
            LOGGER.debug("Stopping internal ZooKeeper server.");
            super.shutdown();
        }

        public boolean waitForStart() throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
            while(serverSillRunning.get()) {
                final ServerCnxnFactory cnxnFactory = getCnxnFactoryLocal();
                if(cnxnFactory != null) {
                    try {
                        if(cnxnFactory.getLocalPort() == server.port)
                            return true;
                    } catch(final NullPointerException npe) {
                        // This is a total hack. The cnxnFactory will throw a NPE if it's not
                        // running yet. Retards!
                        try {
                            Thread.sleep(500);
                        } catch(final InterruptedException e) {}
                    }
                }
                try {
                    Thread.sleep(1);
                } catch(final InterruptedException ie) {}
            }
            return false;
        }

        private ServerCnxnFactory getCnxnFactoryLocal() throws NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
            final Field f = ZooKeeperServerMain.class.getDeclaredField("cnxnFactory");
            f.setAccessible(true);
            return (ServerCnxnFactory)f.get(this);
        }
    }

    public void start() throws IOException {
        start(true);
    }

    public void start(final boolean newDataDir) throws IOException {
        // if this is a restart we want to use the same directory
        if(zkDir == null || newDataDir)
            zkDir = genZookeeperDataDir();
        zkConfig = genZookeeperConfig(zkDir, port);
        zkServer = startZookeeper(zkConfig, this);
    }

    public void start(final Properties zkConfig) throws IOException {
        startZookeeper(zkConfig, this);
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        shutdown(true);
    }

    public void shutdown(final boolean deleteDataDir) {
        props.close();
        if(zkServer != null) {
            try {
                zkServer.shutdown();
            } catch(final Throwable th) {
                LOGGER.error("Failed to shutdown the internal Zookeeper server:", th);
            }
        }

        if(zkDir != null && deleteDataDir)
            deleteRecursivly(zkDir);
    }

    private static File genZookeeperDataDir() {
        File zkDir = null;
        try {
            zkDir = File.createTempFile("zoo", "data");
            if(!zkDir.delete())
                throw new IOException("Can't rm zkDir " + zkDir.getCanonicalPath());
            if(!zkDir.mkdir())
                throw new IOException("Can't mkdir zkDir " + zkDir.getCanonicalPath());
        } catch(final IOException e) {
            fail("Can't make zookeeper data dir");
        }
        return zkDir;
    }

    public static Properties genZookeeperConfig() throws IOException {
        return genZookeeperConfig(genZookeeperDataDir());
    }

    public static Properties genZookeeperConfig(final File zkDir) throws IOException {
        return genZookeeperConfig(zkDir, findNextPort());
    }

    private static Properties genZookeeperConfig(final File zkDir, int port) throws IOException {
        final Properties props = new Properties();
        props.setProperty("timeTick", "2000");
        props.setProperty("initLimit", "10");
        props.setProperty("syncLimit", "5");
        props.setProperty("zookeeper.nio.numWorkerThreads", "3");
        try {
            props.setProperty("dataDir", zkDir.getCanonicalPath());
        } catch(final IOException e) {
            fail("Can't create zkConfig, zkDir has no path");
        }

        if(port <= 0)
            port = findNextPort();
        props.setProperty("clientPort", String.valueOf(port));
        return props;
    }

    private static final AtomicLong serverCount = new AtomicLong(0);

    private static TestZookeeperServerIntern startZookeeper(final Properties zkConfig, final ZookeeperTestServer tserver) {
        LOGGER.debug("Starting the test zookeeper server on port " + zkConfig.get("clientPort"));

        final TestZookeeperServerIntern server = new TestZookeeperServerIntern(tserver);
        try {
            final QuorumPeerConfig qpConfig = new QuorumPeerConfig();
            qpConfig.parseProperties(zkConfig);

            final Thread t = new Thread(new Runnable() {

                QuorumPeerConfig lqpConfig = qpConfig;

                @Override
                public void run() {
                    ServerConfig sConfig = new ServerConfig();
                    sConfig.readFrom(qpConfig);

                    try {
                        int failedCount = 0;
                        for(boolean done = false; !done;) {
                            done = true;
                            try {
                                server.runFromConfig(sConfig);
                            } catch(final BindException be) {
                                // assume this is because the port was in use, let's try again.
                                failedCount++;
                                if(failedCount > 10) {
                                    LOGGER.error("Apparent failure to bind. Giving up.", be);
                                    done = true;
                                } else {
                                    LOGGER.error("Apparent failure to bind. Will try again.", be);
                                    try {
                                        tserver.port = findNextPort();
                                        zkConfig.setProperty("clientPort", String.valueOf(tserver.port));
                                        lqpConfig = new QuorumPeerConfig();
                                        lqpConfig.parseProperties(zkConfig);
                                        sConfig = new ServerConfig();
                                        sConfig.readFrom(qpConfig);
                                        done = false;
                                    } catch(final Exception ioe) {
                                        LOGGER.error("Now I can't even find the port. Giving up", ioe);
                                        fail("can't start zookeeper");
                                    }
                                }
                            } catch(final IOException ioe) {
                                LOGGER.error(MarkerFactory.getMarker("FATAL"), "", ioe);
                                fail("can't start zookeeper");
                            } catch(final Exception e) {
                                LOGGER.error(MarkerFactory.getMarker("FATAL"), "Zookeeper test server startup threw an unexpected exception", e);
                                fail("Zookeeper test server startup threw an unexpected exception");
                            }
                        }
                    } finally {
                        server.serverSillRunning.set(false);
                    }
                }
            }, "ZookeeperTestServer-" + serverCount.getAndIncrement());
            t.start();
            Thread.sleep(2000); // give the server time to start
        } catch(final Exception e) {
            LOGGER.error("Can't start zookeeper", e);
            fail("Can't start zookeeper");
        }
        return server;
    }

    private static void deleteRecursivly(final File path) {
        if(path.isDirectory())
            for(final File f: path.listFiles())
            deleteRecursivly(f);

        LOGGER.debug("Deleting zookeeper data directory:" + path);
        path.delete();
    }

}
