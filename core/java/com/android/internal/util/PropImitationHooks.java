/*
 * Copyright (C) 2022-2024 Paranoid Android
 *           (C) 2023 ArrowOS
 *           (C) 2023 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;

import sun.misc.Unsafe;

/**
 * @hide
 */
public class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = SystemProperties.getBoolean("debug.pihooks.log", false);

    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";

    private static volatile String sProcessName;

    /* Initialized on first use, so only processes that set props pay for the lookups */
    private static final class UnsafeHolder {
        static final Field OFFSET_FIELD;
        static final Unsafe UNSAFE;

        static {
            Unsafe unsafe = null;
            Field offsetField = null;

            try {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                unsafe = (Unsafe) field.get(null);
                field.setAccessible(false);

                offsetField = Field.class.getDeclaredField("offset");
                offsetField.setAccessible(true);
            } catch (Exception e) {
                Log.e(TAG, "Unable to initialize Unsafe", e);
            }

            UNSAFE = unsafe;
            OFFSET_FIELD = offsetField;
        }
    }

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        /* Set GMS props for every GMSCore process except the unstable one,
         * which runs Play Integrity and keeps the real build
         */
        if (!packageName.equals(PACKAGE_GMS) || processName.equals(PROCESS_GMS_UNSTABLE)) {
            return;
        }

        final Resources res = context.getResources();
        if (res == null) {
            Log.e(TAG, "Null resources");
            return;
        }

        sProcessName = processName;
        dlog("Setting GMS props for: " + packageName + " process: " + processName);
        for (String entry : res.getStringArray(R.array.config_gmsBuildProperties)) {
            // Each entry must be of the format FIELD:value
            final String[] fieldAndProp = entry.split(":", 2);
            if (fieldAndProp.length != 2) {
                Log.e(TAG, "Invalid entry in GMS props: " + entry);
                continue;
            }
            setPropValue(fieldAndProp[0], fieldAndProp[1]);
        }
    }

    private static void setPropValue(String key, String value) {
        final Unsafe unsafe = UnsafeHolder.UNSAFE;
        final Field offsetField = UnsafeHolder.OFFSET_FIELD;
        if (unsafe == null || offsetField == null) {
            Log.e(TAG, "Unsafe is unavailable", new IllegalStateException());
            return;
        }

        try {
            dlog("Setting prop " + key + " to " + value);
            Class<?> clazz = Build.class;
            if (key.startsWith("VERSION.")) {
                clazz = Build.VERSION.class;
                key = key.substring(8);
            }
            Field field = clazz.getDeclaredField(key);
            // Android 17 write-protects Build fields against reflection, so write
            // them in place through Unsafe.
            // Cast the value to int if it's an integer field, otherwise string.
            long offset = offsetField.getInt(field);
            if (field.getType().equals(Integer.TYPE)) {
                unsafe.putInt(clazz, offset, Integer.parseInt(value));
            } else {
                unsafe.putObject(clazz, offset, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
