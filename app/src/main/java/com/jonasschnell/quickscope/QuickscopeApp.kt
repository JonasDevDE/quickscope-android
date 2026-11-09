package com.jonasschnell.quickscope

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.jonasschnell.quickscope.data.AttemptSync
import com.jonasschnell.quickscope.data.AuthManager
import com.jonasschnell.quickscope.data.MaterialPreview
import com.jonasschnell.quickscope.logic.AttemptQueue
import com.jonasschnell.quickscope.data.Network
import com.jonasschnell.quickscope.data.Store
import com.jonasschnell.quickscope.data.Updater

class QuickscopeApp : Application(), SingletonImageLoader.Factory {
    val store by lazy { Store(this) }
    val auth by lazy { AuthManager(this, BuildConfig.GOOGLE_WEB_CLIENT_ID) }
    val network by lazy { Network(auth, store) }

    val attemptSync by lazy { AttemptSync(network.api, AttemptQueue(store)) }
    val preview by lazy { MaterialPreview(this, network.client) }

    val updater by lazy { Updater(this, network.client, network.api) }

    override fun onCreate() {
        super.onCreate()
        network.onUpgradeRequired = { updater.markForced() }
        // Update erst installieren, wenn die App in den Hintergrund geht (nie waehrend der Nutzer sie benutzt).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = updater.installIfReady()
        })
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { network.client })) }
            .crossfade(300)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("image_cache").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
            .build()
}
