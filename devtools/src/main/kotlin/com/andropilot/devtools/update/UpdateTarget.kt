package com.andropilot.devtools.update

/**
 * An app the updater can look after.
 *
 * Introduced so one app can update another. Keeping the package name explicit rather than
 * implying it from the caller is what makes the check before installing meaningful: the
 * downloaded archive is compared against the package it was *supposed* to be, which is a
 * real assertion, instead of against whoever happens to be running, which stops being one
 * as soon as an updater looks after anything but itself.
 */
public data class UpdateTarget(
    /** The application id to check and install, for example `com.andropilot.agent`. */
    val packageName: String,
    /**
     * Part of the APK filename identifying this app on a release.
     *
     * Required whenever a release carries more than one APK. See [chooseApk].
     */
    val apkAsset: String? = null,
    /** What to call it on screen. */
    val label: String = packageName,
    /**
     * True when updating this app tears down an accessibility service.
     *
     * Replacing a package kills its processes, and Android records the lost accessibility
     * binding as crashed and never rebinds it. The service then reads as switched on while
     * nothing runs, which is indistinguishable from a broken install unless somebody says
     * so beforehand.
     */
    val disablesAccessibilityService: Boolean = false,
)
