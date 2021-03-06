/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.aws.core.io.s3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;
import com.amazonaws.util.BinaryUtils;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.WritableResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ExecutorServiceAdapter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/**
 * {@link org.springframework.core.io.Resource} implementation for {@code com.amazonaws.services.s3.model.S3Object}
 * handles. Implements the extended {@link WritableResource} interface.
 *
 * @author Agim Emruli
 * @author Alain Sahli
 * @since 1.0
 */
class SimpleStorageResource extends AbstractResource implements WritableResource {

	private final String bucketName;
	private final String objectName;
	private final AmazonS3 amazonS3;
	private final TaskExecutor taskExecutor;

	private ObjectMetadata objectMetadata;

	SimpleStorageResource(AmazonS3 amazonS3, String bucketName, String objectName, TaskExecutor taskExecutor) {
		this.bucketName = bucketName;
		this.objectName = objectName;
		this.amazonS3 = amazonS3;
		this.taskExecutor = taskExecutor;
		fetchObjectMetadata();
	}

	@Override
	public String getDescription() {
		StringBuilder builder = new StringBuilder("Amazon s3 resource [bucket='");
		builder.append(this.bucketName);
		builder.append("' and object='");
		builder.append(this.objectName);
		builder.append("']");
		return builder.toString();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this.amazonS3.getObject(this.bucketName, this.objectName).getObjectContent();
	}

	@Override
	public boolean exists() {
		return this.objectMetadata != null;
	}

	@Override
	public long contentLength() throws IOException {
		assertThatResourceExists();
		return this.objectMetadata.getContentLength();
	}

	@Override
	public long lastModified() throws IOException {
		assertThatResourceExists();
		return this.objectMetadata.getLastModified().getTime();
	}

	@Override
	public String getFilename() throws IllegalStateException {
		return this.objectName;
	}

	private void assertThatResourceExists() throws FileNotFoundException {
		if (this.objectMetadata == null) {
			throw new FileNotFoundException(new StringBuilder().
					append("Resource with bucket='").
					append(this.bucketName).
					append("' and objectName='").
					append(this.objectName).
					append("' not found!").
					toString());
		}
	}

	@Override
	public boolean isWritable() {
		return true;
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return new SimpleStorageOutputStream(this.amazonS3, this.taskExecutor, this.bucketName, this.objectName);
	}

	private void fetchObjectMetadata() {
		try {
			this.objectMetadata = this.amazonS3.getObjectMetadata(this.bucketName, this.objectName);
		} catch (AmazonS3Exception e) {
			if (e.getStatusCode() == 404) {
				this.objectMetadata = null;
			} else {
				throw e;
			}
		}
	}

	private static class SimpleStorageOutputStream extends OutputStream {

		// The minimum size for a multi part is 5 MB, hence the buffer size of 5 MB
		private static final int BUFFER_SIZE = 1024 * 1024 * 5;
		@SuppressWarnings("FieldMayBeFinal")
		private ByteArrayOutputStream currentOutputStream = new ByteArrayOutputStream(BUFFER_SIZE);
		private final Object monitor = new Object();
		private final AmazonS3 amazonS3;
		private final TaskExecutor taskExecutor;
		private final String bucketName;
		private final String objectName;
		private final CompletionService<UploadPartResult> completionService;
		private int partNumberCounter = 1;
		private InitiateMultipartUploadResult multiPartUploadResult;

		SimpleStorageOutputStream(AmazonS3 amazonS3, TaskExecutor taskExecutor, String bucketName, String objectName) {
			this.amazonS3 = amazonS3;
			this.taskExecutor = taskExecutor;
			this.bucketName = bucketName;
			this.objectName = objectName;
			this.completionService = new ExecutorCompletionService<>(new ExecutorServiceAdapter(this.taskExecutor));
		}

		@Override
		public void write(int b) throws IOException {
			synchronized (this.monitor) {
				if (this.currentOutputStream.size() == BUFFER_SIZE) {
					initiateMultiPartIfNeeded();
					this.completionService.submit(
							new UploadPartResultCallable(this.amazonS3, this.currentOutputStream.toByteArray(), this.currentOutputStream.size(), this.bucketName, this.objectName, this.multiPartUploadResult.getUploadId(), this.partNumberCounter++, false));
					this.currentOutputStream.reset();
				}
				this.currentOutputStream.write(b);
			}
		}

