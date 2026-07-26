package com.local.autobookkeeping

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class ThemeSwitchTestRunner : AndroidJUnitRunner() {
    private var testApplication: ThemeSwitchTestApplication? = null

    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        return super.newApplication(
            classLoader,
            ThemeSwitchTestApplication::class.java.name,
            context,
        ).also { application ->
            testApplication = application as ThemeSwitchTestApplication
        }
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try {
            testApplication?.cleanUpTestStorage()
        } finally {
            super.finish(resultCode, results)
        }
    }
}
