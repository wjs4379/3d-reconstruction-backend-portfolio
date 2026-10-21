package com.hanyang.backend.domain.media

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Path

@Configuration
class MediaConfiguration {
    @Bean fun mediaStorage(
        @Value("\${app.media.mode:local}") mode: String,
        @Value("\${app.media.local-directory:./.local-media}") directory: String,
        @Value("\${app.media.s3-bucket:}") bucket: String,
        @Value("\${app.media.s3-region:ap-northeast-2}") region: String
    ): MediaStorage = when (mode) {
        "local" -> LocalMediaStorage(Path.of(directory))
        "s3" -> S3MediaStorage(bucket,region)
        else -> error("MEDIA_MODE must be local or s3")
    }
}
