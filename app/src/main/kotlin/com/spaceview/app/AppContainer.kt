package com.spaceview.app

import android.content.Context
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.data.history.MeasurementRepository
import com.spaceview.data.settings.MeasureSettingsRepository

/**
 * Manual dependency injection: one instance per process, held by [SpaceViewApplication].
 * Everything is lazy, so nothing (no database, no DataStore file) is opened at app start.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Measurement history (Room). */
    val history: MeasurementRepository by lazy { MeasurementRepository.create(appContext) }

    /** Calibration, depth and safety-notice settings (`spaceview_measure` DataStore). */
    val measureSettings: MeasureSettingsRepository by lazy { MeasureSettingsRepository.create(appContext) }

    /** Units, sounds and onboarding (`spaceview_settings` DataStore, owned by the settings feature). */
    val appSettings: SettingsRepository get() = SettingsRepository.get(appContext)

    /** Wall clock, injectable for tests. */
    val clock: () -> Long = System::currentTimeMillis
}

/** The process-wide container. */
val Context.appContainer: AppContainer
    get() = (applicationContext as SpaceViewApplication).container
