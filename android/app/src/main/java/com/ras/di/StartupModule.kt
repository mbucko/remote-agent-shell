package com.ras.di

import com.ras.data.credentials.CredentialRepository
import com.ras.data.credentials.CredentialRepositoryImpl
import com.ras.data.reconnection.ReconnectionService
import com.ras.data.reconnection.ReconnectionServiceImpl
import com.ras.domain.startup.AttemptReconnectionUseCase
import com.ras.domain.startup.AttemptReconnectionUseCaseImpl
import com.ras.domain.startup.CheckCredentialsUseCase
import com.ras.domain.startup.CheckCredentialsUseCaseImpl
import com.ras.domain.startup.ClearCredentialsUseCase
import com.ras.domain.startup.ClearCredentialsUseCaseImpl
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
    abstract fun bindReconnectionService(
        impl: ReconnectionServiceImpl
    ): ReconnectionService

    @Binds
    @Singleton
    abstract fun bindAttemptReconnectionUseCase(
        impl: AttemptReconnectionUseCaseImpl
    ): AttemptReconnectionUseCase
}
