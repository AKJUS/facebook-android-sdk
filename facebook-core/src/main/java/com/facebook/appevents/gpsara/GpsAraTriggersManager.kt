/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents.gpsara

import android.adservices.common.AdServicesOutcomeReceiver
import android.adservices.measurement.MeasurementManager
import android.annotation.TargetApi
import android.net.Uri
import android.os.OutcomeReceiver
import android.util.Log
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEvent
import com.facebook.internal.AnalyticsEvents
import com.facebook.internal.instrument.crashshield.AutoHandleExceptions
import java.net.URLEncoder

@AutoHandleExceptions
object GpsAraTriggersManager {
    private var enabled = false
    private val TAG = GpsAraTriggersManager::class.java.toString()
    private const val SERVER_URI = "https://www.facebook.com/privacy_sandbox/mobile/register/trigger"

    @JvmStatic
    fun enable() {
        enabled = true
    }

    fun registerTriggerAsync(applicationId: String, event: AppEvent) {
        FacebookSdk.getExecutor().execute {
            registerTrigger(applicationId, event)
        }
    }

    @TargetApi(34)
    fun registerTrigger(applicationId: String, event: AppEvent) {
        if (applicationId == null) return
        if (!canRegisterTrigger()) return

        val context = FacebookSdk.getApplicationContext()
        var measurementManager: MeasurementManager? = null

        try {
            measurementManager =
                context.getSystemService(MeasurementManager::class.java)
            if (measurementManager == null) {
                // On certain Android versions, Context.getSystemService() returns null since ARA is not yet
                // merged into the public SDK. If this happens, we use the factory method to get the
                // MeasurementManager instance.
                measurementManager = MeasurementManager.get(context.applicationContext)
            }

            if (measurementManager == null) {
                Log.w(TAG, "FAILURE_GET_MEASUREMENT_MANAGER")
                return
            }

            val params = getEventParameters(event)
            val appIdKey = AnalyticsEvents.PARAMETER_APP_ID
            val attributionTriggerUri: Uri =
                Uri.parse("$SERVER_URI?$appIdKey=$applicationId&$params")

            // On Android 12 and above, MeasurementManager.registerTrigger() takes an OutcomeReceiver and the
            // rest takes an AdServicesOutcomeReceiver.
            if (GpsCapabilityChecker.useOutcomeReceiver()) {
                val outcomeReceiver: OutcomeReceiver<Any, Exception> =
                    object : OutcomeReceiver<Any, Exception> {
                        override fun onResult(result: Any) {
                            Log.d(TAG, "OUTCOME_RECEIVER_TRIGGER_SUCCESS")
                        }

                        override fun onError(error: Exception) {
                            Log.d(TAG, "OUTCOME_RECEIVER_TRIGGER_FAILURE")
                        }
                    }

                measurementManager.registerTrigger(
                    attributionTriggerUri, FacebookSdk.getExecutor(), outcomeReceiver
                )
            } else {
                val adServicesOutcomeReceiver: AdServicesOutcomeReceiver<Any, Exception> =
                    object : AdServicesOutcomeReceiver<Any, Exception> {
                        override fun onResult(result: Any) {
                            Log.d(TAG, "AD_SERVICE_OUTCOME_RECEIVER_TRIGGER_SUCCESS")
                        }

                        override fun onError(error: Exception) {
                            Log.d(TAG, "AD_SERVICE_OUTCOME_RECEIVER_TRIGGER_FAILURE")
                        }
                    }

                measurementManager.registerTrigger(
                    attributionTriggerUri, FacebookSdk.getExecutor(), adServicesOutcomeReceiver
                )
            }

        } catch (e: Exception) {
            Log.w(TAG, "FAILURE_TRIGGER_REGISTRATION_FAILED")
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "FAILURE_TRIGGER_REGISTRATION_NO_CLASS_FOUND")
        } catch (e: NoSuchMethodError) {
            Log.w(TAG, "FAILURE_TRIGGER_REGISTRATION_NO_METHOD_FOUND")
        }
    }

    private fun canRegisterTrigger(): Boolean {
        if (!enabled) {
            return false
        }

        try {
            Class.forName("android.adservices.measurement.MeasurementManager")
            return true
        } catch (e: Exception) {
            Log.i(TAG, "FAILURE_NO_MEASUREMENT_MANAGER_CLASS")
            return false
        } catch (e: NoClassDefFoundError) {
            Log.i(TAG, "FAILURE_NO_MEASUREMENT_MANAGER_CLASS_DEF")
            return false
        }
    }

    private fun getEventParameters(event: AppEvent): String {
        val params = event.getJSONObject()

        if (params == null || params.length() == 0) {
            return ""
        }

        return params.keys().asSequence().mapNotNull { key ->
            val value = params.opt(key) ?: return@mapNotNull null
            try {
                val encodedKey = URLEncoder.encode(key, "UTF-8")
                val encodedValue = URLEncoder.encode(value.toString(), "UTF-8")
                "$encodedKey=$encodedValue"
            } catch (e: Exception) {
                null // Ignore invalid keys
            }
        }
            .joinToString("&")
    }
}