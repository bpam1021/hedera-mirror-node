package com.hedera.mirror.importer.downloader.record;

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

import io.micrometer.core.instrument.MeterRegistry;
import javax.inject.Named;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import com.hedera.mirror.common.domain.transaction.RecordFile;
import com.hedera.mirror.importer.addressbook.AddressBookService;
import com.hedera.mirror.importer.config.MirrorDateRangePropertiesProcessor;
import com.hedera.mirror.importer.downloader.Downloader;
import com.hedera.mirror.importer.downloader.NodeSignatureVerifier;
import com.hedera.mirror.importer.downloader.StreamFileNotifier;
import com.hedera.mirror.importer.leader.Leader;
import com.hedera.mirror.importer.reader.record.ProtoRecordFileReader;
import com.hedera.mirror.importer.reader.record.RecordFileReader;
import com.hedera.mirror.importer.reader.signature.SignatureFileReader;

@Named
public class RecordFileDownloader extends Downloader<RecordFile> {

    public RecordFileDownloader(
            S3AsyncClient s3Client, AddressBookService addressBookService,
            RecordDownloaderProperties downloaderProperties,
            MeterRegistry meterRegistry, NodeSignatureVerifier nodeSignatureVerifier,
            SignatureFileReader signatureFileReader, RecordFileReader recordFileReader,
            StreamFileNotifier streamFileNotifier,
            MirrorDateRangePropertiesProcessor mirrorDateRangePropertiesProcessor) {
        super(s3Client, addressBookService, downloaderProperties, meterRegistry,
                nodeSignatureVerifier, signatureFileReader, recordFileReader, streamFileNotifier,
                mirrorDateRangePropertiesProcessor);
    }

    @Override
    @Leader
    @Scheduled(fixedDelayString = "#{@recordDownloaderProperties.getFrequency().toMillis()}")
    public void download() {
        downloadNextBatch();
    }

    @Override
    protected void setStreamFileIndex(RecordFile recordFile) {
        // Starting from the record stream file v6, the record file index is externalized as the block_number field of
        // the protobuf RecordStreamFile, so only set the record file index to be last + 1 if it's pre-v6.
        if (recordFile.getVersion() < ProtoRecordFileReader.VERSION) {
            super.setStreamFileIndex(recordFile);
        }
    }
}
