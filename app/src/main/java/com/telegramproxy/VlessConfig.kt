package com.telegramproxy

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * Parsed VLESS node + helpers to build Xray JSON config.
 * Supports: VLESS + WebSocket + TLS (SNI from link).
 */
data class VlessServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uuid: String,
    val address: String,
    val port: Int,
    val encryption: String = "none",
    val flow: String = "",
    val network: String = "ws",
    val security: String = "tls",
    val sni: String = "",
    val host: String = "",
    val path: String = "/",
    val alpn: String = "",
    val fp: String = "",
    val type: String = "ws",
    val country: String = "",
    val protocol: String = "VLESS",
    val rawUrl: String = "",
    val subscriptionId: String = ""
) {
    val displayAddress: String get() = "$address:$port"
    val transportLabel: String get() = when {
        network.equals("ws", true) || type.equals("ws", true) -> "WS"
        network.equals("tcp", true) -> "TCP"
        network.equals("grpc", true) -> "gRPC"
        else -> network.uppercase()
    }
    val securityLabel: String get() = security.ifBlank { "none" }.uppercase()
}

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceUrl: String = "",
    val servers: List<VlessServer> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

object VlessConfig {

    const val LOCAL_SOCKS_PORT = 10808
    const val LOCAL_HTTP_PORT = 10809

