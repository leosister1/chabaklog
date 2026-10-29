package chabaklog.app

import android.app.Application
import chabaklog.app.data.ChabaklogDatabase

class ChabaklogApp : Application() {
    val database: ChabaklogDatabase by lazy { ChabaklogDatabase.create(this) }
}
