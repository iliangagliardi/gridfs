package com.mongodb.demo.gridfs.config;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;

/**
 * Exposes the raw driver {@link GridFSBucket}.
 *
 * <p>Spring Boot auto-configures a {@code GridFsTemplate}, which is convenient
 * for storing and for {@code find}, but it deliberately hides the driver's
 * {@code GridFSDownloadStream} behind a plain {@code InputStream}. The seekable
 * streaming that makes this demo interesting lives on that concrete type
 * ({@code skip(long)} performs a chunk-level jump), so we take the bucket
 * directly and keep full control.
 */
@Configuration
public class MongoConfig {

    /**
     * Bucket name, matching {@code spring.data.mongodb.gridfs.bucket}. The
     * bucket name is the prefix of the two backing collections, so "fs" means
     * {@code fs.files} and {@code fs.chunks}.
     */
    @Bean
    public GridFSBucket gridFsBucket(MongoDatabaseFactory factory,
                                     @Value("${spring.data.mongodb.gridfs.bucket:fs}") String bucketName) {
        return GridFSBuckets.create(factory.getMongoDatabase(), bucketName);
    }
}
