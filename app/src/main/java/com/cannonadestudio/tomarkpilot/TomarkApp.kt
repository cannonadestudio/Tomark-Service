package com.cannonadestudio.tomarkpilot

import android.app.Application
import com.cannonadestudio.tomarkpilot.data.Repo
import com.cannonadestudio.tomarkpilot.work.CheckWorker

class TomarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        CheckWorker.createChannel(this)
        CheckWorker.schedule(this)
    }
}
