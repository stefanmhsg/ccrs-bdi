/* Initial beliefs and rules */
entry_point("http://127.0.1.1:8080/maze") .
discover_start("http://www.w3.org/1999/xhtml/vocab#start").

outgoing_link("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#north") .
outgoing_link("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#south") .
outgoing_link("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#east") .
outgoing_link("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#west") .
is_exit("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#exit") .

blocked("https://kaefer3000.github.io/2021-02-dagstuhl/vocab#Wall") .

environment_agent_name_prefix("http://127.0.1.1:8080/agents/") .

/* Initial goals */

!start.

/* Plans */

+!start :
    true
    <-
        .date(Y,M,D); .time(H,Min,Sec,MilSec) ; // get current date & time
        +started(Y,M,D,H,Min,Sec,MilSec) ;             // add a new belief
        !construct_agent_name ;
        !enter_maze ;
    .

+!construct_agent_name :
    environment_agent_name_prefix(Prefix)
    <-
        .my_name(Me) ;
        .concat(Prefix, Me, AgentName) ;
        +agent_name(AgentName) ;
        .print("Agent name in the environment is: ", AgentName) ;
    .

+!enter_maze :
    entry_point(URI) & discover_start(P)
    <-
        +at(URI) ;
        !crawl(URI) ;
        ?rdf(S, P, Start)[rdf_type_map(_, _, uri), source(Anchor)] ;
        .print("Discovered start: ", Start) ;
        !access(Start) ;
    .

/*******************
MAIN LOOP
*******************/
+!escape(URI) :
    not crawling
    <-
        !naviagte(URI) ;
    .

// Stop at exit
+!naviagte(URI) :
    exit(URI)
    <-
        .print("Already at exit: ", URI) ;
        .succeed_goal(escape) ;
        .date(Y,M,D); .time(H,Min,Sec,MilSec) ;
        +finished(Y,M,D,H,Min,Sec,MilSec) ;
        !!stop ;
    .

// Move on
+!naviagte(Location) :
    not requires_action(Location) & not exit(Location)
    <-
        .print("No open actions. Deciding next step from: ", Location) ;
        // DFS
        !evaluate_affordances(Location) ; // Annotate valid affordances
        // DFS
        !track_unexplored_affordances(Location) ; // Tracking which valid affordances have (not) been explored from current Location
        !select_next(Location) ; // Select next move
    .

// Must perform action
+!naviagte(Location) : 
    requires_action(Location)
    <-
        .print("Cell requires action: ", Location) ;
        !evaluate_actions(Location) ; // Check for necessary actions
    .

