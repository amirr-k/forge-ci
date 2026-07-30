package payments;

import shared.Money;

public final class PaymentGateway {

    public boolean authorize(Money amount) {
        return amount.minorUnits() > 0;
    }
}
