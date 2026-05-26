package com.atombits.pocopaw

import android.content.Context

internal data class ProviderRuntimeConfig(
    val apiKey: String,
    val model: String,
    val endpoint: String,
    val apiStyle: ProviderApiStyle
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank()
}

internal enum class ProviderApiStyle {
    OPENAI_CHAT,
    GEMINI_GENERATE_CONTENT
}

internal enum class ProviderProfileId {
    DOMESTIC_DEFAULT,
    GLOBAL_DEFAULT,
    CUSTOM
}

internal enum class RegionMode {
    DOMESTIC,
    GLOBAL,
    CUSTOM
}

internal enum class SemanticProviderKind {
    DEEPSEEK,
    OPENAI,
    GEMINI,
    QWEN
}

internal enum class VisionProviderKind {
    DEEPSEEK_VISION,
    QWEN_VISION,
    GEMINI_VISION,
    OPENAI_VISION
}

internal data class ModelControlSupport(
    val thinkingSupported: Boolean,
    val searchSupported: Boolean
)

internal enum class SemanticThinkingControlBackend {
    NONE,
    DEEPSEEK_DEFAULT_ON,
    QWEN_ENABLE_THINKING,
    GEMINI_THINKING_BUDGET,
    OPENAI_REASONING_EFFORT
}

internal data class SemanticModelControlSupport(
    val controls: ModelControlSupport,
    val thinkingBackend: SemanticThinkingControlBackend = SemanticThinkingControlBackend.NONE
)

internal val CUSTOM_SEMANTIC_MODEL_OPTIONS = listOf(
    BuildConfig.DEEPSEEK_MODEL_FAST,
    BuildConfig.DEEPSEEK_MODEL_EXPERT,
    BuildConfig.QWEN_VISION_MODEL_FAST,
    BuildConfig.QWEN_VISION_MODEL_EXPERT,
    BuildConfig.GEMINI_MODEL_FAST,
    BuildConfig.GEMINI_MODEL_EXPERT,
    BuildConfig.OPENAI_MODEL_FAST,
    BuildConfig.OPENAI_MODEL_EXPERT
).map { value ->
    value.trim()
}.filter { value ->
    value.isNotBlank()
}.distinct()

internal val CUSTOM_MODEL_OPTIONS = CUSTOM_SEMANTIC_MODEL_OPTIONS

internal val CUSTOM_VISION_MODEL_OPTIONS = CUSTOM_SEMANTIC_MODEL_OPTIONS.filterNot { option ->
    option.startsWith("deepseek", ignoreCase = true)
}

internal enum class SearchProviderKind {
    ALIYUN_OPENSEARCH,
    GOOGLE_CSE
}

internal data class SemanticProviderRuntimeConfig(
    val provider: SemanticProviderKind,
    val apiKey: String,
    val endpoint: String,
    val fastModel: String,
    val expertModel: String
) {
    fun modelForTier(tier: SemanticModelTier): String = when (tier) {
        SemanticModelTier.FAST -> fastModel
        SemanticModelTier.EXPERT -> expertModel
    }
}

internal data class VisionProviderRuntimeConfig(
    val provider: VisionProviderKind,
    val apiKey: String,
    val endpoint: String,
    val fastModel: String,
    val expertModel: String,
    val modelTier: SemanticModelTier = SemanticModelTier.FAST
) {
    fun modelForTier(tier: SemanticModelTier): String = when (tier) {
        SemanticModelTier.FAST -> fastModel
        SemanticModelTier.EXPERT -> expertModel
    }

    val model: String
        get() = modelForTier(modelTier)
}

internal data class SearchProviderRuntimeConfig(
    val provider: SearchProviderKind,
    val apiKey: String,
    val endpoint: String,
    val engineId: String = "",
    val workspace: String = "",
    val serviceId: String = "",
    val gl: String = "us",
    val hl: String = "en",
    val safe: String = "active"
)

internal data class ProviderProfileRuntimeConfig(
    val profileId: ProviderProfileId,
    val regionMode: RegionMode,
    val semantic: SemanticProviderRuntimeConfig,
    val vision: VisionProviderRuntimeConfig,
    val search: SearchProviderRuntimeConfig,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
)

private data class RuntimeConnectionDescriptor(
    val apiKey: String,
    val endpoint: String,
    val apiStyle: ProviderApiStyle
)

private data class SemanticProviderDescriptor(
    val kind: SemanticProviderKind,
    val connection: RuntimeConnectionDescriptor,
    val fastModel: String,
    val expertModel: String
) {
    fun toRuntimeConfig(): SemanticProviderRuntimeConfig {
        return SemanticProviderRuntimeConfig(
            provider = kind,
            apiKey = connection.apiKey,
            endpoint = connection.endpoint,
            fastModel = fastModel,
            expertModel = expertModel
        )
    }
}

private data class VisionProviderDescriptor(
    val kind: VisionProviderKind,
    val connection: RuntimeConnectionDescriptor,
    val fastModel: String,
    val expertModel: String,
    val selectedModel: String
) {
    fun inferredTier(fallback: SemanticModelTier): SemanticModelTier {
        return inferSelectedTier(
            selectedModel = selectedModel,
            fastModel = fastModel,
            expertModel = expertModel,
            fallback = fallback
        )
    }

    fun toRuntimeConfig(modelTier: SemanticModelTier): VisionProviderRuntimeConfig {
        return VisionProviderRuntimeConfig(
            provider = kind,
            apiKey = connection.apiKey,
            endpoint = connection.endpoint,
            fastModel = fastModel,
            expertModel = expertModel,
            modelTier = modelTier
        )
    }
}

