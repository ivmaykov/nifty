/*
 * Copyright (C) 2012-2013 Facebook, Inc.
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
package com.facebook.nifty.server;

import com.facebook.nifty.client.FramedClientConnector;
import com.facebook.nifty.client.NettyClientConfig;
import com.facebook.nifty.client.NiftyClient;
import com.facebook.nifty.client.TNiftyClientChannelTransport;
import com.facebook.nifty.core.NettyServerConfig;
import com.facebook.nifty.core.NettyServerTransport;
import com.facebook.nifty.core.RequestContext;
import com.facebook.nifty.core.RequestContexts;
import com.facebook.nifty.core.ThriftServerDefBuilder;
import com.facebook.nifty.ssl.OpenSslServerConfiguration;
import com.facebook.nifty.ssl.SslClientConfiguration;
import com.facebook.nifty.ssl.SslConfigFileWatcher;
import com.facebook.nifty.ssl.SslServerConfiguration;
import com.facebook.nifty.ssl.TicketSeedFileParser;
import com.facebook.nifty.ssl.TransportAttachObserver;
import com.facebook.nifty.test.LogEntry;
import com.facebook.nifty.test.ResultCode;
import com.facebook.nifty.test.scribe;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import io.airlift.log.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.tomcat.jni.SessionTicketKey;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.handler.ssl.SslHandler;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.net.ssl.SSLSession;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TestNiftyOpenSslServer
{
    private static final Logger log = Logger.get(TestNiftyOpenSslServer.class);
    private NettyServerTransport server;
    private int port;
    private File ticketSeedFile = null;
    private File privateKeyFile = null;
    private File certificateFile = null;

    @BeforeMethod(alwaysRun = true)
    public void setup()
    {
        server = null;
    }

    @AfterMethod(alwaysRun = true)
    public void teardown()
            throws InterruptedException
    {
        if (server != null) {
            server.stop();
        }
        deleteFilesIfExistIgnoreErrors(ticketSeedFile, privateKeyFile, certificateFile);
        ticketSeedFile = privateKeyFile = certificateFile = null;
    }

    private void startServer() {
        startServer(false);
    }

    private void startServer(boolean allowPlaintext)
    {
        try {
            List<SessionTicketKey> ticketKeysList = new TicketSeedFileParser().parse(getTicketSeedFile());
            SessionTicketKey[] ticketKeys = ticketKeysList.toArray(new SessionTicketKey[ticketKeysList.size()]);
            SslConfigFileWatcher configUpdater = new SslConfigFileWatcher(
                getTicketSeedFile(),
                getPrivateKeyFile(),
                getCertificateFile(),
                0,
                100,
                TimeUnit.MILLISECONDS,
                null);
            SslServerConfiguration config = createSSLServerConfiguration(allowPlaintext, ticketKeys);
            startServer(getThriftServerDefBuilder(config, configUpdater));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void startServer(final ThriftServerDefBuilder thriftServerDefBuilder)
    {
        server = new NettyServerTransport(thriftServerDefBuilder.build(),
                                          NettyServerConfig.newBuilder().build(),
                                          new DefaultChannelGroup());
        server.start();
        port = ((InetSocketAddress)server.getServerChannel().getLocalAddress()).getPort();
    }

    SslServerConfiguration createSSLServerConfiguration(boolean allowPlaintext,
                                                        SessionTicketKey[] ticketKeys) throws IOException {
        return OpenSslServerConfiguration.newBuilder()
                .certFile(getCertificateFile())
                .keyFile(getPrivateKeyFile())
                .allowPlaintext(allowPlaintext)
                .ticketKeys(ticketKeys)
                .build();
    }

    private ThriftServerDefBuilder getThriftServerDefBuilder(
            SslServerConfiguration sslServerConfiguration,
            TransportAttachObserver configUpdater)
    {
        return new ThriftServerDefBuilder()
                .listen(0)
                .withSSLConfiguration(sslServerConfiguration)
                .withTransportAttachObserver(configUpdater)
                .withProcessor(new scribe.Processor<>(new scribe.Iface() {
                    @Override
                    public ResultCode Log(List<LogEntry> messages)
                            throws TException {
                        RequestContext context = RequestContexts.getCurrentContext();

                        for (LogEntry message : messages) {
                            log.info("[Client: %s] %s: %s",
                                    context.getConnectionContext().getRemoteAddress(),
                                    message.getCategory(),
                                    message.getMessage());
                        }
                        return ResultCode.OK;
                    }
                }));
    }

    private SslClientConfiguration getClientSSLConfiguration() throws IOException {
        return getClientSSLConfiguration(null);
    }

    private SslClientConfiguration getClientSSLConfiguration(File certFile) throws IOException {
        return new SslClientConfiguration.Builder()
                .caFile(certFile == null ? getCertificateFile() : certFile)
                .sessionCacheSize(10000)
                .sessionTimeoutSeconds(10000)
                .build();
    }

    private scribe.Client makeNiftyClient(SslClientConfiguration clientSSLConfiguration)
            throws TTransportException, InterruptedException
    {
        NettyClientConfig config =
                NettyClientConfig.newBuilder()
                        .setSSLClientConfiguration(clientSSLConfiguration).build();
        InetSocketAddress address = new InetSocketAddress("localhost", port);
        TTransport transport = new NiftyClient(config)
                .connectSync(scribe.Client.class, new FramedClientConnector(address));
        TProtocol protocol = new TBinaryProtocol(transport);
        return new scribe.Client(protocol);
    }

    private scribe.Client makeNiftyPlaintextClient()
            throws TTransportException, InterruptedException
    {
        NettyClientConfig config =
                NettyClientConfig.newBuilder().build();
        InetSocketAddress address = new InetSocketAddress("localhost", port);
        TTransport transport = new NiftyClient(config)
                .connectSync(scribe.Client.class, new FramedClientConnector(address));
        TProtocol protocol = new TBinaryProtocol(transport);
        return new scribe.Client(protocol);
    }

    /**
     * Returns a file path to the given resource loaded using the given class's class loader.
     *
     * @param clazz the class whose class loader should be used to load the resource.
     * @param resourcePath the resource path.
     * @return a File object representing the path to the resource.
     */
    private File getResourceFile(Class<?> clazz, String resourcePath) {
        return new File(clazz.getResource(resourcePath).getFile());
    }

    /**
     * Returns the contents of the given resource loaded using the given class's class loader.
     *
     * @param clazz the class whose class loader should be used to load the resource.
     * @param resourcePath the resource path.
     * @return the contents of the resource file.
     * @throws IOException if the resource file could not be read.
     */
    private byte[] getResourceFileContents(Class<?> clazz, String resourcePath) throws IOException {
        return Files.toByteArray(getResourceFile(clazz, resourcePath));
    }

    /**
     * Overwrites the contents of the given file with the given byte array. If the file does not exist, it will
     * be created.
     *
     * @param file the file to overwrite.
     * @param newContents new file contents.
     * @throws IOException if the write fails.
     */
    private void overwriteFile(File file, byte[] newContents) throws IOException {
        java.nio.file.Files.write(file.toPath(), newContents);
    }

    /**
     * Best-effort attempt to delete all of the given files if they exist. Ignores errors.
     *
     * @param files the files to delete.
     */
    private void deleteFilesIfExistIgnoreErrors(File... files) {
        for (File file : files) {
            if (file != null) {
                try {
                    java.nio.file.Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    // silently ignore delete errors
                }
            }
        }
    }

    /**
     * Creates a temp file with the same contents as the given resource. Returns the path to the temp file.
     * The temp file should be deleted by the user when the test finishes.
     *
     * @param clazz the class whose class loader should be used to load the resource.
     * @param resourcePath the resource path.
     * @return a File object representing the path to the new temp file.
     * @throws IOException if the resource file could not be read, or temp file could not be created or written.
     */
    private File initTempFileFromResource(Class<?> clazz, String resourcePath) throws IOException {
        File result = File.createTempFile("test_nifty_openssl_server", resourcePath.replaceAll("/", "_"));
        overwriteFile(result, getResourceFileContents(clazz, resourcePath));
        return result;
    }

    /**
     * Returns the path to a temporary ticket seed file. If the temp file does not yet exist, it is created on
     * demand and initialized with the contents of the "/ticket_seeds.json" resource.
     * The temp file should be deleted by the user when the test finishes.
     *
     * @return the new file.
     * @throws IOException if reading the resource or creating the temp file fails.
     */
    private File getTicketSeedFile() throws IOException {
        if (ticketSeedFile == null) {
            ticketSeedFile = initTempFileFromResource(Plain.class, "/ticket_seeds.json");
       }
       return ticketSeedFile;
    }

    /**
     * Overwrites the contents of the ticket seed file with the given byte array.
     *
     * @param newContents new ticket seed file contents.
     * @throws IOException if writing the file fails.
     */
    private void updateTicketSeedFile(byte[] newContents) throws IOException {
        overwriteFile(getTicketSeedFile(), newContents);
    }

    /**
     * Returns the path to a temporary private key file. If the temp file does not yet exist, it is created on
     * demand and initialized with the contents of the "/rsa.key" resource.
     * The temp file should be deleted by the user when the test finishes.
     *
     * @return the new file.
     * @throws IOException if reading the resource or creating the temp file fails.
     */
    private File getPrivateKeyFile() throws IOException {
        if (privateKeyFile == null) {
            privateKeyFile = initTempFileFromResource(Plain.class, "/rsa.key");
        }
        return privateKeyFile;
    }

    /**
     * Overwrites the contents of the private key file with the given byte array.
     *
     * @param newContents new private key file contents.
     * @throws IOException if writing the file fails.
     */
    private void updatePrivateKeyFile(byte[] newContents) throws IOException {
        overwriteFile(getPrivateKeyFile(), newContents);
    }

    /**
     * Returns the path to a temporary certificate file. If the temp file does not yet exist, it is created on
     * demand and initialized with the contents of the "/rsa.crt" resource.
     * The temp file should be deleted by the user when the test finishes.
     *
     * @return the new file.
     * @throws IOException if reading the resource or creating the temp file fails.
     */
    private File getCertificateFile() throws IOException {
        if (certificateFile == null) {
            certificateFile = initTempFileFromResource(Plain.class, "/rsa.crt");
        }
        return certificateFile;
    }

    /**
     * Overwrites the contents of the certificate file with the given byte array.
     *
     * @param newContents new certificate file contents.
     * @throws IOException if writing the file fails.
     */
    private void updateCertificateFile(byte[] newContents) throws IOException {
        overwriteFile(getCertificateFile(), newContents);
    }

    /**
     * Asserts that the given lists of session ticket keys are the same. {@link SessionTicketKey} seems to not
     * implement a proper equals() method so we have to do this the hard way.
     *
     * @param actualKeys the actual ticket keys.
     * @param expectedKeys the expected ticket keys.
     */
    private void assertTicketKeysEqual(List<SessionTicketKey> actualKeys, List<SessionTicketKey> expectedKeys) {
        Assert.assertEquals(actualKeys.size(), expectedKeys.size());
        for (int i = 0; i < actualKeys.size(); ++i) {
            SessionTicketKey actualKey = actualKeys.get(i);
            SessionTicketKey expectedKey = expectedKeys.get(i);
            Assert.assertEquals(actualKey.getAesKey(), expectedKey.getAesKey());
            Assert.assertEquals(actualKey.getHmacKey(), expectedKey.getHmacKey());
            Assert.assertEquals(actualKey.getName(), expectedKey.getName());
        }
    }

    @Test
    public void testSSL() throws InterruptedException, TException, IOException
    {
        startServer();
        scribe.Client client1 = makeNiftyClient(getClientSSLConfiguration());
        Assert.assertEquals(client1.Log(Arrays.asList(new LogEntry("client1", "aaa"))), ResultCode.OK);
        Assert.assertEquals(client1.Log(Arrays.asList(new LogEntry("client1", "bbb"))), ResultCode.OK);
        scribe.Client client2 = makeNiftyClient(getClientSSLConfiguration());
        Assert.assertEquals(client2.Log(Arrays.asList(new LogEntry("client2", "ccc"))), ResultCode.OK);
    }

    @Test
    public void testSSLWithPlaintextAllowedServer() throws InterruptedException, TException, IOException
    {
        startServer(true);
        scribe.Client client1 = makeNiftyClient(getClientSSLConfiguration());
        Assert.assertEquals(client1.Log(Arrays.asList(new LogEntry("client1", "aaa"))), ResultCode.OK);
        Assert.assertEquals(client1.Log(Arrays.asList(new LogEntry("client1", "bbb"))), ResultCode.OK);
        scribe.Client client2 = makeNiftyClient(getClientSSLConfiguration());
        Assert.assertEquals(client2.Log(Arrays.asList(new LogEntry("client2", "ccc"))), ResultCode.OK);
    }

    @Test(expectedExceptions = TTransportException.class)
    public void testUnencryptedClient() throws InterruptedException, TException
    {
        startServer();
        scribe.Client client = makeNiftyPlaintextClient();
        client.Log(Arrays.asList(new LogEntry("client2", "aaa")));
        client.Log(Arrays.asList(new LogEntry("client2", "bbb")));
        client.Log(Arrays.asList(new LogEntry("client2", "ccc")));
    }

    @Test
    public void testUnencryptedClientWithAllowPlaintextServer() throws InterruptedException, TException, IOException
    {
        startServer(true);
        scribe.Client client = makeNiftyPlaintextClient();
        client.Log(Arrays.asList(new LogEntry("client2", "aaa")));
        client.Log(Arrays.asList(new LogEntry("client2", "bbb")));
        client.Log(Arrays.asList(new LogEntry("client2", "ccc")));
    }

    @Test
    public void testSSLSessionResumption() throws Exception {
        // Ticket resumes are not supported by nifty client, so we test stateful session resumption
        // only.
        SessionTicketKey[] keys = { createSessionTicketKey() };
        SslServerConfiguration sslServerConfiguration = createSSLServerConfiguration(true, keys);
        startServer(getThriftServerDefBuilder(sslServerConfiguration, null));

        SslClientConfiguration sslClientConfiguration = getClientSSLConfiguration();

        scribe.Client client1 = makeNiftyClient(sslClientConfiguration);
        client1.Log(Arrays.asList(new LogEntry("client1", "aaa")));
        Assert.assertFalse(isSessionResumed(getSSLSession(client1)));

        scribe.Client client2 = makeNiftyClient(sslClientConfiguration);
        client2.Log(Arrays.asList(new LogEntry("client2", "aaa")));
        Assert.assertTrue(isSessionResumed(getSSLSession(client2)));

        client2.Log(Arrays.asList(new LogEntry("client2", "bbb")));
        Assert.assertTrue(isSessionResumed(getSSLSession(client2)));

        SessionTicketKey[] keys2 = { createSessionTicketKey() };
        SslServerConfiguration sslServerConfiguration2 = createSSLServerConfiguration(true, keys2);
        server.updateSSLConfiguration(sslServerConfiguration2);

        scribe.Client client3 = makeNiftyClient(sslClientConfiguration);
        client3.Log(Arrays.asList(new LogEntry("client3", "aaa")));
        Assert.assertFalse(isSessionResumed(getSSLSession(client3)));

        scribe.Client client4 = makeNiftyClient(sslClientConfiguration);
        client4.Log(Arrays.asList(new LogEntry("client4", "aaa")));
        Assert.assertTrue(isSessionResumed(getSSLSession(client4)));
    }

    class TestConfigUpdater implements TransportAttachObserver {

        public NettyServerTransport attachedTransport;

        @Override
        public void attachTransport(NettyServerTransport transport) {
            attachedTransport = transport;
        }

        @Override
        public void detachTransport() {
            attachedTransport = null;
        }

        void updateSSLConfig(SslServerConfiguration newConfig) {
            attachedTransport.updateSSLConfiguration(newConfig);
        }
    };

    @Test
    public void testAttachTransportToUpdater() throws InterruptedException, IOException {
        TestConfigUpdater configUpdater = new TestConfigUpdater();
        SessionTicketKey[] keys = { createSessionTicketKey() };
        SslServerConfiguration sslServerConfiguration = createSSLServerConfiguration(true, keys);
        startServer(getThriftServerDefBuilder(sslServerConfiguration, configUpdater));
        Assert.assertNotNull(configUpdater.attachedTransport);

        SessionTicketKey[] newKeys = { createSessionTicketKey() };
        SslServerConfiguration newConfig = createSSLServerConfiguration(true, newKeys);
        configUpdater.updateSSLConfig(newConfig);

        server.stop();
        server = null;
        Assert.assertNull(configUpdater.attachedTransport);
    }

    @Test
    public void testRotateTicketSeedFile() throws InterruptedException, IOException {
        startServer();
        OpenSslServerConfiguration config = (OpenSslServerConfiguration) server.getSSLConfiguration();

        List<SessionTicketKey> actual = ImmutableList.copyOf(config.ticketKeys);
        List<SessionTicketKey> expected = new TicketSeedFileParser().parse(getTicketSeedFile());
        assertTicketKeysEqual(actual, expected);

        // Rotate the ticket seeds file
        updateTicketSeedFile(getResourceFileContents(Plain.class, "/ticket_seeds2.json"));
        Thread.sleep(300); // Wait for three update cycles to make sure new configuration is in place

        config = (OpenSslServerConfiguration) server.getSSLConfiguration();
        List<SessionTicketKey> actual2 = ImmutableList.copyOf(config.ticketKeys);
        List<SessionTicketKey> expected2 = new TicketSeedFileParser().parse(getTicketSeedFile());
        assertTicketKeysEqual(actual2, expected2);

        // Make sure the keys actually changed ...
        Assert.assertNotEquals(actual.get(0).getName(), actual2.get(0).getName());
    }

    @Test
    public void testRotateSSLKeyAndCertFiles() throws InterruptedException, IOException, TException {
        startServer();
        // This client config is using the original cert that the server starts up with
        SslClientConfiguration config1 = getClientSSLConfiguration(getResourceFile(Plain.class, "/rsa.crt"));
        // This client config is using the cert that we change to halfway through this test
        SslClientConfiguration config2 = getClientSSLConfiguration(getResourceFile(Plain.class, "/rsa2.crt"));
        scribe.Client client1 = makeNiftyClient(config1);
        scribe.Client client2 = makeNiftyClient(config2);

        Assert.assertEquals(client1.Log(Arrays.asList(new LogEntry("client1", "aaa"))), ResultCode.OK);
        // Before the server cert is rotated, using it on the client should fail
        try {
            client2.Log(Arrays.asList(new LogEntry("client2", "aaa")));
            Assert.fail("Request with wrong certificate should have thrown an exception");
        } catch (TTransportException e) {
            // The error is expected
        }

        // Rotate the cert and private key files
        updateCertificateFile(getResourceFileContents(Plain.class, "/rsa2.crt"));
        updatePrivateKeyFile(getResourceFileContents(Plain.class, "/rsa2.key"));
        Thread.sleep(300); // Wait for three update cycles to make sure new configuration is in place

        // Need to re-create clients to get their connections to use the new server cert.
        client1 = makeNiftyClient(config1);
        client2 = makeNiftyClient(config2);

        // After the server cert is rotated, using the original cert on the client should fail
        try {
            client1.Log(Arrays.asList(new LogEntry("client1", "bbb")));
            Assert.fail("Request with wrong certificate should have thrown an exception");
        } catch (TTransportException e) {
            // The error is expected
        }

        Assert.assertEquals(client2.Log(Arrays.asList(new LogEntry("client2", "bbb"))), ResultCode.OK);
    }

    private static SessionTicketKey createSessionTicketKey() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] name = new byte[SessionTicketKey.NAME_SIZE];
        byte[] hmac = new byte[SessionTicketKey.HMAC_KEY_SIZE];
        byte[] aes = new byte[SessionTicketKey.AES_KEY_SIZE];
        secureRandom.nextBytes(name);
        secureRandom.nextBytes(hmac);
        secureRandom.nextBytes(aes);

        return new SessionTicketKey(name, hmac, aes);
    }

    private static SSLSession getSSLSession(scribe.Client client) {
        TNiftyClientChannelTransport clientTransport =
                (TNiftyClientChannelTransport) client.getInputProtocol().getTransport();
        SslHandler sslHandler = (SslHandler) clientTransport.getChannel().getNettyChannel().getPipeline().get("ssl");
        return sslHandler.getEngine().getSession();
    }

    private static boolean isSessionResumed(SSLSession sslSession) throws NoSuchFieldException, IllegalAccessException {
        Field sslResumedField =  sslSession.getClass().getDeclaredField("isSessionResumption");
        sslResumedField.setAccessible(true);
        return sslResumedField.getBoolean(sslSession);
    }
}
