package rise.http;

import rise.DigitalGoodsStore;
import rise.RiseException;
import rise.db.DbIterator;
import rise.db.DbUtils;
import rise.db.FilteringIterator;
import rise.util.Convert;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

public final class SearchDGSGoods extends APIServlet.APIRequestHandler {

    static final SearchDGSGoods instance = new SearchDGSGoods();

    private SearchDGSGoods() {
        super(new APITag[] {APITag.DGS, APITag.SEARCH}, "query", "tag", "seller", "firstIndex", "lastIndex", "inStockOnly", "hideDelisted", "includeCounts");
    }

    @Override
    JSONStreamAware processRequest(HttpServletRequest req) throws RiseException {
        long sellerId = ParameterParser.getSellerId(req);
        String query = Convert.nullToEmpty(req.getParameter("query")).trim();
        String tag = Convert.emptyToNull(req.getParameter("tag"));
        if (tag != null) {
            query = "TAGS:" + tag + (query.equals("") ? "" : (" AND (" + query + ")"));
        }
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);
        boolean inStockOnly = !"false".equalsIgnoreCase(req.getParameter("inStockOnly"));
        boolean hideDelisted = "true".equalsIgnoreCase(req.getParameter("hideDelisted"));
        boolean includeCounts = !"false".equalsIgnoreCase(req.getParameter("includeCounts"));

        JSONObject response = new JSONObject();
        JSONArray goodsJSON = new JSONArray();
        response.put("goods", goodsJSON);

        FilteringIterator.Filter<DigitalGoodsStore.Goods> filter = hideDelisted ?
                new FilteringIterator.Filter<DigitalGoodsStore.Goods>() {
                    @Override
                    public boolean ok(DigitalGoodsStore.Goods goods) {
                        return ! goods.isDelisted();
                    }
                } :
                new FilteringIterator.Filter<DigitalGoodsStore.Goods>() {
                    @Override
                    public boolean ok(DigitalGoodsStore.Goods goods) {
                        return true;
                    }
                };

        FilteringIterator<DigitalGoodsStore.Goods> iterator = null;
        try {
            DbIterator<DigitalGoodsStore.Goods> goods;
            if (sellerId == 0) {
                goods = DigitalGoodsStore.searchGoods(query, inStockOnly, 0, -1);
            } else {
                goods = DigitalGoodsStore.searchSellerGoods(query, sellerId, inStockOnly, 0, -1);
            }
            iterator = new FilteringIterator<>(goods, filter, firstIndex, lastIndex);
            while (iterator.hasNext()) {
                DigitalGoodsStore.Goods good = iterator.next();
                goodsJSON.add(JSONData.goods(good, includeCounts));
            }
        } finally {
            DbUtils.close(iterator);
        }

        return response;
    }

}
