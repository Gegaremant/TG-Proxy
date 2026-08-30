package org.flowseal.tgwsproxy.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration storage backed by SharedPreferences.
 */
class ProxyConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "0.0.0.0")!!
        set(value) = prefs.edit().putString("host", value).apply()

    var port: Int
        get() {
            var p = prefs.getInt("port", -1)
            if (p == -1) {
                p = (10000..60000).random()
                prefs.edit().putInt("port", p).apply()
            }
            return p
        }
        set(value) = prefs.edit().putInt("port", value).apply()

    var dcIps: List<String>
        get() {
            val raw = prefs.getString("dc_ips", "2:149.154.167.220,4:149.154.167.220")!!
            return raw.split(",").filter { it.isNotBlank() }
        }
        set(value) = prefs.edit().putString("dc_ips", value.joinToString(",")).apply()

    var poolSize: Int
        get() = prefs.getInt("pool_size", 4)
        set(value) = prefs.edit().putInt("pool_size", value).apply()

    var bufKb: Int
        get() = prefs.getInt("buf_kb", 256)
        set(value) = prefs.edit().putInt("buf_kb", value).apply()

    var autostart: Boolean
        get() = prefs.getBoolean("autostart", false)
        set(value) = prefs.edit().putBoolean("autostart", value).apply()

    var secret: String
        get() {
            var s = prefs.getString("secret", null)
            if (s == null) {
                // Generate a random 32-hex-char secret
                s = List(32) { (0..15).random().toString(16) }.joinToString("")
                prefs.edit().putString("secret", s).apply()
            }
            return s
        }
        set(value) = prefs.edit().putString("secret", value).apply()

    var fakeTlsDomain: String
        get() = prefs.getString("fake_tls_domain", "sberbank.ru") ?: "sberbank.ru"
        set(value) = prefs.edit().putString("fake_tls_domain", value).apply()

    /** Theme mode: "system", "light", or "dark" */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(value) = prefs.edit().putString("language", value).apply()

    var showLogs: Boolean
        get() = prefs.getBoolean("show_logs", false)
        set(value) = prefs.edit().putBoolean("show_logs", value).apply()

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("first_launch", true)
        set(value) = prefs.edit().putBoolean("first_launch", value).apply()

    var useCfProxy: Boolean
        get() = prefs.getBoolean("use_cf_proxy", true)
        set(value) = prefs.edit().putBoolean("use_cf_proxy", value).apply()

    var cfProxyDomain: String
        get() = prefs.getString("cf_proxy_domain", "") ?: ""
        set(value) = prefs.edit().putString("cf_proxy_domain", value).apply()

    /**
     * Cloudflare Worker domain (e.g. "mwapp-is-super.workers.dev"). When set,
     * the proxy relays via  wss://{domain}/apiws?dst={dc_ip}&dc={dc}  following
     * the desktop (Python) cf_worker fallback. Empty = disabled.
     */
    var cfWorkerDomain: String
        get() = prefs.getString("cf_worker_domain", "") ?: ""
        set(value) = prefs.edit().putString("cf_worker_domain", value).apply()

    /**
     * Upstream SOCKS5 proxy for all outbound connections, e.g. Hiddify's
     * local port ("127.0.0.1:2334"). Empty = connect directly.
     */
    var upstreamProxy: String
        get() = prefs.getString("upstream_proxy", "") ?: ""
        set(value) = prefs.edit().putString("upstream_proxy", value).apply()

    /**
     * DNS servers (comma/newline separated) used for manual DNS resolution of
     * CF proxy domains when the system DNS cannot resolve them. Empty = use
     * the built-in default list (public + known ISP resolvers).
     */
    var dnsServers: String
        get() = prefs.getString("dns_servers", "") ?: ""
        set(value) = prefs.edit().putString("dns_servers", value).apply()

    fun parseDnsServers(): List<String> {
        val raw = dnsServers
        return raw.split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun parseDcOpt(): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (entry in dcIps) {
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val dc = parts[0].trim().toIntOrNull() ?: continue
                val ip = parts[1].trim()
                if (ip.isNotEmpty()) result[dc] = ip
            }
        }
        return result
    }
}