private data class SearchProviderDescriptor(
    val kind: SearchProviderKind,
    val apiKey: String,
    val endpoint: String,
    val engineId: String = "",
    val workspace: String = "",
    val serviceId: String = "",
    val defaultGl: String = "us",
    val defaultHl: String = "en",
    val defaultSafe: String = "active"
) {
    fun toRuntimeConfig(
        gl: String = defaultGl,
        hl: String = defaultHl,
        safe: String = defaultSafe
    ): SearchProviderRuntimeConfig {
        return SearchProviderRuntimeConfig(
            provider = kind,
            apiKey = apiKey,
            endpoint = endpoint,
            engineId = engineId,
            workspace = workspace,
            serviceId = serviceId,
            gl = gl,
            hl = hl,
            safe = safe
        )
    }
}

private data class ProviderProfilePresetDescriptor(
    val profileId: ProviderProfileId,
    val regionMode: RegionMode,
    val semanticProvider: SemanticProviderKind,
    val visionProvider: VisionProviderKind,
    val searchProvider: SearchProviderKind,
    val defaultVisionTierFallback: SemanticModelTier
)

private fun compatibleChatConnection(apiKey: String, endpoint: String): RuntimeConnectionDescriptor {
    return RuntimeConnectionDescriptor(
        apiKey = apiKey,
        endpoint = endpoint,
        apiStyle = ProviderApiStyle.OPENAI_CHAT
    )
}

private fun geminiConnection(apiKey: String, endpoint: String): RuntimeConnectionDescriptor {
    return RuntimeConnectionDescriptor(
        apiKey = apiKey,
        endpoint = endpoint,
        apiStyle = ProviderApiStyle.GEMINI_GENERATE_CONTENT
    )
}

