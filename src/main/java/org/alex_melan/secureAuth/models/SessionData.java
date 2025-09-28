package org.alex_melan.secureAuth.models;

// Модель данных сессии
public class SessionData {
    private String sessionHash;
    private String username;
    private String ipAddress;
    private long createdAt;
    private long expiresAt;

    public SessionData(String sessionHash, String username, String ipAddress, long createdAt, long expiresAt) {
        this.sessionHash = sessionHash;
        this.username = username;
        this.ipAddress = ipAddress;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean isValidForIp(String ipAddress) {
        return this.ipAddress.equals(ipAddress);
    }

    // Геттеры
    public String getSessionHash() { return sessionHash; }
    public String getUsername() { return username; }
    public String getIpAddress() { return ipAddress; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
}
