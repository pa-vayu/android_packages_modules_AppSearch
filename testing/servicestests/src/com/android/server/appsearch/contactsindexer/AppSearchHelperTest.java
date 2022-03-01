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

import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetSchemaResponse;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaRequest;
import android.content.Context;
import android.util.ArraySet;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import com.google.common.collect.ImmutableSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

// Since AppSearchHelper mainly just calls AppSearch's api to index/remove files, we shouldn't
// worry too much about it since AppSearch has good test coverage. Here just add some simple checks.
public class AppSearchHelperTest {
    private Context mContext;
    private AppSearchHelper mAppSearchHelper;

    @Before
    public void setUp() throws Exception {
        mContext = ApplicationProvider.getApplicationContext();
        mAppSearchHelper = AppSearchHelper.createAppSearchHelper(mContext, Runnable::run);
    }

    @After
    public void tearDown() throws Exception {
        AppSearchSession session = mAppSearchHelper.getSession();
        SetSchemaRequest request = new SetSchemaRequest.Builder().setForceOverride(true).build();
        session.setSchema(request, Runnable::run, Runnable::run, result -> {
            if (!result.isSuccess()) {
                throw new RuntimeException("Failed to wipe the test data in AppSearch");
            }
        });
    }

    @Test
    public void testAppSearchHelper_permissionIsSetCorrectlyForPerson() throws Exception {
        // TODO(b/203605504) We can create AppSearchHelper in the test itself so make things more
        //  clear.
        AppSearchSession session = mAppSearchHelper.getSession();
        CompletableFuture<AppSearchResult<GetSchemaResponse>> responseFuture =
                new CompletableFuture<>();

        // TODO(b/203605504) Considering using AppSearchShim, which is our test utility that
        //  glues AppSearchSession to the Future API
        session.getSchema(Runnable::run, responseFuture::complete);

        AppSearchResult<GetSchemaResponse> result = responseFuture.get();
        assertThat(result.isSuccess()).isTrue();
        GetSchemaResponse response = result.getResultValue();
        assertThat(response.getRequiredPermissionsForSchemaTypeVisibility()).hasSize(2);
        assertThat(response.getRequiredPermissionsForSchemaTypeVisibility()).containsKey(
                ContactPoint.SCHEMA_TYPE);
        assertThat(response.getRequiredPermissionsForSchemaTypeVisibility()).containsEntry(
                Person.SCHEMA_TYPE,
                ImmutableSet.of(ImmutableSet.of(SetSchemaRequest.READ_CONTACTS)));
    }

    @Test
    public void testIndexContacts() throws Exception {
        int contactsExisted = 10;
        int contactsDeleted = 10;
        Person[] contactData = new FakeContactsProvider(mContext.getResources(),
                contactsExisted, contactsExisted + contactsDeleted).getAllContactData();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= contactsExisted; ++i) {
            ids.add(String.valueOf(i));
        }

        mAppSearchHelper.indexContactsAsync(Arrays.asList(contactData)).get();

        AppSearchBatchResult<String, GenericDocument> result = TestUtils.getDocsByIdAsync(
                mAppSearchHelper.getSession(),
                ids, Runnable::run).get();
        assertThat(result.getSuccesses()).hasSize(contactsExisted);
        for (int i = 1; i <= contactsExisted; ++i) {
            String contactId = String.valueOf(i);
            TestUtils.assertEquals(new Person(result.getSuccesses().get(contactId)),
                    contactData[i - 1]);
        }
    }

    @Test
    public void testIndexContacts_clearAfterIndex() throws Exception {
        int numAvailableContacts = 50;
        List<Person> contacts = new ArrayList<>(Arrays.asList(
                new FakeContactsProvider(mContext.getResources(), numAvailableContacts,
                        /*contactsTotal=*/ numAvailableContacts).getAllContactData()));

        CompletableFuture<Void> indexContactsFuture = mAppSearchHelper.indexContactsAsync(contacts);
        contacts.clear();
        indexContactsFuture.get();

        Set<String> appSearchIds = TestUtils.getDocIdsByQuery(mAppSearchHelper.getSession(),
                /*query=*/ "", new SearchSpec.Builder().build(), Runnable::run);
        assertThat(appSearchIds.size()).isEqualTo(numAvailableContacts);

    }

    @Test
    public void testAppSearchHelper_removeContacts() throws Exception {
        int contactsExisted = 10;
        int contactsDeleted = 10;
        Person[] contactData = new FakeContactsProvider(mContext.getResources(),
                contactsExisted, contactsExisted + contactsDeleted).getAllContactData();
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= contactsExisted; ++i) {
            ids.add(String.valueOf(i));
        }
        mAppSearchHelper.indexContactsAsync(Arrays.asList(contactData)).get();
        AppSearchBatchResult<String, GenericDocument> resultBeforeRemove =
                TestUtils.getDocsByIdAsync(mAppSearchHelper.getSession(), ids, Runnable::run).get();

        mAppSearchHelper.removeContactsByIdAsync(ids).get();

        AppSearchBatchResult<String, GenericDocument> resultAfterRemove =
                TestUtils.getDocsByIdAsync(mAppSearchHelper.getSession(), ids, Runnable::run).get();
        assertThat(resultBeforeRemove.getSuccesses()).hasSize(contactsExisted);
        assertThat(resultAfterRemove.getSuccesses()).hasSize(0);
    }
}
