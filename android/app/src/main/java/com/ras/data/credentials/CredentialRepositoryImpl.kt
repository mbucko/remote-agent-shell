package com.ras.data.credentials

import com.ras.crypto.KeyDerivation
import com.ras.data.database.PairedDeviceDao
import com.ras.data.database.PairedDeviceEntity
import com.ras.data.encryption.DeviceEncryptionHelper
import com.ras.data.keystore.KeyManager
import com.ras.data.model.DeviceStatus
import com.ras.data.model.DeviceType
import com.ras.data.model.PairedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CredentialRepository using Room for multi-device storage.
 *
 * Master secrets are encrypted using Android Keystore before being stored in the database.
 * Each device has its own encrypted master secret.
 */
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val dao: PairedDeviceDao,
    private val encryptionHelper: DeviceEncryptionHelper,
    private val keyManager: KeyManager // For migration only
) : CredentialRepository {

    // Multi-device operations

    override suspend fun getAllDevices(): List<PairedDevice> {
        // Returns ALL devices including unpaired
        return dao.getAllDevices().first().map { it.toDomainModel() }
    }

    override fun getAllDevicesFlow(): Flow<List<PairedDevice>> {
        // Returns only PAIRED devices for UI display
        return dao.getAllPairedDevices().map { entities ->
            entities.map { entity -> entity.toDomainModel() }
        }
    }

    override suspend fun getDevice(deviceId: String): PairedDevice? {
        val entity = dao.getDevice(deviceId) ?: return null
        return entity.toDomainModel()
    }

    override suspend fun getSelectedDevice(): PairedDevice? {
        val entity = dao.getSelectedDevice() ?: return null
        return entity.toDomainModel()
    }

    override suspend fun addDevice(
        deviceId: String,
        masterSecret: ByteArray,
        deviceName: String,
        deviceType: DeviceType,
        isSelected: Boolean,
        daemonHost: String?,
        daemonPort: Int?,
        daemonTailscaleIp: String?,
        daemonTailscalePort: Int?,
        daemonVpnIp: String?,
        daemonVpnPort: Int?
    ) {
        // Encrypt master secret
        val (encrypted, iv) = encryptionHelper.encrypt(masterSecret)

        val entity = PairedDeviceEntity(
            deviceId = deviceId,
            masterSecretEncrypted = encrypted,
            masterSecretIv = iv,
            deviceName = deviceName,
            deviceType = deviceType.name,
            status = DeviceStatus.PAIRED.name,
            isSelected = isSelected,
            pairedAt = Instant.now().epochSecond,
            lastConnectedAt = null,
            daemonHost = daemonHost,
            daemonPort = daemonPort,
            daemonTailscaleIp = daemonTailscaleIp,
            daemonTailscalePort = daemonTailscalePort,
            daemonVpnIp = daemonVpnIp,
            daemonVpnPort = daemonVpnPort
        )

        dao.insertDevice(entity)
    }

    override suspend fun setSelectedDevice(deviceId: String) {
        dao.setSelectedDevice(deviceId)
    }

    override suspend fun updateDeviceStatus(deviceId: String, status: DeviceStatus) {
        dao.updateDeviceStatus(deviceId, status.name)
    }

    override suspend fun removeDevice(deviceId: String) {
        dao.deleteDevice(deviceId)
    }

    override suspend fun unpairDevice(deviceId: String) {
        dao.updateDeviceStatus(deviceId, DeviceStatus.UNPAIRED_BY_USER.name)
    }

    // Backward compatible methods (for gradual migration)

    override suspend fun hasCredentials(): Boolean {
        return dao.getSelectedDevice() != null
    }

    override suspend fun getCredentials(): StoredCredentials? {
        val device = getSelectedDevice() ?: return null

        // Derive ntfyTopic from master_secret
        val ntfyTopic = KeyDerivation.deriveNtfyTopic(device.masterSecret)

        return StoredCredentials(
            deviceId = device.deviceId,
            masterSecret = device.masterSecret,
            daemonHost = device.daemonHost,
            daemonPort = device.daemonPort,
            ntfyTopic = ntfyTopic,
            daemonTailscaleIp = device.daemonTailscaleIp,
            daemonTailscalePort = device.daemonTailscalePort,
            daemonVpnIp = device.daemonVpnIp,
            daemonVpnPort = device.daemonVpnPort
        )
    }

    override suspend fun getDeviceName(): String? {
        return getSelectedDevice()?.deviceName
    }

    override suspend fun getDeviceType(): DeviceType {
        return getSelectedDevice()?.deviceType ?: DeviceType.UNKNOWN
    }

    override suspend fun clearCredentials() {
        // Legacy method - mark selected device as unpaired
        val selected = dao.getSelectedDevice()
        if (selected != null) {
            unpairDevice(selected.deviceId)
        }
    }

    // Private helpers

    /**
     * Convert entity to domain model, decrypting the master secret.
     */
    private fun PairedDeviceEntity.toDomainModel(): PairedDevice {
        val masterSecret = encryptionHelper.decrypt(
            masterSecretEncrypted,
            masterSecretIv
        )

        return PairedDevice(
            deviceId = deviceId,
            masterSecret = masterSecret,
            deviceName = deviceName,
            deviceType = DeviceType.valueOf(deviceType),
            status = DeviceStatus.valueOf(status),
            isSelected = isSelected,
            pairedAt = Instant.ofEpochSecond(pairedAt),
            lastConnectedAt = lastConnectedAt?.let { Instant.ofEpochSecond(it) },
            daemonHost = daemonHost,
            daemonPort = daemonPort,
            daemonTailscaleIp = daemonTailscaleIp,
            daemonTailscalePort = daemonTailscalePort,
            daemonVpnIp = daemonVpnIp,
            daemonVpnPort = daemonVpnPort
        )
    }
}
