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
package com.emc.object.s3.jersey;

import com.emc.object.AbstractJerseyClient;
import com.emc.object.Method;
import com.emc.object.ObjectRequest;
import com.emc.object.Range;
import com.emc.object.s3.*;
import com.emc.object.s3.bean.*;
import com.emc.object.s3.request.*;
import com.emc.object.util.RestUtil;
import com.emc.rest.smart.PollingDaemon;
import com.emc.rest.smart.SmartClientFactory;
import com.emc.rest.smart.SmartConfig;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import org.apache.log4j.Logger;

import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.util.Date;

public class S3JerseyClient extends AbstractJerseyClient implements S3Client {
    private static final Logger l4j = Logger.getLogger(S3JerseyClient.class);

    protected S3Config s3Config;
    protected Client client;

    public S3JerseyClient(S3Config s3Config) {
        super(s3Config);
        this.s3Config = s3Config;

        // add Checksum

        SmartConfig smartConfig = s3Config.toSmartConfig();

        // creates a standard (non-load-balancing) jersey client
        client = SmartClientFactory.createStandardClient(smartConfig);

        if (!s3Config.isUseVHost()) {
            // SMART CLIENT SETUP

            // S.C. - ENDPOINT POLLING
            // create a host list provider based on the S3 ?endpoint call (will use the standard client we just made)
            S3HostListProvider hostListProvider = new S3HostListProvider(client, smartConfig.getLoadBalancer(),
                    s3Config.getIdentity(), s3Config.getSecretKey());
            smartConfig.setHostListProvider(hostListProvider);

            if (s3Config.property(S3Config.PROPERTY_POLL_PROTOCOL) != null)
                hostListProvider.setProtocol(s3Config.propAsString(S3Config.PROPERTY_POLL_PROTOCOL));
            else
                hostListProvider.setProtocol(s3Config.getProtocol().toString());

            if (s3Config.property(S3Config.PROPERTY_POLL_PORT) != null) {
                try {
                    hostListProvider.setPort(Integer.parseInt(s3Config.propAsString(S3Config.PROPERTY_POLL_PORT)));
                } catch (NumberFormatException e) {
                    throw new RuntimeException(String.format("invalid poll port (%s=%s)",
                            S3Config.PROPERTY_POLL_PORT, s3Config.propAsString(S3Config.PROPERTY_POLL_PORT)), e);
                }
            } else
                hostListProvider.setPort(s3Config.getPort());

            // S.C. - CLIENT CREATION
            // create a load-balancing jersey client
            client = SmartClientFactory.createSmartClient(smartConfig);
        }

        // jersey filters
        client.addFilter(new ErrorFilter());
        client.addFilter(new ChecksumFilter());
        client.addFilter(new AuthorizationFilter(s3Config));
        client.addFilter(new BucketFilter(s3Config));
        client.addFilter(new NamespaceFilter(s3Config));
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize(); // make sure we call super.finalize() no matter what!
        }
    }

    @Override
    public void shutdown() {
        l4j.debug("terminating polling daemon");
        PollingDaemon pollingDaemon = (PollingDaemon) client.getProperties().get(PollingDaemon.PROPERTY_KEY);
        if (pollingDaemon != null) pollingDaemon.terminate();
    }

    @Override
    public ListDataNode listDataNodes() {
        return executeRequest(client, new ObjectRequest(Method.GET, "", "endpoint"), ListDataNode.class);
    }

    @Override
    public ListBucketsResult listBuckets() {
        return listBuckets(new ListBucketsRequest());
    }

    @Override
    public ListBucketsResult listBuckets(ListBucketsRequest request) {
        return executeRequest(client, request, ListBucketsResult.class);
    }

    @Override
    public boolean bucketExists(String bucketName) {
        try {
            executeAndClose(client, new GenericBucketRequest(Method.HEAD, bucketName, null));
            return true;
        } catch (S3Exception e) {
            switch (e.getHttpCode()) {
                case RestUtil.STATUS_REDIRECT:
                case RestUtil.STATUS_UNAUTHORIZED:
                    return true;
                case RestUtil.STATUS_NOT_FOUND:
                    return false;
                default:
                    throw e;
            }
        }
    }

    @Override
    public void createBucket(String bucketName) {
        createBucket(new CreateBucketRequest(bucketName));
    }

    @Override
    public void createBucket(CreateBucketRequest request) {
        executeAndClose(client, request);
    }

    @Override
    public void deleteBucket(String bucketName) {
        executeAndClose(client, new GenericBucketRequest(Method.DELETE, bucketName, null));
    }

    @Override
    public void setBucketAcl(String bucketName, AccessControlList acl) {
        setBucketAcl(new SetBucketAclRequest(bucketName).withAcl(acl));
    }

    @Override
    public void setBucketAcl(String bucketName, CannedAcl cannedAcl) {
        setBucketAcl(new SetBucketAclRequest(bucketName).withCannedAcl(cannedAcl));
    }

    @Override
    public void setBucketAcl(SetBucketAclRequest request) {
        executeAndClose(client, request);
    }

    @Override
    public AccessControlList getBucketAcl(String bucketName) {
        ObjectRequest request = new GenericBucketRequest(Method.GET, bucketName, "acl");
        return executeRequest(client, request, AccessControlList.class);
    }

    @Override
    public void setBucketCors(String bucketName, CorsConfiguration corsConfiguration) {
        ObjectRequest request = new GenericBucketEntityRequest<CorsConfiguration>(
                Method.PUT, bucketName, "cors", corsConfiguration).withContentType(RestUtil.TYPE_APPLICATION_XML);
        executeAndClose(client, request);
    }

    @Override
    public CorsConfiguration getBucketCors(String bucketName) {
        ObjectRequest request = new GenericBucketRequest(Method.GET, bucketName, "cors");
        return executeRequest(client, request, CorsConfiguration.class);
    }

    @Override
    public void deleteBucketCors(String bucketName) {
        executeAndClose(client, new GenericBucketRequest(Method.DELETE, bucketName, "cors"));
    }

    @Override
    public void setBucketLifecycle(String bucketName, LifecycleConfiguration lifecycleConfiguration) {
        ObjectRequest request = new GenericBucketEntityRequest<LifecycleConfiguration>(
                Method.PUT, bucketName, "lifecycle", lifecycleConfiguration).withContentType(RestUtil.TYPE_APPLICATION_XML);
        executeAndClose(client, request);
    }

    @Override
    public LifecycleConfiguration getBucketLifecycle(String bucketName) {
        ObjectRequest request = new GenericBucketRequest(Method.GET, bucketName, "lifecycle");
        return executeRequest(client, request, LifecycleConfiguration.class);
    }

    @Override
    public void deleteBucketLifecycle(String bucketName) {
        executeAndClose(client, new GenericBucketRequest(Method.DELETE, bucketName, "lifecycle"));
    }

    @Override
    public LocationConstraint getBucketLocation(String bucketName) {
        ObjectRequest request = new GenericBucketRequest(Method.GET, bucketName, "location");
        return executeRequest(client, request, LocationConstraint.class);
    }

    @Override
    public void setBucketVersioning(String bucketName, VersioningConfiguration versioningConfiguration) {
        ObjectRequest request = new GenericBucketEntityRequest<VersioningConfiguration>(
                Method.PUT, bucketName, "versioning", versioningConfiguration).withContentType(RestUtil.TYPE_APPLICATION_XML);
        executeAndClose(client, request);
    }

    @Override
    public VersioningConfiguration getBucketVersioning(String bucketName) {
        ObjectRequest request = new GenericBucketRequest(Method.GET, bucketName, "versioning");
        return executeRequest(client, request, VersioningConfiguration.class);
    }

    @Override
    public ListObjectsResult listObjects(String bucketName) {
        return listObjects(new ListObjectsRequest(bucketName));
    }

    @Override
    public ListObjectsResult listObjects(String bucketName, String prefix) {
        return listObjects(new ListObjectsRequest(bucketName).withPrefix(prefix));
    }

    @Override
    public ListObjectsResult listObjects(ListObjectsRequest request) {
        return executeRequest(client, request, ListObjectsResult.class);
    }

    @Override
    public ListVersionsResult listVersions(String bucketName, String prefix) {
        return listVersions(new ListVersionsRequest(bucketName).withPrefix(prefix));
    }

    @Override
    public ListVersionsResult listVersions(ListVersionsRequest request) {
        return executeRequest(client, request, ListVersionsResult.class);
    }

    @Override
    public void putObject(String bucketName, String key, Object content, String contentType) {
        S3ObjectMetadata metadata = new S3ObjectMetadata().withContentType(contentType);
        putObject(new PutObjectRequest(bucketName, key, content).withObjectMetadata(metadata));
    }

    @Override
    public void putObject(String bucketName, String key, Range range, Object content) {
        putObject(new PutObjectRequest(bucketName, key, content).withRange(range));
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {

        // enable checksum of the object
        request.property(RestUtil.PROPERTY_VERIFY_WRITE_CHECKSUM, Boolean.TRUE);

        PutObjectResult result = new PutObjectResult();
        fillResponseEntity(result, executeAndClose(client, request));
        return result;
    }

    @Override
    public long appendObject(String bucketName, String key, Object content) {
        return putObject(new PutObjectRequest(bucketName, key, content)
                .withRange(Range.fromOffset(-1))).getAppendOffset();
    }

    @Override
    public CopyObjectResult copyObject(String sourceBucketName, String sourceKey, String bucketName, String key) {
        return copyObject(new CopyObjectRequest(sourceBucketName, sourceKey, bucketName, key));
    }

    @Override
    public CopyObjectResult copyObject(CopyObjectRequest request) {
        return executeRequest(client, request, CopyObjectResult.class);
    }

    @Override
    public <T> T readObject(String bucketName, String key, Class<T> objectType) {
        return getObject(new GetObjectRequest(bucketName, key), objectType).getObject();
    }

    @Override
    public <T> T readObject(String bucketName, String key, String versionId, Class<T> objectType) {
        return getObject(new GetObjectRequest(bucketName, key).withVersionId(versionId), objectType).getObject();
    }

    @Override
    public InputStream readObjectStream(String bucketName, String key, Range range) {
        return getObject(new GetObjectRequest(bucketName, key).withRange(range), InputStream.class).getObject();
    }

    @Override
    public GetObjectResult<InputStream> getObject(String bucketName, String key) {
        return getObject(new GetObjectRequest(bucketName, key), InputStream.class);
    }

    @Override
    public <T> GetObjectResult<T> getObject(GetObjectRequest request, Class<T> objectType) {
        try {
            if (request.getRange() == null) {
                // enable checksum of the object (verification is handled in interceptor)
                request.property(RestUtil.PROPERTY_VERIFY_READ_CHECKSUM, Boolean.TRUE);
            }

            GetObjectResult<T> result = new GetObjectResult<T>();
            ClientResponse response = executeRequest(client, request);
            fillResponseEntity(result, response);
            result.setObject(response.getEntity(objectType));
            return result;
        } catch (S3Exception e) {
            // a 304 or 412 means If-* headers were used and a condition failed
            if (e.getHttpCode() == 304 || e.getHttpCode() == 412) return null;
            throw e;
        }
    }

    @Override
    public URL getPresignedUrl(String bucketName, String key, Date expirationTime) {
        return getPresignedUrl(new PresignedUrlRequest(Method.GET, bucketName, key, expirationTime));
    }

    @Override
    public URL getPresignedUrl(PresignedUrlRequest request) {
        return S3AuthUtil.generatePresignedUrl(request, s3Config);
    }

    @Override
    public void deleteObject(String bucketName, final String key) {
        executeAndClose(client, new S3ObjectRequest(Method.DELETE, bucketName, key, null));
    }

    @Override
    public void deleteVersion(String bucketName, String key, String versionId) {
        executeAndClose(client, new S3ObjectRequest(Method.DELETE, bucketName, key, "versionId=" + versionId));
    }

    @Override
    public DeleteObjectsResult deleteObjects(DeleteObjectsRequest request) {
        return executeRequest(client, request, DeleteObjectsResult.class);
    }

    @Override
    public void setObjectMetadata(String bucketName, String key, S3ObjectMetadata objectMetadata) {
        AccessControlList acl = getObjectAcl(bucketName, key);
        copyObject(new CopyObjectRequest(bucketName, key, bucketName, key).withAcl(acl).withObjectMetadata(objectMetadata));
    }

    @Override
    public S3ObjectMetadata getObjectMetadata(String bucketName, String key) {
        return getObjectMetadata(new GetObjectMetadataRequest(bucketName, key));
    }

    @Override
    public S3ObjectMetadata getObjectMetadata(GetObjectMetadataRequest request) {
        try {
            return S3ObjectMetadata.fromHeaders(executeAndClose(client, request).getHeaders());
        } catch (S3Exception e) {
            // a 304 or 412 means If-* headers were used and a condition failed
            if (e.getHttpCode() == 304 || e.getHttpCode() == 412) return null;
            throw e;
        }
    }

    @Override
    public void setObjectAcl(String bucketName, String key, AccessControlList acl) {
        setObjectAcl(new SetObjectAclRequest(bucketName, key).withAcl(acl));
    }

    @Override
    public void setObjectAcl(String bucketName, String key, CannedAcl cannedAcl) {
        setObjectAcl(new SetObjectAclRequest(bucketName, key).withCannedAcl(cannedAcl));
    }

    @Override
    public void setObjectAcl(SetObjectAclRequest request) {
        executeAndClose(client, request);
    }

    @Override
    public AccessControlList getObjectAcl(String bucketName, String key) {
        ObjectRequest request = new S3ObjectRequest(Method.GET, bucketName, key, "acl");
        return executeRequest(client, request, AccessControlList.class);
    }

    @Override
    public ListMultipartUploadsResult listMultipartUploads(String bucketName) {
        return listMultipartUploads(new ListMultipartUploadsRequest(bucketName));
    }

    @Override
    public ListMultipartUploadsResult listMultipartUploads(ListMultipartUploadsRequest request) {
        return executeRequest(client, request, ListMultipartUploadsResult.class);
    }

    @Override
    public String initiateMultipartUpload(String bucketName, String key) {
        return initiateMultipartUpload(new InitiateMultipartUploadRequest(bucketName, key)).getUploadId();
    }

    @Override
    public InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest request) {
        return executeRequest(client, request, InitiateMultipartUploadResult.class);
    }

    @Override
    public ListPartsResult listParts(String bucketName, String key, String uploadId) {
        return listParts(new ListPartsRequest(bucketName, key, uploadId));
    }

    @Override
    public ListPartsResult listParts(ListPartsRequest request) {
        return executeRequest(client, request, ListPartsResult.class);
    }

    @Override
    public MultipartPartETag uploadPart(UploadPartRequest request) {
        return new MultipartPartETag(request.getPartNumber(), executeAndClose(client, request).getEntityTag().getValue());
    }

    @Override
    public CopyPartResult copyPart(CopyPartRequest request) {
        CopyPartResult result = executeRequest(client, request, CopyPartResult.class);
        result.setPartNumber(request.getPartNumber());
        return result;
    }

    @Override
    public CompleteMultipartUploadResult completeMultipartUpload(CompleteMultipartUploadRequest request) {
        return executeRequest(client, request, CompleteMultipartUploadResult.class);
    }

    @Override
    public void abortMultipartUpload(AbortMultipartUploadRequest request) {
        executeAndClose(client, request);
    }

    @Override
    protected <T> T executeRequest(Client client, ObjectRequest request, Class<T> responseType) {
        ClientResponse response = executeRequest(client, request);
        try {
            T responseEntity = response.getEntity(responseType);
            fillResponseEntity(responseEntity, response);
            return responseEntity;
        } catch (ClientHandlerException e) {

            // some S3 responses return a 200 right away, but may fail and include an error XML package instead of the
            // expected entity. check for that here.
            try {
                throw ErrorFilter.parseErrorResponse(new StringReader(response.getEntity(String.class)), response.getStatus());
            } catch (Throwable t) {

                // must be a reader error
                throw e;
            }
        }
    }
}