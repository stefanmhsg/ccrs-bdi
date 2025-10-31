// Agent sample_agent in project ccrs_bdi

/* Initial beliefs and rules */
knownVocab("https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze") .
mazeEntry("http://127.0.1.1:8080/maze") .

is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#north") .
is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#south") .
is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#east") .
is_direction("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#west") .

is_wall("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#Wall") .

knownResource(URI) :- rdf(_, _, _)[source(URI)] . // consider this resource (URI) already visited if any triple was retrieved from this URI
visitedCell(URI) :- visited(URI)[parent(_)] . // consider cell visited if belief is present

/* Initial goals */

!start.

/* Plans */

+!start :
    true
    <-
    .date(Y,M,D); .time(H,Min,Sec,MilSec); // get current date & time
    +started(Y,M,D,H,Min,Sec);             // add a new belief
    !crawl("http://127.0.1.1:8080/maze");
  .


+!crawl(URI) :
    true
    <-
        for (knownVocab(Vocab)) {
            .print("Retrieving OWL definitions of ", Vocab) ;
        //    get(Vocab) ; TODO: uncomment if needed.
        }
        +crawling ;
        +at("Root") ;
        .print("Retrieving ", URI) ;
        get(URI) ;
  .

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
        
        -transition(Dir, _) ; // remove previous transition of this direction
        +transition(Dir, Option)[base(CurrentCell)] ; // add curently perceived transition of this direction.
        .print(Dir," is ", Option);
    .

// Plan next move
-crawling :
    true
    <-  
        ?at(CurrentCell) ; // using a test-goal to bind logical variable "CurrentCell" to the value from the belief at()
        
        ?visited(CurrentCell)[parent(Parent)] ; // Get Parent Cell
        .print("Parent cell is: ", Parent) ;

        -transition(_,_)[base(Parent)] ; // Delete all transition beliefs from Parent Cell. If no Dir is present because is Locked -> should still remove the belief of this Dir!
        
        // Evaluate transition possibilities
        for (transition(Dir, Option)) { // Loop through beliefs of form transtion/2
            if (not (is_wall(Option)) & CurrentCell \== Option & Parent \== Option) { // Filter out Walls -> TODO & CurrentCell \== Option
                .print("Could go ", Option) ;
                .add_annot(transition(Dir, Option), valid_transition("True"), T) ; // Returns T as the annotated belief transition(Dir,Option)[cell("True")]. BB is not updated.
                .print("Create annotated belief: ", T) ;
                -transition(Dir, Option) ; // Remove belief
                +T[base(CurrentCell)] ; // Add the transition belief with new annotation and append base annotation.
            }
        }

        // Create DFS structure if not existing
        if (not remaining(CurrentCell, _)) { // Ensure this is only added once to keep track of explored paths
            .findall(X, transition(_,X)[valid_transition("True")], List) ; // Retruns List as list of all X = Options from transition beliefs that are annotated as valid.
            .print("Valid transitions are: ", List) ;
            +remaining(CurrentCell, List) ; // Add belief of unexplored options based from current cell.
        }

        // Select next move
        !select_next(CurrentCell) ;
    .

+!select_next(CurrentCell) :
    remaining(CurrentCell, []) // check if list is empty = cell fully explored
    <-
        .print("Cell fully explored, lets backtrack") ;
        !backtrack_from(CurrentCell) ; // Dead-end.. Go to parent cell
    .

+!select_next(CurrentCell) :
    remaining(CurrentCell, [Next | Tail]) // check if list has next item
    <-
        .print("Selected next option: ", Next) ;
        -remaining(CurrentCell, [Next|Tail]) ; // Delete item
        +remaining(CurrentCell, Tail) ; // Add item minus next option
        .print("remaining options: ", Tail) ;
        +crawling ;
        !get(Next) ;
    .


+!get(URI) :
    crawling
    <-
    //    if (not (knownResource(URI))) { // TODO: prevents backtracking.
            get(URI) ;
            !!checkEndCrawl ;
    //    }
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
        //!countTriples ;
  .  

+!countTriples :
    true
    <-
        // all crawled triples are exposed to the agent as rdf/3 terms
        .count(rdf(S, P, O), Count) ;
        .print("found ", Count, " triples in the KG.") ;
  .



/* Include common templates */
{ include("$jacamo/templates/common-cartago.asl") }
{ include("$jacamo/templates/common-moise.asl") }

// uncomment the include below to have an agent compliant with its organisation
//{ include("$moise/asl/org-obedient.asl") }
