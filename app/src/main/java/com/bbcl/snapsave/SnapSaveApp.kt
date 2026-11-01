package com.bbcl.snapsave

import android.app.Application
import com.bbcl.snapsave.download.Downloads

class SnapSaveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
    }
}
