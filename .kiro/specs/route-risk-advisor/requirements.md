# Requirements Document

## Introduction

The Route Risk Advisor is a Java application that helps drivers understand and reduce the risks associated with the routes they travel. It provides two primary capabilities:

1. **Route Risk Classification** — A user enters an origin and destination (their usual path). The application resolves the route and classifies it by risk category (accident, theft, fire, and other car-related risks) so the user can choose an appropriate car insurance type.
2. **Safest Route Finder** — Given a set of locations, the application compares candidate routes and recommends the route with the lowest risk in each category.

For this first version, the application targets the Miami-Dade County, Florida region within the United States. All external data sources (geocoding, routing, crash data, crime/theft data, and fire incident data) are accessed through abstraction interfaces backed by placeholder/mock implementations that return realistic sample data. The design must allow real data providers to be substituted later without changing application logic.

## Glossary

- **Route_Risk_Advisor**: The overall Java application that classifies route risk and recommends safest routes.
- **Route_Classifier**: The component that assigns risk categories and scores to a resolved route.
- **Route_Finder**: The component that compares multiple candidate routes and selects the lowest-risk route per category.
- **Insurance_Advisor**: The component that maps route risk categories to recommended car insurance types.
- **Geocoding_Provider**: The abstraction that converts a location description into geographic coordinates.
- **Routing_Provider**: The abstraction that produces one or more candidate routes between geographic points.
- **Crash_Data_Provider**: The abstraction that supplies accident/crash risk data for a geographic area or route segment.
- **Crime_Data_Provider**: The abstraction that supplies theft/crime risk data for a geographic area or route segment.
- **Fire_Data_Provider**: The abstraction that supplies fire incident risk data for a geographic area or route segment.
- **Data_Provider**: A collective term for any of Geocoding_Provider, Routing_Provider, Crash_Data_Provider, Crime_Data_Provider, or Fire_Data_Provider.
- **Placeholder_Provider**: A mock implementation of a Data_Provider that returns realistic sample data instead of calling an external service.
- **Route**: An ordered sequence of connected road segments between an origin and a destination.
- **Route_Segment**: A single portion of a Route with an associated geographic extent.
- **Risk_Category**: One of the defined categories of route risk: Accident, Theft, Fire, or Other.
- **Risk_Score**: A numeric value from 0 to 100 representing the relative risk of a Route for a single Risk_Category, where higher values indicate greater risk.
- **Service_Area**: The supported geographic region for the current version, defined as Miami-Dade County, Florida, United States.
- **Insurance_Type**: A category of car insurance recommendation associated with one or more Risk_Categories.

## Requirements

### Requirement 1: Enter a Usual Route

**User Story:** As a driver, I want to enter my usual origin and destination, so that the application can resolve the path I typically travel.

#### Acceptance Criteria

1. WHEN a user submits an origin location and a destination location that each contain at least 1 and at most 250 non-whitespace characters, THE Route_Risk_Advisor SHALL request coordinates for both locations from the Geocoding_Provider.
2. IF the origin location or the destination location is empty, blank, or exceeds 250 characters, THEN THE Route_Risk_Advisor SHALL reject the submission, retain any previously entered values, and return an error message identifying which location is invalid.
3. WHEN the Geocoding_Provider returns coordinates for both locations, THE Route_Risk_Advisor SHALL request a Route between the coordinates from the Routing_Provider.
4. IF the origin location or the destination location cannot be resolved to coordinates, THEN THE Route_Risk_Advisor SHALL return an error message identifying which location could not be resolved.
5. IF the origin location or the destination location falls outside the Service_Area, THEN THE Route_Risk_Advisor SHALL return a message stating that the location is outside the supported Miami-Dade County service area.
6. IF the Routing_Provider returns no Route between the resolved coordinates, THEN THE Route_Risk_Advisor SHALL return a message stating that no route was found.
7. IF the resolved origin coordinates and destination coordinates are identical, THEN THE Route_Risk_Advisor SHALL return a message stating that the origin and destination are the same location and SHALL NOT request a Route from the Routing_Provider.
8. IF the Geocoding_Provider or the Routing_Provider does not return a response within 10 seconds, or returns a failure response, THEN THE Route_Risk_Advisor SHALL abort the request and return an error message indicating that the location or route service is temporarily unavailable.

