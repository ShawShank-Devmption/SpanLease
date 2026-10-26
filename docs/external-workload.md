# External topology provenance

The `hotel-search-paths` workload is a synchronous path projection of DeathStarBench
Hotel Reservation at commit `82bdfc5327ed`:

- [Frontend source](https://github.com/delimitrou/DeathStarBench/blob/82bdfc5327ed/hotelReservation/services/frontend/server.go)
- [Search source](https://github.com/delimitrou/DeathStarBench/blob/82bdfc5327ed/hotelReservation/services/search/server.go)

The frontend calls search; search calls geo and then rate. Our two independent finite
root chains project the paths frontend→search→geo and frontend→search→rate. Their
service bindings come from the source. Work durations, request rates, capacity and
queue policy are synthetic configuration, recorded in each run. We have not copied
source code, data, workload distributions or business logic.

This projection removes sibling-call sequencing, data dependencies, databases, caches,
service discovery and Go concurrency. It deliberately does not claim to reproduce the
original application's performance or its deadlock behavior. The paths provide an
externally sourced negative topology beyond constructed rings; they are not a second
independent application implementation. End-to-end realism acceptance remains a
review question under E-REALISM. Do not describe these runs as DeathStarBench results.
