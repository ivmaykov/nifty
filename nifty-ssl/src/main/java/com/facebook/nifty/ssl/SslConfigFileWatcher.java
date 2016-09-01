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
package com.facebook.nifty.ssl;

import com.facebook.nifty.core.NettyServerTransport;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import org.apache.tomcat.jni.SessionTicketKey;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches SSL config files for changes and updates the {@link SslServerConfiguration} of the attached
 * {@link NettyServerTransport} when the files change. Three kind of files are watched:
 * <ul>
 * <li>a ticket seed file. New ticket seeds are constructed from it with a {@link TicketSeedFileParser}.</li>
 * <li>a TLS private key file. Passed to the SslServerConfiguration, which loads the key from the file.</li>
 * <li>a TLS certificate file. Passed to the SslServerConfiguration, which loads the cert from the file.</li>
 * </ul>
 * Note that the file paths cannot be changed after the watcher is created. The watcher will automatically start
 * polling files for changes when it is attached to a NettyServerTransport, and will stop polling when it is
 * detached from the transport. All three file paths are currently required.
 */
public class SslConfigFileWatcher extends AbstractMultiFileWatcher implements TransportAttachObserver {
    private final AtomicReference<NettyServerTransport> transportRef;
    private final File ticketSeedFile;
    private final File keyFile;
    private final File certFile;
    private final TicketSeedFileParser ticketSeedFileParser;

    private static final Logger log = Logger.get(AbstractMultiFileWatcher.class);

    /**
     * Constructs a new config file watcher.
     * @param ticketSeedFile the path to the ticket seed file. May not be null.
     * @param keyFile the path to the TLS private key file. May not be null.
     * @param certFile the path to the TLS certificate file. May not be null.
     * @param initialDelay how long to wait until the first scan of the files.
     * @param interval how often to rescan the files.
     * @param timeUnit time unit for {@code initialDelay} and {@code interval}.
     * @param ticketSeedSalt the ticket seed salt. If null, uses {@link TicketSeedFileParser#DEFAULT_TICKET_SALT}.
     */
    public SslConfigFileWatcher(File ticketSeedFile,
                                File keyFile,
                                File certFile,
                                long initialDelay,
                                long interval,
                                TimeUnit timeUnit,
                                byte[] ticketSeedSalt) {
        super(ImmutableList.of(ticketSeedFile, keyFile, certFile), initialDelay, interval, timeUnit);
        transportRef = new AtomicReference<>(null);
        this.ticketSeedFile = ticketSeedFile;
        this.keyFile = keyFile;
        this.certFile = certFile;
        ticketSeedFileParser = new TicketSeedFileParser(ticketSeedSalt);
    }

    @Override
    public void attachTransport(NettyServerTransport transport) {
        log.debug("Attaching %s observer to %s",
            getClass().getSimpleName(),
            transport == null ? "null" : transport.getClass().getSimpleName());
        this.transportRef.set(transport);
        start();
    }

    @Override
    public void detachTransport() {
        NettyServerTransport transport = transportRef.get();
        log.debug("Detaching %s observer from %s",
            getClass().getSimpleName(),
            transport == null ? "null" : transport.getClass().getSimpleName());
        shutdown();
        this.transportRef.set(null);
    }

    @Override
    protected void onFilesUpdated(Set<File> modifiedFiles) {
        log.info("SslConfigFileWatcher: modifiedFiles = ");
        for (File f : modifiedFiles) {
            log.info("\t%s", f.toString());
        }
        NettyServerTransport transport = transportRef.get();
        if (transport == null) { // not attached to a transport, nothing to do
            return;
        }

        boolean ticketSeedFileUpdated = modifiedFiles.contains(ticketSeedFile);
        boolean keyFileUpdated = modifiedFiles.contains(keyFile);
        boolean certFileUpdated = modifiedFiles.contains(certFile);
        boolean needUpdate = ticketSeedFileUpdated || keyFileUpdated || certFileUpdated;
        while (needUpdate) {
            log.info("Trying to update server configuration ...");
            SslServerConfiguration oldConfig = transport.getSSLConfiguration();
            SslServerConfiguration.BuilderBase<?> builder;
            boolean isOpenSsl = false;
            if (oldConfig instanceof OpenSslServerConfiguration) {
                builder = OpenSslServerConfiguration.newBuilder();
                isOpenSsl = true;
            }
            else {
                builder = JavaSslServerConfiguration.newBuilder();
            }
            builder.initFromConfiguration(oldConfig);
            if (ticketSeedFileUpdated && isOpenSsl) {
                // Note: JavaSslServerConfiguration does not currently support ticket keys, so only update them
                // if using the OpenSSL implementation.
                OpenSslServerConfiguration.Builder openSslBuilder = (OpenSslServerConfiguration.Builder) builder;
                try {
                    List<SessionTicketKey> ticketKeys = ticketSeedFileParser.parse(ticketSeedFile);
                    openSslBuilder.ticketKeys(ticketKeys.toArray(new SessionTicketKey[ticketKeys.size()]));
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (keyFileUpdated) {
                builder.keyFile(keyFile);
            }
            if (certFileUpdated) {
                builder.certFile(certFile);
            }
            SslServerConfiguration newConfig = builder.createServerConfiguration();
            needUpdate = !transport.compareAndSetSSLConfiguration(oldConfig, newConfig);
            if (!needUpdate) {
                log.info("Update succeeded!");
            }
            else {
                log.info("Update failed!");
            }
        }
    }
}
