package pricing;

import shared.Money;

public final class PriceCalculator {

    // demo scenario: leaf-module change — a discount rule added to Pricing only.
    public Money total(Money base, Money shipping) {
        return base.plus(shipping).plus(discount());
    }

    private Money discount() {
        return new Money("USD", 0L);
    }
}
