package com.atombits.pocopaw

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceDiscoveryManualScanRunnerTest {

    @Test
    fun run_capturesScreenshotsOffCallingThread() = runBlocking {
        val callerThread = Thread.currentThread()
        var captureThread: Thread? = null
        val runner = PreferenceDiscoveryManualScanRunner(
            appContext = TestPreferenceDiscoveryContext(),
            screenCaptureCoordinator = object : ScreenCaptureCoordinator {
                override fun hasPermission(): Boolean = true

                override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot {
                    captureThread = Thread.currentThread()
                    return ScreenCaptureSnapshot(
                        imageDataUrl = "data:image/jpeg;base64,AAA",
                        captureWidth = 1080,
                        captureHeight = 2400
                    )
                }
            },
            visionResolver = object : PreferenceDiscoveryVisionResolver {
                override suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution {
                    return PreferenceDiscoveryVisionResolution.failure("skip writeback")
                }
            },
            appLauncher = { _, _ -> true },
            countdownNotifier = { _, _ -> },
            accessibilityReady = { true },
            pageSwiper = { true },
            pause = { },
            nowProvider = { 1357L },
            stringResolver = ::resolveTestString
        )

        runner.run(
            store = PrototypeStoreData(),
            request = PreferenceDiscoveryManualScanRequest(
                target = PreferenceDiscoveryAppTarget(
                    domain = CapabilityDomain.SHOPPING,
                    appId = "jd",
                    displayName = "京东",
                    packageName = "com.jingdong.app.mall"
                ),
                pageCount = 1,
                countdownSeconds = 0
            )
        )

        assertNotNull(captureThread)
        assertTrue("capture should not run on the calling thread", captureThread !== callerThread)
    }

    @Test
    fun run_relaunchesAgentApp_immediatelyAfterCaptureBeforeVisionResolution() = runBlocking {
        val launchedPackages = mutableListOf<String>()
        val events = mutableListOf<String>()
        val countdownMessages = mutableListOf<String>()
        val pauses = mutableListOf<Long>()
        var swipeCount = 0
        var visionRequest: PreferenceDiscoveryVisionRequest? = null
        val screenshots = ArrayDeque(
            listOf(
                ScreenCaptureSnapshot(
                    imageDataUrl = "data:image/jpeg;base64,AAA",
                    captureWidth = 1080,
                    captureHeight = 2400
                ),
                ScreenCaptureSnapshot(
                    imageDataUrl = "data:image/jpeg;base64,BBB",
                    captureWidth = 1080,
                    captureHeight = 2400
                ),
                ScreenCaptureSnapshot(
                    imageDataUrl = "data:image/jpeg;base64,CCC",
                    captureWidth = 1080,
                    captureHeight = 2400
                )
            )
        )
        val runner = PreferenceDiscoveryManualScanRunner(
            appContext = TestPreferenceDiscoveryContext(),
            screenCaptureCoordinator = object : ScreenCaptureCoordinator {
                override fun hasPermission(): Boolean = true

                override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot? {
                    return if (screenshots.isEmpty()) null else screenshots.removeFirst()
                }
            },
            visionResolver = object : PreferenceDiscoveryVisionResolver {
                override suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution {
                    events += "vision"
                    visionRequest = request
                    return PreferenceDiscoveryVisionResolution.success(
                        PreferenceDiscoveryVisionResult(
                            observations = listOf(
                                PreferenceDiscoveryStructuredObservation(
                                    anchorObject = "通勤外套",
                                    slotKey = "brand",
                                    slotValue = "Uniqlo",
                                    freshnessHint = "LONG_TERM"
                                )
                            ),
                            summary = "found brand preference"
                        ),
                    )
                }
            },
            appLauncher = { _, packageName ->
                events += "launch:$packageName"
                launchedPackages.add(packageName)
                true
            },
            countdownNotifier = { _, message -> countdownMessages.add(message) },
            accessibilityReady = { true },
            pageSwiper = {
                swipeCount += 1
                true
            },
            pause = { durationMs -> pauses += durationMs },
            nowProvider = { 1234L },
            stringResolver = ::resolveTestString
        )

        val outcome = runner.run(
            store = PrototypeStoreData(),
            request = PreferenceDiscoveryManualScanRequest(
                target = PreferenceDiscoveryAppTarget(
                    domain = CapabilityDomain.SHOPPING,
                    appId = "jd",
                    displayName = "京东",
                    packageName = "com.jingdong.app.mall"
                ),
                pageCount = 3,
                countdownSeconds = 0
            )
        )

        assertTrue(outcome.applied)
        assertEquals(3, outcome.capturedPageCount)
        assertEquals(listOf("com.jingdong.app.mall", "com.atombits.pocopaw"), launchedPackages)
        assertEquals(
            listOf(
                "launch:com.jingdong.app.mall",
                "launch:com.atombits.pocopaw",
                "vision"
            ),
            events
        )
        assertEquals(listOf("Open the 京东 order/history list within 0 seconds."), countdownMessages)
        assertEquals(listOf(1500L, 0L, 1400L, 1400L), pauses)
        assertEquals(2, swipeCount)
        assertNotNull(visionRequest)
        assertEquals(
            listOf(
                "data:image/jpeg;base64,AAA",
                "data:image/jpeg;base64,BBB",
                "data:image/jpeg;base64,CCC"
            ),
            visionRequest?.screenshots
        )
        val memoryState = outcome.updatedStore.memoryState
        assertNotNull(memoryState)
        assertEquals(1234L, memoryState?.preferenceDiscoveryRuntime?.lastConsumedAt)
        assertEquals(
            listOf("com.jingdong.app.mall"),
            memoryState?.preferenceDiscoveryRuntime?.attemptedSourceApps
        )
        assertEquals(
            "Preference discovery updated from 3 captured page(s).",
            memoryState?.preferenceDiscoveryRuntime?.lastOutcomeMessage
        )
        val facts = memoryState?.appScanPreferenceFacts() ?: error("missing structured preference facts")
        assertEquals(1, facts.size)
        assertEquals("shopping", facts.single().domain)
        assertEquals("brand", facts.single().facetKey)
        assertEquals("Uniqlo", facts.single().facetValue)
        assertEquals("com.jingdong.app.mall", facts.single().sourceApp)
    }

    @Test
    fun run_syncsCurrentMemorySliceBeforeNormalization() = runBlocking {
        val runner = PreferenceDiscoveryManualScanRunner(
            appContext = TestPreferenceDiscoveryContext(),
            screenCaptureCoordinator = object : ScreenCaptureCoordinator {
                override fun hasPermission(): Boolean = true

                override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot {
                    return ScreenCaptureSnapshot(
                        imageDataUrl = "data:image/jpeg;base64,AAA",
                        captureWidth = 1080,
                        captureHeight = 2400
                    )
                }
            },
            visionResolver = object : PreferenceDiscoveryVisionResolver {
                override suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution {
                    return PreferenceDiscoveryVisionResolution.success(
                        PreferenceDiscoveryVisionResult(
                            observations = listOf(
                                PreferenceDiscoveryStructuredObservation(
                                    anchorObject = "通勤外套",
                                    slotKey = "brand",
                                    slotValue = "Uniqlo",
                                    freshnessHint = "LONG_TERM"
                                )
                            ),
                            summary = "found brand preference"
                        )
                    )
                }
            },
            appLauncher = { _, _ -> true },
            countdownNotifier = { _, _ -> },
            accessibilityReady = { true },
            pageSwiper = { true },
            pause = { },
            nowProvider = { 1234L },
            stringResolver = ::resolveTestString
        )

        val outcome = runner.run(
            store = PrototypeStoreData(
                currentMemorySlice = MemorySlice(MemoryState()),
                memoryState = MemoryState()
            ),
            request = PreferenceDiscoveryManualScanRequest(
                target = PreferenceDiscoveryAppTarget(
                    domain = CapabilityDomain.SHOPPING,
                    appId = "jd",
                    displayName = "京东",
                    packageName = "com.jingdong.app.mall"
                ),
                pageCount = 1,
                countdownSeconds = 0
            )
        )

        val normalizedStore = normalizePrototypeStoreData(outcome.updatedStore)

        assertEquals(1, normalizedStore.memoryState?.appScanPreferenceFacts()?.size)
        assertEquals(1, normalizedStore.currentMemorySlice?.memoryState?.appScanPreferenceFacts()?.size)
        assertEquals(
            "Preference discovery updated from 1 captured page(s).",
            normalizedStore.memoryState?.preferenceDiscoveryRuntime?.lastOutcomeMessage
        )
    }

    @Test
    fun run_marksAttemptAndReturnsToAgentApp_whenCaptureYieldsNoValidScreenshot() = runBlocking {
        var visionInvoked = false
        val launchedPackages = mutableListOf<String>()
        val runner = PreferenceDiscoveryManualScanRunner(
            appContext = TestPreferenceDiscoveryContext(),
            screenCaptureCoordinator = object : ScreenCaptureCoordinator {
                override fun hasPermission(): Boolean = true

                override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot? = null
            },
            visionResolver = object : PreferenceDiscoveryVisionResolver {
                override suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution {
                    visionInvoked = true
                    return PreferenceDiscoveryVisionResolution.failure("unused")
                }
            },
            appLauncher = { _, packageName ->
                launchedPackages += packageName
                true
            },
            countdownNotifier = { _, _ -> },
            accessibilityReady = { true },
            pageSwiper = { true },
            pause = { },
            nowProvider = { 9876L },
            stringResolver = ::resolveTestString
        )

        val outcome = runner.run(
            store = PrototypeStoreData(),
            request = PreferenceDiscoveryManualScanRequest(
                target = PreferenceDiscoveryAppTarget(
                    domain = CapabilityDomain.SHOPPING,
                    appId = "jd",
                    displayName = "京东",
                    packageName = "com.jingdong.app.mall"
                ),
                pageCount = 1,
                countdownSeconds = 0
            )
        )

        assertFalse(outcome.applied)
        assertEquals(0, outcome.capturedPageCount)
        assertEquals("Preference discovery capture failed: no valid page was captured.", outcome.message)
        assertEquals(listOf("com.jingdong.app.mall", "com.atombits.pocopaw"), launchedPackages)
        assertFalse(visionInvoked)
        assertEquals(9876L, outcome.updatedStore.memoryState?.preferenceDiscoveryRuntime?.lastConsumedAt)
        assertEquals(
            listOf("com.jingdong.app.mall"),
            outcome.updatedStore.memoryState?.preferenceDiscoveryRuntime?.attemptedSourceApps
        )
        assertEquals(
            "Preference discovery capture failed: no valid page was captured.",
            outcome.updatedStore.memoryState?.preferenceDiscoveryRuntime?.lastOutcomeMessage
        )
        assertTrue(outcome.updatedStore.memoryState?.appScanPreferenceFacts()?.isEmpty() == true)
        assertTrue(outcome.updatedStore.memoryState?.appScanBiasRecords()?.isEmpty() == true)
    }

    @Test
    fun run_surfacesVisionFailureReason_whenResolverReturnsDiagnostic() = runBlocking {
        val launchedPackages = mutableListOf<String>()
        val runner = PreferenceDiscoveryManualScanRunner(
            appContext = TestPreferenceDiscoveryContext(),
            screenCaptureCoordinator = object : ScreenCaptureCoordinator {
                override fun hasPermission(): Boolean = true

                override fun captureSnapshot(timeoutMs: Long): ScreenCaptureSnapshot {
                    return ScreenCaptureSnapshot(
                        imageDataUrl = "data:image/jpeg;base64,AAA",
                        captureWidth = 1080,
                        captureHeight = 2400
                    )
                }
            },
            visionResolver = object : PreferenceDiscoveryVisionResolver {
                override suspend fun resolve(request: PreferenceDiscoveryVisionRequest): PreferenceDiscoveryVisionResolution {
                    return PreferenceDiscoveryVisionResolution.failure(
                        "http 400: InternalError.Algo.InvalidParameter"
                    )
                }
            },
            appLauncher = { _, packageName ->
                launchedPackages += packageName
                true
            },
            countdownNotifier = { _, _ -> },
            accessibilityReady = { true },
            pageSwiper = { true },
            pause = { },
            nowProvider = { 2468L },
            stringResolver = ::resolveTestString
        )

        val outcome = runner.run(
            store = PrototypeStoreData(),
            request = PreferenceDiscoveryManualScanRequest(
                target = PreferenceDiscoveryAppTarget(
                    domain = CapabilityDomain.SHOPPING,
                    appId = "jd",
                    displayName = "京东",
                    packageName = "com.jingdong.app.mall"
                ),
                pageCount = 1,
                countdownSeconds = 0
            )
        )

        assertFalse(outcome.applied)
        assertEquals(1, outcome.capturedPageCount)
        assertEquals(listOf("com.jingdong.app.mall", "com.atombits.pocopaw"), launchedPackages)
        assertEquals(
            "Preference discovery vision extraction failed: http 400: InternalError.Algo.InvalidParameter",
            outcome.message
        )
        assertEquals(
            "Preference discovery vision extraction failed: http 400: InternalError.Algo.InvalidParameter",
            outcome.updatedStore.memoryState?.preferenceDiscoveryRuntime?.lastOutcomeMessage
        )
    }

    @Test
    fun resolve_returnsHttpFailureReason_whenResponseIsNotSuccessful() = runBlocking {
        val stubClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body(
                        """{"error":{"message":"InternalError.Algo.InvalidParameter"}}"""
                            .toResponseBody()
                    )
                    .build()
            }
            .build()
        val resolver = VisionPreferenceDiscoveryResolver(
            apiKey = "test-key",
            model = "qwen3.5-plus",
            endpoint = "https://example.com/compatible-mode/v1/chat/completions",
            httpClient = stubClient
        )

        val resolution = resolver.resolve(
            PreferenceDiscoveryVisionRequest(
                target = PreferenceDiscoveryAppTarget(
                    domain = CapabilityDomain.SHOPPING,
                    appId = "jd",
                    displayName = "京东",
                    packageName = "com.jingdong.app.mall"
                ),
                screenshots = listOf("data:image/jpeg;base64,AAA")
            )
        )

        assertNull(resolution.result)
        assertEquals("http 400: InternalError.Algo.InvalidParameter", resolution.failureReason)
    }

    @Test
    fun createPreferenceDiscoveryHttpClient_usesExtendedTimeouts() {
        val httpClient = VisionPreferenceDiscoveryResolver.createPreferenceDiscoveryHttpClient()

        assertEquals(180_000, httpClient.callTimeoutMillis)
        assertEquals(120_000, httpClient.readTimeoutMillis)
        assertEquals(15_000, httpClient.connectTimeoutMillis)
        assertEquals(60_000, httpClient.writeTimeoutMillis)
    }

    @Test
    fun countFilteredPreferenceDiscoveryApps_countsAttemptedAppsWithoutEvidence() {
        val count = countFilteredPreferenceDiscoveryApps(
            MemoryState(
                preferenceDiscoveryRuntime = PreferenceDiscoveryRuntimeState(
                    attemptedSourceApps = listOf(
                        "com.jingdong.app.mall",
                        "com.taobao.taobao"
                    )
                )
            )
        )

        assertEquals(2, count)
    }
}

