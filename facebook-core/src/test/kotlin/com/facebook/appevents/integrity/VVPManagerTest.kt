/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents.integrity

import com.facebook.FacebookPowerMockTestCase
import com.facebook.FacebookSdk
import com.facebook.internal.FetchedAppSettings
import com.facebook.internal.FetchedAppSettingsManager
import com.facebook.internal.FacebookRequestErrorClassification
import com.facebook.internal.SmartLoginOption
import org.assertj.core.api.Assertions.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import org.powermock.api.mockito.PowerMockito
import org.powermock.core.classloader.annotations.PrepareForTest

@PrepareForTest(FacebookSdk::class, FetchedAppSettingsManager::class)
class VVPManagerTest : FacebookPowerMockTestCase() {

    @Mock
    private lateinit var mockFacebookRequestErrorClassification: FacebookRequestErrorClassification
    private val mockAppID = "123"
    private val emptyJSONArray = JSONArray()

    @Before
    override fun setup() {
        super.setup()
        PowerMockito.mockStatic(FacebookSdk::class.java)
        whenever(FacebookSdk.getApplicationId()).thenReturn(mockAppID)
    }

    @After
    fun tearDown() {
        VVPManager.clearForTests()
    }

    private fun initMockFetchedAppSettings(vvpConfig: String?) {
        val mockFetchedAppSettings = FetchedAppSettings(
            false,
            "",
            false,
            1,
            SmartLoginOption.parseOptions(0),
            emptyMap(),
            false,
            mockFacebookRequestErrorClassification,
            "",
            "",
            false,
            codelessEventsEnabled = false,
            eventBindings = emptyJSONArray,
            sdkUpdateMessage = "",
            trackUninstallEnabled = false,
            monitorViaDialogEnabled = false,
            rawAamRules = "",
            suggestedEventsSetting = "",
            restrictiveDataSetting = "",
            protectedModeStandardParamsSetting = emptyJSONArray,
            MACARuleMatchingSetting = emptyJSONArray,
            migratedAutoLogValues = null,
            blocklistEvents = emptyJSONArray,
            redactedEvents = emptyJSONArray,
            sensitiveParams = emptyJSONArray,
            schemaRestrictions = emptyJSONArray,
            bannedParams = emptyJSONArray,
            vvpConfig = vvpConfig,
            currencyDedupeParameters = emptyList(),
            purchaseValueDedupeParameters = emptyList(),
            prodDedupeParameters = emptyList(),
            testDedupeParameters = emptyList(),
            dedupeWindow = 0L
        )
        PowerMockito.mockStatic(FetchedAppSettingsManager::class.java)
        whenever(FetchedAppSettingsManager.queryAppSettings(mockAppID, false))
            .thenReturn(mockFetchedAppSettings)
    }

    // --- lifecycle ---

    @Test
    fun `disabled by default`() {
        assertThat(VVPManager.isEnabled()).isFalse
        assertThat(VVPManager.config).isNull()
    }

    @Test
    fun `enable flips state`() {
        initMockFetchedAppSettings(null)
        VVPManager.enable()
        assertThat(VVPManager.isEnabled()).isTrue
    }

    @Test
    fun `enable with null vvpConfig leaves config null`() {
        initMockFetchedAppSettings(null)
        VVPManager.enable()
        assertThat(VVPManager.config).isNull()
    }

    @Test
    fun `enable with empty vvpConfig leaves config null`() {
        initMockFetchedAppSettings("")
        VVPManager.enable()
        assertThat(VVPManager.config).isNull()
    }

    @Test
    fun `disable preserves cached config`() {
        initMockFetchedAppSettings(VALID_NON_RETAIL_CONFIG)
        VVPManager.enable()
        assertThat(VVPManager.config).isNotNull
        VVPManager.disable()
        assertThat(VVPManager.isEnabled()).isFalse
        assertThat(VVPManager.config).isNotNull
    }

    // --- top-level shape ---

    @Test
    fun `parseConfig returns valid config with rules and standardParams and null inScopeEventNames`() {
        val cfg = VVPManager.parseConfig(VALID_NON_RETAIL_CONFIG)
        assertThat(cfg).isNotNull
        assertThat(cfg!!.rules).hasSize(1)
        assertThat(cfg.standardParams).containsExactlyInAnyOrder("fb_currency", "fb_value")
        assertThat(cfg.inScopeEventNames).isNull()
    }

    @Test
    fun `parseConfig returns valid config with inScopeEventNames for retail`() {
        val cfg = VVPManager.parseConfig(VALID_RETAIL_CONFIG)
        assertThat(cfg).isNotNull
        assertThat(cfg!!.inScopeEventNames).containsExactlyInAnyOrder("Purchase", "AddToCart")
    }

    @Test
    fun `parseConfig returns null when enabled is false`() {
        val json = """{
          "enabled": false,
          "rules": [{"place": 1, "keyRegex": "", "valueRegex": "tt\\d+"}],
          "standardParams": {"fb_currency": true}
        }"""
        assertThat(VVPManager.parseConfig(json)).isNull()
    }

    @Test
    fun `parseConfig returns null when rules array is empty`() {
        val json = """{
          "enabled": true,
          "rules": [],
          "standardParams": {"fb_currency": true}
        }"""
        assertThat(VVPManager.parseConfig(json)).isNull()
    }

    @Test
    fun `parseConfig returns null on malformed JSON`() {
        assertThat(VVPManager.parseConfig("not valid json")).isNull()
    }

