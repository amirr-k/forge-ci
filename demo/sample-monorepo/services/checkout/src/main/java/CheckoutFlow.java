package checkout;

import payments.PaymentGateway;
import pricing.PriceCalculator;
import shared.Money;

public final class CheckoutFlow {

    private final PriceCalculator prices = new PriceCalculator();
    private final PaymentGateway payments = new PaymentGateway();

    public boolean checkout(Money base, Money shipping) {
        return payments.authorize(prices.total(base, shipping));
    }
}