private class TestPreferenceDiscoveryContext : ContextWrapper(null) {
    override fun getPackageName(): String = "com.atombits.pocopaw"
}

private fun resolveTestString(resId: Int, formatArgs: Array<out Any?>): String {
    return when (resId) {
        R.string.screen_capture_permission_required -> "Screen capture permission is required."
        R.string.preference_discovery_accessibility_required -> "Accessibility permission is required before preference discovery can continue."
        R.string.preference_discovery_capture_failed -> "Preference discovery capture failed: no valid page was captured."
        R.string.preference_discovery_vision_unavailable -> "Preference discovery vision extraction is unavailable or returned an empty response."
        R.string.preference_discovery_vision_unavailable_detail -> "Preference discovery vision extraction failed: ${formatArgs[0]}"
        R.string.preference_discovery_no_evidence -> "Preference discovery completed, but no stable evidence was extracted from captured pages."
        R.string.preference_discovery_no_change -> "Preference discovery completed, but no new stable preference evidence was produced."
        R.string.preference_discovery_not_executed -> "not executed"
        R.string.preference_discovery_updated_at_prefix -> "updated: "
        R.string.preference_discovery_launch_failed -> "Unable to launch ${formatArgs[0]}. Confirm the app is installed."
        R.string.preference_discovery_countdown_toast -> {
            "Open the ${formatArgs[0]} order/history list within ${formatArgs[1]} seconds."
        }
        R.string.preference_discovery_success -> "Preference discovery updated from ${formatArgs[0]} captured page(s)."
        else -> "string-$resId"
    }
}