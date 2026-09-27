# Implementation Plan: Route Risk Advisor

## Overview

This plan implements the Route Risk Advisor as a Spring Boot (Java 17) application built with Maven and tested with JUnit 5 and jqwik. The build proceeds bottom-up and test-driven: data models first, then the provider abstraction interfaces and their deterministic Miami-Dade placeholder implementations, then the pure domain logic (service-area containment, risk scoring, classification, insurance mapping, safest-route selection), then the application services that orchestrate the flows with timeout/degradation policy, then the REST controllers and Spring configuration (`@ConditionalOnProperty` provider selection + startup validation), and finally end-to-end wiring.

Each of the 16 correctness properties from the design is implemented by exactly one jqwik property test, tagged with a comment in the format `// Feature: route-risk-advisor, Property {number}: {property_text}`, running a minimum of 100 generated cases. Property and unit test sub-tasks are marked optional with `*`; core implementation tasks are never optional.

## Tasks

- [x] 1. Set up Maven project structure and testing frameworks
  - Create the Maven project with Java 17, Spring Boot (web) starter, JUnit 5, jqwik, and ArchUnit dependencies in `pom.xml`
  - Establish package layout: `domain.model`, `provider`, `provider.placeholder`, `domain`, `service`, `web`, `config`
  - Add an `application.yml` skeleton with the `route-risk-advisor` config tree (service-area, providers, timeouts) from the design
  - Verify the project compiles and an empty test suite runs
  - _Requirements: 5.1, 6.1_

- [x] 2. Implement core data models
  - [x] 2.1 Implement geometry and route records
    - Create `GeoCoordinate`, `RouteSegment`, and `Route` records exactly as defined in the design (including `Route.providerIndex` and `totalDistanceMeters`)
    - _Requirements: 1.3, 4.7_

  - [x] 2.2 Implement risk and classification records/enums
    - Create `RiskCategory`, `RiskLevel`, `RiskObservation`, `RiskAssessment`, and `RouteClassification`
    - Encode that `RiskAssessment.score` is `null` when `level == UNKNOWN`
    - _Requirements: 2.4, 2.5, 2.7, 2.8_

  - [x] 2.3 Implement insurance and safest-route result records/enums
    - Create `InsuranceType`, `InsuranceRecommendation`, `RiskJustification`, `RecommendationStatus`, `InsuranceRecommendationResult`, `CategoryRecommendation`, and `SafestRouteResult`
    - _Requirements: 3.5, 3.6, 3.7, 4.4, 4.5_

- [x] 3. Define the provider abstraction layer
  - Create the five provider interfaces (`GeocodingProvider`, `RoutingProvider`, `CrashDataProvider`, `CrimeDataProvider`, `FireDataProvider`) with the exact signatures from the design
  - Create the `ProviderException` type carrying a timeout-vs-failure discriminator used by the service layer to choose degrade-vs-abort policy
  - _Requirements: 5.1_

- [x] 4. Implement the Service_Area validator
  - [x] 4.1 Implement `ServiceAreaValidator` with point-in-polygon containment
    - Define the Miami-Dade County bounding polygon and implement `contains(GeoCoordinate)` via a point-in-polygon test
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 4.2 Write property test for Service_Area containment
    - **Property 4: Service_Area containment governs acceptance**
    - **Validates: Requirements 1.5, 6.2, 6.3, 6.4**
    - Use in-polygon and out-of-polygon Miami-Dade `GeoCoordinate` generators
    - _Requirements: 1.5, 6.2, 6.3, 6.4_

- [x] 5. Implement the risk scorer
  - [x] 5.1 Implement `RiskScorer` distance-weighted mean scoring and level banding
    - Aggregate present segment intensities via distance-weighted mean of `normalizedIntensity`, map to `round(intensity * 100)` clamped to `[0,100]`, and band into Low (0–33) / Medium (34–66) / High (67–100)
    - Return `Unknown` (no score) for a category only when every segment lacked data for it
    - _Requirements: 2.4, 2.5, 2.7, 2.8_

  - [x] 5.2 Write property test for bounded integer scores
    - **Property 5: Risk scores are bounded integers**
    - **Validates: Requirements 2.4**
    - Use `Route`/`RouteSegment` and `RiskObservation` generators over `[0.0,1.0]`
    - _Requirements: 2.4_

  - [x] 5.3 Write property test for score-to-level mapping
    - **Property 6: Score maps to the correct risk level**
    - **Validates: Requirements 2.5**
    - Use score generators over `0..100`
    - _Requirements: 2.5_

