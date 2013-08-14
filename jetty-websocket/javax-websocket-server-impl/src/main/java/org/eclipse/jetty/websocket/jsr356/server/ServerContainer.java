//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.JsrSessionFactory;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.endpoints.EndpointInstance;
import org.eclipse.jetty.websocket.jsr356.endpoints.JsrEndpointImpl;
import org.eclipse.jetty.websocket.jsr356.metadata.EndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.server.pathmap.WebSocketPathSpec;
import org.eclipse.jetty.websocket.server.MappedWebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;

public class ServerContainer extends ClientContainer implements javax.websocket.server.ServerContainer
{
    private static final Logger LOG = Log.getLogger(ServerContainer.class);

    private final MappedWebSocketCreator mappedCreator;
    private final WebSocketServerFactory webSocketServerFactory;
    private final Map<Class<?>, ServerEndpointMetadata> endpointServerMetadataCache = new ConcurrentHashMap<>();

    public ServerContainer(MappedWebSocketCreator creator, WebSocketServerFactory factory)
    {
        super();
        this.mappedCreator = creator;
        this.webSocketServerFactory = factory;
        EventDriverFactory eventDriverFactory = this.webSocketServerFactory.getEventDriverFactory();
        eventDriverFactory.addImplementation(new JsrServerEndpointImpl());
        eventDriverFactory.addImplementation(new JsrEndpointImpl());
        this.webSocketServerFactory.addSessionFactory(new JsrSessionFactory(this));
    }

    @Override
    protected void doStop() throws Exception
    {
        endpointServerMetadataCache.clear();
        super.doStop();
    }

    public EndpointInstance newClientEndpointInstance(Object endpoint, ServerEndpointConfig config, String path)
    {
        EndpointMetadata metadata = getClientEndpointMetadata(endpoint.getClass());
        ServerEndpointConfig cec = config;
        if (config == null)
        {
            if (metadata instanceof AnnotatedServerEndpointMetadata)
            {
                cec = ((AnnotatedServerEndpointMetadata)metadata).getConfig();
            }
            else
            {
                cec = new BasicServerEndpointConfig(endpoint.getClass(),path);
            }
        }
        return new EndpointInstance(endpoint,cec,metadata);
    }

    @Override
    public void addEndpoint(Class<?> endpointClass) throws DeploymentException
    {
        ServerEndpointMetadata metadata = getServerEndpointMetadata(endpointClass,null);
        addEndpoint(metadata);
    }

    public void addEndpoint(ServerEndpointMetadata metadata) throws DeploymentException
    {
        JsrCreator creator = new JsrCreator(metadata);
        mappedCreator.addMapping(new WebSocketPathSpec(metadata.getPath()),creator);
    }

    @Override
    public void addEndpoint(ServerEndpointConfig config) throws DeploymentException
    {
        LOG.debug("addEndpoint({})",config);
        ServerEndpointMetadata metadata = getServerEndpointMetadata(config.getEndpointClass(),config);
        addEndpoint(metadata);
    }

    public ServerEndpointMetadata getServerEndpointMetadata(Class<?> endpoint, ServerEndpointConfig config) throws DeploymentException
    {
        synchronized (endpointServerMetadataCache)
        {
            ServerEndpointMetadata metadata = endpointServerMetadataCache.get(endpoint);
            if (metadata != null)
            {
                return metadata;
            }

            ServerEndpoint anno = endpoint.getAnnotation(ServerEndpoint.class);
            if (anno != null)
            {
                // Annotated takes precedence here
                AnnotatedServerEndpointMetadata ametadata = new AnnotatedServerEndpointMetadata(this,endpoint,config);
                AnnotatedEndpointScanner<ServerEndpoint,ServerEndpointConfig> scanner = new AnnotatedEndpointScanner<>(ametadata);
                metadata = ametadata;
                scanner.scan();
            }
            else if (Endpoint.class.isAssignableFrom(endpoint))
            {
                // extends Endpoint
                @SuppressWarnings("unchecked")
                Class<? extends Endpoint> eendpoint = (Class<? extends Endpoint>)endpoint;
                metadata = new SimpleServerEndpointMetadata(eendpoint,config);
            }
            else
            {
                StringBuilder err = new StringBuilder();
                err.append("Not a recognized websocket [");
                err.append(endpoint.getName());
                err.append("] does not extend @").append(ServerEndpoint.class.getName());
                err.append(" or extend from ").append(Endpoint.class.getName());
                throw new DeploymentException("Unable to identify as valid Endpoint: " + endpoint);
            }

            endpointServerMetadataCache.put(endpoint,metadata);

            return metadata;
        }
    }

    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return webSocketServerFactory.getPolicy().getAsyncWriteTimeout();
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return webSocketServerFactory.getPolicy().getMaxBinaryMessageSize();
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return webSocketServerFactory.getPolicy().getIdleTimeout();
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return webSocketServerFactory.getPolicy().getMaxTextMessageSize();
    }

    @Override
    public void setAsyncSendTimeout(long ms)
    {
        webSocketServerFactory.getPolicy().setAsyncWriteTimeout(ms);
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        // overall message limit (used in non-streaming)
        webSocketServerFactory.getPolicy().setMaxBinaryMessageSize(max);
        // incoming streaming buffer size
        webSocketServerFactory.getPolicy().setMaxBinaryMessageBufferSize(max);
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        webSocketServerFactory.getPolicy().setIdleTimeout(ms);
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        // overall message limit (used in non-streaming)
        webSocketServerFactory.getPolicy().setMaxTextMessageSize(max);
        // incoming streaming buffer size
        webSocketServerFactory.getPolicy().setMaxTextMessageBufferSize(max);
    }
}