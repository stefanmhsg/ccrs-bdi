/*******************
Action vocabulary
*******************/

// Actions
is_action_predicate("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsAction") .
is_action_object("https://schema.org/Action") .

// Boolean States
is_boolean_predicate("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#hasStatus") .
is_boolean_predicate("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#state") .
// false, off, inactive
is_boolean_0("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#open") .
is_boolean_0("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#unlocked") .
// true, on, active
is_boolean_1("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#done") .
is_boolean_1("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#locked") .

// Enum States
is_enum_predicate("https://schema.org/ActionStatusType") .

/*******************
Stigmergy vocabulary
*******************/

// Markers
is_stigmark_object("https://example.org/stigmark#Marker") .
is_stigmark_predicate("https://example.org/stigmark#hasMarker") .

// Types
is_stigmergy_type("https://example.org/stigmark#quantitative") .

// Target
is_pheromone_target_predicate("https://example.org/stigmark#refersTo") .

/*******************
PROACTIVE CCRS
*******************/

// Detect open actions
+rdf(Subject, Predicate, Action)[rdf_type_map(_, _, _), source(Anchor)] :
    is_action_predicate(Predicate) | is_action_object(Action) & h.target(Anchor, Target)
    <-
        ?rdf(Action, P, O) ;

        // Check status
        if (is_boolean_predicate(P) & is_boolean_0(O) ) {
            +ccrs(Subject, Action)[ccrs_type("Action"),source(Target)] ;
            .print("[CCRS] Action required: ", Action, " for ", Subject, ". Source: ", Target) ;
        }
        // Variations of this
    .

/*******************
STIGMERGY CCRS
*******************/

// Detect Stigmergy Markers
+rdf(Subject, Predicate, Marker)[rdf_type_map(_, _, _), source(Anchor)] :
    is_stigmark_predicate(Predicate) | is_stigmark_object(Marker) & h.target(Anchor, Target)
    <-
        if ( 
             ( rdf(Marker,_,Pheromone) & rdf(Pheromone,P,Value) & is_stigmergy_type(P) ) 
             &
             ( rdf(Pheromone,P2,PheromoneTarget) & is_pheromone_target_predicate(P2) )
            ) {
                +ccrs(PheromoneTarget, Value)[ccrs_type("Stigmergy"),stigmergy_type(P),source(Target)] ;
                .print("[CCRS] Stigmergy value: ", Value, " assigned to Target: ", PheromoneTarget) ;
             }
    .