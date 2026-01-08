package ua.com.programmer.pick.core.util

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasswordHasher @Inject constructor() {

    fun hash(password: String, salt: String = ""): String {
        val input = password + salt
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verify(password: String, hashedPassword: String, salt: String = ""): Boolean {
        return hash(password, salt) == hashedPassword
    }
}
