package auth;

public final class AuthService {

    public boolean authorize(String accountId, String token) {
        return token != null && token.length() > 0 && accountId != null;
    }
}
