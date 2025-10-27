// Agent sample_agent in project ccrs_bdi

/* Initial beliefs and rules */


/* Initial goals */

!start.

/* Plans */

+!start : true
    <- .print("hello world.");
       .date(Y,M,D); .time(H,Min,Sec,MilSec); // get current date & time
       +started(Y,M,D,H,Min,Sec).             // add a new belief
       !enter_maze("http://127.0.1.1:8080/maze"). // add a new goal

+!enter_maze(URI) : true
    <- .print("*************** entering the maze... ***************");
        h.target(URI, TargetURI) ;
        get(URI);
        for (rdf(URI, P, O)[source(TargetURI)]) {
            .print("Found S: ", URI, " P: ", P, " O: ", O)
        } ;
    .


/* Include common templates */
{ include("$jacamo/templates/common-cartago.asl") }
{ include("$jacamo/templates/common-moise.asl") }

// uncomment the include below to have an agent compliant with its organisation
//{ include("$moise/asl/org-obedient.asl") }
