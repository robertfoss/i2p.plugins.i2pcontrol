package net.i2p.i2pcontrol;
/*
 *  Copyright 2010 hottuna (dev@robertfoss.se)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.i2pcontrol.security.KeyStoreProvider;
import net.i2p.i2pcontrol.security.SecurityManager;
import net.i2p.i2pcontrol.servlets.JSONRPC2Servlet;
import net.i2p.i2pcontrol.servlets.configuration.ConfigurationManager;
import net.i2p.util.Log;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.http.HttpVersion;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.KeyStore;


/**
 * This handles the starting and stopping of an eepsite tunnel and jetty
 * from a single static class so it can be called via clients.config.
 *
 * This makes installation of a new eepsite a turnkey operation -
 * the user is not required to configure a new tunnel in i2ptunnel manually.
 *
 * Usage: I2PControlController -d $PLUGIN [start|stop]
 *
 * @author hottuna
 */
public class I2PControlController {
    private static final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(I2PControlController.class);
    private static String _pluginDir = "";
    private static ConfigurationManager _conf;
    private static SecurityManager _secMan;
    private static Server _server = new Server();


    public static void main(String args[]) {
        if (args.length != 3 || (!"-d".equals(args[0])))
            throw new IllegalArgumentException("Usage: PluginController -d $PLUGINDIR [start|stop]");

        if ("start".equals(args[2])) {
            File pluginDir = new File(args[1]);
            if (!pluginDir.exists())
                throw new IllegalArgumentException("Plugin directory " + pluginDir.getAbsolutePath() + " does not exist");
            _pluginDir = pluginDir.getAbsolutePath();
            ConfigurationManager.setConfDir(pluginDir.getAbsolutePath());
            _conf = ConfigurationManager.getInstance();
            _secMan = SecurityManager.getInstance();
            start(args);
            //stop(); // Delete Me

        } else if ("stop".equals(args[2]))
            stop();
        else
            throw new IllegalArgumentException("Usage: PluginController -d $PLUGINDIR [start|stop]");
    }


    private static void start(String args[]) {
        I2PAppContext.getGlobalContext().logManager().getLog(JSONRPC2Servlet.class).setMinimumPriority(Log.DEBUG);

        try {
            ServerConnector ssl = buildDefaultListenter();
            _server = buildServer(ssl);
            _log.info("I2PControl started");
        } catch (IOException e) {
            _log.error("Unable to add listener " + _conf.getConf("i2pcontrol.listen.address", "127.0.0.1") + ":" + _conf.getConf("i2pcontrol.listen.port", 7560) + " - " + e.getMessage());
        } catch (ClassNotFoundException e) {
            _log.error("Unable to find class net.i2p.i2pcontrol.JSONRPCServlet: " + e.getMessage());
        } catch (InstantiationException e) {
            _log.error("Unable to instantiate class net.i2p.i2pcontrol.JSONRPCServlet: " + e.getMessage());
        } catch (IllegalAccessException e) {
            _log.error("Illegal access: " + e.getMessage());
        } catch (Exception e) {
            _log.error("Unable to start jetty server: ", e);
        }
    }



    /**
     * Builds a new server. Used for changing ports during operation and such.
     * @return Server - A new server built from current configuration.
     * @throws UnknownHostException
     */
    public static ServerConnector buildDefaultListenter() throws UnknownHostException {
        ServerConnector ssl = buildSslListener(_conf.getConf("i2pcontrol.listen.address", "127.0.0.1"),
                                 _conf.getConf("i2pcontrol.listen.port", 7650));
        return ssl;
    }


    /**
     * Builds a new server. Used for changing ports during operation and such.
     * @return Server - A new server built from current configuration.
     * @throws UnknownHostException
     * @throws Exception
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public static Server buildServer(ServerConnector ssl) throws UnknownHostException, Exception, InstantiationException, IllegalAccessException {
        Server server = _server;
        server.addConnector(ssl);

        ServletHandler sh = new ServletHandler();
        sh.addServletWithMapping(net.i2p.i2pcontrol.servlets.JSONRPC2Servlet.class, "/");
        server.setHandler(sh);
        server.start();
        server.join();

        return server;
    }


    /**
     * Creates a SSLListener with all the default options. The listener will use all the default options.
     * @param address - The address the listener will listen to.
     * @param port - The port the listener will listen to.
     * @return - Newly created listener
     * @throws UnknownHostException
     */
    public static ServerConnector buildSslListener(String address, int port) throws UnknownHostException {
        int listeners = 0;
        if (_server != null) {
            listeners = _server.getConnectors().length;
        }

        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(KeyStoreProvider.getKeyStoreLocation());
        sslContextFactory.setKeyStorePassword(KeyStoreProvider.DEFAULT_KEYSTORE_PASSWORD);
        sslContextFactory.setKeyStoreType(KeyStore.getDefaultType());
        sslContextFactory.setProvider(_secMan.getSecurityProvider());

        HttpConfiguration http_config = new HttpConfiguration();
        http_config.addCustomizer(new SecureRequestCustomizer());

        ServerConnector https = new ServerConnector(_server,
            new SslConnectionFactory(sslContextFactory,HttpVersion.HTTP_1_1.asString()),
            new HttpConnectionFactory(http_config));
        https.setHost(address);
        https.setPort(port);
        https.setName("SSL Listener-" + ++listeners);

        return https;
    }


    /**
     * Add a listener to the server
     * If a listener listening to the same port as the provided listener
     * uses already exists within the server, replace the one already used by
     * the server with the provided listener.
     * @param listener
     * @throws Exception
     */
    public static void replaceListener(ServerConnector listener) throws Exception {
        if (_server != null) {
            stopServer();
        }
        _server = buildServer(listener);
    }

    /**
     * Get all listeners of the server.
     * @return
     */
    public static Connector[] getListeners() {
        if (_server != null) {
            return _server.getConnectors();
        }
        return new Connector[0];
    }

    /**
     * Removes all listeners
     */
    public static void clearListeners() {
        if (_server != null) {
            for (Connector listen : getListeners()) {
                _server.removeConnector(listen);
            }
        }
    }

    private static void stopServer()
    {
        try {
            if (_server != null) {
                _server.stop();
                for (Connector listener : _server.getConnectors()) {
                    listener.stop();
                }
                _server.destroy();
                _server = null;
            }
            _log.info("I2PControl stopped");
        } catch (Exception e) {
            _log.error("Stopping server" + e);
        }
    }

    private static void stop() {
        ConfigurationManager.writeConfFile();
        if (_secMan != null) {
            _secMan.stopTimedEvents();
        }

        stopServer();

        // Get and stop all running threads
        ThreadGroup threadgroup = Thread.currentThread().getThreadGroup();
        Thread[] threads = new Thread[threadgroup.activeCount() + 3];
        threadgroup.enumerate(threads, true);
        for (Thread thread : threads) {
            if (thread != null) {//&& thread.isAlive()){
                thread.interrupt();
            }
        }

        for (Thread thread : threads) {
            if (thread != null) {
                System.out.println("Active thread: " + thread.getName());
            }
        }
        threadgroup.interrupt();

        //Thread.currentThread().getThreadGroup().destroy();
    }

    public static String getPluginDir() {
        return _pluginDir;
    }
}
