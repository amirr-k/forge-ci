package storefront;

import catalog.CatalogService;

public final class Storefront {

    private final CatalogService catalog = new CatalogService();

    public String render(String sku) {
        return sku + " " + catalog.listPrice(sku);
    }
}
