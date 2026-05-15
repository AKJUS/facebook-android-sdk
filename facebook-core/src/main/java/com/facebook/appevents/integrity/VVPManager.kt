/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.appevents.integrity

import com.facebook.FacebookSdk
import com.facebook.internal.FetchedAppSettingsManager
import com.facebook.internal.instrument.crashshield.AutoHandleExceptions
import java.util.regex.Pattern
import org.json.JSONException
import org.json.JSONObject

/**
 * VPPA Video Viewing Protections — Android SDK side. Mirrors the JS pixel plugin
 * (`SignalsFBEvents.plugins.vvp.js`). Consumes the `vvp_config` field served by
 * `GraphApplicationProtectedModeRulesNode` (parsed into `FetchedAppSettings.vvpConfig`) and
 * exposes a typed [VVPConfig] for the per-event hook to consult.
 *
 * This file owns parsing + lifecycle only. Detection / sanitization / payload tagging will be added
 * in subsequent diffs.
 */
@AutoHandleExceptions
object VVPManager {

  // Mirror of `SignalsIntegrityCheckPlace` int values. Only these two reach the SDK today.
  internal const val PLACE_CUSTOM_DATA = 1
  internal const val PLACE_EVENT_NAME = 3

  // Wire field names — match the server-side TVVPAppConfig / TVVPAppRule shape.
  private const val ENABLED_KEY = "enabled"
  private const val RULES_KEY = "rules"
  private const val STANDARD_PARAMS_KEY = "standardParams"
  private const val IN_SCOPE_EVENT_NAMES_KEY = "inScopeEventNames"
  private const val PLACE_KEY = "place"
  private const val KEY_REGEX_KEY = "keyRegex"
  private const val VALUE_REGEX_KEY = "valueRegex"

  /**
   * One detection rule, with regexes pre-compiled at parse time. Either regex can be null (meaning
   * "no constraint on that side"); a rule with both null is dropped during parse.
   */
  data class CompiledRule(val place: Int, val keyRegex: Pattern?, val valueRegex: Pattern?)

  data class VVPConfig(
      val rules: List<CompiledRule>,
      val standardParams: Set<String>,
      // null means no event-name gate (NON_RETAIL); non-null restricts detection to this set.
      val inScopeEventNames: Set<String>?,
  )

  private var enabled: Boolean = false

  @Volatile
  internal var config: VVPConfig? = null
    private set

  @JvmStatic
  fun enable() {
    enabled = true
    loadConfig()
  }

  @JvmStatic
  fun disable() {
    enabled = false
  }

  @JvmStatic fun isEnabled(): Boolean = enabled

  /** Reload [config] from the latest [FetchedAppSettings.vvpConfig] payload. */
  internal fun loadConfig() {
    val settings =
        FetchedAppSettingsManager.queryAppSettings(FacebookSdk.getApplicationId(), false)
    val raw = settings?.vvpConfig
    config =
        if (raw.isNullOrEmpty()) {
          // Empty string is the server's "not in VVP scope" signal.
          null
        } else {
          parseConfig(raw)
        }
  }

  /** Parse the JSON-encoded `vvp_config` payload. Returns null on any structural failure. */
  internal fun parseConfig(jsonStr: String): VVPConfig? {
    return try {
      val obj = JSONObject(jsonStr)
      if (!obj.optBoolean(ENABLED_KEY, false)) {
        return null
      }
      val rules = parseRules(obj)
      // Empty rules list -> nothing to detect; treat as out-of-scope so callers can no-op fast.
      if (rules.isEmpty()) {
        return null
      }
      VVPConfig(
          rules = rules,
          standardParams = parseStandardParams(obj),
          inScopeEventNames = parseInScopeEventNames(obj),
      )
    } catch (_: JSONException) {
      null
    }
  }

  private fun parseRules(obj: JSONObject): List<CompiledRule> {
    val arr = obj.optJSONArray(RULES_KEY) ?: return emptyList()
    val out = mutableListOf<CompiledRule>()
    for (i in 0 until arr.length()) {
      val ruleObj = arr.optJSONObject(i) ?: continue
      val compiled = compileRule(ruleObj) ?: continue
      out.add(compiled)
    }
    return out
  }

  internal fun compileRule(ruleObj: JSONObject): CompiledRule? {
    val place = ruleObj.optInt(PLACE_KEY, -1)
    if (place != PLACE_CUSTOM_DATA && place != PLACE_EVENT_NAME) {
      // Unknown place -> drop silently. Mirror of the JS plugin behaviour.
      return null
    }
    val keyRegex = optRegex(ruleObj, KEY_REGEX_KEY)
    val valueRegex = optRegex(ruleObj, VALUE_REGEX_KEY)
    if (keyRegex == null && valueRegex == null) {
      // Rule with no constraint would match every event -> drop.
      return null
    }
    return CompiledRule(place, keyRegex, valueRegex)
  }

  private fun optRegex(obj: JSONObject, key: String): Pattern? {
    if (!obj.has(key) || obj.isNull(key)) {
      return null
    }
    val raw = obj.optString(key, "")
    if (raw.isEmpty()) {
      // Empty string is treated as null (matches JS plugin: both mean "no constraint").
      return null
    }
    return try {
      Pattern.compile(raw, Pattern.CASE_INSENSITIVE)
    } catch (_: Exception) {
      // Malformed regex -> drop.
      null
    }
  }

  private fun parseStandardParams(obj: JSONObject): Set<String> {
    val mapObj = obj.optJSONObject(STANDARD_PARAMS_KEY) ?: return emptySet()
    val out = HashSet<String>()
    val it = mapObj.keys()
    while (it.hasNext()) {
      val key = it.next()
      // Server emits {key: true} for every entry; preserve that contract by only including
      // keys whose value is truthy.
      if (mapObj.optBoolean(key, false)) {
        out.add(key)
      }
    }
    return out
  }

  private fun parseInScopeEventNames(obj: JSONObject): Set<String>? {
    if (!obj.has(IN_SCOPE_EVENT_NAMES_KEY) || obj.isNull(IN_SCOPE_EVENT_NAMES_KEY)) {
      return null
    }
    val arr = obj.optJSONArray(IN_SCOPE_EVENT_NAMES_KEY) ?: return null
    val out = HashSet<String>()
    for (i in 0 until arr.length()) {
      val s = arr.optString(i, "")
      if (s.isNotEmpty()) {
        out.add(s)
      }
    }
    return out
  }

  /** Reset all state — for tests. */
  internal fun clearForTests() {
    enabled = false
    config = null
  }
}
