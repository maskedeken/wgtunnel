package com.zaneschepke.wireguardautotunnel.ui.screens.main

import android.app.Application
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.config.Config
import com.zaneschepke.wireguardautotunnel.WireGuardAutoTunnel
import com.zaneschepke.wireguardautotunnel.data.domain.Settings
import com.zaneschepke.wireguardautotunnel.data.domain.TunnelConfig
import com.zaneschepke.wireguardautotunnel.data.repository.AppDataRepository
import com.zaneschepke.wireguardautotunnel.service.foreground.ServiceManager
import com.zaneschepke.wireguardautotunnel.service.tunnel.VpnService
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.Event
import com.zaneschepke.wireguardautotunnel.util.NumberUtils
import com.zaneschepke.wireguardautotunnel.util.Result
import com.zaneschepke.wireguardautotunnel.util.toWgQuickString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class MainViewModel
@Inject
constructor(
    private val application: Application,
    private val appDataRepository: AppDataRepository,
    private val serviceManager: ServiceManager,
    val vpnService: VpnService
) : ViewModel() {

    val uiState =
        combine(
            appDataRepository.settings.getSettingsFlow(),
            appDataRepository.tunnels.getTunnelConfigsFlow(),
            vpnService.vpnState,
        ) { settings, tunnels, vpnState ->
            MainUiState(settings, tunnels, vpnState, false)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(Constants.SUBSCRIPTION_TIMEOUT),
                MainUiState(),
            )

    private fun stopWatcherService() =
        viewModelScope.launch(Dispatchers.IO) {
            serviceManager.stopWatcherService(application.applicationContext)
        }

    fun onDelete(tunnel: TunnelConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = appDataRepository.settings.getSettings()
            val isPrimary = tunnel.isPrimaryTunnel
            if (appDataRepository.tunnels.count() == 1 || isPrimary) {
                stopWatcherService()
                resetTunnelSetting(settings)
            }
            appDataRepository.tunnels.delete(tunnel)
            WireGuardAutoTunnel.requestTunnelTileServiceStateUpdate(application)
        }
    }

    private fun resetTunnelSetting(settings: Settings) {
        saveSettings(
            settings.copy(
                isAutoTunnelEnabled = false,
                isAlwaysOnVpnEnabled = false,
            ),
        )
    }

    fun onTunnelStart(tunnelConfig: TunnelConfig) =
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("On start called!")
            serviceManager.startVpnService(
                application.applicationContext,
                tunnelConfig.id,
                isManualStart = true,
            )
        }


    fun onTunnelStop() =
        viewModelScope.launch(Dispatchers.IO) {
            Timber.i("Stopping active tunnel")
            serviceManager.stopVpnService(application.applicationContext, isManualStop = true)
        }

    private fun validateConfigString(config: String, configType: ConfigType) {
        when(configType) {
            ConfigType.AMNEZIA -> TunnelConfig.configFromAmQuick(config)
            ConfigType.WIREGUARD -> TunnelConfig.configFromWgQuick(config)
        }
    }

    suspend fun onTunnelQrResult(result: String, configType: ConfigType): Result<Unit> {
        return try {
            validateConfigString(result, configType)
            val tunnelConfig = when(configType) {
                ConfigType.AMNEZIA ->{
                    TunnelConfig(name = NumberUtils.generateRandomTunnelName(), amQuick = result,
                        wgQuick = TunnelConfig.configFromAmQuick(result).toWgQuickString())
                }
                ConfigType.WIREGUARD -> TunnelConfig(name = NumberUtils.generateRandomTunnelName(), wgQuick = result)
            }
            addTunnel(tunnelConfig)
            Result.Success(Unit)
        } catch (e: Exception) {
            Timber.e(e)
            Result.Error(Event.Error.InvalidQrCode)
        }
    }

    private suspend fun saveTunnelConfigFromStream(stream: InputStream, fileName: String, type: ConfigType) {
        var amQuick : String? = null
        val wgQuick = stream.use {
            when(type) {
                ConfigType.AMNEZIA -> {
                    val config = org.amnezia.awg.config.Config.parse(it)
                    amQuick = config.toAwgQuickString()
                    config.toWgQuickString()
                }
                ConfigType.WIREGUARD -> {
                    Config.parse(it).toWgQuickString()
                }
            }
        }
        val tunnelName = getNameFromFileName(fileName)
        addTunnel(TunnelConfig(name = tunnelName, wgQuick = wgQuick, amQuick = amQuick ?: TunnelConfig.AM_QUICK_DEFAULT))
    }

    private fun getInputStreamFromUri(uri: Uri): InputStream? {
        return application.applicationContext.contentResolver.openInputStream(uri)
    }

    suspend fun onTunnelFileSelected(uri: Uri, configType: ConfigType): Result<Unit> {
        try {
            if (isValidUriContentScheme(uri)) {
                val fileName = getFileName(application.applicationContext, uri)
                when (getFileExtensionFromFileName(fileName)) {
                    Constants.CONF_FILE_EXTENSION ->
                        saveTunnelFromConfUri(fileName, uri, configType).let {
                            when (it) {
                                is Result.Error -> return Result.Error(Event.Error.FileReadFailed)
                                is Result.Success -> return it
                            }
                        }

                    Constants.ZIP_FILE_EXTENSION -> saveTunnelsFromZipUri(uri, configType)
                    else -> return Result.Error(Event.Error.InvalidFileExtension)
                }
                return Result.Success(Unit)
            } else {
                return Result.Error(Event.Error.InvalidFileExtension)
            }
        } catch (e: Exception) {
            Timber.e(e)
            return Result.Error(Event.Error.FileReadFailed)
        }
    }

    private suspend fun saveTunnelsFromZipUri(uri: Uri, configType: ConfigType) {
        ZipInputStream(getInputStreamFromUri(uri)).use { zip ->
            generateSequence { zip.nextEntry }
                .filterNot {
                    it.isDirectory ||
                        getFileExtensionFromFileName(it.name) != Constants.CONF_FILE_EXTENSION
                }
                .forEach {
                    val name = getNameFromFileName(it.name)
                    viewModelScope.launch(Dispatchers.IO) {
                        var amQuick : String? = null
                        val wgQuick =
                            when(configType) {
                                ConfigType.AMNEZIA -> {
                                    val config = org.amnezia.awg.config.Config.parse(zip)
                                    amQuick = config.toAwgQuickString()
                                    config.toWgQuickString()
                                }
                                ConfigType.WIREGUARD -> {
                                    Config.parse(zip).toWgQuickString()
                                }
                            }
                        addTunnel(TunnelConfig(name = name, wgQuick = wgQuick, amQuick = amQuick ?: TunnelConfig.AM_QUICK_DEFAULT))
                    }
                }
        }
    }

    private suspend fun saveTunnelFromConfUri(name: String, uri: Uri, configType: ConfigType): Result<Unit> {
        val stream = getInputStreamFromUri(uri)
        return if (stream != null) {
            saveTunnelConfigFromStream(stream, name, configType)
            Result.Success(Unit)
        } else {
            Result.Error(Event.Error.FileReadFailed)
        }
    }

    private suspend fun addTunnel(tunnelConfig: TunnelConfig) {
        val firstTunnel = appDataRepository.tunnels.count() == 0
        saveTunnel(tunnelConfig)
        if (firstTunnel) WireGuardAutoTunnel.requestTunnelTileServiceStateUpdate(application)
    }

    fun pauseAutoTunneling() =
        viewModelScope.launch {
            appDataRepository.settings.save(uiState.value.settings.copy(isAutoTunnelPaused = true))
            WireGuardAutoTunnel.requestAutoTunnelTileServiceUpdate(application)
        }

    fun resumeAutoTunneling() =
        viewModelScope.launch {
            appDataRepository.settings.save(uiState.value.settings.copy(isAutoTunnelPaused = false))
            WireGuardAutoTunnel.requestAutoTunnelTileServiceUpdate(application)
        }

    private suspend fun saveTunnel(tunnelConfig: TunnelConfig) {
        appDataRepository.tunnels.save(tunnelConfig)
    }

    private fun getFileNameByCursor(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use {
            return getDisplayNameByCursor(it)
        }
        return null
    }

    private fun getDisplayNameColumnIndex(cursor: Cursor): Int? {
        val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        return if (columnIndex != -1) {
            return columnIndex
        } else {
            null
        }
    }

    private fun getDisplayNameByCursor(cursor: Cursor): String? {
        return if (cursor.moveToFirst()) {
            val index = getDisplayNameColumnIndex(cursor)
            if (index != null) {
                cursor.getString(index)
            } else null
        } else null
    }

    private fun isValidUriContentScheme(uri: Uri): Boolean {
        return uri.scheme == Constants.URI_CONTENT_SCHEME
    }

    private fun getFileName(context: Context, uri: Uri): String {
        return getFileNameByCursor(context, uri) ?: NumberUtils.generateRandomTunnelName()
    }

    private fun getNameFromFileName(fileName: String): String {
        return fileName.substring(0, fileName.lastIndexOf('.'))
    }

    private fun getFileExtensionFromFileName(fileName: String): String {
        return try {
            fileName.substring(fileName.lastIndexOf('.'))
        } catch (e: Exception) {
            Timber.e(e)
            ""
        }
    }

    private fun saveSettings(settings: Settings) =
        viewModelScope.launch(Dispatchers.IO) { appDataRepository.settings.save(settings) }


    fun onCopyTunnel(tunnel: TunnelConfig?) = viewModelScope.launch {
        tunnel?.let {
            saveTunnel(
                TunnelConfig(
                    name = it.name.plus(NumberUtils.randomThree()),
                    wgQuick = it.wgQuick,
                ),
            )
        }
    }
}
