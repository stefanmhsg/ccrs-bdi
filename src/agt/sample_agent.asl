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
            get(Vocab) ;
        }
        +crawling ;
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
        -at(_) ; // remove previous position
        .print("I'm now at: ", Target) ;
        +at(Target) ;
    .

// Map neighboring cells
+rdf(CurrentCell, Dir, Option)[rdf_type_map(_, _, uri), source(Anchor)] :
    is_direction(Dir) // Filter for Predicates that are a direction
    <-
        -transition(Dir, _)[source(_)] ; // remove previous transition of this direction
        +transition(Dir, Option)[source(CurrentCell)] ; // add curently perceived transition of this direction
        .print(Dir," is ", Option);
    .

// Plan next move
-crawling :
    true
    <-  
        ?at(CurrentCell) ; // using a test-goal to bind logical variable "CurrentCell" to the value from the belief at()
        for (transition(Dir, Option)) { // Loop through beliefs of form transtion/2
            if (not (is_wall(Option))) { // Filter out Walls
                .print("Could go ", Option) ;
            }
        }
    .



+!get(URI) :
    crawling
    <-
        if (not (knownResource(URI))) {
            get(URI) ;
            !!checkEndCrawl ;
        }
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
