package shared;

// shared value type used by pricing, payments, catalog, and checkout
public record Money(String currency, long minorUnits) {

    public Money plus(Money other) {
        return new Money(currency, minorUnits + other.minorUnits());
    }
}
