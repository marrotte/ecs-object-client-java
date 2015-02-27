/*
 * Copyright (c) 2015, EMC Corporation.
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * + Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * + Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * + The name of EMC Corporation may not be used to endorse or promote
 *   products derived from this software without specific prior written
 *   permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.emc.object;

import com.emc.object.util.RestUtil;
import com.emc.rest.smart.SizeOverrideWriter;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.client.apache4.config.ApacheHttpClient4Config;
import org.apache.log4j.Logger;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Map;

public abstract class AbstractJerseyClient {
    private static final Logger l4j = Logger.getLogger(AbstractJerseyClient.class);

    protected static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];

    protected ObjectConfig objectConfig;

    protected AbstractJerseyClient(ObjectConfig objectConfig) {
        this.objectConfig = objectConfig;
    }

    protected ClientResponse executeAndClose(Client client, ObjectRequest request) {
        ClientResponse response = executeRequest(client, request);
        response.close();
        return response;
    }

    @SuppressWarnings("unchecked")
    protected ClientResponse executeRequest(Client client, ObjectRequest request) {
        try {
            if (request.getMethod().isRequiresEntity()) {
                String contentType = RestUtil.DEFAULT_CONTENT_TYPE;
                Object entity = new byte[0];
                if (request instanceof EntityRequest) {
                    EntityRequest<?> entityRequest = (EntityRequest) request;

                    if (entityRequest.getContentType() != null) contentType = entityRequest.getContentType();

                    if (entityRequest.getEntity() != null) entity = entityRequest.getEntity();

                    // calculate and set content-length
                    Long size = entityRequest.getContentLength();
                    if (size == null) {
                        // try and find an entity writer that can determine the size
                        // TODO: can remove when chunked encoding is supported
                        MediaType mediaType = MediaType.valueOf(contentType);
                        MessageBodyWriter writer = client.getProviders().getMessageBodyWriter(entity.getClass(), entity.getClass(),
                                EMPTY_ANNOTATIONS, mediaType);
                        size = writer.getSize(entity, entity.getClass(), entity.getClass(), EMPTY_ANNOTATIONS, mediaType);
                    }

                    // if size cannot be determined, enable buffering to let HttpClient set the content-length
                    // NOTE: if a non-apache client handler is used, this has no effect and chunked encoding will always be enabled
                    // NOTE2: if the entity is an input stream, it will be streamed into memory to determine its size. this
                    //        may cause OutOfMemoryException if there is not enough memory to hold the data
                    // TODO: can remove when chunked encoding is supported
                    if (size < 0) {
                        l4j.info("entity size cannot be determined; enabling Apache client entity buffering...");
                        if (entity instanceof InputStream)
                            l4j.warn("set a content-length for input streams to save memory");
                        request.property(ApacheHttpClient4Config.PROPERTY_ENABLE_BUFFERING, Boolean.TRUE);
                    }

                    SizeOverrideWriter.setEntitySize(size);
                } else {

                    // no entity, but make sure the apache handler doesn't mess up the content-length somehow
                    // (i.e. if content-encoding is set)
                    request.property(ApacheHttpClient4Config.PROPERTY_ENABLE_BUFFERING, Boolean.TRUE);

                    String headerContentType = RestUtil.getFirstAsString(request.getHeaders(), RestUtil.DEFAULT_CONTENT_TYPE);
                    if (headerContentType != null) contentType = headerContentType;
                }

                WebResource.Builder builder = buildRequest(client, request);

                // jersey requires content-type for entity requests
                builder.type(contentType);
                return builder.method(request.getMethod().toString(), ClientResponse.class, entity);
            } else { // non-entity request method

                // can't send content with non-entity methods (GET, HEAD, etc.)
                if (request instanceof EntityRequest)
                    throw new UnsupportedOperationException("an entity request is using a non-entity method (" + request.getMethod() + ")");

                WebResource.Builder builder = buildRequest(client, request);

                return builder.method(request.getMethod().toString(), ClientResponse.class);
            }
        } finally {
            // make sure we clear the content-length override for this thread
            SizeOverrideWriter.setEntitySize(null);
        }
    }

    protected <T> T executeRequest(Client client, ObjectRequest request, Class<T> responseType) {
        ClientResponse response = executeRequest(client, request);
        T responseEntity = response.getEntity(responseType);
        fillResponseEntity(responseEntity, response);
        return responseEntity;
    }

    protected void fillResponseEntity(Object responseEntity, ClientResponse response) {
        if (responseEntity instanceof ObjectResponse)
            ((ObjectResponse) responseEntity).setHeaders(response.getHeaders());
    }

    protected WebResource.Builder buildRequest(Client client, ObjectRequest request) {
        URI uri = objectConfig.resolvePath(request.getPath(), request.getQueryString());
        WebResource resource = client.resource(uri);

        // set properties
        for (Map.Entry<String, Object> entry : request.getProperties().entrySet()) {
            resource.setProperty(entry.getKey(), entry.getValue());
        }

        // set namespace
        String namespace = request.getNamespace() != null ? request.getNamespace() : objectConfig.getNamespace();
        if (namespace != null)
            resource.setProperty(RestUtil.PROPERTY_NAMESPACE, namespace);

        WebResource.Builder builder = resource.getRequestBuilder();

        // set headers
        for (String name : request.getHeaders().keySet()) {
            for (Object value : request.getHeaders().get(name)) {
                builder = builder.header(name, value);
            }
        }

        return builder;
    }
}
