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

+!naviagte(Location) :
    backtracking(_)
    <-
        !follow_backtrack ;
    .


// Move on
+!naviagte(Location) :
    not requires_action(Location) & not exit(Location)
    <-
        .print("No open actions. Deciding next step from: ", Location) ;
        !track_progress(Location) ;
        !select_next(Location) ; // Select next move
    .

// Progress heuristic
+!track_progress(Location) : 
    distance(D) 
    <-
        D2 = D + 1 ;
        -+distance(D2) ;

        if (not (discovered(Location))) { // Only add once on first encounter.
            +discovered(Location) ; // mark current location as discovered
            .print("Discovered: ", Location) ;
            .count(discovered(_), N) ;
            -+distinct_count(N) ;
        }
        ?distinct_count(N2) ;
        Progress = N2 / D2 ;
        -+progress(Progress) ; // 1 = every step yields a new state i.e. highest possible value. 
        .print("New Progress = ",Progress, " Discovered = ", N2, " Distance = ", D2) ;
    .

// Progress heuristic init
+!track_progress(Location) : 
    true
    <-
        +discovered(0) ;
        +distance(0) ;
        +progress(10) ;
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

// Next move if Exit in sight
+!select_next(Location) :
    exit(ExitCell)
    <-
        !access(ExitCell) ;
    .

// Next move 
+!select_next(Location) :
    progress(P)
    <-
        // Retruns List as list of all X = Options from affords beliefs that are annotated as valid.
        .findall(X, affords(_,X)[base(Location)], List) ; 

        if (P < 0.5) {
            // *** Contingency-CCRS: Trigger ***
            !escalate("Stuck", "performance", Location) ;
        } else {
            !select_random(List) ;
        }

        ?next(URI) ;
        !access(URI) ;   
    .

+!select_random(List) :
    true
    <-
        jia.pick(List, Result) ;
        .print("Random IA resultet in: ", Result) ;
        -+next(Result) ;
        .print("Selecting random resulted in: ", Result) ;
    .
/**
********** Contigency-CCRS **********
*/
+!escalate(Type, Trigger, Location) :
    true
    <-
        .print("Escalte to CCRS") ;
        // Contingency-CCRS
        ccrs.jacamo.jason.contingency.evaluate("Failure", "Low performance", Location, Suggestions) ;

        // Pattern 1: Get first suggestion
        //
        //.nth(0, Suggestions, suggestion(StrategyId, ActionType, Target, _, _, _, _)) ;
        //.print("[CCRS] First Suggestion is: Executing '", ActionType, "' to ", Target) ;

        // Pattern 2: Iterate through all suggestions
        //
        //for (.member(Sug, Suggestions)) {
        //    !process_suggestion(Sug) ;
        //}

        // Pattern 3: Select best suggestion by confidence
        //
        !select_confident_suggestion(Suggestions, BestSuggestion) ;

        // Pattern 4: Decompose a single suggestion
        //
        !process_suggestion(BestSuggestion) ;
    .

// Pattern 4: Decompose suggestion structure with all fields
+!process_suggestion(suggestion(StrategyId, ActionType, Target, Confidence, Cost, Rationale, Params)) :
    true
    <-
        .print("[CCRS Suggestion]") ;
        .print("  Strategy ID: ", StrategyId) ;
        .print("  Action: ", ActionType, " -> Target: ", Target) ;
        .print("  Confidence: ", Confidence, ", Cost: ", Cost) ;
        .print("  Rationale: ", Rationale) ;
        
        // Extract specific parameters
        !extract_params(Params) ;
    .