- [x] 6. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement the Route_Classifier
  - [x] 7.1 Implement `RouteClassifier` orchestration over crash/crime/fire providers
    - For each segment, draw Accident from `CrashDataProvider`, Theft from `CrimeDataProvider`, Fire from `FireDataProvider`; aggregate per category via `RiskScorer`; always return one assessment per Accident/Theft/Fire category; never throw for provider failure — degrade the affected category to `Unknown`
    - _Requirements: 2.1, 2.2, 2.3, 2.6, 2.7, 2.8, 2.9_

  - [x] 7.2 Write property test for Unknown aggregation and category completeness
    - **Property 7: Unknown aggregation and category completeness**
    - **Validates: Requirements 2.6, 2.7, 2.8**
    - Use `Route` generators with per-segment has-data/no-data flags
    - _Requirements: 2.6, 2.7, 2.8_

  - [x] 7.3 Write property test for isolated provider failure
    - **Property 8: Provider failure is isolated to its category**
    - **Validates: Requirements 2.9**
    - Use provider-failure subset generators over `{crash, crime, fire}`
    - _Requirements: 2.9_

  - [x] 7.4 Write unit tests for classifier provider wiring
    - Assert Accident/Theft/Fire are drawn from crash/crime/fire providers respectively, and score→level boundary examples at 33/34 and 66/67
    - _Requirements: 2.1, 2.2, 2.3, 2.5_

- [x] 8. Implement the Insurance_Advisor
  - [x] 8.1 Implement `InsuranceAdvisor` mapping, baseline, and none-produced rules
    - Map each Medium/High category through the fixed table (Accident→collision, Theft→theft, Fire→fire); when all categories are Low emit exactly one `LIABILITY_BASELINE`; attach justifications; return empty set with `NONE_PRODUCED` and retain received assessments unchanged when nothing maps
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x] 8.2 Write property test for Medium/High insurance mapping
    - **Property 9: Insurance mapping for Medium and High categories**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4**
    - Use `RouteClassification` generators spanning mixed Medium/High
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [x] 8.3 Write property test for all-Low baseline recommendation
    - **Property 10: All-Low routes recommend a single baseline liability**
    - **Validates: Requirements 3.5**
    - _Requirements: 3.5_

  - [x] 8.4 Write property test for justified recommendations
    - **Property 11: Every recommendation is justified**
    - **Validates: Requirements 3.6**
    - _Requirements: 3.6_

  - [x] 8.5 Write property test for unmappable input none-produced
    - **Property 12: Unmappable input yields none-produced with input retained**
    - **Validates: Requirements 3.7**
    - Use all-Unknown and empty `RouteClassification` generators
    - _Requirements: 3.7_

- [x] 9. Implement the Route_Finder
  - [x] 9.1 Implement `RouteFinder` per-category minimum selection with tie-breaking
    - Select the minimum under lexicographic order `(Risk_Score, total distance, provider index)` per category, skipping `Unknown` categories; a single candidate is recommended for every category
    - _Requirements: 4.3, 4.4, 4.5, 4.7_

  - [x] 9.2 Write property test for safest-route ordering and tie-breaking
    - **Property 14: Safest route is the minimum under the tie-break order**
    - **Validates: Requirements 4.3, 4.4, 4.5, 4.7**
    - Use candidate-route-set generators with deliberately colliding scores and distances
    - _Requirements: 4.3, 4.4, 4.5, 4.7_

- [x] 10. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Implement the placeholder providers
  - [x] 11.1 Implement placeholder Geocoding and Routing providers
    - `PlaceholderGeocodingProvider` maps a curated set of Miami-Dade place names to in-county coordinates and returns empty for unresolvable inputs; `PlaceholderRoutingProvider` synthesizes single and multiple candidate routes with stable `providerIndex` and plausible distances, all coordinates in-area
    - _Requirements: 5.2, 5.3, 6.6_

  - [x] 11.2 Implement placeholder Crash, Crime, and Fire providers
    - Return deterministic per-segment `RiskObservation`s keyed off segment geography, with some segments deliberately empty to exercise `Unknown` paths; all referenced coordinates in-area
    - _Requirements: 5.2, 5.3, 6.6_

  - [x] 11.3 Write property test for placeholder validity and in-area output
    - **Property 16: Placeholder output is valid and in-area**
    - **Validates: Requirements 5.3, 6.6**
    - Assert returned records conform to interface field structure/ranges and every coordinate is inside the Service_Area
    - _Requirements: 5.3, 6.6_

- [x] 12. Implement Spring provider-selection configuration and startup validation
  - [x] 12.1 Wire provider beans via `@ConditionalOnProperty` with placeholder defaults
    - Register exactly one bean per interface based on the `route-risk-advisor.providers.*` keys, defaulting to the placeholder implementations
    - _Requirements: 5.4, 5.5, 5.6_

  - [x] 12.2 Implement startup validator halting on unresolved implementations
    - On startup confirm each of the five interfaces resolves to exactly one instantiable bean; halt startup with an error naming the interface and unresolved implementation otherwise
    - _Requirements: 5.7_

  - [x] 12.3 Write integration tests for provider selection and startup validation
    - Assert default config routes to placeholders, a stub "real" implementation receives calls without invoking the placeholder, and an unresolvable implementation fails startup with the naming error
    - _Requirements: 5.4, 5.5, 5.7_

  - [x] 12.4 Write architecture test for the abstraction constraint
    - ArchUnit rule: `Route_Classifier`, `Route_Finder`, `Insurance_Advisor`, and `RiskScorer` depend only on provider interfaces, never on concrete implementations
    - _Requirements: 5.1, 5.6_

