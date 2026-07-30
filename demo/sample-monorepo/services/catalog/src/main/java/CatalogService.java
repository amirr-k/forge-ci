package catalog;

import shared.Money;

public final class CatalogService {

    public Money listPrice(String sku) {
        return new Money("USD", sku.length() * 100L);
    }
}
