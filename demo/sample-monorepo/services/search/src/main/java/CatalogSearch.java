package search;

import catalog.CatalogService;

public final class CatalogSearch {

    public boolean matches(CatalogService catalog, String sku, String query) {
        return catalog.listPrice(sku) != null && sku.toLowerCase().contains(query.toLowerCase());
    }
}
