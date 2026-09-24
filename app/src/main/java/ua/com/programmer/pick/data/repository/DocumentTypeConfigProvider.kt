package ua.com.programmer.pick.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.com.programmer.pick.data.local.preferences.AppPreferences
import ua.com.programmer.pick.data.remote.transport.AvailableDocumentTypeDto
import ua.com.programmer.pick.domain.model.AvailableDocumentType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for per-document-type capability flags. Backed by
 * [AppPreferences.availableDocumentTypes] (JSON blob written by the sync
 * orchestrator on every successful USER_LOGIN_RESULT), so the flags refresh
 * within the next login without a relaunch.
 *
 * Callers receive a [Flow] of `code -> AvailableDocumentType`; missing codes
 * yield `null` from [forCode], signaling "ERP didn't declare this type — apply
 * client defaults via [AvailableDocumentType]'s `*OrDefault` accessors".
 */
@Singleton
class DocumentTypeConfigProvider @Inject constructor(
    private val appPreferences: AppPreferences,
    private val gson: Gson
) {
    private val listType = object : TypeToken<List<AvailableDocumentTypeDto>>() {}.type

    val configs: Flow<Map<String, AvailableDocumentType>> =
        appPreferences.availableDocumentTypes.map { json -> parse(json) }

    private fun parse(json: String?): Map<String, AvailableDocumentType> {
        if (json.isNullOrEmpty()) return emptyMap()
        val dtos: List<AvailableDocumentTypeDto> = try {
            gson.fromJson(json, listType) ?: return emptyMap()
        } catch (_: Exception) {
            return emptyMap()
        }
        return dtos.associate {
            it.code to AvailableDocumentType(
                code = it.code,
                description = it.description,
                allowsOverPlan = it.allowsOverPlan,
                allowsExtraLines = it.allowsExtraLines,
                requiresPlan = it.requiresPlan,
                mode = it.mode,
                wmsFlow = it.wmsFlow
            )
        }
    }
}