### Requirement 2: Classify Route Risk by Category

**User Story:** As a driver, I want my route classified by risk category, so that I understand what kinds of car-related risks I face on that path.

#### Acceptance Criteria

1. WHEN a Route is resolved, THE Route_Classifier SHALL compute a Risk_Score for the Accident Risk_Category using data from the Crash_Data_Provider.
2. WHEN a Route is resolved, THE Route_Classifier SHALL compute a Risk_Score for the Theft Risk_Category using data from the Crime_Data_Provider.
3. WHEN a Route is resolved, THE Route_Classifier SHALL compute a Risk_Score for the Fire Risk_Category using data from the Fire_Data_Provider.
4. THE Route_Classifier SHALL assign each Risk_Score an integer value between 0 and 100 inclusive.
5. THE Route_Classifier SHALL assign each Risk_Category a risk level of Low, Medium, or High based on the Risk_Score, where 0 to 33 is Low, 34 to 66 is Medium, and 67 to 100 is High.
6. WHEN all Risk_Scores for a Route are computed within 5 seconds of receiving the resolved Route, THE Route_Classifier SHALL return the set of Risk_Categories with their Risk_Scores and risk levels.
7. IF a Data_Provider returns no data for a Route_Segment, THEN THE Route_Classifier SHALL mark the corresponding Risk_Category as Unknown for that Route_Segment.
8. IF every Route_Segment for a Risk_Category is marked Unknown, THEN THE Route_Classifier SHALL report that Risk_Category as Unknown for the Route rather than assigning a Risk_Score.
9. IF a Data_Provider does not return a response within 5 seconds or returns a failure response, THEN THE Route_Classifier SHALL mark the corresponding Risk_Category as Unknown for the Route and SHALL continue computing the remaining Risk_Categories.
10. IF the 5-second deadline for computing all Risk_Scores is exceeded, THEN THE Route_Classifier SHALL discard any computed result and report a timeout failure rather than returning a late result.

### Requirement 3: Recommend Insurance Type

**User Story:** As a driver, I want an insurance type recommendation based on my route risk, so that I can choose coverage that matches my exposure.

#### Acceptance Criteria

1. WHEN the Route_Classifier returns Risk_Categories for a Route, THE Insurance_Advisor SHALL map each Risk_Category with a risk level of Medium or High to a recommended Insurance_Type within 2 seconds of receiving the Risk_Categories.
2. THE Insurance_Advisor SHALL map the Accident Risk_Category to a collision coverage Insurance_Type.
3. THE Insurance_Advisor SHALL map the Theft Risk_Category to a comprehensive theft coverage Insurance_Type.
4. THE Insurance_Advisor SHALL map the Fire Risk_Category to a comprehensive fire coverage Insurance_Type.
5. WHERE all Risk_Categories for a Route have a risk level of Low, THE Insurance_Advisor SHALL recommend exactly one baseline liability coverage Insurance_Type and no additional Insurance_Types.
6. WHEN the Insurance_Advisor produces recommendations, THE Insurance_Advisor SHALL return each recommended Insurance_Type together with the one or more Risk_Categories that justify it, including for each the Risk_Category name and its risk level.
7. IF the Route_Classifier returns no Risk_Categories or a set that cannot be mapped to any Insurance_Type, THEN THE Insurance_Advisor SHALL return an empty recommendation set together with a status indicating that no recommendation could be produced, and SHALL retain the received Risk_Categories unchanged.
8. IF the Route_Classifier returns a set of Risk_Categories where some can be mapped to an Insurance_Type and others cannot, THEN THE Insurance_Advisor SHALL return recommendations for the mappable Risk_Categories and SHALL flag each unmappable Risk_Category as unresolved.

### Requirement 4: Find the Safest Route Among Multiple Locations

**User Story:** As a driver, I want to compare possible routes between a set of locations, so that I can travel the path with the lowest risk in each category.

#### Acceptance Criteria

