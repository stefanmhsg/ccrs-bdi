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
Signifier vocabulary
*******************/

// Signifiers
is_signifier_predicate("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#green") . // Would expect this as Object..


/*******************/
/*******************/

/*******************
PROACTIVE CCRS
*******************/

// Detect open actions
+rdf(Subject, Predicate, Action)[rdf_type_map(_, _, _), source(Anchor)] :
    is_action_predicate(Predicate) | is_action_object(Action)
    <-
        .print("[CCRS] Action evaluation...") ;
        ?rdf(Action, P, O) ;

        // Check status
        if (is_boolean_predicate(P) & is_boolean_0(O) ) {
            +ccrs(Subject, Action)[ccrs_type("Action"),source(Anchor)] ;
            .print("[CCRS] Action required: ", Action, " for ", Subject, ". Source: ", Anchor) ;
            +requires_action(Anchor) ; // TODO: Thight coupling to agents
        }
        if (is_boolean_predicate(P) & is_boolean_1(O) ) {
            .print("[CCRS] Action already completed. Ignoring Action: ", Action, " for ", Subject, ". Source: ", Anchor) ;
        }
        // Variations of this
    .

/*******************
STIGMERGY CCRS
*******************/

// Detect Stigmergy Markers
+rdf(Subject, Predicate, Marker)[rdf_type_map(_, _, _), source(Anchor)] :
    is_stigmark_predicate(Predicate) | is_stigmark_object(Marker)
    <-
        if ( 
             ( rdf(Marker,_,Pheromone) & rdf(Pheromone,P,Value) & is_stigmergy_type(P) ) 
             &
             ( rdf(Pheromone,P2,PheromoneTarget) & is_pheromone_target_predicate(P2) )
            ) {
                .abolish( ccrs(PheromoneTarget,_)[ccrs_type("Stigmergy"),stigmergy_type(P),source(Anchor)] ) ;
                +ccrs(PheromoneTarget, Value)[ccrs_type("Stigmergy"),stigmergy_type(P),source(Anchor)] ;
                .print("[CCRS] Stigmergy value: ", Value, " assigned to Target: ", PheromoneTarget) ;
             }
    .

/*******************
SIGNIFIER CCRS
*******************/

// Detect Signifier
+rdf(Subject, Signifier, Target)[rdf_type_map(_, _, _), source(Anchor)] :
    is_signifier_predicate(Signifier)
    <-
        .abolish( ccrs(Target, _)[ccrs_type("Signifier"), source(Anchor)] ) ;
        +ccrs(Target, Signifier)[ccrs_type("Signifier"), source(Anchor)] ;
        .print("[CCRS] Signifier : ", Signifier, " assigned to Target: ", Target) ;
    .

/*******************/
/*******************/

/*******************
META CCRS
*******************/

+!ccrs(CurrentCell, OptionsList) :
    true
    <-
        !signifier_ccrs(CurrentCell, OptionsList) ;
        // !stigmergy_ccrs(CurrentCell, OptionsList) ;
        !random_ccrs(OptionsList) ;
    .


/*******************
SIGNIFIER CCRS
*******************/

+!signifier_ccrs(CurrentCell, List) :
    true
    <-
        .findall(ccrs(Target, _)[ccrs_type("Signifier"),source(CurrentCell)]), SigList) ; // Union of currently valid transition Options and CCRS Signifier Objects
        .difference(List, SigList, NonSigList) ; // Compute non-signifier options
        .concat(SigList, NonSigList, PrioritizedList) ; // Prepend CCRS Signifier Objects to remaining Options to create prioritized order

        .abolish(ccrs(_, _, _)[ccrs_type("Signifier"),type("PrioritizedList"),source(CurrentCell)) ;
        +ccrs(CurrentCell, List, PrioritizedList)[ccrs_type("Signifier"),type("PrioritizedList"),source(CurrentCell)] ;
        .print("[CCRS] Signifier resulted in: ", PrioritizedList) ;
        .succeed_goal(ccrs(CurrentCell, OptionsList) ;
    .


/*******************
STIGMERGY CCRS
*******************/



/*******************
RANDOM CCRS
*******************/

+!random_ccrs(CurrentCell, List) :
    true
    <-        
        // randomly choose from list
        jia.pick(List, Result) ;
        .abolish(ccrs(_,_,_)[ccrs_type("Random")]) ;
        +ccrs(CurrentCell, List, Result)[ccrs_type("Random")] ;
        .print("[CCRS] Random resultet in: ", Result) ;
        .succeed_goal(ccrs(CurrentCell, OptionsList) ;
    .