    // --- rule compilation ---

    @Test
    fun `compileRule drops unknown place`() {
        val ruleObj = JSONObject().apply {
            put("place", 99)
            put("keyRegex", "video")
            put("valueRegex", "")
        }
        assertThat(VVPManager.compileRule(ruleObj)).isNull()
    }

    @Test
    fun `compileRule drops rule with both regexes empty`() {
        val ruleObj = JSONObject().apply {
            put("place", VVPManager.PLACE_CUSTOM_DATA)
            put("keyRegex", "")
            put("valueRegex", "")
        }
        assertThat(VVPManager.compileRule(ruleObj)).isNull()
    }

    @Test
    fun `compileRule drops malformed regex`() {
        val ruleObj = JSONObject().apply {
            put("place", VVPManager.PLACE_CUSTOM_DATA)
            put("keyRegex", "[invalid")
            put("valueRegex", "")
        }
        assertThat(VVPManager.compileRule(ruleObj)).isNull()
    }

    @Test
    fun `compileRule treats null and empty regex strings the same`() {
        val ruleObj = JSONObject().apply {
            put("place", VVPManager.PLACE_CUSTOM_DATA)
            put("keyRegex", JSONObject.NULL)
            put("valueRegex", "tt\\d+")
        }
        val rule = VVPManager.compileRule(ruleObj)
        assertThat(rule).isNotNull
        assertThat(rule!!.keyRegex).isNull()
        assertThat(rule.valueRegex).isNotNull
    }

    @Test
    fun `event-name rule keeps only keyRegex`() {
        val json = """{
          "enabled": true,
          "rules": [{"place": 3, "keyRegex": "video_view", "valueRegex": ""}],
          "standardParams": {}
        }"""
        val cfg = VVPManager.parseConfig(json)
        assertThat(cfg).isNotNull
        assertThat(cfg!!.rules).hasSize(1)
        val rule = cfg.rules[0]
        assertThat(rule.place).isEqualTo(VVPManager.PLACE_EVENT_NAME)
        assertThat(rule.keyRegex).isNotNull
        assertThat(rule.valueRegex).isNull()
    }

    @Test
    fun `compiled regex is case-insensitive`() {
        val cfg = VVPManager.parseConfig(VALID_NON_RETAIL_CONFIG)
        val rule = cfg!!.rules[0]
        // The valueRegex is "\\btt\\d{7,}\\b" — should match "TT1234567" (uppercase).
        assertThat(rule.valueRegex!!.matcher("TT1234567").find()).isTrue
    }

    // --- standardParams parsing ---

    @Test
    fun `parseConfig drops standardParams entries whose value is false`() {
        val json = """{
          "enabled": true,
          "rules": [{"place": 1, "keyRegex": "", "valueRegex": "tt\\d+"}],
          "standardParams": {"keep": true, "drop": false}
        }"""
        val cfg = VVPManager.parseConfig(json)
        assertThat(cfg!!.standardParams).containsExactly("keep")
    }

    @Test
    fun `parseConfig handles missing standardParams`() {
        val json = """{
          "enabled": true,
          "rules": [{"place": 1, "keyRegex": "", "valueRegex": "tt\\d+"}]
        }"""
        val cfg = VVPManager.parseConfig(json)
        assertThat(cfg!!.standardParams).isEmpty()
    }

    // --- inScopeEventNames parsing ---

    @Test
    fun `parseConfig handles JSONNull inScopeEventNames as no gate`() {
        val raw = JSONObject(VALID_NON_RETAIL_CONFIG)
        raw.put("inScopeEventNames", JSONObject.NULL)
        val cfg = VVPManager.parseConfig(raw.toString())
        assertThat(cfg!!.inScopeEventNames).isNull()
    }

    @Test
    fun `parseConfig handles missing inScopeEventNames as no gate`() {
        val cfg = VVPManager.parseConfig(VALID_NON_RETAIL_CONFIG)
        assertThat(cfg!!.inScopeEventNames).isNull()
    }

    // --- loadConfig integration with FetchedAppSettingsManager ---

    @Test
    fun `enable picks up config from FetchedAppSettings`() {
        initMockFetchedAppSettings(VALID_NON_RETAIL_CONFIG)
        VVPManager.enable()
        assertThat(VVPManager.config).isNotNull
        assertThat(VVPManager.config!!.rules).hasSize(1)
    }

    @Test
    fun `loadConfig reflects subsequent settings changes`() {
        initMockFetchedAppSettings(null)
        VVPManager.enable()
        assertThat(VVPManager.config).isNull()
        // Simulate settings change
        initMockFetchedAppSettings(VALID_NON_RETAIL_CONFIG)
        VVPManager.loadConfig()
        assertThat(VVPManager.config).isNotNull
    }

    companion object {
        private const val VALID_NON_RETAIL_CONFIG =
            """{
              "enabled": true,
              "rules": [{"place": 1, "keyRegex": "", "valueRegex": "\\btt\\d{7,}\\b"}],
              "standardParams": {"fb_currency": true, "fb_value": true},
              "inScopeEventNames": null
            }"""

        private const val VALID_RETAIL_CONFIG =
            """{
              "enabled": true,
              "rules": [{"place": 1, "keyRegex": "content_id", "valueRegex": "tt\\d+"}],
              "standardParams": {"fb_currency": true},
              "inScopeEventNames": ["Purchase", "AddToCart"]
            }"""
    }
}
