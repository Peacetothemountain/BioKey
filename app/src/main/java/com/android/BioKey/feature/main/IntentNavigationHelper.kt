package com.android.BioKey.feature.main

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.android.BioKey.core.security.LockStateTracker
import com.android.BioKey.feature.notification.BioKeyNotificationShieldService

/**
 * Centralized intent navigation with strict fallback hierarchies for all system configuration pages.
 * Pre-authorizes self-protection round-trips via [LockStateTracker] to prevent false lock triggers.
 */
object IntentNavigationHelper {
    private const val TAG = "IntentNavigationHelper"

    /**
     * Attempts a sequence of intents in prioritized order.
     */
    fun launchFallbackCascade(
        context: Context,
        intents: List<Intent>,
        preAuthorizeSelfProtection: Boolean = true
    ): Boolean {
        for (intent in intents) {
            try {
                if (preAuthorizeSelfProtection) {
                    LockStateTracker.beginExternalNavigation(preAuthorizeSelfProtection = true)
                }
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "Activity not found for intent: ${intent.action}, trying next fallback")
            } catch (e: SecurityException) {
                Log.w(TAG, "Security exception for intent: ${intent.action}, trying next fallback")
            }
        }
        Toast.makeText(context, "Could not open settings on this device", Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * Screen Lock setup: Directly open set new password wizard -> Security settings -> Main settings.
     */
    fun openScreenLockSetup(context: Context) {
        val candidates = listOf(
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        launchFallbackCascade(context, candidates)
    }

    /**
     * Battery Optimization: Direct exemption prompt -> Optimization list -> App Info -> Main settings.
     */
    fun openBatteryOptimization(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
            Intent(Settings.ACTION_SETTINGS)
        )
        launchFallbackCascade(context, candidates)
    }

    /**
     * Notification Listener: Detail page (API 30+) -> Global Listener list -> App Info -> Main settings.
     */
    fun openNotificationListenerSettings(context: Context) {
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            candidates.add(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(context, BioKeyNotificationShieldService::class.java).flattenToString()
                    )
                }
            )
        }
        candidates.add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        candidates.add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        )
        candidates.add(Intent(Settings.ACTION_SETTINGS))
        launchFallbackCascade(context, candidates)
    }

    /**
     * Accessibility Settings: A11y settings -> App Info (for restricted settings) -> Main settings.
     */
    fun openAccessibilitySettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
            Intent(Settings.ACTION_SETTINGS)
        )
        launchFallbackCascade(context, candidates)
    }

    /**
     * Application Details Settings (App Info).
     */
    fun openAppDetailsSettings(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
            Intent(Settings.ACTION_SETTINGS)
        )
        launchFallbackCascade(context, candidates)
    }

    /**
     * Application Notification Settings (API 26+).
     */
    fun openAppNotificationSettings(context: Context) {
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
                Intent(Settings.ACTION_SETTINGS)
            )
        } else {
            listOf(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        launchFallbackCascade(context, candidates)
    }
}
