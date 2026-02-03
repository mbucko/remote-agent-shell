package com.ras.di

import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.CredentialRepositoryImpl
import com.ras.data.reconnection.ReconnectionService
import com.ras.data.reconnection.ReconnectionServiceImpl
import com.ras.data.settings.SettingsRepository
import com.ras.data.settings.SettingsRepositoryImpl
import com.ras.domain.startup.AttemptReconnectionUseCase
import com.ras.domain.startup.AttemptReconnectionUseCaseImpl
import com.ras.domain.startup.CheckCredentialsUseCase
import com.ras.domain.startup.CheckCredentialsUseCaseImpl
import com.ras.domain.startup.ClearCredentialsUseCase
import com.ras.domain.startup.ClearCredentialsUseCaseImpl
import com.ras.domain.unpair.UnpairDeviceUseCase
import com.ras.domain.unpair.UnpairDeviceUseCaseImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for startup/reconnection dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StartupModule {

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        impl: CredentialRepositoryImpl
    ): CredentialRepository

    @Binds
    @Singleton
    abstract fun bindCheckCredentialsUseCase(
        impl: CheckCredentialsUseCaseImpl
    ): CheckCredentialsUseCase

    @Binds
    @Singleton
    abstract fun bindClearCredentialsUseCase(
        impl: ClearCredentialsUseCaseImpl
    ): ClearCredentialsUseCase

    @Binds
    @Singleton
    abstract fun bindUnpairDeviceUseCase(
        impl: UnpairDeviceUseCaseImpl
    ): UnpairDeviceUseCase

    @Binds
    @Singleton
    abstract fun bindReconnectionService(
        impl: ReconnectionServiceImpl
    ): ReconnectionService

    @Binds
    @Singleton
    abstract fun bindAttemptReconnectionUseCase(
        impl: AttemptReconnectionUseCaseImpl
    ): AttemptReconnectionUseCase

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository
}
