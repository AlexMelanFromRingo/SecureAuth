package org.alex_melan.secureAuth.utils;

import org.mindrot.jbcrypt.BCrypt;
import java.security.SecureRandom;
import java.util.regex.Pattern;

public class PasswordUtils {

    private static final int BCRYPT_ROUNDS = 12; // Настраиваемая сложность
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d@$!%*?&]{8,32}$");

    /**
     * Хеширование пароля с использованием BCrypt
     */
    public static String hashPassword(String password, String salt) {
        return BCrypt.hashpw(password, salt);
    }

    /**
     * Генерация соли для BCrypt
     */
    public static String generateSalt() {
        return BCrypt.gensalt(BCRYPT_ROUNDS, SECURE_RANDOM);
    }

    /**
     * Проверка пароля
     */
    public static boolean checkPassword(String password, String hash) {
        try {
            return BCrypt.checkpw(password, hash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Валидация сложности пароля
     */
    public static boolean isPasswordValid(String password) {
        if (password == null || password.length() < 8 || password.length() > 32) {
            return false;
        }
        return PASSWORD_PATTERN.matcher(password).matches();
    }

    /**
     * Генерация криптографически стойкого случайного токена
     */
    public static String generateSecureToken(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) {
            token.append(String.format("%02x", b & 0xff));
        }
        return token.toString();
    }

    /**
     * Создание безопасного хеша сессии
     */
    public static String createSessionHash(String username, String ipAddress, long timestamp) {
        String sessionData = username + ":" + ipAddress + ":" + timestamp + ":" + generateSecureToken(16);
        return hashPassword(sessionData, generateSalt());
    }
}