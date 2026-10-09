package com.fullmetalsonic.dosirak.data

import android.content.Context

class OnboardingStore(context: Context, name: String = "ui_onboarding") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Synchronized fun initialize(hasExistingData: Boolean) {
        if (preferences.contains("onboardingVersion")) return
        check(preferences.edit().putInt("onboardingVersion", if (hasExistingData) 1 else 0).commit())
    }

    fun isRequired(): Boolean = preferences.getInt("onboardingVersion", 0) < 1

    @Synchronized fun complete() {
        check(preferences.edit().putInt("onboardingVersion", 1).commit())
    }
}