- [x] 13. Implement the Route Risk Service (classification flow)
  - [x] 13.1 Implement `RouteRiskService` orchestration with timeout/abort policy
    - Validate input length 1..250 (naming invalid field, retaining prior values, no state mutation); geocode both locations with a 10s budget; reject unresolved locations naming them; enforce Service_Area containment (≤2s); short-circuit identical coordinates without routing; request the route (10s) and return no-route when absent; run classification (5s) then insurance recommendation (2s); abort with temporarily-unavailable on geocoding/routing timeout or failure
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 6.5_

  - [x] 13.2 Write property test for input validation
    - **Property 1: Input validation accepts iff within bounds**
    - **Validates: Requirements 1.1, 1.2, 6.5**
    - Use string generators across boundary lengths (0, 1, 250, 251) and whitespace-only content
    - _Requirements: 1.1, 1.2, 6.5_

  - [x] 13.3 Write property test for resolution-failure identification
    - **Property 2: Resolution failure identifies the failing location**
    - **Validates: Requirements 1.4**
    - _Requirements: 1.4_

  - [x] 13.4 Write property test for identical-coordinate short-circuit
    - **Property 3: Identical coordinates short-circuit routing**
    - **Validates: Requirements 1.7**
    - Assert the Routing_Provider is never called
    - _Requirements: 1.7_

  - [x] 13.5 Write integration tests for classification timeout policy
    - Assert geocoding/routing 10s timeout aborts with temporarily-unavailable, and risk-data 5s timeout degrades only the affected category
    - _Requirements: 1.8, 2.9_

- [x] 14. Implement the Safest Route Service (comparison flow)
  - [x] 14.1 Implement `SafestRouteService` orchestration with count validation and atomic abort
    - Reject <2 or >25 locations with the count message and no routing; validate every location in-area; request candidate routes (10s) and abort with no-routes-retrieved on empty/timeout; classify each candidate (10s) and abort with route-risk-unevaluated on any classifier failure/timeout; return the safest route per category via `RouteFinder`
    - _Requirements: 4.1, 4.2, 4.6, 4.8, 4.9_

  - [x] 14.2 Write property test for location-count validation
    - **Property 13: Location count validation**
    - **Validates: Requirements 4.1, 4.6**
    - Use location-count generators over `0..40`
    - _Requirements: 4.1, 4.6_

  - [x] 14.3 Write property test for atomic abort without partial results
    - **Property 15: Comparison aborts atomically without partial results**
    - **Validates: Requirements 4.8, 4.9**
    - _Requirements: 4.8, 4.9_

  - [x] 14.4 Write unit/integration tests for classifier-per-candidate and abort messages
    - Assert the classifier is invoked once per candidate route and the correct abort messages/timeouts for no-routes and classifier failure
    - _Requirements: 4.2, 4.8, 4.9_

- [x] 15. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 16. Implement the REST controllers and error envelope
  - [x] 16.1 Implement classification and safest-route endpoints with error mapping
    - Add `POST /api/routes/classify` and `POST /api/routes/safest`; wire request/response DTOs; implement the shared `{ code, message, field? }` error envelope with the HTTP status map (400 validation/business, 404 no-route, 503 provider unavailable)
    - _Requirements: 1.2, 1.4, 1.5, 1.6, 1.7, 1.8, 4.6, 4.8, 4.9_

  - [x] 16.2 Write end-to-end integration tests for both endpoints
    - Placeholder-backed happy paths for classify and safest returning within time budgets, plus a smoke test asserting a placeholder bean exists for each interface and the Service_Area resolves to Miami-Dade, FL
    - _Requirements: 2.6, 3.1, 5.2, 6.1, 6.2_

- [x] 17. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional (unit, property, integration, and architecture tests) and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each of the 16 correctness properties is implemented by exactly one jqwik property test (Properties 1–16 appear in tasks 13.2, 13.3, 13.4, 4.2, 5.2, 5.3, 7.2, 7.3, 8.2, 8.3, 8.4, 8.5, 14.2, 9.2, 14.3, 11.3), each tagged `// Feature: route-risk-advisor, Property {number}: {property_text}` and running ≥100 generated cases.
- Each task references specific requirement sub-clauses for traceability; checkpoints ensure incremental validation.
- Property tests validate universal correctness; unit tests cover branch/boundary examples; integration tests cover timeout, provider-selection, and startup behavior; the ArchUnit test enforces the abstraction constraint.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "2.2", "2.3", "4.1"] },
    { "id": 2, "tasks": ["3.1", "4.2", "5.1", "11.1", "11.2"] },
    { "id": 3, "tasks": ["5.2", "5.3", "7.1", "8.1", "9.1", "11.3", "12.1"] },
    { "id": 4, "tasks": ["7.2", "7.3", "7.4", "8.2", "8.3", "8.4", "8.5", "9.2", "12.2", "12.4"] },
    { "id": 5, "tasks": ["12.3", "13.1", "14.1"] },
    { "id": 6, "tasks": ["13.2", "13.3", "13.4", "13.5", "14.2", "14.3", "14.4", "16.1"] },
    { "id": 7, "tasks": ["16.2"] }
  ]
}
```