1. WHEN a user submits between 2 and 25 locations, THE Route_Finder SHALL request candidate Routes connecting the locations from the Routing_Provider.
2. WHEN candidate Routes are returned, THE Route_Finder SHALL request a Risk_Score for each Risk_Category for every candidate Route from the Route_Classifier.
3. WHEN Risk_Scores for all candidate Routes are computed, THE Route_Finder SHALL identify the candidate Route with the lowest Risk_Score for each Risk_Category.
4. THE Route_Finder SHALL return, for each Risk_Category, the recommended candidate Route and its Risk_Score.
5. IF only one candidate Route is available, THEN THE Route_Finder SHALL return that Route as the recommendation for every Risk_Category.
6. IF fewer than 2 locations or more than 25 locations are submitted, THEN THE Route_Finder SHALL reject the request, return an error message stating that between 2 and 25 locations are required, and SHALL NOT request candidate Routes.
7. IF two or more candidate Routes share the lowest Risk_Score for a Risk_Category, THEN THE Route_Finder SHALL return the Route with the shorter total distance for that Risk_Category, and IF distances are also equal THEN THE Route_Finder SHALL return the Route that was returned earliest by the Routing_Provider.
8. IF the Routing_Provider returns no candidate Routes or does not respond within 10 seconds, THEN THE Route_Finder SHALL abort the comparison and return an error message indicating that no routes could be retrieved, without returning any recommendation.
9. IF the Route_Classifier fails to compute Risk_Scores for a candidate Route or does not respond within 10 seconds, THEN THE Route_Finder SHALL abort the comparison and return an error message indicating that route risk could not be evaluated, without returning a partial recommendation.

### Requirement 5: Pluggable Data Provider Abstraction

**User Story:** As a developer, I want all external data access behind abstraction interfaces, so that real data sources can replace placeholders without changing application logic.

#### Acceptance Criteria

1. THE Route_Risk_Advisor SHALL access geocoding, routing, crash, crime, and fire data exclusively through Data_Provider interfaces.
2. THE Route_Risk_Advisor SHALL provide a Placeholder_Provider implementation for each of the five Data_Provider interfaces (geocoding, routing, crash, crime, and fire).
3. WHEN a Placeholder_Provider is invoked, THE Placeholder_Provider SHALL return sample data for the Service_Area without calling an external network service, where each returned record conforms to the field structure defined by its Data_Provider interface.
4. WHERE a real Data_Provider implementation is configured for an interface, THE Route_Risk_Advisor SHALL route all calls for that interface to the configured implementation and SHALL NOT invoke the corresponding Placeholder_Provider.
5. WHERE no real Data_Provider implementation is configured for an interface, THE Route_Risk_Advisor SHALL route all calls for that interface to the Placeholder_Provider for that interface.
6. THE Route_Risk_Advisor SHALL select the Data_Provider implementation for each interface through configuration without requiring changes to the source of the Route_Classifier, Route_Finder, or Insurance_Advisor.
7. IF the configuration references a Data_Provider implementation that cannot be resolved or instantiated for an interface, THEN THE Route_Risk_Advisor SHALL halt startup and return an error indicating the interface name and the unresolved implementation, without processing any route request.

### Requirement 6: Miami-First Geographic Scope

**User Story:** As a product owner, I want the first version scoped to Miami-Dade County, so that we can validate the concept in a small area before expanding.

#### Acceptance Criteria

1. THE Route_Risk_Advisor SHALL define the Service_Area as Miami-Dade County, Florida, United States.
2. WHEN a user submits a location that resolves to a valid geographic coordinate, THE Route_Risk_Advisor SHALL determine whether the location falls within the Service_Area boundary within 2 seconds.
3. IF a submitted location falls outside the Service_Area, THEN THE Route_Risk_Advisor SHALL reject the request and return a message indicating that only the Miami-Dade County service area is supported, without processing a route.
4. WHEN a submitted location falls within the Service_Area, THE Route_Risk_Advisor SHALL accept the request and proceed with processing.
5. IF a submitted location is empty, malformed, or cannot be resolved to a geographic coordinate, THEN THE Route_Risk_Advisor SHALL reject the request and return a message indicating that the location is invalid, without altering any stored data.
6. THE Placeholder_Provider implementations SHALL return sample data for which every referenced location falls within the Service_Area.
