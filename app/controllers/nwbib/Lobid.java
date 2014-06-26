package controllers.nwbib;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.HasChildFilterBuilder;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.facet.FacetBuilders;
import org.elasticsearch.search.facet.Facets;
import org.elasticsearch.search.facet.terms.TermsFacetBuilder;

import play.Logger;
import play.cache.Cache;
import play.libs.F.Promise;
import play.libs.WS;
import play.libs.WS.WSRequestHolder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Access Lobid title data.
 *
 * @author fsteeg
 *
 */
public class Lobid {

	static Long getTotalResults(JsonNode json) {
		return json.findValue("http://sindice.com/vocab/search#totalResults")
				.asLong();
	}

	static WSRequestHolder request(final String q, final int from,
			final int size, String owner, String t) {
		WSRequestHolder requestHolder = WS
				.url(Application.CONFIG.getString("nwbib.api"))
				.setHeader("Accept", "application/json")
				.setQueryParameter("set",
						Application.CONFIG.getString("nwbib.set"))
				.setQueryParameter("format", "full")
				.setQueryParameter("from", from + "")
				.setQueryParameter("size", size + "")
				.setQueryParameter("q", q);
		if (!owner.equals("all"))
			requestHolder = requestHolder.setQueryParameter("owner", owner);
		if(!t.isEmpty())
			requestHolder = requestHolder.setQueryParameter("t", t);
		Logger.info("Request URL {}, query params {} ", requestHolder.getUrl(),
				requestHolder.getQueryParameters());
		return requestHolder;
	}

	public static Promise<Long> getTotalHits() {
		final Long cachedResult = (Long) Cache.get(String.format("totalHits"));
		if (cachedResult != null) {
			return Promise.promise(() -> {
				return cachedResult;
			});
		}
		WSRequestHolder requestHolder = request("", 0, 0, "", "");
		return requestHolder.get().map((WS.Response response) -> {
			Long total = getTotalResults(response.asJson());
			Cache.set("totalHits", total, Application.ONE_HOUR);
			return total;
		});
	}

	public static String organisationLabel(String url){
		final String cachedResult = (String) Cache.get(String.format("org.label."+url));
		if (cachedResult != null) {
				return cachedResult;
		}
		WSRequestHolder requestHolder = WS
				.url(url)
				.setHeader("Accept", "application/json")
				.setQueryParameter("format","short.name");
		return requestHolder.get().map((WS.Response response) -> {
			String label = response.asJson().elements().next().asText();
			Cache.set("org.label."+url, label, Application.ONE_HOUR);
			return label;
		}).get(5000);
	}

	public static Promise<Facets> getFacets(String q, String owner, String field) {
		return Promise.promise(() -> {
			BoolQueryBuilder query = QueryBuilders
					.boolQuery()
					.must(q.isEmpty() ? QueryBuilders.matchAllQuery()
							: QueryBuilders.queryString(q).field("_all"))
					.must(QueryBuilders.matchQuery(
							"@graph.http://purl.org/dc/terms/isPartOf.@id",
							Application.CONFIG.getString("nwbib.set")).operator(
							MatchQueryBuilder.Operator.AND));
			SearchRequestBuilder req = Application.CLASSIFICATION.client
					.prepareSearch("lobid-resources")
					.setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
					.setQuery(query).setTypes("json-ld-lobid").setFrom(0)
					.setSize(0);
			TermsFacetBuilder facet = FacetBuilders.termsFacet(field).field(field);
			if (!owner.equals("all")) {
				String fieldName = "@graph.http://purl.org/vocab/frbr/core#exemplar.@id";
				FilterBuilder filter = owner.equals("*") ? FilterBuilders.existsFilter(fieldName)
						: ownersFilter(owner, fieldName);
				facet = facet.facetFilter(filter);
			}
			req = req.addFacet(facet);
			SearchResponse res = req.execute().actionGet();
			Facets facets = res.getFacets();
			Logger.debug("Facets for q={}, owner={}, facets={}: {}", q, owner, field, facets);
			return facets;
		}).recover((Throwable x) -> {
			x.printStackTrace();
			return null;
		});
	}

	private static FilterBuilder ownersFilter(final String ownerParam,
			String fieldName) {
		BoolFilterBuilder boolFilter = FilterBuilders.boolFilter();
		for (String owner : ownerParam.split(",")) {
			boolFilter = boolFilter.should(FilterBuilders
					.queryFilter(QueryBuilders.matchQuery(
							"@graph.http://purl.org/vocab/frbr/core#owner.@id",
							owner)));
		}
		HasChildFilterBuilder result = FilterBuilders.hasChildFilter(
				"json-ld-lobid-item", boolFilter);
		return result;
	}

	public static String typeLabel(String type) {
		Logger.trace("Type: " + type);
		final String[] segments = type.split("[/#]");
		final String raw = segments[segments.length - 1];
		@SuppressWarnings("unchecked")
		List<String> vals = (List<String>) Application.CONFIG
				.getObject("type.labels").unwrapped().get(type);
		return vals == null ? raw : vals.get(0);
	}

	public static String typeIcon(List<String> types) {
		Logger.trace("Types: " + types);
		@SuppressWarnings("unchecked")
		List<String> selected = types.stream()
			.map(t -> {
				List<String> vals = ((List<String>) Application.CONFIG
				.getObject("type.labels").unwrapped().get(t));
				return vals == null ? "" : vals.get(1);
			})
			.filter(t -> { return !t.isEmpty(); })
			.collect(Collectors.toList());
		Collections.sort(selected);
		return selected.isEmpty() ? types.get(0) : selected.get(0);
	}
}
