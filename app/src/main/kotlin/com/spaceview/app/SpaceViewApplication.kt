package com.spaceview.app

import android.app.Application

/**
 * Holds the [AppContainer]. `onCreate` stays empty on purpose (cold-start budget ≤ 800 ms): the
 * container is created lazily and every dependency inside it is lazy too.
 */
class SpaceViewApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
