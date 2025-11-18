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

move_endpoint("http://127.0.1.1:8080/move") .

knownResource(URI) :- rdf(_, _, _)[source(URI)] . // consider this resource (URI) already visited if any triple was retrieved from this URI

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

+!move(URI) :
    true
    <-
        +moving ;
        .print("Attempting move to: ", URI) ;
        !post_move(URI) ;
        !crawl(URI) ;
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
        .abolish(transition(_, _)) ; // Forget previous transitions
        !get(URI) ;
  .

/*******************
REACTING TO EVENTS
*******************/

// Go to Maze Start
+rdf(S, "http://www.w3.org/1999/xhtml/vocab#start", Start)[rdf_type_map(_, _, uri), source(Anchor)] :
    h.target(Start, Target)
    <-
        .print("Discovered start: ", Target) ;
        !move(Target) ;
    .

// Update position
+rdf(Position, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "https://kaefer3000.github.io/2021-02-dagstuhl/vocab#Cell")[rdf_type_map(_, _, uri), source(Anchor)] :
    crawling & h.target(Position, Target)
    <-
        ?at(PreviousCell) ;
        -+at(Target) ; // update position. same as -at(_) ; +at(Target) ;
        .print("I'm now at: ", Target) ;
    .

// Map neighboring cells (as soon as they get perceived)
+rdf(CurrentCell, Dir, Option)[rdf_type_map(_, _, uri), source(Anchor)] :
    is_direction(Dir) & not is_wall(Option)// Filter for Predicates that are a direction and discard Walls
    <-
        +transition(Dir, Option)[base(CurrentCell)] ; // add curently perceived transition of this direction.
        .print(Dir," is ", Option);
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
    not finished(_,_,_,_,_,_) & not at("Root")
    <-  
        ?at(CurrentCell) ; // using a test-goal to bind logical variable "CurrentCell" to the value from the belief at()
        .print("Crawl complete, starting navigation from: ", CurrentCell) ;
        !naviagte(CurrentCell) ;
    .

// Can stop at exit
+!naviagte(CurrentCell) :
    exit(CurrentCell)
    <-
        .print("Already at exit: ", CurrentCell) ;
        .succeed_goal(naviagte(CurrentCell)) ;
    .

// Must perform action
+!naviagte(CurrentCell) : 
    requires_action(CurrentCell)
    <-
        .print("Cell requires action: ", CurrentCell) ;
        !evaluate_actions(CurrentCell) ; // Check for necessary actions
    .

// Move on
+!naviagte(CurrentCell) :
    not requires_action(CurrentCell) & not exit(CurrentCell)
    <-
        .print("No open actions. Deciding next step from: ", CurrentCell) ;
        !select_next(CurrentCell) ; // Select next move
    .

// Unspecified or Unknown Action
+!evaluate_actions(CurrentCell) :
    true // DT assumption no actions required
    <-
        .print("Unable to cope with actions in: ", CurrentCell) ;
    .

// Next move if Exit in sight
+!select_next(CurrentCell) :
    exit(ExitCell)
    <-
        !move(ExitCell) ;
    .

// Next move 
+!select_next(CurrentCell) :
    true
    <-
        // Retruns List as list of all X = Options from transition beliefs that are annotated as valid.
        .findall(X, transition(_,X), List) ; 
        
        // randomly choose from valid transitions
        jia.pick(List, Result) ;
        .print("Random IA resultet in: ", Result) ;
        !move(Result) ;   
    .


/*******************
HELPER PLANS
*******************/
+!post_move(URI) :
    moving & move_endpoint(MoveURI)
    <-
        .my_name(Me) ; // Name of the agent as defined in .jcm
        .print("POST to: /move with body: ", URI) ;
        post(MoveURI, [text(URI)], [header("urn:hypermedea:http:authorization", Me)]) ; // Be aware that the Hypermedea artifact deletes the outdated representation (in Agents BB) of target URI when the call returns.
        ?(rdf(MoveURI, related, CreatedResourceURI)) ; 
        .print("Created resource: ", CreatedResourceURI) ;
        !!checkEndMove ;
    .

+!checkEndMove :
    moving
    <-
        if (not .intend(post(_))) { !endMove }
    .

+!endMove :
    moving
    <-
        -moving ;
        .print("End move...") ;
    .

  +!post(URI, Body) :
    true
    <-
        h.target(URI, TargetURI) ;
        .my_name(Me) ; // Name of the agent as defined in .jcm
        .print("POST to: ", URI, " with body: ", Body) ;
        post(URI, Body, [header("urn:hypermedea:http:authorization", Me)]); // Be aware that the Hypermedea artifact deletes the outdated representation (in Agents BB) of target URI when the call returns.
        ?(rdf(URI, related, CreatedResourceURI)) ;
        .print("Created resource: ", CreatedResourceURI) ;
    .

+!get(URI) :
    crawling
    <-
            .my_name(Me) ; // Name of the agent as defined in .jcm
            get(URI, [header("urn:hypermedea:http:authorization", Me)]) ; // Pass a header for identifying the agent which enforces acceess control on the maze server
            !!checkEndCrawl ;
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
        !!report_time ;
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

        .print("It took ", Diff, " seconds") ;
    .



/* Include common templates */
{ include("$jacamo/templates/common-cartago.asl") }
{ include("$jacamo/templates/common-moise.asl") }

// uncomment the include below to have an agent compliant with its organisation
//{ include("$moise/asl/org-obedient.asl") }
