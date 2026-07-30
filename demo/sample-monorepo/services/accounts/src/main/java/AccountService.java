package accounts;

public final class AccountService {

    public boolean exists(String accountId) {
        return accountId != null && !accountId.isBlank();
    }
}
