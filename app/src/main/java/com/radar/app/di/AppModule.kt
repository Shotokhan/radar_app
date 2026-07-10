package com.radar.app.di

import com.radar.app.data.provider.BleSignalProvider
import com.radar.app.data.provider.SignalProvider
import com.radar.app.data.provider.WifiApSignalProvider
import com.radar.app.domain.motion.AndroidMotionTracker
import com.radar.app.domain.motion.MotionTracker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindMotionTracker(impl: AndroidMotionTracker): MotionTracker

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindBleProvider(impl: BleSignalProvider): SignalProvider

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindWifiApProvider(impl: WifiApSignalProvider): SignalProvider
}
