package org.alex_melan.secureAuth.utils;

import org.mindrot.jbcrypt.BCrypt;
import java.security.SecureRandom;
import java.util.regex.Pattern;

public class PasswordUtils {

    private static final int DEFAULT_BCRYPT_ROUNDS = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Паттерн для валидации сложности пароля (расширенный список символов)
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d@$!%*?&.,_\\-+=~`|\\[\\]{}():;\"'<>/\\\\^#]{8,32}$"
    );

    // Дополнительные паттерны для проверки
    private static final Pattern HAS_LOWERCASE = Pattern.compile(".*[a-z].*");
    private static final Pattern HAS_UPPERCASE = Pattern.compile(".*[A-Z].*");
    private static final Pattern HAS_DIGIT = Pattern.compile(".*\\d.*");
    private static final Pattern HAS_SPECIAL = Pattern.compile(".*[@$!%*?&.,_\\-+=~`|\\[\\]{}():;\"'<>/\\\\^#].*");

    /**
     * Хеширование пароля с использованием BCrypt
     * @param password пароль для хеширования
     * @param salt соль для хеширования
     * @return хешированный пароль
     */
    public static String hashPassword(String password, String salt) {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("Пароль и соль не могут быть null");
        }

        try {
            return BCrypt.hashpw(password, salt);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка хеширования пароля", e);
        }
    }

    /**
     * Генерация соли для BCrypt
     * @return сгенерированная соль
     */
    public static String generateSalt() {
        return generateSalt(DEFAULT_BCRYPT_ROUNDS);
    }

    /**
     * Генерация соли для BCrypt с указанным количеством раундов
     * @param rounds количество раундов (10-15)
     * @return сгенерированная соль
     */
    public static String generateSalt(int rounds) {
        if (rounds < 10 || rounds > 15) {
            throw new IllegalArgumentException("Количество раундов должно быть от 10 до 15");
        }

        try {
            return BCrypt.gensalt(rounds, SECURE_RANDOM);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка генерации соли", e);
        }
    }

    /**
     * Проверка пароля
     * @param password введенный пароль
     * @param hash сохраненный хеш
     * @return true если пароль верный
     */
    public static boolean checkPassword(String password, String hash) {
        if (password == null || hash == null) {
            return false;
        }

        try {
            return BCrypt.checkpw(password, hash);
        } catch (Exception e) {
            // Логируем ошибку но не выбрасываем исключение для безопасности
            return false;
        }
    }

    /**
     * Валидация сложности пароля
     * @param password пароль для проверки
     * @return true если пароль соответствует требованиям
     */
    public static boolean isPasswordValid(String password) {
        if (password == null) {
            return false;
        }

        // Основная проверка через паттерн
        return PASSWORD_PATTERN.matcher(password).matches();
    }

    /**
     * Детальная валидация пароля с возвращением конкретных ошибок
     * @param password пароль для проверки
     * @return ValidationResult с деталями
     */
    public static ValidationResult validatePasswordDetailed(String password) {
        ValidationResult result = new ValidationResult();

        if (password == null) {
            result.addError("Пароль не может быть null");
            return result;
        }

        // Проверка длины
        if (password.length() < 8) {
            result.addError("Пароль должен содержать минимум 8 символов");
        }
        if (password.length() > 32) {
            result.addError("Пароль должен содержать максимум 32 символа");
        }

        // Проверка содержания строчных букв
        if (!HAS_LOWERCASE.matcher(password).matches()) {
            result.addError("Пароль должен содержать минимум одну строчную букву (a-z)");
        }

        // Проверка содержания заглавных букв
        if (!HAS_UPPERCASE.matcher(password).matches()) {
            result.addError("Пароль должен содержать минимум одну заглавную букву (A-Z)");
        }

        // Проверка содержания цифр
        if (!HAS_DIGIT.matcher(password).matches()) {
            result.addError("Пароль должен содержать минимум одну цифру (0-9)");
        }

        // Проверка на недопустимые символы (теперь разрешаем намного больше символов)
        if (!password.matches("^[a-zA-Z\\d@$!%*?&.,_\\-+=~`|\\[\\]{}():;\"'<>/\\\\^#]+$")) {
            result.addError("Пароль содержит недопустимые символы. Разрешены: буквы, цифры и символы @$!%*?&.,_-+=~`|[]{}():;\"'<>/\\^#");
        }

        // Проверка на последовательности
        if (containsSequence(password)) {
            result.addError("Пароль не должен содержать последовательности типа '123' или 'abc'");
        }

        // Проверка на повторения
        if (containsRepeating(password)) {
            result.addError("Пароль не должен содержать повторяющиеся символы (более 2 подряд)");
        }

        return result;
    }

    /**
     * Проверка на последовательности символов
     */
    private static boolean containsSequence(String password) {
        String lower = password.toLowerCase();

        // Проверяем числовые последовательности
        for (int i = 0; i <= lower.length() - 3; i++) {
            if (Character.isDigit(lower.charAt(i))) {
                char first = lower.charAt(i);
                char second = lower.charAt(i + 1);
                char third = lower.charAt(i + 2);

                if (Character.isDigit(second) && Character.isDigit(third)) {
                    if ((second - first) == 1 && (third - second) == 1) {
                        return true; // 123, 456, etc.
                    }
                    if ((first - second) == 1 && (second - third) == 1) {
                        return true; // 321, 654, etc.
                    }
                }
            }
        }

        // Проверяем буквенные последовательности
        for (int i = 0; i <= lower.length() - 3; i++) {
            if (Character.isLetter(lower.charAt(i))) {
                char first = lower.charAt(i);
                char second = lower.charAt(i + 1);
                char third = lower.charAt(i + 2);

                if (Character.isLetter(second) && Character.isLetter(third)) {
                    if ((second - first) == 1 && (third - second) == 1) {
                        return true; // abc, def, etc.
                    }
                    if ((first - second) == 1 && (second - third) == 1) {
                        return true; // cba, fed, etc.
                    }
                }
            }
        }

        return false;
    }

    /**
     * Проверка на повторяющиеся символы
     */
    private static boolean containsRepeating(String password) {
        for (int i = 0; i <= password.length() - 3; i++) {
            char current = password.charAt(i);
            if (current == password.charAt(i + 1) && current == password.charAt(i + 2)) {
                return true; // aaa, 111, etc.
            }
        }
        return false;
    }

    /**
     * Генерация криптографически стойкого случайного токена
     * @param length длина токена в байтах
     * @return токен в hex формате
     */
    public static String generateSecureToken(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Длина токена должна быть положительной");
        }

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
     * @param username имя пользователя
     * @param ipAddress IP адрес
     * @param timestamp временная метка
     * @return хеш сессии
     */
    public static String createSessionHash(String username, String ipAddress, long timestamp) {
        if (username == null || ipAddress == null) {
            throw new IllegalArgumentException("Имя пользователя и IP адрес не могут быть null");
        }

        String secureToken = generateSecureToken(16);
        String sessionData = username + ":" + ipAddress + ":" + timestamp + ":" + secureToken;
        String salt = generateSalt();

        return hashPassword(sessionData, salt);
    }

    /**
     * Оценка силы пароля
     * @param password пароль для оценки
     * @return оценка от 0 до 100
     */
    public static int calculatePasswordStrength(String password) {
        if (password == null || password.isEmpty()) {
            return 0;
        }

        int score = 0;
        int length = password.length();

        // Баллы за длину
        if (length >= 8) score += 10;
        if (length >= 12) score += 10;
        if (length >= 16) score += 10;
        if (length >= 20) score += 10;

        // Баллы за разнообразие символов
        if (HAS_LOWERCASE.matcher(password).matches()) score += 10;
        if (HAS_UPPERCASE.matcher(password).matches()) score += 10;
        if (HAS_DIGIT.matcher(password).matches()) score += 10;
        if (HAS_SPECIAL.matcher(password).matches()) score += 10;

        // Дополнительные баллы за сложность
        if (password.length() > 12 && hasVariedCharacters(password)) score += 10;
        if (!containsSequence(password)) score += 5;
        if (!containsRepeating(password)) score += 5;

        return Math.min(100, score);
    }

    /**
     * Проверка разнообразия символов в пароле
     */
    private static boolean hasVariedCharacters(String password) {
        boolean hasLower = false, hasUpper = false, hasDigit = false, hasSpecial = false;

        for (char c : password.toCharArray()) {
            if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }

        int variety = (hasLower ? 1 : 0) + (hasUpper ? 1 : 0) +
                (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);

        return variety >= 3;
    }

    /**
     * Генерация безопасного пароля
     * @param length длина пароля (8-32)
     * @param includeSpecial включать ли специальные символы
     * @return сгенерированный пароль
     */
    public static String generateSecurePassword(int length, boolean includeSpecial) {
        if (length < 8 || length > 32) {
            throw new IllegalArgumentException("Длина пароля должна быть от 8 до 32 символов");
        }

        String lowercase = "abcdefghijklmnopqrstuvwxyz";
        String uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String digits = "0123456789";
        String special = "@$!%*?&.,_-+=~`|[]{}():;\"'<>/\\^#";

        String charset = lowercase + uppercase + digits;
        if (includeSpecial) {
            charset += special;
        }

        StringBuilder password = new StringBuilder();

        // Гарантируем наличие всех типов символов
        password.append(lowercase.charAt(SECURE_RANDOM.nextInt(lowercase.length())));
        password.append(uppercase.charAt(SECURE_RANDOM.nextInt(uppercase.length())));
        password.append(digits.charAt(SECURE_RANDOM.nextInt(digits.length())));

        if (includeSpecial) {
            password.append(special.charAt(SECURE_RANDOM.nextInt(special.length())));
        }

        // Заполняем остальные позиции
        for (int i = password.length(); i < length; i++) {
            password.append(charset.charAt(SECURE_RANDOM.nextInt(charset.length())));
        }

        // Перемешиваем символы
        return shuffleString(password.toString());
    }

    /**
     * Перемешивание символов в строке
     */
    private static String shuffleString(String input) {
        char[] chars = input.toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }

    /**
     * Класс для хранения результатов валидации
     */
    public static class ValidationResult {
        private final java.util.List<String> errors = new java.util.ArrayList<>();

        public void addError(String error) {
            errors.add(error);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public java.util.List<String> getErrors() {
            return new java.util.ArrayList<>(errors);
        }

        public String getErrorsAsString() {
            return String.join("\n", errors);
        }
    }
}