		@Override
		public void close() throws IOException {
			synchronized (this.monitor) {
				if (isMultiPartUpload()) {
					finishMultiPartUpload();
				} else {
					finishSimpleUpload();
				}
			}
		}

		private boolean isMultiPartUpload() {
			return this.multiPartUploadResult != null;
		}

		private void finishSimpleUpload() {
			ObjectMetadata objectMetadata = new ObjectMetadata();
			objectMetadata.setContentLength(this.currentOutputStream.size());

			byte[] content = this.currentOutputStream.toByteArray();
			try {
				MessageDigest messageDigest = MessageDigest.getInstance("MD5");
				String md5Digest = BinaryUtils.toBase64(messageDigest.digest(content));
				objectMetadata.setContentMD5(md5Digest);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException("MessageDigest could not be initialized because it uses an unknown algorithm", e);
			}

			this.amazonS3.putObject(this.bucketName, this.objectName,
					new ByteArrayInputStream(content), objectMetadata);

			//Release the memory early
			this.currentOutputStream = null;
		}

		private void finishMultiPartUpload() throws IOException {
			this.completionService.submit(new UploadPartResultCallable(this.amazonS3, this.currentOutputStream.toByteArray(), this.currentOutputStream.size(), this.bucketName, this.objectName, this.multiPartUploadResult.getUploadId(), this.partNumberCounter, true));
			try {
				List<PartETag> partETags = getMultiPartsUploadResults();
				this.amazonS3.completeMultipartUpload(new CompleteMultipartUploadRequest(this.multiPartUploadResult.getBucketName(),
						this.multiPartUploadResult.getKey(), this.multiPartUploadResult.getUploadId(), partETags));
			} catch (ExecutionException e) {
				abortMultiPartUpload();
				throw new IOException("Multi part upload failed ", e.getCause());
			} catch (InterruptedException e) {
				abortMultiPartUpload();
				Thread.currentThread().interrupt();
			} finally {
				this.currentOutputStream = null;
			}
		}

		private void initiateMultiPartIfNeeded() {
			if (this.multiPartUploadResult == null) {
				this.multiPartUploadResult = this.amazonS3.initiateMultipartUpload(
						new InitiateMultipartUploadRequest(this.bucketName, this.objectName));
			}
		}

		private void abortMultiPartUpload() {
			if (isMultiPartUpload()) {
				this.amazonS3.abortMultipartUpload(new AbortMultipartUploadRequest(this.multiPartUploadResult.getBucketName(),
						this.multiPartUploadResult.getKey(), this.multiPartUploadResult.getUploadId()));
			}
		}

		private List<PartETag> getMultiPartsUploadResults() throws ExecutionException, InterruptedException {
			List<PartETag> result = new ArrayList<>(this.partNumberCounter);
			for (int i = 0; i < this.partNumberCounter; i++) {
				Future<UploadPartResult> uploadPartResultFuture = this.completionService.take();
				result.add(uploadPartResultFuture.get().getPartETag());
			}
			return result;
		}

		private static class UploadPartResultCallable implements Callable<UploadPartResult> {

			private final AmazonS3 amazonS3;
			private final int contentLength;
			private final int partNumber;
			private final boolean last;
			private final String bucketName;
			private final String key;
			private final String uploadId;
			@SuppressWarnings("FieldMayBeFinal")
			private byte[] content;

			private UploadPartResultCallable(AmazonS3 amazon, byte[] content, int writtenDataSize, String bucketName, String key, String uploadId, int partNumber, boolean last) {
				this.amazonS3 = amazon;
				this.content = content;
				this.contentLength = writtenDataSize;
				this.partNumber = partNumber;
				this.last = last;
				this.bucketName = bucketName;
				this.key = key;
				this.uploadId = uploadId;
			}

			@Override
			public UploadPartResult call() throws Exception {
				try {
					return this.amazonS3.uploadPart(new UploadPartRequest().withBucketName(this.bucketName).
							withKey(this.key).
							withUploadId(this.uploadId).
							withInputStream(new ByteArrayInputStream(this.content)).
							withPartNumber(this.partNumber).
							withLastPart(this.last).
							withPartSize(this.contentLength));
				} finally {
					//Release the memory, as the callable may still live inside the CompletionService which would cause
					// an exhaustive memory usage
					this.content = null;
				}
			}
		}
	}
}