package com.larvalabs.brace;

import java.util.Map;

public record RouteMatch(Route route, Map<String, String> pathParams) {
}
