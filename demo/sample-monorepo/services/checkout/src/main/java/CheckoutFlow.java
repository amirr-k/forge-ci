package checkout;

import auth.AuthService;
import orders.OrderService;
import payments.PaymentGateway;
import pricing.PriceCalculator;
import shared.Money;

public final class CheckoutFlow {

    private final PriceCalculator prices = new PriceCalculator();
    private final PaymentGateway payments = new PaymentGateway();
    private final AuthService auth = new AuthService();
    private final OrderService orders = new OrderService();

    public boolean checkout(String accountId, String token, String sku, Money base, Money shipping) {
        return auth.authorize(accountId, token)
                && payments.authorize(prices.total(base, shipping))
                && orders.place(sku);
    }
}
