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
package com.emc.object.s3.request;

import com.emc.object.Method;
import com.emc.object.s3.S3Constants;
import com.emc.object.s3.S3ObjectMetadata;
import com.emc.object.s3.bean.AccessControlList;
import com.emc.object.s3.bean.CannedAcl;
import com.emc.object.util.RestUtil;

import java.util.Date;
import java.util.List;
import java.util.Map;

public class CopyObjectRequest extends S3ObjectRequest {
    private String sourceBucketName;
    private String sourceKey;
    private String sourceVersionId;

    private Date ifModifiedSince;
    private Date ifUnmodifiedSince;
    private String ifMatch;
    private String ifNoneMatch;

    private S3ObjectMetadata objectMetadata;
    private AccessControlList acl;
    private CannedAcl cannedAcl;

    public CopyObjectRequest(String sourceBucketName, String sourceKey, String bucketName, String key) {
        super(Method.PUT, bucketName, key, null);
        this.sourceBucketName = sourceBucketName;
        this.sourceKey = sourceKey;
    }

    @Override
    public Map<String, List<Object>> getHeaders() {
        Map<String, List<Object>> headers = super.getHeaders();

        String source = String.format("/%s/%s", RestUtil.urlEncode(sourceBucketName), RestUtil.urlEncode(sourceKey));
        if (sourceVersionId != null) source += "?versionId=" + sourceVersionId;
        RestUtil.putSingle(headers, S3Constants.AMZ_COPY_SOURCE, source);

        if (ifModifiedSince != null)
            RestUtil.putSingle(headers, S3Constants.AMZ_SOURCE_MODIFIED_SINCE, ifModifiedSince);
        if (ifUnmodifiedSince != null)
            RestUtil.putSingle(headers, S3Constants.AMZ_SOURCE_UNMODIFIED_SINCE, ifUnmodifiedSince);
        if (ifMatch != null) RestUtil.putSingle(headers, S3Constants.AMZ_SOURCE_MATCH, ifMatch);
        if (ifNoneMatch != null) RestUtil.putSingle(headers, S3Constants.AMZ_SOURCE_NONE_MATCH, ifNoneMatch);
        if (objectMetadata != null) {
            RestUtil.putSingle(headers, S3Constants.AMZ_METADATA_DIRECTIVE, "REPLACE");
            headers.putAll(objectMetadata.toHeaders());
        }
        if (acl != null) headers.putAll(acl.toHeaders());
        if (cannedAcl != null) RestUtil.putSingle(headers, S3Constants.AMZ_ACL, cannedAcl.getHeaderValue());
        return headers;
    }

    public String getSourceBucketName() {
        return sourceBucketName;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public String getSourceVersionId() {
        return sourceVersionId;
    }

    public void setSourceVersionId(String sourceVersionId) {
        this.sourceVersionId = sourceVersionId;
    }

    public Date getIfModifiedSince() {
        return ifModifiedSince;
    }

    public void setIfModifiedSince(Date ifModifiedSince) {
        this.ifModifiedSince = ifModifiedSince;
    }

    public Date getIfUnmodifiedSince() {
        return ifUnmodifiedSince;
    }

    public void setIfUnmodifiedSince(Date ifUnmodifiedSince) {
        this.ifUnmodifiedSince = ifUnmodifiedSince;
    }

    public String getIfMatch() {
        return ifMatch;
    }

    public void setIfMatch(String ifMatch) {
        this.ifMatch = ifMatch;
    }

    public String getIfNoneMatch() {
        return ifNoneMatch;
    }

    public void setIfNoneMatch(String ifNoneMatch) {
        this.ifNoneMatch = ifNoneMatch;
    }

    public S3ObjectMetadata getObjectMetadata() {
        return objectMetadata;
    }

    public void setObjectMetadata(S3ObjectMetadata objectMetadata) {
        this.objectMetadata = objectMetadata;
    }

    public AccessControlList getAcl() {
        return acl;
    }

    public void setAcl(AccessControlList acl) {
        this.acl = acl;
    }

    public CannedAcl getCannedAcl() {
        return cannedAcl;
    }

    public void setCannedAcl(CannedAcl cannedAcl) {
        this.cannedAcl = cannedAcl;
    }

    public CopyObjectRequest withSourceVersionId(String sourceVersionId) {
        setSourceVersionId(sourceVersionId);
        return this;
    }

    public CopyObjectRequest withIfModifiedSince(Date ifModifiedSince) {
        setIfModifiedSince(ifModifiedSince);
        return this;
    }

    public CopyObjectRequest withIfUnmodifiedSince(Date ifUnmodifiedSince) {
        setIfUnmodifiedSince(ifUnmodifiedSince);
        return this;
    }

    public CopyObjectRequest withIfMatch(String ifMatch) {
        setIfMatch(ifMatch);
        return this;
    }

    public CopyObjectRequest withIfNoneMatch(String ifNoneMatch) {
        setIfNoneMatch(ifNoneMatch);
        return this;
    }

    public CopyObjectRequest withObjectMetadata(S3ObjectMetadata objectMetadata) {
        setObjectMetadata(objectMetadata);
        return this;
    }

    public CopyObjectRequest withAcl(AccessControlList acl) {
        setAcl(acl);
        return this;
    }

    public CopyObjectRequest withCannedAcl(CannedAcl cannedAcl) {
        setCannedAcl(cannedAcl);
        return this;
    }
}
