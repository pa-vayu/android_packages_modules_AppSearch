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

import android.annotation.UserIdInt;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.util.Log;

import com.android.server.LocalManagerRegistry;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ContactsIndexerMaintenanceService extends JobService {
    private static final String TAG = "ContactsIndexerMaintenanceService";

    /**
     * Generate job ids in the range (MIN_INDEXER_JOB_ID, MAX_INDEXER_JOB_ID) to avoid conflicts
     * with other jobs scheduled by the system service. The range corresponds to 21475 job ids,
     * which is the maximum number of user ids in the system.
     *
     * @see UserManagerService.MAX_USER_ID
     */
    public static final int MIN_INDEXER_JOB_ID = 16942831; // corresponds to ag/16942831
    private static final int MAX_INDEXER_JOB_ID = 16964306; // 16942831 + 21475

    private static final String EXTRA_USER_ID = "user_id";

    private static final Executor EXECUTOR = new ThreadPoolExecutor(/*corePoolSize=*/ 1,
            /*maximumPoolSize=*/ 1, /*keepAliveTime=*/ 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());

    private CancellationSignal mSignal;

    /**
     * Schedules a full update job for the given device-user.
     */
    static void scheduleFullUpdateJob(Context context, @UserIdInt int userId) {
        int jobId = MIN_INDEXER_JOB_ID + userId;
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        ComponentName component =
                new ComponentName(context, ContactsIndexerMaintenanceService.class);
        final Bundle extras = new Bundle();
        extras.putInt(EXTRA_USER_ID, userId);
        JobInfo jobInfo =
                new JobInfo.Builder(jobId, component)
                        .setTransientExtras(extras)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build();
        jobScheduler.schedule(jobInfo);
        Log.v(TAG, "Scheduled full update job " + jobId + " for user " + userId);
    }

    static void cancelFullUpdateJob(Context context, @UserIdInt int userId) {
        int jobId = MIN_INDEXER_JOB_ID + userId;
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(jobId);
        Log.v(TAG, "Canceled full update job " + jobId + " for user " + userId);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int userId = params.getTransientExtras().getInt(EXTRA_USER_ID, /*defaultValue=*/ -1);
        if (userId == -1) {
            return false;
        }

        Log.v(TAG, "Full update job started for user " + userId);
        mSignal = new CancellationSignal();
        EXECUTOR.execute(() -> {
            ContactsIndexerManagerService.LocalService service =
                    LocalManagerRegistry.getManager(
                            ContactsIndexerManagerService.LocalService.class);
            service.doFullUpdateForUser(userId, mSignal);
            jobFinished(params, mSignal.isCanceled());
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mSignal != null) {
            mSignal.cancel();
        }
        return false;
    }
}
