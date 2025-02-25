package com.hedera.mirror.importer.domain;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2022 Hedera Hashgraph, LLC
 * ​
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
 * ‍
 */

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.hedera.mirror.importer.exception.FileOperationException;
import com.hedera.mirror.importer.exception.InvalidStreamFileException;

@Log4j2
@Value
public class StreamFileData {

    private static final CompressorStreamFactory compressorStreamFactory = new CompressorStreamFactory(true);

    private final StreamFilename streamFilename;
    private final byte[] bytes;
    @Getter(lazy = true)
    private final byte[] decompressedBytes = decompressBytes();

    public static StreamFileData from(@NonNull File file) {
        try {
            byte[] bytes = FileUtils.readFileToByteArray(file);
            return new StreamFileData(new StreamFilename(file.getName()), bytes);
        } catch (InvalidStreamFileException e) {
            throw e;
        } catch (Exception e) {
            throw new FileOperationException("Unable to read file to byte array", e);
        }
    }

    // Used for testing String based files like CSVs
    public static StreamFileData from(@NonNull String filename, @NonNull String contents) {
        return new StreamFileData(new StreamFilename(filename), contents.getBytes(StandardCharsets.UTF_8));
    }

    // Used for testing with raw bytes
    public static StreamFileData from(@NonNull String filename, byte[] bytes) {
        return new StreamFileData(new StreamFilename(filename), bytes);
    }

    public InputStream getInputStream() {
        return new ByteArrayInputStream(getDecompressedBytes());
    }

    public Instant getInstant() {
        return streamFilename.getInstant();
    }

    public String getFilename() {
        return streamFilename.getFilename();
    }

    @Override
    public String toString() {
        return streamFilename.toString();
    }

    private byte[] decompressBytes() {
        var compressor = streamFilename.getCompressor();
        if (StringUtils.isBlank(compressor)) {
            return bytes;
        }

        try (var inputStream = new ByteArrayInputStream(bytes);
             var compressorInputStream = compressorStreamFactory.createCompressorInputStream(compressor, inputStream)) {
            return compressorInputStream.readAllBytes();
        } catch (CompressorException | IOException e) {
            var filename = streamFilename.getFilename();
            log.error("Failed to decompress stream file {}", filename);
            throw new InvalidStreamFileException(filename, e);
        }
    }
}