private val semanticProviderRegistry: Map<SemanticProviderKind, SemanticProviderDescriptor> by lazy(LazyThreadSafetyMode.NONE) {
    mapOf(
        SemanticProviderKind.DEEPSEEK to SemanticProviderDescriptor(
            kind = SemanticProviderKind.DEEPSEEK,
            connection = compatibleChatConnection(
                apiKey = BuildConfig.DEEPSEEK_API_KEY.trim(),
                endpoint = BuildConfig.DEEPSEEK_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.DEEPSEEK_MODEL_FAST.trim(),
            expertModel = BuildConfig.DEEPSEEK_MODEL_EXPERT.trim()
        ),
        SemanticProviderKind.OPENAI to SemanticProviderDescriptor(
            kind = SemanticProviderKind.OPENAI,
            connection = compatibleChatConnection(
                apiKey = BuildConfig.OPENAI_API_KEY.trim(),
                endpoint = BuildConfig.OPENAI_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.OPENAI_MODEL_FAST.trim(),
            expertModel = BuildConfig.OPENAI_MODEL_EXPERT.trim()
        ),
        SemanticProviderKind.GEMINI to SemanticProviderDescriptor(
            kind = SemanticProviderKind.GEMINI,
            connection = geminiConnection(
                apiKey = BuildConfig.GEMINI_API_KEY.trim(),
                endpoint = BuildConfig.GEMINI_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.GEMINI_MODEL_FAST.trim(),
            expertModel = BuildConfig.GEMINI_MODEL_EXPERT.trim()
        ),
        SemanticProviderKind.QWEN to SemanticProviderDescriptor(
            kind = SemanticProviderKind.QWEN,
            connection = compatibleChatConnection(
                apiKey = BuildConfig.QWEN_VISION_API_KEY.trim(),
                endpoint = BuildConfig.QWEN_VISION_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.QWEN_VISION_MODEL_FAST.trim(),
            expertModel = BuildConfig.QWEN_VISION_MODEL_EXPERT.trim()
        )
    )
}

private val visionProviderRegistry: Map<VisionProviderKind, VisionProviderDescriptor> by lazy(LazyThreadSafetyMode.NONE) {
    mapOf(
        VisionProviderKind.DEEPSEEK_VISION to VisionProviderDescriptor(
            kind = VisionProviderKind.DEEPSEEK_VISION,
            connection = compatibleChatConnection(
                apiKey = BuildConfig.DEEPSEEK_API_KEY.trim(),
                endpoint = BuildConfig.DEEPSEEK_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.DEEPSEEK_MODEL_FAST.trim(),
            expertModel = BuildConfig.DEEPSEEK_MODEL_EXPERT.trim(),
            selectedModel = BuildConfig.DEEPSEEK_MODEL.trim()
        ),
        VisionProviderKind.QWEN_VISION to VisionProviderDescriptor(
            kind = VisionProviderKind.QWEN_VISION,
            connection = compatibleChatConnection(
                apiKey = BuildConfig.QWEN_VISION_API_KEY.trim(),
                endpoint = BuildConfig.QWEN_VISION_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.QWEN_VISION_MODEL_FAST.trim(),
            expertModel = BuildConfig.QWEN_VISION_MODEL_EXPERT.trim(),
            selectedModel = BuildConfig.QWEN_VISION_MODEL.trim()
        ),
        VisionProviderKind.GEMINI_VISION to VisionProviderDescriptor(
            kind = VisionProviderKind.GEMINI_VISION,
            connection = geminiConnection(
                apiKey = BuildConfig.GEMINI_API_KEY.trim(),
                endpoint = BuildConfig.GEMINI_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.GEMINI_MODEL_FAST.trim(),
            expertModel = BuildConfig.GEMINI_MODEL_EXPERT.trim(),
            selectedModel = BuildConfig.GEMINI_MODEL.trim()
        ),
        VisionProviderKind.OPENAI_VISION to VisionProviderDescriptor(
            kind = VisionProviderKind.OPENAI_VISION,
            connection = compatibleChatConnection(
                apiKey = BuildConfig.OPENAI_API_KEY.trim(),
                endpoint = BuildConfig.OPENAI_ENDPOINT.trim()
            ),
            fastModel = BuildConfig.OPENAI_MODEL_FAST.trim(),
            expertModel = BuildConfig.OPENAI_MODEL_EXPERT.trim(),
            selectedModel = BuildConfig.OPENAI_MODEL.trim()
        )
    )
}

private val searchProviderRegistry: Map<SearchProviderKind, SearchProviderDescriptor> by lazy(LazyThreadSafetyMode.NONE) {
    mapOf(
        SearchProviderKind.ALIYUN_OPENSEARCH to SearchProviderDescriptor(
            kind = SearchProviderKind.ALIYUN_OPENSEARCH,
            apiKey = BuildConfig.OPENSEARCH_API_KEY.trim(),
            endpoint = BuildConfig.OPENSEARCH_ENDPOINT.trim(),
            workspace = BuildConfig.OPENSEARCH_WORKSPACE.trim(),
            serviceId = BuildConfig.OPENSEARCH_SERVICE_ID.trim(),
            defaultGl = "cn",
            defaultHl = "zh-CN",
            defaultSafe = "off"
        ),
        SearchProviderKind.GOOGLE_CSE to SearchProviderDescriptor(
            kind = SearchProviderKind.GOOGLE_CSE,
            apiKey = BuildConfig.GOOGLE_SEARCH_API_KEY.trim(),
            endpoint = BuildConfig.GOOGLE_SEARCH_ENDPOINT.trim(),
            engineId = BuildConfig.GOOGLE_SEARCH_ENGINE_ID.trim(),
            defaultGl = "us",
            defaultHl = "en",
            defaultSafe = "active"
        )
    )
}

private val providerProfilePresetRegistry: Map<ProviderProfileId, ProviderProfilePresetDescriptor> = mapOf(
    ProviderProfileId.DOMESTIC_DEFAULT to ProviderProfilePresetDescriptor(
        profileId = ProviderProfileId.DOMESTIC_DEFAULT,
        regionMode = RegionMode.DOMESTIC,
        semanticProvider = SemanticProviderKind.DEEPSEEK,
        visionProvider = VisionProviderKind.QWEN_VISION,
        searchProvider = SearchProviderKind.ALIYUN_OPENSEARCH,
        defaultVisionTierFallback = SemanticModelTier.EXPERT
    ),
    ProviderProfileId.GLOBAL_DEFAULT to ProviderProfilePresetDescriptor(
        profileId = ProviderProfileId.GLOBAL_DEFAULT,
        regionMode = RegionMode.GLOBAL,
        semanticProvider = SemanticProviderKind.OPENAI,
        visionProvider = VisionProviderKind.GEMINI_VISION,
        searchProvider = SearchProviderKind.GOOGLE_CSE,
        defaultVisionTierFallback = SemanticModelTier.FAST
    )
)

private val defaultSemanticModelControlSupport = SemanticModelControlSupport(
    controls = ModelControlSupport(
        thinkingSupported = false,
        searchSupported = true
    )
)

private val semanticModelControlPrefixes = listOf(
    "deepseek" to SemanticModelControlSupport(
        controls = ModelControlSupport(
            thinkingSupported = true,
            searchSupported = true
        ),
        thinkingBackend = SemanticThinkingControlBackend.DEEPSEEK_DEFAULT_ON
    ),
    "qwen" to SemanticModelControlSupport(
        controls = ModelControlSupport(
            thinkingSupported = true,
            searchSupported = true
        ),
        thinkingBackend = SemanticThinkingControlBackend.QWEN_ENABLE_THINKING
    ),
    "gemini" to SemanticModelControlSupport(
        controls = ModelControlSupport(
            thinkingSupported = true,
            searchSupported = true
        ),
        thinkingBackend = SemanticThinkingControlBackend.GEMINI_THINKING_BUDGET
    ),
    "gpt" to SemanticModelControlSupport(
        controls = ModelControlSupport(
            thinkingSupported = true,
            searchSupported = true
        ),
        thinkingBackend = SemanticThinkingControlBackend.OPENAI_REASONING_EFFORT
    )
)

private val defaultVisionModelControlSupport = ModelControlSupport(
    thinkingSupported = false,
    searchSupported = false
)

private val visionModelControlPrefixes = listOf(
    "deepseek" to defaultVisionModelControlSupport,
    "qwen" to defaultVisionModelControlSupport,
    "gemini" to defaultVisionModelControlSupport,
    "gpt" to defaultVisionModelControlSupport
)

private val semanticModelProviderPrefixes = listOf(
    "deepseek" to SemanticProviderKind.DEEPSEEK,
    "qwen" to SemanticProviderKind.QWEN,
    "gemini" to SemanticProviderKind.GEMINI,
    "gpt" to SemanticProviderKind.OPENAI
)

private val visionModelProviderPrefixes = listOf(
    "deepseek" to VisionProviderKind.DEEPSEEK_VISION,
    "qwen" to VisionProviderKind.QWEN_VISION,
    "gemini" to VisionProviderKind.GEMINI_VISION,
    "gpt" to VisionProviderKind.OPENAI_VISION
)

private fun semanticProviderDescriptor(kind: SemanticProviderKind): SemanticProviderDescriptor {
    return semanticProviderRegistry.getValue(kind)
}

private fun visionProviderDescriptor(kind: VisionProviderKind): VisionProviderDescriptor {
    return visionProviderRegistry.getValue(kind)
}

private fun searchProviderDescriptor(kind: SearchProviderKind): SearchProviderDescriptor {
    return searchProviderRegistry.getValue(kind)
}

private fun buildProfilePresetFromRegistry(profileId: ProviderProfileId): ProviderProfileRuntimeConfig {
    val preset = providerProfilePresetRegistry.getValue(profileId)
    val semantic = semanticProviderDescriptor(preset.semanticProvider)
    val vision = visionProviderDescriptor(preset.visionProvider)
    val search = searchProviderDescriptor(preset.searchProvider)
    return ProviderProfileRuntimeConfig(
        profileId = preset.profileId,
        regionMode = preset.regionMode,
        semantic = semantic.toRuntimeConfig(),
        vision = vision.toRuntimeConfig(
            modelTier = vision.inferredTier(preset.defaultVisionTierFallback)
        ),
        search = search.toRuntimeConfig()
    )
}

private fun <T> resolveProviderByModelPrefix(
    modelName: String,
    fallback: T,
    prefixMappings: List<Pair<String, T>>
): T {
    val normalized = modelName.trim().lowercase()
    return prefixMappings.firstOrNull { (prefix, _) ->
        normalized.startsWith(prefix)
    }?.second ?: fallback
}

private const val CURRENT_SCHEMA_VERSION = 1
private const val PROVIDER_PROFILE_PREFS_NAME = "provider_profile_settings"
private const val KEY_SCHEMA_VERSION = "schema_version"
private const val KEY_PROFILE_ID = "profile_id"
private const val KEY_REGION_MODE = "region_mode"
private const val KEY_SEMANTIC_PROVIDER = "semantic_provider"
private const val KEY_SEMANTIC_FAST_MODEL = "semantic_fast_model"
private const val KEY_SEMANTIC_EXPERT_MODEL = "semantic_expert_model"
private const val KEY_SEMANTIC_ENDPOINT = "semantic_endpoint"
private const val KEY_VISION_PROVIDER = "vision_provider"
private const val KEY_VISION_FAST_MODEL = "vision_fast_model"
private const val KEY_VISION_EXPERT_MODEL = "vision_expert_model"
private const val KEY_VISION_MODEL_TIER = "vision_model_tier"
private const val KEY_VISION_MODEL = "vision_model"
private const val KEY_VISION_ENDPOINT = "vision_endpoint"
private const val KEY_SEARCH_PROVIDER = "search_provider"
private const val KEY_SEARCH_ENDPOINT = "search_endpoint"
private const val KEY_SEARCH_GL = "search_query_gl"
private const val KEY_SEARCH_HL = "search_query_hl"
private const val KEY_SEARCH_SAFE = "search_safe"

internal object ProviderProfileRuntime {
    @Volatile
    private var config: ProviderProfileRuntimeConfig = buildLockedDemoProfileFromBuildConfig()

    fun current(): ProviderProfileRuntimeConfig = config

    fun update(newConfig: ProviderProfileRuntimeConfig): ProviderProfileRuntimeConfig {
        config = newConfig
        return newConfig
    }
}

internal class ProviderProfileSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PROVIDER_PROFILE_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun readConfig(): ProviderProfileRuntimeConfig {
        val locked = buildLockedDemoProfileFromBuildConfig()
        writeConfig(locked)
        return locked
    }

    fun writeConfig(config: ProviderProfileRuntimeConfig): ProviderProfileRuntimeConfig {
        val normalized = buildLockedDemoProfileFromBuildConfig()
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putString(KEY_PROFILE_ID, normalized.profileId.name)
            .putString(KEY_REGION_MODE, normalized.regionMode.name)
            .putString(KEY_SEMANTIC_PROVIDER, normalized.semantic.provider.name)
            .putString(KEY_SEMANTIC_FAST_MODEL, normalized.semantic.fastModel)
            .putString(KEY_SEMANTIC_EXPERT_MODEL, normalized.semantic.expertModel)
            .putString(KEY_SEMANTIC_ENDPOINT, normalized.semantic.endpoint)
            .putString(KEY_VISION_PROVIDER, normalized.vision.provider.name)
            .putString(KEY_VISION_FAST_MODEL, normalized.vision.fastModel)
            .putString(KEY_VISION_EXPERT_MODEL, normalized.vision.expertModel)
            .putString(KEY_VISION_MODEL_TIER, normalized.vision.modelTier.name)
            .putString(KEY_VISION_MODEL, normalized.vision.model)
            .putString(KEY_VISION_ENDPOINT, normalized.vision.endpoint)
            .putString(KEY_SEARCH_PROVIDER, normalized.search.provider.name)
            .putString(KEY_SEARCH_ENDPOINT, normalized.search.endpoint)
            .putString(KEY_SEARCH_GL, normalized.search.gl)
            .putString(KEY_SEARCH_HL, normalized.search.hl)
            .putString(KEY_SEARCH_SAFE, normalized.search.safe)
            .apply()
        ProviderProfileRuntime.update(normalized)
        return normalized
    }

    fun applyStoredConfig(): ProviderProfileRuntimeConfig {
        val stored = buildLockedDemoProfileFromBuildConfig()
        writeConfig(stored)
        ProviderProfileRuntime.update(stored)
        return stored
    }

    private fun migrateLegacyConfigIfNeeded(): ProviderProfileRuntimeConfig {
        val migrated = defaultDomesticProfileFromBuildConfig()
        return writeConfig(migrated)
    }

    private fun readCurrentSchemaConfig(): ProviderProfileRuntimeConfig {
        val profileId = prefs.getString(KEY_PROFILE_ID, null)
            ?.toProviderProfileIdOrNull()
            ?: ProviderProfileId.DOMESTIC_DEFAULT
        val regionMode = prefs.getString(KEY_REGION_MODE, null)
            ?.toRegionModeOrNull()
            ?: RegionMode.DOMESTIC

        val fallback = when (profileId) {
            ProviderProfileId.GLOBAL_DEFAULT -> defaultGlobalProfileFromBuildConfig()
            ProviderProfileId.CUSTOM -> defaultDomesticProfileFromBuildConfig().copy(
                profileId = ProviderProfileId.CUSTOM,
                regionMode = RegionMode.CUSTOM
            )
            else -> defaultDomesticProfileFromBuildConfig()
        }

        val semanticProvider = prefs.getString(KEY_SEMANTIC_PROVIDER, null)
            ?.toSemanticProviderKindOrNull()
            ?: fallback.semantic.provider
        val semanticFastModel = prefs.getString(KEY_SEMANTIC_FAST_MODEL, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: fallback.semantic.fastModel
        val semanticExpertModel = prefs.getString(KEY_SEMANTIC_EXPERT_MODEL, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: fallback.semantic.expertModel
        val semanticEndpoint = prefs.getString(KEY_SEMANTIC_ENDPOINT, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: fallback.semantic.endpoint

        val visionProvider = prefs.getString(KEY_VISION_PROVIDER, null)
            ?.toVisionProviderKindOrNull()
            ?: fallback.vision.provider
        val legacyVisionModel = prefs.getString(KEY_VISION_MODEL, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
        val visionFastModel = prefs.getString(KEY_VISION_FAST_MODEL, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: if (profileId == ProviderProfileId.CUSTOM) {
                legacyVisionModel ?: fallback.vision.model
            } else {
                fallback.vision.fastModel
            }
        val normalizedVisionFastModel = if (profileId == ProviderProfileId.CUSTOM) {
            normalizeVisionCustomModelSelection(
                value = visionFastModel,
                fallback = CUSTOM_VISION_MODEL_OPTIONS.first()
            )
        } else {
            visionFastModel
        }
        val visionExpertModel = prefs.getString(KEY_VISION_EXPERT_MODEL, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: if (profileId == ProviderProfileId.CUSTOM) {
                legacyVisionModel ?: fallback.vision.model
            } else {
                fallback.vision.expertModel
            }
        val normalizedVisionExpertModel = if (profileId == ProviderProfileId.CUSTOM) {
            normalizeVisionCustomModelSelection(
                value = visionExpertModel,
                fallback = normalizedVisionFastModel
            )
        } else {
            visionExpertModel
        }
        val visionModelTier = prefs.getString(KEY_VISION_MODEL_TIER, null)
            ?.toSemanticModelTierOrNull()
            ?: inferSelectedTier(
                selectedModel = legacyVisionModel ?: fallback.vision.model,
                fastModel = normalizedVisionFastModel,
                expertModel = normalizedVisionExpertModel,
                fallback = fallback.vision.modelTier
            )
        val visionEndpoint = prefs.getString(KEY_VISION_ENDPOINT, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: fallback.vision.endpoint

        val searchProvider = prefs.getString(KEY_SEARCH_PROVIDER, null)
            ?.toSearchProviderKindOrNull()
            ?: fallback.search.provider
        val searchEndpoint = prefs.getString(KEY_SEARCH_ENDPOINT, null)
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: fallback.search.endpoint

        return ProviderProfileRuntimeConfig(
            profileId = profileId,
            regionMode = regionMode,
            semantic = SemanticProviderRuntimeConfig(
                provider = semanticProvider,
                apiKey = resolveSemanticApiKey(semanticProvider),
                endpoint = semanticEndpoint,
                fastModel = semanticFastModel,
                expertModel = semanticExpertModel
            ),
            vision = VisionProviderRuntimeConfig(
                provider = visionProvider,
                apiKey = resolveVisionApiKey(visionProvider),
                endpoint = visionEndpoint,
                fastModel = normalizedVisionFastModel,
                expertModel = normalizedVisionExpertModel,
                modelTier = visionModelTier
            ),
            search = SearchProviderRuntimeConfig(
                provider = searchProvider,
                apiKey = resolveSearchApiKey(searchProvider),
                endpoint = searchEndpoint,
                engineId = BuildConfig.GOOGLE_SEARCH_ENGINE_ID.trim(),
                workspace = BuildConfig.OPENSEARCH_WORKSPACE.trim(),
                serviceId = BuildConfig.OPENSEARCH_SERVICE_ID.trim(),
                gl = prefs.getString(KEY_SEARCH_GL, null)
                    ?.trim()
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: fallback.search.gl,
                hl = prefs.getString(KEY_SEARCH_HL, null)
                    ?.trim()
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: fallback.search.hl,
                safe = prefs.getString(KEY_SEARCH_SAFE, null)
                    ?.trim()
                    ?.takeIf { value -> value.isNotBlank() }
                    ?: fallback.search.safe
            )
        )
    }
}

internal fun ProviderRuntimeConfig.resolveSemanticRuntimeConfig(
    preferences: SemanticRuntimePreferences
): ProviderRuntimeConfig {
    return ProviderRuntimeConfigs.semanticRuntimeConfig(preferences)
}

internal fun SemanticModelTier.semanticModelName(): String {
    return ProviderRuntimeConfigs.semanticModelNameForTier(this)
}

internal object ProviderRuntimeConfigs {
    fun semanticRuntimeConfig(preferences: SemanticRuntimePreferences): ProviderRuntimeConfig {
        return semanticRuntimeConfig(preferences.modelTier)
    }

    fun semanticRuntimeConfig(tier: SemanticModelTier): ProviderRuntimeConfig {
        val semanticConfig = ProviderProfileRuntime.current().semantic
        return ProviderRuntimeConfig(
            apiKey = semanticConfig.apiKey,
            model = semanticConfig.modelForTier(tier),
            endpoint = semanticConfig.endpoint,
            apiStyle = resolveSemanticApiStyle(semanticConfig.provider)
        )
    }

    fun semanticProviderKind(): SemanticProviderKind {
        return ProviderProfileRuntime.current().semantic.provider
    }

    val vision: ProviderRuntimeConfig
        get() {
            val visionConfig = ProviderProfileRuntime.current().vision
            return ProviderRuntimeConfig(
                apiKey = visionConfig.apiKey,
                model = visionConfig.model,
                endpoint = visionConfig.endpoint,
                apiStyle = resolveVisionApiStyle(visionConfig.provider)
            )
        }

    fun visionProviderKind(): VisionProviderKind {
        return ProviderProfileRuntime.current().vision.provider
    }

    fun semanticModelNameForTier(tier: SemanticModelTier): String {
        return ProviderProfileRuntime.current().semantic.modelForTier(tier)
    }

    fun visionModelNameForTier(tier: SemanticModelTier): String {
        return ProviderProfileRuntime.current().vision.modelForTier(tier)
    }
}

private fun defaultDomesticProfileFromBuildConfig(): ProviderProfileRuntimeConfig {
    return buildProfilePresetFromRegistry(ProviderProfileId.DOMESTIC_DEFAULT)
}

private fun buildLockedDemoProfileFromBuildConfig(): ProviderProfileRuntimeConfig {
    val domestic = defaultDomesticProfileFromBuildConfig()
    val semanticModel = BuildConfig.DEEPSEEK_MODEL_FAST.trim().ifBlank { "deepseek-v4-flash" }
    val visionModel = BuildConfig.QWEN_VISION_MODEL.trim().ifBlank { "qwen3.6-plus" }
    return domestic.copy(
        profileId = ProviderProfileId.DOMESTIC_DEFAULT,
        regionMode = RegionMode.DOMESTIC,
        semantic = domestic.semantic.copy(
            provider = SemanticProviderKind.DEEPSEEK,
            apiKey = resolveSemanticApiKey(SemanticProviderKind.DEEPSEEK),
            endpoint = resolveSemanticEndpoint(SemanticProviderKind.DEEPSEEK),
            fastModel = semanticModel,
            expertModel = semanticModel
        ),
        vision = domestic.vision.copy(
            provider = VisionProviderKind.QWEN_VISION,
            apiKey = resolveVisionApiKey(VisionProviderKind.QWEN_VISION),
            endpoint = resolveVisionEndpoint(VisionProviderKind.QWEN_VISION),
            fastModel = visionModel,
            expertModel = visionModel,
            modelTier = SemanticModelTier.FAST
        ),
        search = domestic.search.copy(
            provider = SearchProviderKind.ALIYUN_OPENSEARCH,
            apiKey = resolveSearchApiKey(SearchProviderKind.ALIYUN_OPENSEARCH)
        ),
        schemaVersion = CURRENT_SCHEMA_VERSION
    )
}

private fun defaultGlobalProfileFromBuildConfig(): ProviderProfileRuntimeConfig {
    return buildProfilePresetFromRegistry(ProviderProfileId.GLOBAL_DEFAULT)
}

internal fun buildProviderProfilePreset(
    profileId: ProviderProfileId,
    currentConfig: ProviderProfileRuntimeConfig = ProviderProfileRuntime.current()
): ProviderProfileRuntimeConfig {
    return when (profileId) {
        ProviderProfileId.DOMESTIC_DEFAULT -> defaultDomesticProfileFromBuildConfig()
        ProviderProfileId.GLOBAL_DEFAULT -> defaultGlobalProfileFromBuildConfig()
        ProviderProfileId.CUSTOM -> currentConfig.copy(
            profileId = ProviderProfileId.CUSTOM,
            regionMode = RegionMode.CUSTOM,
            schemaVersion = CURRENT_SCHEMA_VERSION
        )
    }
}

internal fun buildCustomProviderProfile(
    currentConfig: ProviderProfileRuntimeConfig = ProviderProfileRuntime.current(),
    semanticTier: SemanticModelTier = SemanticModelTier.FAST
): ProviderProfileRuntimeConfig {
    val selectedSemanticModel = currentConfig.semantic.modelForTier(semanticTier)
    val selectedVisionModel = currentConfig.vision.model
    return currentConfig.copy(
        profileId = ProviderProfileId.CUSTOM,
        regionMode = RegionMode.CUSTOM,
        semantic = currentConfig.semantic.copy(
            fastModel = selectedSemanticModel,
            expertModel = selectedSemanticModel
        ),
        vision = currentConfig.vision.copy(
            fastModel = selectedVisionModel,
            expertModel = selectedVisionModel,
            modelTier = SemanticModelTier.FAST
        ),
        schemaVersion = CURRENT_SCHEMA_VERSION
    )
}

internal fun withCustomSemanticModel(
    config: ProviderProfileRuntimeConfig,
    modelName: String
): ProviderProfileRuntimeConfig {
    val normalizedModel = normalizeCustomModelSelection(modelName, config.semantic.fastModel)
    val provider = resolveSemanticProviderForModel(normalizedModel, config.semantic.provider)
    return config.copy(
        profileId = ProviderProfileId.CUSTOM,
        regionMode = RegionMode.CUSTOM,
        semantic = SemanticProviderRuntimeConfig(
            provider = provider,
            apiKey = resolveSemanticApiKey(provider),
            endpoint = resolveSemanticEndpoint(provider),
            fastModel = normalizedModel,
            expertModel = normalizedModel
        ),
        schemaVersion = CURRENT_SCHEMA_VERSION
    )
}

internal fun withCustomVisionModel(
    config: ProviderProfileRuntimeConfig,
    modelName: String
): ProviderProfileRuntimeConfig {
    val normalizedModel = normalizeVisionCustomModelSelection(
        value = modelName,
        fallback = CUSTOM_VISION_MODEL_OPTIONS.first()
    )
    val provider = resolveVisionProviderForModel(normalizedModel, config.vision.provider)
    return config.copy(
        profileId = ProviderProfileId.CUSTOM,
        regionMode = RegionMode.CUSTOM,
        vision = VisionProviderRuntimeConfig(
            provider = provider,
            apiKey = resolveVisionApiKey(provider),
            endpoint = resolveVisionEndpoint(provider),
            fastModel = normalizedModel,
            expertModel = normalizedModel,
            modelTier = SemanticModelTier.FAST
        ),
        schemaVersion = CURRENT_SCHEMA_VERSION
    )
}

internal fun withVisionModelTier(
    config: ProviderProfileRuntimeConfig,
    tier: SemanticModelTier
): ProviderProfileRuntimeConfig {
    return config.copy(
        vision = config.vision.copy(modelTier = tier),
        schemaVersion = CURRENT_SCHEMA_VERSION
    )
}

internal fun withSearchProvider(
    config: ProviderProfileRuntimeConfig,
    provider: SearchProviderKind
): ProviderProfileRuntimeConfig {
    val defaultSearch = when (provider) {
        SearchProviderKind.ALIYUN_OPENSEARCH -> defaultDomesticProfileFromBuildConfig().search
        SearchProviderKind.GOOGLE_CSE -> defaultGlobalProfileFromBuildConfig().search
    }
    return config.copy(
        profileId = ProviderProfileId.CUSTOM,
        regionMode = RegionMode.CUSTOM,
        search = defaultSearch,
        schemaVersion = CURRENT_SCHEMA_VERSION
    )
}

internal fun resolveSemanticApiKey(kind: SemanticProviderKind): String {
    return semanticProviderDescriptor(kind).connection.apiKey
}

internal fun resolveSemanticApiStyle(kind: SemanticProviderKind): ProviderApiStyle {
    return semanticProviderDescriptor(kind).connection.apiStyle
}

internal fun resolveVisionApiKey(kind: VisionProviderKind): String {
    return visionProviderDescriptor(kind).connection.apiKey
}

internal fun resolveSemanticEndpoint(kind: SemanticProviderKind): String {
    return semanticProviderDescriptor(kind).connection.endpoint
}

internal fun resolveVisionEndpoint(kind: VisionProviderKind): String {
    return visionProviderDescriptor(kind).connection.endpoint
}

internal fun resolveSemanticProviderForModel(
    modelName: String,
    fallback: SemanticProviderKind
): SemanticProviderKind {
    return resolveProviderByModelPrefix(modelName, fallback, semanticModelProviderPrefixes)
}

internal fun resolveVisionProviderForModel(
    modelName: String,
    fallback: VisionProviderKind
): VisionProviderKind {
    return resolveProviderByModelPrefix(modelName, fallback, visionModelProviderPrefixes)
}

internal fun resolveSearchApiKey(kind: SearchProviderKind): String {
    return when (kind) {
        SearchProviderKind.ALIYUN_OPENSEARCH -> BuildConfig.OPENSEARCH_API_KEY.trim()
        SearchProviderKind.GOOGLE_CSE -> BuildConfig.GOOGLE_SEARCH_API_KEY.trim()
    }
}

internal fun resolveSemanticModelControlSupport(modelName: String): SemanticModelControlSupport {
    return resolveProviderByModelPrefix(
        modelName = modelName,
        fallback = defaultSemanticModelControlSupport,
        prefixMappings = semanticModelControlPrefixes
    )
}

internal fun resolveSemanticModelControls(modelName: String): ModelControlSupport {
    return resolveSemanticModelControlSupport(modelName).controls
}

internal fun currentSemanticModelControls(preferences: SemanticRuntimePreferences): ModelControlSupport {
    return resolveSemanticModelControls(ProviderRuntimeConfigs.semanticModelNameForTier(preferences.modelTier))
}

internal fun coerceSemanticTurnOptionsForModel(
    modelName: String,
    requested: ChatTurnOptions
): ChatTurnOptions {
    val controls = resolveSemanticModelControls(modelName)
    return requested.copy(
        thinkingEnabled = requested.thinkingEnabled && controls.thinkingSupported,
        searchEnabled = requested.searchEnabled && controls.searchSupported
    )
}

internal fun coerceSemanticTurnOptions(
    preferences: SemanticRuntimePreferences,
    requested: ChatTurnOptions
): ChatTurnOptions {
    return coerceSemanticTurnOptionsForModel(
        modelName = ProviderRuntimeConfigs.semanticModelNameForTier(preferences.modelTier),
        requested = requested
    )
}

internal fun resolveVisionModelControls(modelName: String): ModelControlSupport {
    return resolveProviderByModelPrefix(
        modelName = modelName,
        fallback = defaultVisionModelControlSupport,
        prefixMappings = visionModelControlPrefixes
    )
}

internal fun normalizeCustomModelSelection(value: String?, fallback: String): String {
    return normalizeSemanticCustomModelSelection(value, fallback)
}

internal fun normalizeSemanticCustomModelSelection(value: String?, fallback: String): String {
    return normalizeCustomModelSelection(value, fallback, CUSTOM_SEMANTIC_MODEL_OPTIONS)
}

internal fun normalizeVisionCustomModelSelection(value: String?, fallback: String): String {
    return normalizeCustomModelSelection(value, fallback, CUSTOM_VISION_MODEL_OPTIONS)
}

private fun normalizeCustomModelSelection(
    value: String?,
    fallback: String,
    options: List<String>
): String {
    val normalizedValue = value.orEmpty().trim()
    val normalizedFallback = fallback.trim()
    return options.firstOrNull { option ->
        option.equals(normalizedValue, ignoreCase = true)
    } ?: options.firstOrNull { option ->
        option.equals(normalizedFallback, ignoreCase = true)
    } ?: options.first()
}

internal fun inferSelectedTier(
    selectedModel: String,
    fastModel: String,
    expertModel: String,
    fallback: SemanticModelTier
): SemanticModelTier {
    return when {
        selectedModel.equals(expertModel, ignoreCase = true) -> SemanticModelTier.EXPERT
        selectedModel.equals(fastModel, ignoreCase = true) -> SemanticModelTier.FAST
        else -> fallback
    }
}

private fun String.toProviderProfileIdOrNull(): ProviderProfileId? {
    return runCatching { ProviderProfileId.valueOf(trim().uppercase()) }.getOrNull()
}

private fun String.toSemanticModelTierOrNull(): SemanticModelTier? {
    return runCatching { SemanticModelTier.valueOf(trim().uppercase()) }.getOrNull()
}

private fun String.toRegionModeOrNull(): RegionMode? {
    return runCatching { RegionMode.valueOf(trim().uppercase()) }.getOrNull()
}

private fun String.toSemanticProviderKindOrNull(): SemanticProviderKind? {
    return runCatching { SemanticProviderKind.valueOf(trim().uppercase()) }.getOrNull()
}

private fun String.toVisionProviderKindOrNull(): VisionProviderKind? {
    return runCatching { VisionProviderKind.valueOf(trim().uppercase()) }.getOrNull()
}

private fun String.toSearchProviderKindOrNull(): SearchProviderKind? {
    return runCatching { SearchProviderKind.valueOf(trim().uppercase()) }.getOrNull()
}

internal fun resolveVisionApiStyle(kind: VisionProviderKind): ProviderApiStyle {
    return visionProviderDescriptor(kind).connection.apiStyle
}