package com.ykasorns.ebookreader

import android.app.Application
import android.content.Context
import com.ykasorns.ebookreader.data.LibraryRepository
import com.ykasorns.ebookreader.data.SettingsRepository
import com.ykasorns.ebookreader.importer.BookImporter

class ReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}

/** Tiny service locator: the app has no backend, so a few singletons are all it needs. */
object AppGraph {
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var importer: BookImporter
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        library = LibraryRepository(app)
        settings = SettingsRepository(app)
        importer = BookImporter(app, library)
    }
}
