/*
 * Copyright (C) 2012-2016 Facebook, Inc.
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

import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.ssl.SslBufferPool;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;

/**
 * An SslHandler which propagates session events to other handlers in the pipeline.
 * This does not support the delayed handshake mode and will start the handshake as soon as the
 * channel is connected.
 */
public class SessionAwareSslHandler extends SslHandler {

    private final SslServerConfiguration sslServerConfiguration;

    public SessionAwareSslHandler(SSLEngine engine, SslBufferPool pool, SslServerConfiguration configuration) {
        super(engine, pool);
        this.sslServerConfiguration = configuration;
    }

    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        doSslHandshake(ctx);
        super.channelConnected(ctx, e);
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        if (e.getMessage() == SslPlaintextHandler.TLSConnectedEvent.SINGLETON) {
            // SslPlaintextHandler has to delay the SSL handshake until it receives some data and realizes that
            // it's handling a TLS connection. It does this by sending us a special MessageEvent with payload =
            // SslPlaintextHandler.TLSConnectedEvent.SINGLETON. This message is only intended for this class and
            // should not be propagated up the handler chain.
            doSslHandshake(ctx);
        } else {
            super.messageReceived(ctx, e);
        }
    }

    private void doSslHandshake(final ChannelHandlerContext ctx) {
        handshake().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    SslSession sslSession = sslServerConfiguration.getSession(getEngine());
                    Channels.fireMessageReceived(ctx, sslSession);
                }
            }
        });

    }
}
