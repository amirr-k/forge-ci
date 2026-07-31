package orders;

import catalog.CatalogService;
import payments.PaymentGateway;
import shared.Money;

public final class OrderService {

    private final CatalogService catalog = new CatalogService();
    private final PaymentGateway payments = new PaymentGateway();

    public boolean place(String sku) {
        Money price = catalog.listPrice(sku);
        return payments.authorize(price);
    }
}
