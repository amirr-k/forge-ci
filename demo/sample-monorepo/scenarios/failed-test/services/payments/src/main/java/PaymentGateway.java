package payments;

import shared.Money;

public final class PaymentGateway {

    // BROKEN: demo scenario — failed test. Left as an obviously wrong guard on purpose.
    public boolean authorize(Money amount) {
        return amount.minorUnits() < 0;
    }
}