    /**
     * Parse a single vless:// URL.
     * Format:
     * vless://uuid@host:port?encryption=none&security=tls&sni=...&type=ws&host=...&path=...#Name
     */
    fun parseVlessUrl(url: String, subscriptionId: String = "", defaultName: String = ""): VlessServer? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) return null

        return try {
            // Manual parse — Uri may mishandle some query encodings
            val withoutScheme = trimmed.removePrefix("vless://").removePrefix("VLESS://")
            val hashIndex = withoutScheme.indexOf('#')
            val fragment = if (hashIndex >= 0) {
                URLDecoder.decode(withoutScheme.substring(hashIndex + 1), "UTF-8")
            } else ""
            val main = if (hashIndex >= 0) withoutScheme.substring(0, hashIndex) else withoutScheme

            val qIndex = main.indexOf('?')
            val userHost = if (qIndex >= 0) main.substring(0, qIndex) else main
            val query = if (qIndex >= 0) main.substring(qIndex + 1) else ""

            val at = userHost.lastIndexOf('@')
            if (at < 0) return null
            val uuid = userHost.substring(0, at)
            val hostPort = userHost.substring(at + 1)

            val (address, port) = parseHostPort(hostPort)
            val params = parseQuery(query)

            val network = params["type"] ?: params["network"] ?: "ws"
            val security = params["security"] ?: "none"
            val sni = params["sni"] ?: params["peer"] ?: ""
            val host = params["host"] ?: sni.ifBlank { address }
            val path = URLDecoder.decode(params["path"] ?: "/", "UTF-8")
            val name = fragment.ifBlank {
                defaultName.ifBlank { "$address:$port" }
            }

            VlessServer(
                name = name,
                uuid = uuid,
                address = address,
                port = port,
                encryption = params["encryption"] ?: "none",
                flow = params["flow"] ?: "",
                network = network,
                security = security,
                sni = sni.ifBlank { host },
                host = host,
                path = if (path.startsWith("/")) path else "/$path",
                alpn = params["alpn"] ?: "",
                fp = params["fp"] ?: params["fingerprint"] ?: "",
                type = network,
                country = guessCountry(name),
                protocol = "VLESS",
                rawUrl = trimmed,
                subscriptionId = subscriptionId
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse subscription body: base64 list of vless:// lines, or plain text lines.
     */
    fun parseSubscriptionBody(
        body: String,
        subscriptionId: String,
        subscriptionName: String
    ): List<VlessServer> {
        val text = decodeMaybeBase64(body)
        val lines = text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        return lines.mapNotNull { line ->
            when {
                line.startsWith("vless://", ignoreCase = true) ->
                    parseVlessUrl(line, subscriptionId, subscriptionName)
                else -> null
            }
        }
    }

    fun decodeMaybeBase64(input: String): String {
        val cleaned = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        // Heuristic: base64 payload often has no "://" and is longer
        if (cleaned.contains("://")) return input
        return try {
            val flags = Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP
            val decoded = Base64.decode(cleaned, flags)
            String(decoded, Charsets.UTF_8)
        } catch (_: Exception) {
            try {
                val decoded = Base64.decode(cleaned, Base64.DEFAULT)
                String(decoded, Charsets.UTF_8)
            } catch (_: Exception) {
                input
            }
        }
    }

    /**
     * Generate full Xray-core JSON config for VLESS + WS + TLS, local SOCKS5 inbound.
     */
    fun generateXrayConfig(server: VlessServer, socksPort: Int = LOCAL_SOCKS_PORT): String {
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "")
            put("error", "")
        })

        // Inbounds: SOCKS5 for Telegram (+ optional HTTP)
        val inbounds = JSONArray()
        inbounds.put(JSONObject().apply {
            put("tag", "socks-in")
            put("port", socksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().put("http").put("tls"))
            })
        })
        inbounds.put(JSONObject().apply {
            put("tag", "http-in")
            put("port", LOCAL_HTTP_PORT)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject())
        })
        root.put("inbounds", inbounds)

        // Outbound: VLESS
        val streamSettings = JSONObject().apply {
            put("network", server.network.ifBlank { "ws" })
            val sec = server.security.ifBlank { "none" }
            put("security", sec)

            if (sec.equals("tls", true) || sec.equals("reality", true)) {
                put("tlsSettings", JSONObject().apply {
                    put("serverName", server.sni.ifBlank { server.host.ifBlank { server.address } })
                    put("allowInsecure", false)
                    if (server.fp.isNotBlank()) {
                        put("fingerprint", server.fp)
                    }
                    if (server.alpn.isNotBlank()) {
                        val alpnArr = JSONArray()
                        server.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { alpnArr.put(it) }
                        put("alpn", alpnArr)
                    }
                })
            }

            when (server.network.lowercase()) {
                "ws", "websocket" -> {
                    put("wsSettings", JSONObject().apply {
                        put("path", server.path.ifBlank { "/" })
                        put("headers", JSONObject().apply {
                            put("Host", server.host.ifBlank { server.sni.ifBlank { server.address } })
                        })
                    })
                }
                "grpc" -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", server.path.trimStart('/'))
                    })
                }
                "tcp" -> {
                    put("tcpSettings", JSONObject())
                }
            }
        }

        val vlessSettings = JSONObject().apply {
            put("vnext", JSONArray().put(JSONObject().apply {
                put("address", server.address)
                put("port", server.port)
                put("users", JSONArray().put(JSONObject().apply {
                    put("id", server.uuid)
                    put("encryption", server.encryption.ifBlank { "none" })
                    put("level", 0)
                    if (server.flow.isNotBlank()) put("flow", server.flow)
                }))
            }))
        }

        val outbounds = JSONArray()
        outbounds.put(JSONObject().apply {
            put("tag", "proxy")
            put("protocol", "vless")
            put("settings", vlessSettings)
            put("streamSettings", streamSettings)
        })
        outbounds.put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        })
        outbounds.put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        })
        root.put("outbounds", outbounds)

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray().put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
            }))
        })

        return root.toString(2)
    }

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        // [IPv6]:port or host:port
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            val host = hostPort.substring(1, end)
            val portPart = hostPort.substring(end + 1).removePrefix(":")
            return host to (portPart.toIntOrNull() ?: 443)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort to 443
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: 443
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else {
                val k = URLDecoder.decode(part.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    private fun guessCountry(name: String): String {
        val n = name.lowercase()
        val flags = listOf(
            "🇳🇱" to "Netherlands", "🇩🇪" to "Germany", "🇺🇸" to "USA",
            "🇬🇧" to "UK", "🇫🇷" to "France", "🇫🇮" to "Finland",
            "🇸🇪" to "Sweden", "🇵🇱" to "Poland", "🇹🇷" to "Turkey",
            "🇯🇵" to "Japan", "🇸🇬" to "Singapore", "🇨🇦" to "Canada",
            "🇷🇺" to "Russia", "🇺🇦" to "Ukraine", "🇮🇹" to "Italy",
            "🇪🇸" to "Spain", "🇭🇰" to "Hong Kong", "🇰🇷" to "Korea",
            "🇦🇪" to "UAE", "🇨🇭" to "Switzerland", "🇦🇹" to "Austria"
        )
        for ((flag, country) in flags) {
            if (name.contains(flag) || n.contains(country.lowercase())) return country
        }
        val keywords = mapOf(
            "nl" to "Netherlands", "de" to "Germany", "us" to "USA",
            "uk" to "UK", "gb" to "UK", "fr" to "France", "fi" to "Finland",
            "se" to "Sweden", "pl" to "Poland", "tr" to "Turkey",
            "jp" to "Japan", "sg" to "Singapore", "ca" to "Canada",
            "ru" to "Russia", "ua" to "Ukraine", "it" to "Italy",
            "es" to "Spain", "hk" to "Hong Kong", "kr" to "Korea"
        )
        for ((code, country) in keywords) {
            if (Regex("\\b$code\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)) return country
        }
        return "Unknown"
    }
}
