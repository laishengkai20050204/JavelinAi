package com.example.storage;

import com.example.storage.impl.MinioStorageService;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageServiceTest.class);

    @Mock
    private MinioClient client;

    private MinioStorageService service;

    @BeforeEach
    void setUp() {
        MinioProps props = new MinioProps();
        props.setDefaultBucket("default");
        service = new MinioStorageService(client, props);
        log.info("MinioStorageService initialized with default bucket '{}'", props.getDefaultBucket());
    }

    @Test
    void ensureBucketCreatesBucketWhenMissing() throws Exception {
        String bucket = "target-bucket";
        log.info("Verifying ensureBucket creates bucket '{}'", bucket);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        StepVerifier.create(service.ensureBucket(bucket))
                .verifyComplete();

        verify(client).bucketExists(any(BucketExistsArgs.class));
        ArgumentCaptor<MakeBucketArgs> makeCaptor = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(client).makeBucket(makeCaptor.capture());
        assertEquals("target-bucket", makeCaptor.getValue().bucket());
    }

    @Test
    void ensureBucketSkipsCreationIfExists() throws Exception {
        String bucket = "existing";
        log.info("Verifying ensureBucket skips existing bucket '{}'", bucket);
        when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        StepVerifier.create(service.ensureBucket(bucket))
                .verifyComplete();

        verify(client, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    void uploadFileUploadsExistingFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, "hello world".getBytes(StandardCharsets.UTF_8));
        log.info("Uploading local file '{}' to bucket '{}' with key '{}'", file.getFileName(), "bucket", "object-key");

        StepVerifier.create(service.uploadFile("bucket", "object-key", file))
                .expectNext("object-key")
                .verifyComplete();

        ArgumentCaptor<UploadObjectArgs> captor = ArgumentCaptor.forClass(UploadObjectArgs.class);
        verify(client).uploadObject(captor.capture());
        UploadObjectArgs args = captor.getValue();
        assertEquals("bucket", args.bucket());
        assertEquals("object-key", args.object());
        assertEquals(file.toString(), args.filename());
    }

    @Test
    void uploadFileEmitsErrorWhenFileMissing() throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        Path missing = Path.of("does-not-exist.txt");
        log.info("Expecting upload failure for missing file '{}' in bucket '{}'", missing, "bucket");

        StepVerifier.create(service.uploadFile("bucket", "key", missing))
                .expectErrorSatisfies(err -> {
                    assertTrue(err instanceof FileNotFoundException);
                    assertTrue(err.getMessage().contains("does-not-exist.txt"));
                })
                .verify();

        verify(client, never()).uploadObject(any(UploadObjectArgs.class));
    }

    @Test
    void uploadBytesDelegatesToMinio() throws Exception {
        byte[] payload = "plain text".getBytes(StandardCharsets.UTF_8);
        log.info("Uploading byte[] to bucket '{}' with object '{}'", "bucket", "bytes-key");

        StepVerifier.create(service.uploadBytes("bucket", "bytes-key", payload, "data.txt"))
                .expectNext("bytes-key")
                .verifyComplete();

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertEquals("bucket", args.bucket());
        assertEquals("bytes-key", args.object());
        assertEquals("text/plain", args.contentType());
    }

    @Test
    void deleteObjectDelegatesToClient() throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Deleting object '{}' from bucket '{}'", "object", "bucket");
        StepVerifier.create(service.deleteObject("bucket", "object"))
                .verifyComplete();

        verify(client).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void existsReturnsTrueWhenStatSucceeds() throws Exception {
        log.info("Checking existence for object '{}' in bucket '{}'", "object", "bucket");
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));

        StepVerifier.create(service.exists("bucket", "object"))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void existsReturnsFalseWhenNoSuchKey() throws Exception {
        ErrorResponseException ex = mock(ErrorResponseException.class);
        ErrorResponse error = mock(ErrorResponse.class);
        when(error.code()).thenReturn("NoSuchKey");
        when(ex.errorResponse()).thenReturn(error);
        when(client.statObject(any(StatObjectArgs.class))).thenThrow(ex);
        log.info("Simulating missing object '{}' in bucket '{}'", "missing", "bucket");

        StepVerifier.create(service.exists("bucket", "missing"))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void presignGetClampsExpiryToSevenDays() throws Exception {
        log.info("Requesting presigned GET for object '{}' in bucket '{}'", "object", "bucket");
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("signed-url");

        StepVerifier.create(service.presignGet("bucket", "object", Duration.ofDays(10)))
                .expectNext("signed-url")
                .verifyComplete();

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals(Method.GET, args.method());
        assertEquals(7 * 24 * 60 * 60, args.expiry());
    }

    @Test
    void presignPutClampsExpiryToAtLeastOneSecond() throws Exception {
        log.info("Requesting presigned PUT for object '{}' in bucket '{}'", "object", "bucket");
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("put-url");

        StepVerifier.create(service.presignPut("bucket", "object", Duration.ZERO))
                .expectNext("put-url")
                .verifyComplete();

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(client).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals(Method.PUT, args.method());
        assertEquals(1, args.expiry());
    }
}
