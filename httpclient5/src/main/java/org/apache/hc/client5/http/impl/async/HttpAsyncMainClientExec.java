/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.async;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.UserTokenHandler;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.async.AsyncExecRuntime;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.message.RequestLine;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.AsyncDataConsumer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class HttpAsyncMainClientExec implements AsyncExecChainHandler {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ConnectionKeepAliveStrategy keepAliveStrategy;
    private final UserTokenHandler userTokenHandler;

    HttpAsyncMainClientExec(final ConnectionKeepAliveStrategy keepAliveStrategy, final UserTokenHandler userTokenHandler) {
        this.keepAliveStrategy = keepAliveStrategy;
        this.userTokenHandler = userTokenHandler;
    }

    @Override
    public void execute(
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) throws HttpException, IOException {
        final String exchangeId = scope.exchangeId;
        final HttpRoute route = scope.route;
        final CancellableDependency operation = scope.cancellableDependency;
        final HttpClientContext clientContext = scope.clientContext;
        final AsyncExecRuntime execRuntime = scope.execRuntime;

        if (log.isDebugEnabled()) {
            log.debug(exchangeId + ": executing " + new RequestLine(request));
        }

        final AtomicInteger messageCountDown = new AtomicInteger(2);
        final AsyncClientExchangeHandler internalExchangeHandler = new AsyncClientExchangeHandler() {

            private final AtomicReference<AsyncDataConsumer> entityConsumerRef = new AtomicReference<>(null);

            @Override
            public void releaseResources() {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.getAndSet(null);
                if (entityConsumer != null) {
                    entityConsumer.releaseResources();
                }
            }

            @Override
            public void failed(final Exception cause) {
                execRuntime.markConnectionNonReusable();
                asyncExecCallback.failed(cause);
            }

            @Override
            public void cancel() {
                failed(new InterruptedIOException());
            }

            @Override
            public void produceRequest(
                    final RequestChannel channel,
                    final HttpContext context) throws HttpException, IOException {
                channel.sendRequest(request, entityProducer, context);
                if (entityProducer == null) {
                    messageCountDown.decrementAndGet();
                }
            }

            @Override
            public int available() {
                return entityProducer.available();
            }

            @Override
            public void produce(final DataStreamChannel channel) throws IOException {
                entityProducer.produce(new DataStreamChannel() {

                    @Override
                    public void requestOutput() {
                        channel.requestOutput();
                    }

                    @Override
                    public int write(final ByteBuffer src) throws IOException {
                        return channel.write(src);
                    }

                    @Override
                    public void endStream(final List<? extends Header> trailers) throws IOException {
                        channel.endStream(trailers);
                        if (messageCountDown.decrementAndGet() <= 0) {
                            asyncExecCallback.completed();
                        }
                    }

                    @Override
                    public void endStream() throws IOException {
                        channel.endStream();
                        if (messageCountDown.decrementAndGet() <= 0) {
                            asyncExecCallback.completed();
                        }
                    }

                });
            }

            @Override
            public void consumeInformation(
                    final HttpResponse response,
                    final HttpContext context) throws HttpException, IOException {
            }

            @Override
            public void consumeResponse(
                    final HttpResponse response,
                    final EntityDetails entityDetails,
                    final HttpContext context) throws HttpException, IOException {
                entityConsumerRef.set(asyncExecCallback.handleResponse(response, entityDetails));
                if (response.getCode() >= HttpStatus.SC_CLIENT_ERROR) {
                    messageCountDown.decrementAndGet();
                }
                final TimeValue keepAliveDuration = keepAliveStrategy.getKeepAliveDuration(response, clientContext);
                Object userToken = clientContext.getUserToken();
                if (userToken == null) {
                    userToken = userTokenHandler.getUserToken(route, clientContext);
                    clientContext.setAttribute(HttpClientContext.USER_TOKEN, userToken);
                }
                execRuntime.markConnectionReusable(userToken, keepAliveDuration);
                if (entityDetails == null) {
                    execRuntime.validateConnection();
                    if (messageCountDown.decrementAndGet() <= 0) {
                        asyncExecCallback.completed();
                    }
                }
            }

            @Override
            public void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.get();
                if (entityConsumer != null) {
                    entityConsumer.updateCapacity(capacityChannel);
                } else {
                    capacityChannel.update(Integer.MAX_VALUE);
                }
            }

            @Override
            public int consume(final ByteBuffer src) throws IOException {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.get();
                if (entityConsumer != null) {
                    return entityConsumer.consume(src);
                } else {
                    return Integer.MAX_VALUE;
                }
            }

            @Override
            public void streamEnd(final List<? extends Header> trailers) throws HttpException, IOException {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.getAndSet(null);
                if (entityConsumer != null) {
                    entityConsumer.streamEnd(trailers);
                } else {
                    execRuntime.validateConnection();
                }
                if (messageCountDown.decrementAndGet() <= 0) {
                    asyncExecCallback.completed();
                }
            }

        };

        if (log.isDebugEnabled()) {
            operation.setDependency(execRuntime.execute(
                    new LoggingAsyncClientExchangeHandler(log, exchangeId, internalExchangeHandler),
                    clientContext));
        } else {
            operation.setDependency(execRuntime.execute(internalExchangeHandler, clientContext));
        }
    }

}
