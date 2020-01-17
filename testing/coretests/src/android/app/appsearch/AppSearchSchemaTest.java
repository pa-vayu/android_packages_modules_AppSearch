/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.appsearch;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import android.app.appsearch.AppSearchSchema.PropertyConfig;

import androidx.test.filters.SmallTest;

import com.google.android.icing.proto.IndexingConfig.TokenizerType;
import com.google.android.icing.proto.PropertyConfigProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.TermMatchType;

import org.junit.Test;

@SmallTest
public class AppSearchSchemaTest {
    @Test
    public void testGetProto_Email() {
        AppSearchSchema emailSchema = AppSearchSchema.newBuilder("Email")
                .addProperty(AppSearchSchema.newPropertyBuilder("subject")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).addProperty(AppSearchSchema.newPropertyBuilder("body")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).build();

        SchemaTypeConfigProto expectedEmailProto = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("Email")
                .addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("subject")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setIndexingConfig(
                                com.google.android.icing.proto.IndexingConfig.newBuilder()
                                        .setTokenizerType(TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        )
                ).addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("body")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setIndexingConfig(
                                com.google.android.icing.proto.IndexingConfig.newBuilder()
                                        .setTokenizerType(TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        )
                ).build();

        assertThat(emailSchema.getProto()).isEqualTo(expectedEmailProto);
    }

    @Test
    public void testGetProto_MusicRecording() {
        AppSearchSchema musicRecordingSchema = AppSearchSchema.newBuilder("MusicRecording")
                .addProperty(AppSearchSchema.newPropertyBuilder("artist")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_REPEATED)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).addProperty(AppSearchSchema.newPropertyBuilder("pubDate")
                        .setDataType(PropertyConfig.DATA_TYPE_INT64)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_NONE)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_NONE)
                        .build()
                ).build();

        SchemaTypeConfigProto expectedMusicRecordingProto = SchemaTypeConfigProto.newBuilder()
                .setSchemaType("MusicRecording")
                .addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("artist")
                        .setDataType(PropertyConfigProto.DataType.Code.STRING)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.REPEATED)
                        .setIndexingConfig(
                                com.google.android.icing.proto.IndexingConfig.newBuilder()
                                        .setTokenizerType(TokenizerType.Code.PLAIN)
                                        .setTermMatchType(TermMatchType.Code.PREFIX)
                        )
                ).addProperties(PropertyConfigProto.newBuilder()
                        .setPropertyName("pubDate")
                        .setDataType(PropertyConfigProto.DataType.Code.INT64)
                        .setCardinality(PropertyConfigProto.Cardinality.Code.OPTIONAL)
                        .setIndexingConfig(
                                com.google.android.icing.proto.IndexingConfig.newBuilder()
                                        .setTokenizerType(TokenizerType.Code.NONE)
                                        .setTermMatchType(TermMatchType.Code.UNKNOWN)
                        )
                ).build();

        assertThat(musicRecordingSchema.getProto()).isEqualTo(expectedMusicRecordingProto);
    }

    @Test
    public void testInvalidEnums() {
        PropertyConfig.Builder builder = AppSearchSchema.newPropertyBuilder("test");
        assertThrows(IllegalArgumentException.class, () -> builder.setDataType(99));
        assertThrows(IllegalArgumentException.class, () -> builder.setCardinality(99));
    }

    @Test
    public void testMissingFields() {
        PropertyConfig.Builder builder = AppSearchSchema.newPropertyBuilder("test");
        Exception e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: dataType");

        builder.setDataType(PropertyConfig.DATA_TYPE_DOCUMENT);
        e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: schemaType");

        builder.setSchemaType("TestType");
        e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Missing field: cardinality");

        builder.setCardinality(PropertyConfig.CARDINALITY_REPEATED);
        builder.build();
    }

    @Test
    public void testDuplicateProperties() {
        AppSearchSchema.Builder builder = AppSearchSchema.newBuilder("Email")
                .addProperty(AppSearchSchema.newPropertyBuilder("subject")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                ).addProperty(AppSearchSchema.newPropertyBuilder("subject")
                        .setDataType(PropertyConfig.DATA_TYPE_STRING)
                        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
                        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
                        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
                        .build()
                );

        Exception e = expectThrows(IllegalSchemaException.class, builder::build);
        assertThat(e).hasMessageThat().contains("Property defined more than once: subject");
    }
}