// Extract useful parameters from the list
+!extract_params(Params) :
    true
    <-
        // Check for specific parameters
        // .member(paramName(Value), Params)
        if (.member(reason(R), Params)) {
            .print("  Reason: ", R) ;
        }
        
        if (.member(checkpoint(C), Params)) {
            .print("  Checkpoint: ", C) ;
        }
        
        if (.member(unexploredCount(UC), Params)) {
            .print("  Unexplored options: ", UC) ;
        }
        
        if (.member(backtrackPath(Path), Params)) {
            .print("  Backtrack path: ", Path) ;
            -+backtracking(Path) ;
            !follow_backtrack ;
        }
        
        if (.member(alternativesByCheckpoint(Alts), Params)) {
            .print("  Alternatives: ", Alts) ;
        }
        
        // Print all parameters for debugging
        .print("  All params: ", Params) ;
    .

// Pattern 3: Select best suggestion by confidence
+!select_confident_suggestion([Sug], Sug) : true.

// Pattern 3: Select best suggestion by confidence
+!select_confident_suggestion([suggestion(S1,T1,Tgt1,C1,Cost1,R1,P1) | Rest], Best) :
    true
    <-
        !select_confident_suggestion(Rest, suggestion(S2,T2,Tgt2,C2,Cost2,R2,P2)) ;
        
        if (C1 > C2) {
            Best = suggestion(S1,T1,Tgt1,C1,Cost1,R1,P1) ;
        } else {
            Best = suggestion(S2,T2,Tgt2,C2,Cost2,R2,P2) ;
        }
    .

+!follow_backtrack :
    backtracking([Next | Rest])
    <-
        .print("Backtracking step to: ", Next) ;
        -+backtracking(Rest) ;
        !access(Next) ;
    .

+!follow_backtrack :
    backtracking([])
    <-
        .print("Backtracking complete") ;
        -backtracking([]) ;
    .


//-!select_next(Location) :
//    true
//    <-
//        .print("Plan-Failure -!select_next") ;
//        // Contingency-CCRS
//        ccrs.jacamo.jason.contingency.evaluate("Failure", "Error", Location, Suggestions) ;
//        // Pattern 2: Iterate through all suggestions
//        //
//        for (.member(Sug, Suggestions)) {
//            !process_suggestion(Sug) ;
//        }
//    .

/**
********** Contigency-CCRS **********
*/


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
        -+at(Target) ; // update location. (Same as -at(_) ; +at(Target) ;)
        .print("I'm now at: ", Target) ;
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
+!crawl(RequestURI) :
    agent_name(Name) & h.target(RequestURI, TargetURI)
    <-
        +crawling ;
        .print("Retrieving ", TargetURI) ;
        .abolish(affords(_, _)) ; // Forget previous affordances
        get(TargetURI, [header("urn:hypermedea:http:authorization", Name), header("urn:hypermedea:http:accept", "text/turtle")]) ; // Pass a header for identifying the agent which enforces acceess control on the maze server
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

+!request_access(RequestURI) :
    at(URI) & agent_name(AgentName) & h.target(RequestURI, TargetURI)
    <-
        .print("POST to target URI - requesting MOVE to: ", TargetURI) ;
        post(TargetURI, [rdf(AgentName, "https://paul.ti.rw.fau.de/~am52etar/dynmaze/dynmaze#entersFrom", URI)[rdf_type_map(uri,uri,uri)]], [header("urn:hypermedea:http:authorization", AgentName)]) ; // Be aware that the Hypermedea artifact deletes the outdated representation (in Agents BB) of target URI when the call returns.
        ?(rdf(TargetURI, related, CreatedResourceURI)) ; 
        .abolish(rdf(_,"https://kaefer3000.github.io/2021-02-dagstuhl/vocab#contains", AgentName)) ;
        .print("Access approved: ", CreatedResourceURI) ;
    .

  +!post(RequestURI, Body) :
    agent_name(AgentName) & h.target(RequestURI, TargetURI)
    <-
        .print("POST to: ", TargetURI, " with body: ", Body) ;
        post(TargetURI, Body, [header("urn:hypermedea:http:authorization", AgentName)]); // Be aware that the Hypermedea artifact deletes the outdated representation (in Agents BB) of target URI when the call returns.
        ?(rdf(TargetURI, related, CreatedResourceURI)) ;
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