// Unlock Action if Cell is of type Lock
+!evaluate_actions(Location) :
    rdf(Location, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#Lock")
    <-
        ?rdf(Location, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsAction", Action) ;
        ?rdf(Action, "http://www.w3.org/2011/http#body", Body) ;
        ?rdf(Action, "http://www.w3.org/2011/http#mthd", Method) ;
        
        ?rdf(Body, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsProperty", Property) ;
        ?rdf(Body, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#foundAt", Keyname) ;
        
        ?rdf(KeyId, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", Keyname) ;
        ?rdf(KeyId, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#keyValue", Keyvalue) ;

        .print(Location, " needs ", Action, " with Method ", Method, " and Body ", Body, " with Property ", Property, " of ", Keyname, " which is ", Keyvalue) ;

        !post(Location, [rdf(Location, Property, Keyvalue)[rdf_type_map(uri,uri,literal)]]) ;
        !crawl(Location) ;
    .

// Unspecified or Unknown Action
+!evaluate_actions(Location) :
    not rdf(Location, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#Lock") // DT assumption that only Lock related actions exist
    <-
        .print("Unable to cope with actions in: ", Location) ;
    .


// DFS: Annotate valid affordances
+!evaluate_affordances(Location) :
    discovered(Location)[parent(Parent)]
    <-
        .print("Evaluate affordances from: ", Location) ;
        -affords(_,_)[base(Parent)] ; // Delete all remaining affordance beliefs from Parent. If none is present because is Locked -> should still remove the previous belief of this affordance!
        // Evaluate affordance possibilities
        for (affords(P, Option)) { // Loop through beliefs of form affords/2
            if (not (blocked(Option)) & Location \== Option & Parent \== Option) { // Filter out blocked options / going to self / going back
                .print("Could go ", Option) ;
                +affords(P, Option)[valid_affordance("True")] ; // Append an annotation to the existing belief
            }
        }    
    .

// DFS: Create DFS structure if not existing (for tracking which dirs have been explored from current Location).
+!track_unexplored_affordances(Location) :
    true 
    <-
        if (not remaining(Location, _)) { // Ensure this is only added once to keep track of explored affordances
            .findall(X, affords(_,X)[valid_affordance("True")], List) ; // Retruns List as list of all X = Options from affordance beliefs that are annotated as valid.
            .print("Tracking unexplored affordances: ", List) ;
            +remaining(Location, List) ; // Add belief of unexplored options based from current Location.
        } else {
            .print("Tracking list of unexplored affordances already available.") ;
        }
    .

// Next move if Exit in sight
+!select_next(Location) :
    exit(ExitCell)
    <-
        !access(ExitCell) ;
    .

// DFS: Next move if current Location has unexplored options left
+!select_next(Location) :
    remaining(Location, [Next | Tail]) // check if list has next item
    <-
        .print("Selected next option: ", Next) ;
        -remaining(Location, [Next|Tail]) ; // Delete item
        +remaining(Location, Tail) ; // Add item minus next option
        .print("remaining options: ", Tail) ;
        !access(Next) ;
    .

// DFS: Next move if current Location has no options left
+!select_next(Location) :
    remaining(Location, []) // check if list is empty = Location fully explored
    <-
        .print("Location fully explored, lets backtrack") ;
        !backtrack_from(Location) ; // Dead-end.. Go to parent        
    .

// DFS: Back at start
+!backtrack_from(Location) :
    discovered(Location)[parent (Parent)] & entry_point(Parent)
    <-
        .print("All the way back to http://127.0.1.1:8080/maze. I give up.") ;
        .fail_goal(backtrack_from(Location)) ;
        .fail_goal(select_next(Location)) ;
    .

// DFS: Take one step backwards
+!backtrack_from(Location) :
    discovered(Location)[parent (Parent)] & not entry_point(Parent)
    <-
        .print("Going back to parent: ", Parent) ;
        !access(Parent) ;
    .

// Start next loop
-crawling : 
    at(URI) & not entry_point(URI)
    <- 
        !escape(URI) ;
    .

/*******************
REACTING TO EVENTS
*******************/

// Update location
+rdf(Location, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "https://kaefer3000.github.io/2021-02-dagstuhl/vocab#Cell")[rdf_type_map(_, _, uri), source(Anchor)] :
    crawling & h.target(Location, Target)
    <-
        // DFS: Path tracking
        ?at(Previous) ;
        -+at(Target) ; // update location. (Same as -at(_) ; +at(Target) ;)
        .print("I'm now at: ", Target) ;         
        if (not (discovered(Target))) { // Only add once on first encounter.
            +discovered(Target)[parent(Previous)] ; // mark current location as discovered (path tracking, not same as fully explored). Keep track of where we came from with a custom annotation.
            .print("Discovered: ", Target, " from: ", Previous) ;
        }       
    .

// Map outgoing links (as soon as they get perceived)
+rdf(Location, Dir, Option)[rdf_type_map(_, _, uri), source(Anchor)] :
    outgoing_link(Dir) & not blocked(Option) 
    <-
        +affords(Dir, Option)[base(Location)] ; // add curently perceived outgoing link.
        .print(Dir," is ", Option);
    .

// Detect open action
+rdf(Location, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#needsAction", Action)[rdf_type_map(uri, _, _), source(Anchor)] :
    crawling & h.target(Location, Target)
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
+rdf(Location, Dir, ExitCell)[rdf_type_map(_, _, uri), source(Anchor)] :
    is_exit(Dir)
    <-
        -affords(Dir, _) ;
        +affords(Dir, ExitCell)[base(Location)] ;
        +exit(ExitCell) ;
        .print("Found Exit! It's at: ", ExitCell);
    .

/*******************
HELPER PLANS
*******************/
+!crawl(URI) :
    agent_name(Name)
    <-
        +crawling ;
        .print("Retrieving ", URI) ;
        .abolish(affords(_, _)) ; // Forget previous affordances
        get(URI, [header("urn:hypermedea:http:authorization", Name), header("urn:hypermedea:http:accept", "text/turtle")]) ; // Pass a header for identifying the agent which enforces acceess control on the maze server
        !!checkEndCrawl ;
  .

+!checkEndCrawl : crawling <- if (not .intend(get(_))) { !endCrawl } .

+!endCrawl : crawling <- -crawling .

+!access(URI) :
    true
    <-
        .print("Attempting move to: ", URI) ;
        !request_access(URI) ;
        !crawl(URI) ;
    .

+!request_access(TargetURI) :
    at(URI) & agent_name(AgentName)
    <-
        .print("POST to target URI - requesting MOVE to: ", TargetURI) ;
        post(TargetURI, [rdf(AgentName, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#entersFrom", URI)[rdf_type_map(uri,uri,uri)]], [header("urn:hypermedea:http:authorization", AgentName)]) ; // Be aware that the Hypermedea artifact deletes the outdated representation (in Agents BB) of target URI when the call returns.
        ?(rdf(TargetURI, related, CreatedResourceURI)) ; 
        .abolish(rdf(_,"https://kaefer3000.github.io/2021-02-dagstuhl/vocab#contains", AgentName)) ;
        .print("Access approved: ", CreatedResourceURI) ;
    .

  +!post(URI, Body) :
    agent_name(AgentName)
    <-
        h.target(URI, TargetURI) ;
        .print("POST to: ", URI, " with body: ", Body) ;
        post(URI, Body, [header("urn:hypermedea:http:authorization", AgentName)]); // Be aware that the Hypermedea artifact deletes the outdated representation (in Agents BB) of target URI when the call returns.
        ?(rdf(URI, related, CreatedResourceURI)) ;
        .print("Created resource: ", CreatedResourceURI) ;
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
        ?started(Y1, M1, D1, H1, Min1, S1, MilSec1) ;
        ?finished(Y2, M2, D2, H2, Min2, S2, MilSec2) ;

        // convert both to milliseconds
        T1ms = ((H1 * 3600 + Min1 * 60 + S1) * 1000) + MilSec1 ;
        T2ms = ((H2 * 3600 + Min2 * 60 + S2) * 1000) + MilSec2 ;

        DiffMs = T2ms - T1ms ;

        // derive seconds and remaining milliseconds
        DiffSec = DiffMs div 1000 ;
        DiffRemMs = DiffMs mod 1000 ;
        
        .print("It took ", DiffSec, " seconds and ", DiffRemMs, " milliseconds") ;
    .



/* Include common templates */
{ include("$jacamo/templates/common-cartago.asl") }
{ include("$jacamo/templates/common-moise.asl") }

// uncomment the include below to have an agent compliant with its organisation
//{ include("$moise/asl/org-obedient.asl") }
