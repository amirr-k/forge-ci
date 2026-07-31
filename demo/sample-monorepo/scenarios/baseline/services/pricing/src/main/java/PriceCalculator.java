package pricing;

import shared.Money;

public final class PriceCalculator {

    public Money total(Money base, Money shipping) {
        return base.plus(shipping);
    }
}
