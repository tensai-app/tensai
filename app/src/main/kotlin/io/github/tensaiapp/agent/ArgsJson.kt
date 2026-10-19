package io.github.tensaiapp.agent

import org.json.JSONObject

/**
 * The string argument [key], `null` when it is absent or a JSON null. A model that writes `"text": null`
 * means "no text", and Android's `optString` would hand back the four letters "null".
 */
internal fun JSONObject.stringArg(key: String): String? =
    if (!has(key) || isNull(key)) null else get(key).toString()
