// Agent sample_agent in project ccrs_bdi

/* Initial beliefs and rules */
knownVocab("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze") .
mazeEntry("http://127.0.1.1:8080/maze") .

is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#north") .
is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#south") .
is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#east") .
is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#west") .
is_exit("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#exit") .

is_wall("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#Wall") .

knownResource(URI) :- rdf(_, _, _)[source(URI)] . // consider this resource (URI) already visited if any triple was retrieved from this URI
visitedCell(URI) :- visited(URI)[parent(_)] . // consider cell visited if belief is present

/* Initial goals */

!start.

/* Plans */

+!start :
    true
    <-
    .date(Y,M,D); .time(H,Min,Sec,MilSec) ; // get current date & time
    +started(Y,M,D,H,Min,Sec) ;             // add a new belief
    +at("Root") ;
    !crawl("http://127.0.1.1:8080/maze") ;
  .


+!crawl(URI) :
    true
    <-
    //    for (knownVocab(Vocab)) {
    //        .print("Retrieving OWL definitions of ", Vocab) ;
    //        get(Vocab) ; // TODO: uncomment if needed.
    //    }
        +crawling ;
        .print("Retrieving ", URI) ;
        !get(URI) ;
  .

/*******************
REACTING TO EVENTS
*******************/

// Go to Maze Start
+rdf(S, "http://www.w3.org/1999/xhtml/vocab#start", Start)[rdf_type_map(_, _, uri), source(Anchor)] :
    crawling & h.target(Start, Target)
    <-
        .print("Discovered start: ", Target) ;
        !!get(Target) ;
    .

// Update position
+rdf(Position, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "https://kaefer3000.github.io/2021-02-dagstuhl/vocab#Cell")[rdf_type_map(_, _, uri), source(Anchor)] :
    crawling & h.target(Position, Target)
    <-
        ?at(PreviousCell) ;
        -+at(Target) ; // update position. same as -at(_) ; +at(Target) ;
        .print("I'm now at: ", Target) ;
        if (not (visitedCell(Target))) { // Only add once on first visit.
            +visited(Target)[parent(PreviousCell)] ; // mark current position as visited (path tracking, not same as fully explored). Keep track of where we came from with a custom annotation.
        }
    .

// Map neighboring cells (as soon as they get perceived)
+rdf(CurrentCell, Dir, Option)[rdf_type_map(_, _, uri), source(Anchor)] :
    is_direction(Dir) // Filter for Predicates that are a direction
    <-
        -transition(Dir, _) ; // remove previous transition of this direction. Ensure that transitions of e.g. (north, wall) get deleted and re-added and dont get base[] added only.
        +transition(Dir, Option)[base(CurrentCell)] ; // add curently perceived transition of this direction.
        .print(Dir," is ", Option);
    .

