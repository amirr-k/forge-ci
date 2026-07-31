package shared;

// shared value type used by pricing, payments, catalog, and checkout
// demo scenario: shared-core change — rounding behavior added for every consumer.
public record Money(String currency, long minorUnits) {

    public Money plus(Money other) {
        return new Money(currency, minorUnits + other.minorUnits());
    }

    public Money rounded() {
        return new Money(currency, minorUnits);
    }
}
