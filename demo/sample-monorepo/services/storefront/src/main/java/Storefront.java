package storefront;

import catalog.CatalogService;
import search.CatalogSearch;

public final class Storefront {

    private final CatalogService catalog = new CatalogService();
    private final CatalogSearch search = new CatalogSearch();

    public String render(String sku, String query) {
        if (!search.matches(catalog, sku, query)) {
            return "";
        }
        return sku + " " + catalog.listPrice(sku);
    }
}
