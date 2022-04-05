/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.test.ProviderTestCase2;
import android.util.ArraySet;

import com.android.server.appsearch.contactsindexer.FakeContactsProvider;

import java.util.Set;

// TODO(b/203605504) this is a junit3 test but we should use junit4. Right now I can't make
//  ProviderTestRule work so we stick to ProviderTestCase2 for now.
public class ContactsProviderUtilTest extends ProviderTestCase2<FakeContactsProvider> {
    public ContactsProviderUtilTest() {
        super(FakeContactsProvider.class, FakeContactsProvider.AUTHORITY);
    }

    public void setUp() throws Exception {
        super.setUp();
        mContext = mock(Context.class);
        doReturn(getMockContentResolver()).when(mContext).getContentResolver();
    }

    public void testGetUpdatedContactIds_getAll() {
        long idAsTimestamp = 0L;
        Set<String> expected = new ArraySet<>();
        for (long i = 1L; i <= FakeContactsProvider.NUM_EXISTED_CONTACTS; ++i) {
            expected.add(String.valueOf(i));
        }

        Set<String> ids = new ArraySet<>();
        long lastUpdatedTime = ContactsProviderUtil.getUpdatedContactIds(mContext, idAsTimestamp,
                ids);

        assertThat(lastUpdatedTime).isEqualTo(FakeContactsProvider.NUM_EXISTED_CONTACTS);
        assertThat(ids).isEqualTo(expected);
    }

    public void testGetUpdatedContactIds_getNone() {
        long idAsTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS + 1;

        Set<String> ids = new ArraySet<>();
        long lastUpdatedTime = ContactsProviderUtil.getUpdatedContactIds(mContext,
                idAsTimestamp,
                ids);

        assertThat(lastUpdatedTime).isEqualTo(idAsTimestamp);
        assertThat(ids).isEmpty();
    }

    public void testGetUpdatedContactIds() {
        int expectedUpdatedNum = FakeContactsProvider.NUM_EXISTED_CONTACTS / 2;
        long idAsTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS - expectedUpdatedNum;
        Set<String> expected = new ArraySet<>();
        for (long i = expectedUpdatedNum + 1; i <= FakeContactsProvider.NUM_EXISTED_CONTACTS; ++i) {
            expected.add(String.valueOf(i));
        }

        Set<String> ids = new ArraySet<>();
        long lastUpdatedTime = ContactsProviderUtil.getUpdatedContactIds(mContext, idAsTimestamp,
                ids);

        assertThat(lastUpdatedTime).isEqualTo(FakeContactsProvider.NUM_EXISTED_CONTACTS);
        assertThat(ids).isEqualTo(expected);
    }

    public void testGetDeletedContactIds_deleteNone() {
        long idAsTimestamp = FakeContactsProvider.NUM_TOTAL_CONTACTS + 1;

        Set<String> ids = new ArraySet<>();
        long lastDeleteTime = ContactsProviderUtil.getDeletedContactIds(mContext, idAsTimestamp,
                ids);

        assertThat(lastDeleteTime).isEqualTo(idAsTimestamp);
        assertThat(ids).isEmpty();
    }

    public void testGetDeletedContactIds_deleteAll() {
        long idAsTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS / 2;
        Set<String> expectedDeleted = new ArraySet<>();
        for (long i = FakeContactsProvider.NUM_EXISTED_CONTACTS + 1;
                i <= FakeContactsProvider.NUM_TOTAL_CONTACTS; ++i) {
            expectedDeleted.add(String.valueOf(i));
        }

        Set<String> ids = new ArraySet<>();
        long lastDeleteTime = ContactsProviderUtil.getDeletedContactIds(mContext, idAsTimestamp,
                ids);

        assertThat(lastDeleteTime).isEqualTo(FakeContactsProvider.NUM_TOTAL_CONTACTS);
        assertThat(ids).isEqualTo(expectedDeleted);
    }

    public void testGetDeletedContactIds() {
        long idAsTimestamp = FakeContactsProvider.NUM_EXISTED_CONTACTS +
                (FakeContactsProvider.NUM_TOTAL_CONTACTS
                        - FakeContactsProvider.NUM_EXISTED_CONTACTS) / 2;
        Set<String> expectedDeleted = new ArraySet<>();
        for (long i = idAsTimestamp + 1; i <= FakeContactsProvider.NUM_TOTAL_CONTACTS; ++i) {
            expectedDeleted.add(String.valueOf(i));
        }

        Set<String> ids = new ArraySet<>();
        long lastDeletedTime = ContactsProviderUtil.getDeletedContactIds(mContext, idAsTimestamp,
                ids);

        assertThat(lastDeletedTime).isEqualTo(FakeContactsProvider.NUM_TOTAL_CONTACTS);
        assertThat(ids).isEqualTo(expectedDeleted);
    }
}
