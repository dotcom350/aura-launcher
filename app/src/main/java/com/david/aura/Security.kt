package com.david.aura

import android.os.SystemClock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Codigo de seguridad (PIN) opcional para la web y el MCP.
 *
 * El QR da el token a cualquiera que lo escanee; con el PIN activado, ademas del token hace falta
 * una sesion que solo se consigue escribiendo el PIN. El PIN se guarda con PBKDF2 (sal aleatoria) y
 * tras 5 fallos seguidos se bloquea 5 minutos. Las sesiones duran 30 dias y sobreviven a reinicios.
 */
object Security {
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 5 * 60_000L
    private const val SESSION_MS = 30L * 24 * 3600 * 1000
    private const val ITERATIONS = 20_000

    enum class Check { OK, BAD, LOCKED }

    private val random = SecureRandom()
    private var fails = 0
    private var lockedUntil = 0L
    private val sessions = HashMap<String, Long>()
    private var loaded = false

    private fun file() = File(Aura.app.filesDir, "sessions.json")
    private val mapSerializer = MapSerializer(String.serializer(), Long.serializer())

    fun enabled() = Aura.config.state.value.pinHash.isNotEmpty()

    fun validFormat(pin: String?) = pin != null && pin.length in 4..8 && pin.all { it in '0'..'9' }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    /** Segundos de bloqueo que quedan (0 = no bloqueado). */
    @Synchronized
    fun lockSeconds(): Long = ((lockedUntil - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)

    @Synchronized
    fun attemptsLeft() = MAX_FAILS - fails

    @Synchronized
    fun check(pin: String?): Check {
        if (lockSeconds() > 0) return Check.LOCKED
        val stored = Aura.config.state.value.pinHash
        val parts = stored.split(':')
        val ok = pin != null && parts.size == 2 && runCatching {
            MessageDigest.isEqual(derive(pin, unhex(parts[0])), unhex(parts[1]))
        }.getOrDefault(false)
        if (ok) { fails = 0; return Check.OK }
        fails++
        if (fails >= MAX_FAILS) { fails = 0; lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS; return Check.LOCKED }
        return Check.BAD
    }

    /** Activa o cambia el PIN. Cierra todas las sesiones y devuelve una nueva para quien lo cambio. */
    @Synchronized
    fun setPin(pin: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        Aura.config.update { it.copy(pinHash = "${hex(salt)}:${hex(derive(pin, salt))}") }
        load(); sessions.clear()
        return newSession()
    }

    @Synchronized
    fun clearPin() {
        Aura.config.update { it.copy(pinHash = "") }
        load(); sessions.clear(); save()
    }

    @Synchronized
    fun newSession(): String {
        load()
        val s = ByteArray(24).also { random.nextBytes(it) }.let(::hex)
        sessions[s] = System.currentTimeMillis() + SESSION_MS
        save()
        return s
    }

    @Synchronized
    fun sessionOk(s: String?): Boolean {
        if (s.isNullOrEmpty()) return false
        load()
        val now = System.currentTimeMillis()
        if (sessions.values.any { it < now }) { sessions.entries.removeAll { it.value < now }; save() }
        // Comparacion en tiempo constante contra cada sesion valida
        return sessions.keys.fold(false) { acc, k -> MessageDigest.isEqual(k.toByteArray(), s.toByteArray()) || acc }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching { sessions.putAll(AuraJson.decodeFromString(mapSerializer, file().readText())) }
    }

    private fun save() {
        runCatching { file().writeText(AuraJson.encodeToString(mapSerializer, sessions)) }
    }
}
