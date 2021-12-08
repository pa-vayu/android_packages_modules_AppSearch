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

package com.android.server.appsearch.observer;

import android.annotation.NonNull;
import android.app.appsearch.aidl.IAppSearchObserverProxy;
import android.app.appsearch.observer.AppSearchObserverCallback;
import android.app.appsearch.observer.DocumentChangeInfo;
import android.app.appsearch.observer.SchemaChangeInfo;
import android.os.RemoteException;
import android.util.Log;

/**
 * A wrapper that adapts {@link android.app.appsearch.aidl.IAppSearchObserverProxy} to the
 * {@link android.app.appsearch.observer.AppSearchObserverCallback} interface.
 *
 * @hide
 */
public class AppSearchObserverProxy implements AppSearchObserverCallback {
    private static final String TAG = "AppSearchObserverProxy";

    private final IAppSearchObserverProxy mStub;

    public AppSearchObserverProxy(@NonNull IAppSearchObserverProxy stub) {
        mStub = stub;
    }

    @Override
    public void onSchemaChanged(@NonNull SchemaChangeInfo changeInfo) {
        try {
            mStub.onSchemaChanged(changeInfo.getPackageName(), changeInfo.getDatabaseName());
        } catch (RemoteException e) {
            onRemoteException(e);
        }
    }

    @Override
    public void onDocumentChanged(@NonNull DocumentChangeInfo changeInfo) {
        try {
            mStub.onDocumentChanged(
                    changeInfo.getPackageName(),
                    changeInfo.getDatabaseName(),
                    changeInfo.getNamespace(),
                    changeInfo.getSchemaName());
        } catch (RemoteException e) {
            onRemoteException(e);
        }
    }

    private void onRemoteException(RemoteException e) {
        Log.w(TAG, "AppSearchObserver failed to fire; stub disconnected", e);
        // TODO(b/193494000): Since the originating app has disconnected, unregister this observer
        //  from AppSearch.
    }
}
