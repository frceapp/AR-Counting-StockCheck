package com.warehouse.stockchecker

import android.app.Application
import com.warehouse.stockchecker.session.SessionRepository

/**
 * App-scoped singletons.
 *
 * Holding these on [Application] is fine here: the detector + tracker are heavy to create and
 * the singleton is the same instance [MainActivity] would have created itself anyway. The
 * session repository is a thin wrapper over [android.content.SharedPreferences] + MediaStore,
 * so sharing it across fragments is harmless.
 */
class StockCheckApp : Application() {

    /** Lazily built so the app doesn't pay the I/O cost during cold start. */
    val sessionRepository: SessionRepository by lazy { SessionRepository(this) }
}
