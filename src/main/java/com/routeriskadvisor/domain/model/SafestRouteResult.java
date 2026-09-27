package com.routeriskadvisor.domain.model;

import java.util.List;

/**
 * The safest-route selection result: one recommendation per risk category (Req 4.4, 4.5).
 */
public record SafestRouteResult(List<CategoryRecommendation> perCategory) {}
