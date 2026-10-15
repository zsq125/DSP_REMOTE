package com.deepseek.dshremote

import android.app.Application
import com.deepseek.dshremote.data.DshRemoteStore

/** 应用入口：持有全局存储。 */
class DshRemoteApp : Application() {
    lateinit var store: DshRemoteStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = DshRemoteStore(this)
    }
}
