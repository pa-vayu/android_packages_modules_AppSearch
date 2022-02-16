/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch.contactsindexer;


import org.junit.Test;
import static com.google.common.truth.Truth.assertThat;

public class PersistedDataTest {
    @Test
    public void testPersistedData_toAndFromString() {
        PerUserContactsIndexer.PersistedData persistedData =
                new PerUserContactsIndexer.PersistedData();
        PerUserContactsIndexer.PersistedData persistedDataCopy =
                new PerUserContactsIndexer.PersistedData();
        persistedData.mLastDeltaUpdateTimestampMillis = 3;
        persistedData.mLastDeltaUpdateTimestampMillis = 5;

        persistedDataCopy.fromString(persistedData.toString());

        assertThat(persistedDataCopy.mLastDeltaUpdateTimestampMillis).isEqualTo(
                persistedData.mLastDeltaUpdateTimestampMillis);
        assertThat(persistedDataCopy.mLastDeltaDeleteTimestampMillis).isEqualTo(
                persistedData.mLastDeltaDeleteTimestampMillis);
    }
}
