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

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchSession;
import android.app.appsearch.BatchResultCallback;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GetByDocumentIdRequest;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;
import android.util.ArraySet;

import com.android.server.appsearch.contactsindexer.appsearchtypes.ContactPoint;
import com.android.server.appsearch.contactsindexer.appsearchtypes.Person;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

class TestUtils {
    @NonNull
    public static CompletableFuture<AppSearchBatchResult> getDocsByIdAsync(
            @NonNull AppSearchSession session, @NonNull Collection<String> ids,
            @NonNull Executor executor) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(ids);
        Objects.requireNonNull(executor);

        CompletableFuture<AppSearchBatchResult> future = new CompletableFuture<>();
        GetByDocumentIdRequest request = new GetByDocumentIdRequest.Builder(
                AppSearchHelper.NAMESPACE_NAME).addIds(ids).build();
        session.getByDocumentId(request, executor,
                new BatchResultCallback<String, GenericDocument>() {
                    @Override
                    public void onResult(AppSearchBatchResult<String, GenericDocument> result) {
                        future.complete(result);
                    }

                    @Override
                    public void onSystemError(Throwable throwable) {
                        future.completeExceptionally(throwable);
                    }
                });

        return future;
    }

    @NonNull
    public static CompletableFuture<List<SearchResult>> getNextPageAsync(
            @NonNull AppSearchSession session,
            @NonNull SearchResults searchResults,
            @NonNull Executor executor) {
        Objects.requireNonNull(searchResults);
        Objects.requireNonNull(executor);

        CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
        searchResults.getNextPage(executor, result -> {
            if (result.isSuccess()) {
                future.complete(result.getResultValue());
            } else {
                future.completeExceptionally(
                        new AppSearchException(result.getResultCode(), result.getErrorMessage()));
            }
        });

        return future;
    }

    @NonNull
    public static Set<String> getDocIdsByQuery(@NonNull AppSearchSession session,
            @NonNull String query,
            @NonNull SearchSpec spec,
            @NonNull Executor executor) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(query);
        Objects.requireNonNull(spec);
        Objects.requireNonNull(executor);

        SearchResults results = session.search(query, spec);
        Set<String> allIds = new ArraySet<>();
        try {
            while (true) {
                List<SearchResult> docs = getNextPageAsync(session, results, executor).get();
                if (docs.isEmpty()) {
                    break;
                }
                for (int i = 0; i < docs.size(); ++i) {
                    allIds.add(docs.get(i).getGenericDocument().getId());
                }
            }
        } catch (InterruptedException | ExecutionException | IllegalStateException e) {
            // do nothing.
        }

        return allIds;
    }

    static void assertEquals(@NonNull ContactPoint actual,
            @NonNull ContactPoint expected) {
        Objects.requireNonNull(actual);
        Objects.requireNonNull(expected);

        if (actual == expected) {
            return;
        }

        // TODO(b/203605504) use toBuilder to reset creationTimestamp so we can directly compare
        //  two GenericDocuments. This way, we won't miss adding any new properties in the future.
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getLabel()).isEqualTo(expected.getLabel());
        assertThat(actual.getAppIds()).isEqualTo(expected.getAppIds());
        assertThat(actual.getEmails()).isEqualTo(expected.getEmails());
        assertThat(actual.getAddresses()).isEqualTo(expected.getAddresses());
        assertThat(actual.getPhones()).isEqualTo(expected.getPhones());
    }

    static void assertEquals(@NonNull Person actual, @NonNull Person expected) {
        Objects.requireNonNull(actual);
        Objects.requireNonNull(expected);

        if (actual == expected) {
            return;
        }

        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getName()).isEqualTo(expected.getName());
        assertThat(actual.getGivenName()).isEqualTo(expected.getGivenName());
        assertThat(actual.getMiddleName()).isEqualTo(expected.getMiddleName());
        assertThat(actual.getFamilyName()).isEqualTo(expected.getFamilyName());
        assertThat(actual.getExternalUri()).isEqualTo(expected.getExternalUri());
        assertThat(actual.getImageUri()).isEqualTo(expected.getImageUri());
        assertThat(actual.isImportant()).isEqualTo(expected.isImportant());
        assertThat(actual.isBot()).isEqualTo(expected.isBot());
        assertThat(actual.getAdditionalNames()).isEqualTo(expected.getAdditionalNames());
        assertThat(actual.getAffiliations()).isEqualTo(expected.getAffiliations());
        assertThat(actual.getRelations()).isEqualTo(expected.getRelations());
        // TODO(b/203605504) use toBuilder to reset creationTimestamp so we can directly compare
        //  two GenericDocuments. This way, we won't miss adding any new properties in the future.
        // Compare two contact point arrays. We can't directly use assert(genericDoc1).isEqualTo
        // (genericDoc2) since the creationTimestamps are different, and they can't easily be
        // reset to 0.
        ContactPoint[] contactPointsActual = actual.getContactPoints();
        ContactPoint[] contactPointsExpected = expected.getContactPoints();
        assertThat(contactPointsActual.length).isEqualTo(contactPointsExpected.length);
        for (int i = 0; i < contactPointsActual.length; ++i) {
            assertEquals(contactPointsActual[i], contactPointsExpected[i]);
        }
    }
}
