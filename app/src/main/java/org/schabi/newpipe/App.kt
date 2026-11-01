package org.schabi.newpipe

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.jakewharton.processphoenix.ProcessPhoenix
import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.exceptions.MissingBackpressureException
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException
import io.reactivex.rxjava3.exceptions.UndeliverableException
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.SocketException
import okhttp3.OkHttpClient
import org.acra.ACRA.init
import org.acra.ACRA.isACRASenderServiceProcess
import org.acra.config.CoreConfigurationBuilder
import org.schabi.newpipe.error.ReCaptchaActivity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.ktx.hasAssignableCause
import org.schabi.newpipe.settings.NewPipeSettings
import org.schabi.newpipe.util.BridgeStateSaverInitializer
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ProxyConfig
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.SingBoxManager
import org.schabi.newpipe.util.StateSaver
import org.schabi.newpipe.util.image.ImageStrategy
import org.schabi.newpipe.util.image.PreferredImageQuality
import org.schabi.newpipe.util.potoken.PoTokenProviderImpl

/*
 * Copyright (C) Hans-Christoph Steiner 2016 <hans@eds.org>
 * App.kt is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */
open class App :
    Application(),
    SingletonImageLoader.Factory {
    var isFirstRun = false
        private set
    var notificationsRequested = false
        private set

    fun setNotificationsRequested() {
        notificationsRequested = true
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        initACRA()
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        if (ProcessPhoenix.isPhoenixProcess(this)) {
            Log.i(TAG, "This is a phoenix process! Aborting initialization of App[onCreate]")
            return
        }

        // check if the last used preference version is set
        // to determine whether this is the first app run
        val lastUsedPrefVersion =
            PreferenceManager
                .getDefaultSharedPreferences(this)
                .getInt(getString(R.string.last_used_preferences_version), -1)
        isFirstRun = lastUsedPrefVersion == -1

        // Initialize settings first because other initializations can use its values
        NewPipeSettings.initSettings(this)

        // Route process-wide HttpURLConnection traffic (ExoPlayer, Coil, …) through the proxy.
        applyProxySystemProperties()

        NewPipe.init(
            getDownloader(),
            Localization.getPreferredLocalization(this),
            Localization.getPreferredContentCountry(this)
        )
        Localization.initPrettyTime(Localization.resolvePrettyTime())

        BridgeStateSaverInitializer.init(this)
        StateSaver.init(this)
        initNotificationChannels()

        ServiceHelper.initServices(this)

        // Initialize image loader
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ImageStrategy.setPreferredImageQuality(
            PreferredImageQuality.fromPreferenceKey(
                this,
                prefs.getString(
                    getString(R.string.image_quality_key),
                    getString(R.string.image_quality_default)
                )
            )
        )

        configureRxJavaErrorHandler()

        YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader
        .Builder(this)
        .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
        .allowRgb565(getSystemService<ActivityManager>()!!.isLowRamDevice)
        .crossfade(true)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = DownloaderImpl.getInstance().client))
        }.build()

    protected open fun getDownloader(): Downloader {
        val builder = OkHttpClient.Builder()
        applyProxy(builder)
        val downloader = DownloaderImpl.init(builder)
        setCookiesToDownloader(downloader)
        return downloader
    }

    /**
     * Routes OkHttp requests through the configured proxy (HTTP or LAN/Hiddify).
     * Also used by the debug application, which builds its own client.
     */
    protected fun applyProxy(builder: OkHttpClient.Builder) {
        val proxy = ProxyConfig.getProxy(this)
        if (proxy != null) {
            builder.proxy(proxy)
            val authenticator = ProxyConfig.getOkHttpAuthenticator(this)
            if (authenticator != null) {
                builder.proxyAuthenticator(authenticator)
            }
        }
    }

    /**
     * Applies the configured HTTP proxy process-wide through the standard
     * {@code http.*}/{@code https.*} system properties. This routes every
     * {@link java.net.HttpURLConnection} through the proxy, which covers the network paths that
     * cannot be configured directly (e.g. ExoPlayer's {@code DefaultHttpDataSource} used for live
     * streams) and Coil thumbnails. The OkHttp downloader and the YouTube data source set the proxy
     * explicitly and therefore take precedence over these properties.
     */
    private fun applyProxySystemProperties() {
        // Always clear any previous HTTP-proxy system properties first; the sing-box
        // branch re-sets them to the local endpoint afterwards.
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")
        System.clearProperty("https.proxyHost")
        System.clearProperty("https.proxyPort")
        Authenticator.setDefault(null)

        when (ProxyConfig.getMode(this)) {
            getString(R.string.proxy_mode_http_value) -> applyHttpProxy()
            getString(R.string.proxy_mode_lan_value) -> applyLanProxy()
            getString(R.string.proxy_mode_singbox_value) -> startSingBoxProxy()
        }
    }

    private fun applyHttpProxy() {
        if (!ProxyConfig.isHttpMode(this)) {
            return
        }
        setHttpProxySystemProperties(ProxyConfig.getHost(this), ProxyConfig.getPort(this))
        val user = ProxyConfig.getUser(this)
        val password = ProxyConfig.getPassword(this)
        if (user.isNotEmpty() && password.isNotEmpty()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType == RequestorType.PROXY) {
                        return PasswordAuthentication(user, password.toCharArray())
                    }
                    return null
                }
            })
        }
    }

    private fun applyLanProxy() {
        if (!ProxyConfig.isLanMode(this)) {
            return
        }
        setHttpProxySystemProperties(
            ProxyConfig.getLanHost(this),
            ProxyConfig.getLanPort(this)
        )
    }

    private fun setHttpProxySystemProperties(host: String, port: Int) {
        if (host.isEmpty() || port <= 0) {
            return
        }
        System.setProperty("http.proxyHost", host)
        System.setProperty("http.proxyPort", port.toString())
        System.setProperty("https.proxyHost", host)
        System.setProperty("https.proxyPort", port.toString())
    }

    private fun startSingBoxProxy() {
        val config = ProxyConfig.getEffectiveSingBoxConfig(this)
        if (config.isBlank()) {
            Log.w(TAG, "sing-box mode selected but no config provided")
            return
        }
        val manager = SingBoxManager.getInstance(
            this,
            object : SingBoxManager.Callback {
                override fun onStarted(port: Int) {
                    // Route all HttpURLConnection traffic through the local sing-box endpoint.
                    System.setProperty("http.proxyHost", "127.0.0.1")
                    System.setProperty("http.proxyPort", if (port > 0) port.toString() else "2333")
                    System.setProperty("https.proxyHost", "127.0.0.1")
                    System.setProperty("https.proxyPort", if (port > 0) port.toString() else "2333")
                    Log.i(TAG, "sing-box started, proxy -> 127.0.0.1:${if (port > 0) port else 2333}")
                }

                override fun onStopped() {
                    System.clearProperty("http.proxyHost")
                    System.clearProperty("http.proxyPort")
                    System.clearProperty("https.proxyHost")
                    System.clearProperty("https.proxyPort")
                }

                override fun onError(message: String) {
                    Log.e(TAG, "sing-box error: $message")
                }
            }
        )
        manager.start(config)
    }

    protected fun setCookiesToDownloader(downloader: DownloaderImpl) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val key = getString(R.string.recaptcha_cookies_key)
        downloader.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, prefs.getString(key, null))
        downloader.updateYoutubeRestrictedModeCookies(this)
    }

    private fun configureRxJavaErrorHandler() {
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(
            object : Consumer<Throwable> {
                override fun accept(throwable: Throwable) {
                    Log.e(TAG, "RxJavaPlugins.ErrorHandler called with -> : throwable = [${throwable.javaClass.getName()}]")

                    // As UndeliverableException is a wrapper,
                    // get the cause of it to get the "real" exception
                    val actualThrowable = (throwable as? UndeliverableException)?.cause ?: throwable

                    val errors = (actualThrowable as? CompositeException)?.exceptions ?: listOf(actualThrowable)

                    for (error in errors) {
                        if (isThrowableIgnored(error)) {
                            return
                        }
                        if (isThrowableCritical(error)) {
                            reportException(error)
                            return
                        }
                    }

                    // Out-of-lifecycle exceptions should only be reported if a debug user wishes so,
                    // When exception is not reported, log it
                    if (isDisposedRxExceptionsReported()) {
                        reportException(actualThrowable)
                    } else {
                        Log.e(TAG, "RxJavaPlugin: Undeliverable Exception received: ", actualThrowable)
                    }
                }

                fun isThrowableIgnored(throwable: Throwable): Boolean {
                    // Don't crash the application over a simple network problem
                    return throwable // network api cancellation
                        .hasAssignableCause(
                            IOException::class.java,
                            SocketException::class.java, // blocking code disposed
                            InterruptedException::class.java,
                            InterruptedIOException::class.java
                        )
                }

                fun isThrowableCritical(throwable: Throwable): Boolean {
                    // Though these exceptions cannot be ignored
                    return throwable
                        .hasAssignableCause(
                            // bug in app
                            NullPointerException::class.java,
                            IllegalArgumentException::class.java,
                            OnErrorNotImplementedException::class.java,
                            MissingBackpressureException::class.java,
                            // bug in operator
                            IllegalStateException::class.java
                        )
                }

                fun reportException(throwable: Throwable) {
                    // Throw uncaught exception that will trigger the report system
                    Thread
                        .currentThread()
                        .uncaughtExceptionHandler
                        .uncaughtException(Thread.currentThread(), throwable)
                }
            }
        )
    }

    /**
     * Called in [.attachBaseContext] after calling the `super` method.
     * Should be overridden if MultiDex is enabled, since it has to be initialized before ACRA.
     */
    protected fun initACRA() {
        if (isACRASenderServiceProcess()) {
            return
        }

        val acraConfig =
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
        init(this, acraConfig)
    }

    private fun initNotificationChannels() {
        // Keep the importance below DEFAULT to avoid making noise on every notification update for
        // the main and update channels
        val mainChannel =
            NotificationChannelCompat
                .Builder(
                    getString(R.string.notification_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.notification_channel_name))
                .setDescription(getString(R.string.notification_channel_description))
                .build()
        val appUpdateChannel =
            NotificationChannelCompat
                .Builder(
                    getString(R.string.app_update_notification_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.app_update_notification_channel_name))
                .setDescription(getString(R.string.app_update_notification_channel_description))
                .build()
        val hashChannel =
            NotificationChannelCompat
                .Builder(
                    getString(R.string.hash_channel_id),
                    NotificationManagerCompat.IMPORTANCE_HIGH
                ).setName(getString(R.string.hash_channel_name))
                .setDescription(getString(R.string.hash_channel_description))
                .build()
        val errorReportChannel =
            NotificationChannelCompat
                .Builder(
                    getString(R.string.error_report_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.error_report_channel_name))
                .setDescription(getString(R.string.error_report_channel_description))
                .build()
        val newStreamChannel =
            NotificationChannelCompat
                .Builder(
                    getString(R.string.streams_notification_channel_id),
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.streams_notification_channel_name))
                .setDescription(getString(R.string.streams_notification_channel_description))
                .build()

        val channels = listOf(mainChannel, appUpdateChannel, hashChannel, errorReportChannel, newStreamChannel)

        NotificationManagerCompat.from(this).createNotificationChannelsCompat(channels)
    }

    protected open fun isDisposedRxExceptionsReported(): Boolean = false

    companion object {
        const val PACKAGE_NAME: String = BuildConfig.APPLICATION_ID
        private val TAG = App::class.java.toString()

        @JvmStatic
        lateinit var instance: App
            private set
    }
}
