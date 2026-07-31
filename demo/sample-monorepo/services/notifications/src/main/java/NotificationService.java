package notifications;

public final class NotificationService {

    public String orderConfirmation(String accountId, String sku) {
        return "Order confirmed for " + accountId + ": " + sku;
    }
}