// Detect locked state
+rdf(CurrentCell, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#state", "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#locked")[rdf_type_map(uri, _, _), source(Anchor)] :
    crawling & h.target(CurrentCell, Target)
    <-
        if (not rdf(CurrentCell, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#state", "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#unlocked")) {
            .print(Target, " is locked!") ;
            +isLocked(Target) ;
        }
    .

// Detect open action
+rdf(CurrentCell, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsAction", Action)[rdf_type_map(uri, _, _), source(Anchor)] :
    crawling & h.target(CurrentCell, Target)
    <-
        if (not rdf(Action, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#hasStatus", "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#done")) {
            +requires_action(Target) ;
            .print("Action required: ", Action) ;
        } else {
            -requires_action(Target) ;
            .print("Action completed: ", Action) ;
        }
    .

// Detect exit
+rdf(CurrentCell, Dir, ExitCell)[rdf_type_map(_, _, uri), source(Anchor)] :
    is_exit(Dir)
    <-
        -transition(Dir, _) ;
        +transition(Dir, ExitCell)[base(CurrentCell)] ; // add curently perceived transition of this direction.
        +exit(ExitCell) ;
        .print("Found Exit! It's at: ", ExitCell);
    .

/*******************
DELIBERATION STEPS
*******************/

// Plan next move
-crawling :
    not finished(_,_,_,_,_,_)
    <-  
        ?at(CurrentCell) ; // using a test-goal to bind logical variable "CurrentCell" to the value from the belief at()
        if (requires_action(CurrentCell)) { //check if cell requires action
            .print("Evaluating actions in: ", CurrentCell) ;
            !evaluate_actions(CurrentCell) ; // Check for necessary actions
        } elif (not exit(CurrentCell)) {
            .print("No open actions. Deciding next step from: ", CurrentCell) ;
            !evaluate_transitions(CurrentCell) ; // Annotate valid transitions
            !track_unexplored_transitions(CurrentCell) ; // Tracking which valid dirs have (not) been explored from current cell
            !select_next(CurrentCell) ; // Select next move
        }
    .

+!evaluate_actions(CurrentCell) :
    isLocked(CurrentCell)
    <-
        ?rdf(CurrentCell, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsAction", Action) ;
        ?rdf(Action, "http://www.w3.org/2011/http#body", Body) ;
        ?rdf(Action, "http://www.w3.org/2011/http#mthd", Method) ;
        
        ?rdf(Body, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsProperty", Property) ;
        ?rdf(Body, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#foundAt", Keyname) ;
        
        ?rdf(KeyId, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", Keyname) ;
        ?rdf(KeyId, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#keyValue", Keyvalue) ;

        .print(CurrentCell, " needs ", Action, " with Method ", Method, " and Body ", Body, " with Property ", Property, " of ", Keyname, " which is ", Keyvalue) ;

        !post(CurrentCell, [rdf(CurrentCell, Property, Keyvalue)[rdf_type_map(uri,uri,literal)]]) ;
        -isLocked(Target) ;
        //.wait(10000) ;
        !crawl(CurrentCell) ; // TODO: potential loop
    .

+!evaluate_actions(CurrentCell) :
    not isLocked(CurrentCell)
    <-
        .print("Unable to cope with actions in: ", CurrentCell) ;
    .

// Annotate valid transitions
+!evaluate_transitions(CurrentCell) :
    visited(CurrentCell)[parent(Parent)]
    <-
        if (not crawling) {
             .print("Evaluate transitions from: ", CurrentCell) ;
            -transition(_,_)[base(Parent)] ; // Delete all remaining transition beliefs from Parent Cell. If no Dir is present because is Locked -> should still remove the previous belief of this Dir!
            // Evaluate transition possibilities
            for (transition(Dir, Option)) { // Loop through beliefs of form transtion/2
                if (not (is_wall(Option)) & CurrentCell \== Option & Parent \== Option) { // Filter out Walls / going to self / going back
                    .print("Could go ", Option) ;
                    +transition(Dir, Option)[valid_transition("True")] ; // Append an annotation to the existing belief
                }
            }       
        }
    .

// Create DFS structure if not existing (for tracking which dirs have been explored from current cell).
+!track_unexplored_transitions(CurrentCell) :
    true 
    <-
        if (not remaining(CurrentCell, _)) { // Ensure this is only added once to keep track of explored paths
            .findall(X, transition(_,X)[valid_transition("True")], List) ; // Retruns List as list of all X = Options from transition beliefs that are annotated as valid.
            .print("Tracking unexplored transitions: ", List) ;
            +remaining(CurrentCell, List) ; // Add belief of unexplored options based from current cell.
        } else {
            .print("Tracking list of unexplored transitions already available.") ;
        }
    .

// Next move if current cell has unexplored options left
+!select_next(CurrentCell) :
    remaining(CurrentCell, [Next | Tail]) // check if list has next item
    <-
        if (exit(ExitCell)) {
            !crawl(ExitCell) ;
        }
        elif (not crawling) {
            .print("Selected next option: ", Next) ;
            -remaining(CurrentCell, [Next|Tail]) ; // Delete item
            +remaining(CurrentCell, Tail) ; // Add item minus next option
            .print("remaining options: ", Tail) ;
            !crawl(Next) ;        
        }
    .

// Next move if current cell has no options left
+!select_next(CurrentCell) :
    remaining(CurrentCell, []) // check if list is empty = cell fully explored
    <-
        if (not crawling) {
            .print("Cell fully explored, lets backtrack") ;
            !backtrack_from(CurrentCell) ; // Dead-end.. Go to parent cell        
        }
    .

+!backtrack_from(CurrentCell) :
    visited(CurrentCell)[parent (Parent)]
    <-
        .print("Going back to parent cell: ", Parent) ;
        !crawl(Parent) ;
    .

/*******************
HELPER PLANS
*******************/

+!get(URI) :
    crawling
    <-
            get(URI) ;
            !!checkEndCrawl ;
    .

  +!post(URI, Body) :
    true
    <-
        h.target(URI, TargetURI) ;
        .print("POST to: ", URI, " with body: ", Body) ;
        post(URI, Body);

       // ?(rdf(TargetURI, "related", CreatedResourceURI)) ;
       // .print("Created resource: ", CreatedResourceURI) ;
       // h.target(CreatedResourceURI, CreatedTargetURI) ;
        //get(CreatedResourceURI) ;
        //?(json(Val)[source(CreatedTargetURI)]) ;
    .

+!checkEndCrawl :
    crawling
    <-
        if (not .intend(get(_))) { !endCrawl }
    .

+!endCrawl :
    crawling
    <-
        -crawling ; // remove crawling belief
        .print("End crawling...") ;
        ?at(CurrentCell) ;
        if (exit(CurrentCell)) {
            .print("Sucessfully exited") ;
            .date(Y,M,D); .time(H,Min,Sec,MilSec) ;
            +finished(Y,M,D,H,Min,Sec) ;    
            !!stop ;         
        }
    .  

+!stop :
    true
    <-
        !report_time ;
        // all crawled triples are exposed to the agent as rdf/3 terms
        .count(rdf(S, P, O), Count) ;
        .print("found ", Count, " triples in the KG.") ;

    .

+!report_time :
    true
    <-
        ?started(Y1, M1, D1, H1, Min1, S1) ;
        ?finished(Y2, M2, D2, H2, Min2, S2) ;

        // convert both to seconds
        T1 = ((H1 * 3600) + (Min1 * 60) + S1) ;
        T2 = ((H2 * 3600) + (Min2 * 60) + S2) ;

        Diff = T2 - T1 ;

        //Min = Diff / 60;
        //Sec = Diff mod 60;

        .print("It took ", Diff, " seconds");
    .



/* Include common templates */
{ include("$jacamo/templates/common-cartago.asl") }
{ include("$jacamo/templates/common-moise.asl") }

// uncomment the include below to have an agent compliant with its organisation
//{ include("$moise/asl/org-obedient.asl") }
