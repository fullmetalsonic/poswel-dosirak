package com.fullmetalsonic.dosirak

internal enum class NotificationPermissionRoute { REQUEST, SETTINGS }

internal fun notificationPermissionRoute(runtimePermissionRequired: Boolean, permissionGranted: Boolean,
    askedBefore: Boolean, shouldShowRationale: Boolean): NotificationPermissionRoute =
    if (runtimePermissionRequired && !permissionGranted && (!askedBefore || shouldShowRationale))
        NotificationPermissionRoute.REQUEST else NotificationPermissionRoute.SETTINGS

internal fun notificationPermissionNeededAfterSetup(previousAutomaticEnabled: Boolean?, previousOnboardingRequired: Boolean?,
    automaticEnabled: Boolean, onboardingRequired: Boolean): Boolean =
    previousAutomaticEnabled != null && previousOnboardingRequired != null &&
        ((!previousAutomaticEnabled && automaticEnabled) || (previousOnboardingRequired && !onboardingRequired))
